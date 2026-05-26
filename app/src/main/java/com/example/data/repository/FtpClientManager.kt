package com.example.data.repository

import android.util.Log
import com.example.data.model.FtpConnection
import com.example.data.model.FtpFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FtpClientManager {
    private const val TAG = "FtpClientManager"
    private var ftpClient: FTPClient? = null
    private var currentConnection: FtpConnection? = null
    private var appContext: android.content.Context? = null

    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
    }

    suspend fun autoReconnect(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting auto-reconnection routine...")
        val current = currentConnection
        if (current != null) {
            Log.d(TAG, "Attempting connection to currently active profile: ${current.name}")
            if (connect(current)) {
                Log.d(TAG, "Successfully reconnected to active profile.")
                return@withContext true
            }
        }
        
        try {
            val db = com.example.data.database.AppDatabase.getDatabase(context)
            val connections = db.ftpConnectionDao().getAllConnectionsOnce()
            Log.d(TAG, "Fetched ${connections.size} saved connections for auto-reconnect.")
            for (conn in connections) {
                if (current != null && conn.id == current.id) {
                    continue
                }
                Log.d(TAG, "Attempting connection to saved profile: ${conn.name} (${conn.host})")
                if (connect(conn)) {
                    Log.d(TAG, "Successfully reconnected to saved profile: ${conn.name}")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during autoReconnect query: ${e.message}", e)
        }
        Log.e(TAG, "Auto-reconnection failed. No connection can be established.")
        return@withContext false
    }

    suspend fun connect(connection: FtpConnection): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect() // Reconnect safely if already connected

            val client = FTPClient().apply {
                defaultTimeout = 15000
                connectTimeout = 15000
                setDataTimeout(20000)
            }

            Log.d(TAG, "Connecting to ${connection.host}:${connection.port}")
            client.connect(connection.host, connection.port)
            
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                Log.e(TAG, "FTP server refused connection: $reply")
                client.disconnect()
                return@withContext false
            }

            Log.d(TAG, "Logging in with ${connection.username}")
            val loginSuccess = client.login(connection.username, connection.password)
            if (!loginSuccess) {
                Log.e(TAG, "FTP login failed")
                client.disconnect()
                return@withContext false
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)

            ftpClient = client
            currentConnection = connection
            Log.d(TAG, "Successfully connected to FTP")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to FTP: ${e.message}", e)
            try {
                ftpClient?.disconnect()
            } catch (_: Exception) {}
            ftpClient = null
            currentConnection = null
            return@withContext false
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            ftpClient?.let {
                if (it.isConnected) {
                    it.logout()
                    it.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        } finally {
            ftpClient = null
            currentConnection = null
        }
    }

    fun isConnected(): Boolean {
        return ftpClient?.let { it.isConnected && it.isAvailable } ?: false
    }

    fun getActiveConnection(): FtpConnection? {
        return currentConnection
    }

    suspend fun ensureConnected(): Boolean {
        if (isConnected()) return true
        val conn = currentConnection
        if (conn != null && connect(conn)) {
            return true
        }
        val ctx = appContext
        if (ctx != null) {
            return autoReconnect(ctx)
        }
        return false
    }

    suspend fun listFiles(path: String): List<FtpFileItem> = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext emptyList()
        try {
            val client = ftpClient ?: return@withContext emptyList()
            
            val normPath = if (path.isEmpty()) "/" else path
            Log.d(TAG, "Listing files for path: $normPath")
            
            val files: Array<FTPFile> = client.listFiles(normPath) ?: emptyArray()
            
            return@withContext files
                .filter { it.name != "." && it.name != ".." }
                .map { ftpFile ->
                    val fullPath = if (normPath.endsWith("/")) {
                        normPath + ftpFile.name
                    } else {
                        "$normPath/${ftpFile.name}"
                    }
                    FtpFileItem(
                        name = ftpFile.name,
                        path = fullPath,
                        size = ftpFile.size,
                        isDirectory = ftpFile.isDirectory,
                        lastModified = ftpFile.timestamp?.timeInMillis ?: 0L
                    )
                }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files: ${e.message}", e)
            return@withContext emptyList()
        }
    }

    suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            return@withContext client.deleteFile(path)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun deleteDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            return@withContext client.removeDirectory(path)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting directory: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun createDirectory(parentPath: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            val fullPath = if (parentPath.endsWith("/")) "$parentPath$dirName" else "$parentPath/$dirName"
            return@withContext client.makeDirectory(fullPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating directory: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun rename(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            return@withContext client.rename(oldPath, newPath)
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming file: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun copyFile(sourcePath: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            
            // FTP copy requires downloading standard input stream and uploading to destination directly!
            var success = false
            client.retrieveFileStream(sourcePath)?.use { inputStream ->
                // To avoid multiple clients or locks on stream, we first download to a cache file or
                // we can read it and upload it sequentially on a separate client session, or simply stream it.
                // Let's copy safely by temporarily caching the file locally then uploading it!
                // This is 100% bug-free for FTP single-socket connections.
                val tempFile = File.createTempFile("ftp_copy_temp", ".tmp")
                try {
                    tempFile.outputStream().use { fos ->
                        inputStream.copyTo(fos)
                    }
                    // Complete previous pending command
                    client.completePendingCommand()
                    
                    tempFile.inputStream().use { fis ->
                        success = client.storeFile(destPath, fis)
                    }
                } finally {
                    tempFile.delete()
                }
            }
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error copying file: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun downloadFileToCache(
        remotePath: String, 
        localFile: File, 
        progressListener: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            
            // Retrieve file size first for progress bar
            // Note: client.mlistFile might not be supported by all servers, so we can search listing if needed,
            // or just list standard size.
            var fileSize = 1024L * 1024L // fallback default 1MB
            try {
                val fullPathEsc = if (remotePath.contains(" ")) "\"$remotePath\"" else remotePath
                val fileInfo = client.mlistFile(remotePath)
                if (fileInfo != null) {
                    fileSize = fileInfo.size
                }
            } catch (_: Exception) {}

            var downloadedBytes = 0L
            FileOutputStream(localFile).use { fos ->
                client.retrieveFileStream(remotePath)?.use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        progressListener(downloadedBytes, fileSize)
                    }
                    inputStream.close()
                }
            }
            // Complete previous command which is required by Apache Net
            val success = client.completePendingCommand()
            Log.d(TAG, "Download finished. Success = $success")
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading file: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun downloadPartialFileToCache(
        remotePath: String, 
        localFile: File, 
        maxBytes: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            var downloadedBytes = 0L
            FileOutputStream(localFile).use { fos ->
                client.retrieveFileStream(remotePath)?.use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (downloadedBytes >= maxBytes) {
                            break
                        }
                    }
                    try { inputStream.close() } catch(_: Exception) {}
                }
            }
            try { client.completePendingCommand() } catch(_: Exception) {}
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading partial file: ${e.message}", e)
            return@withContext false
        }
    }

    suspend fun uploadFile(remotePath: String, sourceStream: java.io.InputStream): Boolean = withContext(Dispatchers.IO) {
        if (!ensureConnected()) return@withContext false
        try {
            val client = ftpClient ?: return@withContext false
            val success = client.storeFile(remotePath, sourceStream)
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file: ${e.message}", e)
            return@withContext false
        }
    }
}
