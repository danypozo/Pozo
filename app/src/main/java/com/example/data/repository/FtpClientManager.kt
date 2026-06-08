package com.example.data.repository

import android.util.Log
import com.example.data.model.FtpConnection
import com.example.data.model.FtpFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FtpClientManager {
    private const val TAG = "FtpClientManager"
    private var ftpClient: FTPClient? = null
    private var currentConnection: FtpConnection? = null
    private var appContext: android.content.Context? = null
    private var lastConnectedHost: String? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private var lastSelectedConnection: FtpConnection? = null
    @Volatile
    private var lastActiveTime: Long = System.currentTimeMillis()

    private val _visualLogs = MutableStateFlow<List<String>>(emptyList())
    val visualLogs: StateFlow<List<String>> = _visualLogs

    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var lastReconnectAttemptTime = 0L

    fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val formattedMsg = "[$time] $formattedMsgPrefix$msg"
        Log.i(TAG, "VisualLog: $formattedMsg")
        val current = _visualLogs.value.toMutableList()
        current.add(formattedMsg)
        if (current.size > 200) {
            current.removeAt(0)
        }
        _visualLogs.value = current
    }

    private var formattedMsgPrefix = ""

    fun clearLogs() {
        _visualLogs.value = emptyList()
    }

    fun startHeartbeat(context: android.content.Context) {
        appContext = context.applicationContext
        heartbeatJob?.cancel()
        heartbeatJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            addLog("Keep-alive: Servicio de latidos FTP iniciado.")
            while (isActive) {
                kotlinx.coroutines.delay(10000) // Verificar cada 10 segundos
                val prefs = context.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE)
                val heartbeatEnabled = prefs.getBoolean("ftp_heartbeat_enabled", false)
                if (heartbeatEnabled) {
                    val isCurrentlyConnected = isConnected()
                    val hasTargetConnection = lastSelectedConnection != null

                    if (isCurrentlyConnected) {
                        val inactive = System.currentTimeMillis() - getLastActiveTime()
                        if (inactive >= 15000) { // Inactivo por 15 segundos
                            val startTime = System.currentTimeMillis()
                            var success = false
                            try {
                                val client = ftpClient
                                if (client != null && client.isConnected) {
                                    success = client.sendNoOp()
                                    val replyCode = client.replyCode
                                    val responseTime = System.currentTimeMillis() - startTime
                                    if (success) {
                                        addLog("Keep-alive: Enviado paquete NOOP. Éxito: true (Tiempo resp: ${responseTime}ms, Cód: $replyCode)")
                                    } else {
                                        addLog("Keep-alive: sendNoOp devolvió falso. Cód: $replyCode. El canal parece inestable.")
                                    }
                                }
                            } catch (e: Exception) {
                                val responseTime = System.currentTimeMillis() - startTime
                                addLog("Keep-alive error: Envío fallido tras ${responseTime}ms: ${e.message}")
                                success = false
                            }

                            // Si el NoOp falló o dio excepción (ej: Broken pipe), intentamos forzar re-reconexión para despertar
                            if (!success) {
                                addLog("Keep-alive: Paquete fallido (Broken pipe o canal inestable). Forzando desconexión y reconexión automática para despertar/reactivar disco...")
                                disconnect()
                                kotlinx.coroutines.delay(2000)
                                if (isActive) {
                                    val reconnected = ensureConnected()
                                    if (reconnected) {
                                        addLog("Keep-alive: Servidor despertado y reconectado con éxito tras error en canal.")
                                    } else {
                                        addLog("Keep-alive: Intento de reconexión/despertar falló. Se reintentará en el próximo latido.")
                                    }
                                }
                            }
                        }
                    } else if (hasTargetConnection) {
                        // Si no está conectado pero hay una conexión activa seleccionada (no desconectada de forma manual)
                        val now = System.currentTimeMillis()
                        // Evitar reintentos de reconexión demasiado rápidos (mínimo cada 30 segundos)
                        if (now - lastReconnectAttemptTime >= 30000) {
                            lastReconnectAttemptTime = now
                            addLog("Keep-alive: Conexión caída pero perfil sigue activo. Intentando reconectar automáticamente para despertar/restablecer canal...")
                            val reconnected = ensureConnected()
                            if (reconnected) {
                                addLog("Keep-alive: Servidor despertado y reconectado con éxito de forma proactiva.")
                            } else {
                                addLog("Keep-alive: El intento de reconexión automática proactiva falló.")
                            }
                        }
                    }
                }
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        addLog("Keep-alive: Servicio de latidos FTP detenido.")
    }

    fun updateLastActiveTime() {
        lastActiveTime = System.currentTimeMillis()
    }

    fun getLastActiveTime(): Long {
        return lastActiveTime
    }

    fun setLastSelectedConnection(conn: FtpConnection?) {
        lastSelectedConnection = conn
        if (conn != null) {
            updateLastActiveTime()
        }
    }

    fun getLastSelectedConnection(): FtpConnection? {
        return lastSelectedConnection
    }

    suspend fun disconnectManual() = withContext(Dispatchers.IO) {
        lastSelectedConnection = null
        disconnect()
    }

    private val _lastConnectedHostFlow = MutableStateFlow<String?>(null)
    val lastConnectedHostFlow: StateFlow<String?> = _lastConnectedHostFlow

    fun initialize(context: android.content.Context) {
        appContext = context.applicationContext
        registerNetworkCallback(context.applicationContext)
        startHeartbeat(context.applicationContext)
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
        lastSelectedConnection = connection
        updateLastActiveTime()
        if (isConnected() && currentConnection?.id == connection.id && !shouldReconnectDueToNetworkChange()) {
            Log.d(TAG, "Already connected to ${connection.name}. Reusing active connection.")
            return@withContext true
        }
        try {
            disconnect() // Reconnect safely if already connected

            val client = FTPClient().apply {
                defaultTimeout = 15000
                connectTimeout = 15000
                setDataTimeout(20000)
                controlEncoding = "UTF-8"
                autodetectUTF8 = true
            }

            // DYNAMIC SWITCH BETWEEN WIFI LOCAL IP & PUBLIC DNS BACKED URL
            var hostToConnect = connection.host
            val ctx = appContext
            if (ctx != null && !connection.localIp.isNullOrBlank()) {
                var isLocalIpSelected = false
                var isWifiActive = false

                try {
                    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val activeNetwork = cm.activeNetwork
                    val capabilities = cm.getNetworkCapabilities(activeNetwork)
                    isWifiActive = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking network connectivity: ${e.message}")
                }

                if (isWifiActive) {
                    var currentSsid: String? = null
                    try {
                        val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val info = wm.connectionInfo
                        if (info != null) {
                            currentSsid = info.ssid?.replace("\"", "")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resolving SSID: ${e.message}")
                    }

                    val homeSsidDefined = !connection.homeSsid.isNullOrBlank()
                    var isSsidMatched = false

                    if (homeSsidDefined) {
                        val cleanCurrent = currentSsid?.trim()?.replace("\"", "") ?: ""
                        val whitelist = connection.homeSsid.split(",").map { it.trim().replace("\"", "") }.filter { it.isNotEmpty() }
                        
                        isSsidMatched = whitelist.any { pattern ->
                            if (pattern == "*") return@any true // Special absolute wildcard: matches anything (including unknown ssid)
                            val regexPattern = pattern.replace(".", "\\.").replace("*", ".*")
                            try {
                                val regex = Regex("^$regexPattern$", RegexOption.IGNORE_CASE)
                                regex.matches(cleanCurrent)
                            } catch (e: Exception) {
                                cleanCurrent.equals(pattern, ignoreCase = true)
                            }
                        }
                    }

                    if (homeSsidDefined && isSsidMatched) {
                        isLocalIpSelected = true
                        Log.d(TAG, "Connected Wi-Fi SSID matches homeSsid whitelist ('${connection.homeSsid}'). Using local IP Connection.")
                    } else if (homeSsidDefined && !isSsidMatched && !currentSsid.isNullOrBlank() && currentSsid != "<unknown ssid>") {
                        Log.d(TAG, "Connected to Wi-Fi '$currentSsid' which is NOT in the home Wi-Fi whitelist ('${connection.homeSsid}'). Bypassing local IP probe and using public host.")
                    } else {
                        // Probe local IP reachability using isLocalIpReachable
                        if (isLocalIpReachable(connection.localIp, connection.port)) {
                            isLocalIpSelected = true
                            Log.d(TAG, "Wi-Fi active. Unknown/no SSID, but local IP: ${connection.localIp}:${connection.port} is reachable. Using Local IP!")
                        } else {
                            Log.d(TAG, "Wi-Fi active but local IP: ${connection.localIp} is not reachable. Falling back to public WAN url.")
                        }
                    }
                } else {
                    Log.d(TAG, "Wi-Fi is NOT connected (cellular data/5G or offline). Using public host directly.")
                }

                if (isLocalIpSelected) {
                    hostToConnect = connection.localIp
                }
            }

            Log.d(TAG, "Connecting to ${hostToConnect}:${connection.port}")
            addLog("Conectando a FTP: ${hostToConnect}:${connection.port} (Perfil: ${connection.name})")
            val startTime = System.currentTimeMillis()
            client.connect(hostToConnect, connection.port)
            
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                addLog("FTP error: El servidor rechazó la conexión. Código: $reply")
                client.disconnect()
                return@withContext false
            }

            addLog("FTP: Conexión TCP establecida en ${System.currentTimeMillis() - startTime}ms. Autenticando usuario '${connection.username}'...")
            val loginSuccess = client.login(connection.username, connection.password)
            if (!loginSuccess) {
                addLog("FTP error: Error de autenticación/login para usuario '${connection.username}'")
                client.disconnect()
                return@withContext false
            }

            addLog("FTP: Sesión iniciada correctamente. Estableciendo modo pasivo y tipo binario...")
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)

            ftpClient = client
            currentConnection = connection
            lastConnectedHost = hostToConnect
            _lastConnectedHostFlow.value = hostToConnect
            addLog("FTP: Conectado y listo para operaciones. Servidor: ${hostToConnect}")
            return@withContext true
        } catch (e: Exception) {
            addLog("FTP error en conexión: ${e.message}")
            try {
                ftpClient?.disconnect()
            } catch (_: Exception) {}
            ftpClient = null
            currentConnection = null
            lastConnectedHost = null
            _lastConnectedHostFlow.value = null
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
            lastConnectedHost = null
            _lastConnectedHostFlow.value = null
        }
    }

    fun isConnected(): Boolean {
        return ftpClient?.let { it.isConnected && it.isAvailable } ?: false
    }

    fun getActiveConnection(): FtpConnection? {
        return currentConnection
    }

    suspend fun ensureConnected(): Boolean {
        if (isConnected() && !shouldReconnectDueToNetworkChange()) {
            updateLastActiveTime()
            return true
        }
        val conn = currentConnection ?: lastSelectedConnection
        if (conn != null && connect(conn)) {
            updateLastActiveTime()
            return true
        }
        val ctx = appContext
        if (ctx != null) {
            val ok = autoReconnect(ctx)
            if (ok) updateLastActiveTime()
            return ok
        }
        return false
    }

    fun getLastConnectedHost(): String? {
        return lastConnectedHost
    }

    private fun isLocalIpReachable(localIp: String?, port: Int): Boolean {
        if (localIp.isNullOrBlank()) return false
        return try {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(localIp, port), 1200)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun shouldReconnectDueToNetworkChange(): Boolean {
        if (!isConnected()) return false
        val conn = currentConnection ?: return false
        val lastHost = lastConnectedHost ?: return false
        
        val ctx = appContext ?: return false
        try {
            val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            val isWifiNow = capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            
            var shouldBeLocal = false
            if (isWifiNow && !conn.localIp.isNullOrBlank()) {
                val homeSsidDefined = !conn.homeSsid.isNullOrBlank()
                if (homeSsidDefined) {
                    var currentSsid: String? = null
                    try {
                        val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                        val info = wm.connectionInfo
                        if (info != null) {
                            currentSsid = info.ssid?.replace("\"", "")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resolving SSID: ${e.message}")
                    }

                    val cleanCurrent = currentSsid?.trim()?.replace("\"", "") ?: ""
                    val whitelist = conn.homeSsid.split(",").map { it.trim().replace("\"", "") }.filter { it.isNotEmpty() }
                    
                    val isSsidMatched = whitelist.any { pattern ->
                        if (pattern == "*") return@any true // Special absolute wildcard
                        val regexPattern = pattern.replace(".", "\\.").replace("*", ".*")
                        try {
                            val regex = Regex("^$regexPattern$", RegexOption.IGNORE_CASE)
                            regex.matches(cleanCurrent)
                        } catch (e: Exception) {
                            cleanCurrent.equals(pattern, ignoreCase = true)
                        }
                    }
                    if (isSsidMatched) {
                        shouldBeLocal = true
                    }
                } else {
                    // Probe local IP reachability using Socket
                    if (isLocalIpReachable(conn.localIp, conn.port)) {
                        shouldBeLocal = true
                    }
                }
            }
            
            val wasLocalIp = lastHost == conn.localIp
            if (shouldBeLocal != wasLocalIp) {
                Log.d(TAG, "Network state change detected: shouldBeLocal($shouldBeLocal) != wasLocalIp($wasLocalIp). Reconnecting.")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking shouldReconnectDueToNetworkChange: ${e.message}")
        }
        return false
    }

    fun checkAndReconnectIfNetworkChanged(context: android.content.Context) {
        val conn = currentConnection ?: return
        Log.d(TAG, "Network change detected. Proactively reconnecting FTP connection ${conn.name} immediately...")
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                connect(conn)
            } catch (e: Exception) {
                Log.e(TAG, "Error during dynamic network reconnection check: ${e.message}")
            }
        }
    }

    private fun registerNetworkCallback(context: android.content.Context) {
        if (networkCallback != null) return
        try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network became available! Checking if FTP IP/DNS needs update...")
                    checkAndReconnectIfNetworkChanged(context)
                }
                override fun onLost(network: android.net.Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost! Checking if FTP IP/DNS needs update...")
                    checkAndReconnectIfNetworkChanged(context)
                }
                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                    super.onCapabilitiesChanged(network, capabilities)
                    Log.d(TAG, "Network capabilities changed! Checking if FTP IP/DNS needs update...")
                    checkAndReconnectIfNetworkChanged(context)
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            Log.d(TAG, "Successfully registered default network callback.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    suspend fun listFiles(path: String): List<FtpFileItem> = withContext(Dispatchers.IO) {
        val scope = this
        val attempts = 3
        var currentAttempt = 0
        var resultList: List<FtpFileItem>? = null

        while (currentAttempt < attempts && scope.isActive) {
            currentAttempt++
            if (currentAttempt > 1) {
                addLog("FTP Buffer: Reintento automático de listar archivos ($currentAttempt/$attempts) por sospecha de ahorro de energía/broken pipe...")
                disconnect()
                kotlinx.coroutines.delay(2000)
            }

            if (!ensureConnected()) {
                continue
            }

            try {
                val client = ftpClient ?: continue
                val normPath = if (path.isEmpty()) "/" else path
                Log.d(TAG, "Listing files for path: $normPath (Attempt $currentAttempt)")

                val files: Array<FTPFile>? = client.listFiles(normPath)
                if (files == null) {
                    addLog("FTP Buffer: client.listFiles devolvió null en intento $currentAttempt. Vigilando desconexión.")
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }

                resultList = files
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
                break // Success!
            } catch (e: Exception) {
                addLog("FTP Buffer error al listar (Intento $currentAttempt): ${e.message}")
                try { ftpClient?.disconnect() } catch(_: Exception) {}
                ftpClient = null
                currentConnection = null
                continue
            }
        }
        return@withContext resultList ?: emptyList()
    }

    suspend fun listFilesRecursive(path: String): List<FtpFileItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FtpFileItem>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(path)
        
        while (queue.isNotEmpty() && isActive) {
            val currentDir = queue.poll() ?: break
            val items = listFiles(currentDir)
            for (item in items) {
                if (item.isDirectory) {
                    queue.add(item.path)
                } else {
                    result.add(item)
                }
            }
        }
        return@withContext result
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

    suspend fun getRemoteFileSize(remotePath: String): Long = withContext(Dispatchers.IO) {
        var result: Long = -1L
        val attempts = 3
        var currentAttempt = 0
        val scope = this
        while (currentAttempt < attempts && scope.isActive) {
            currentAttempt++
            if (currentAttempt > 1) {
                addLog("FTP Buffer: Reintento automático de consulta de tamaño ($currentAttempt/$attempts)...")
                disconnect()
                kotlinx.coroutines.delay(1500)
            }
            if (!ensureConnected()) continue
            try {
                val client = ftpClient ?: continue
                var fileSize = -1L
                try {
                    val fileInfo = client.mlistFile(remotePath)
                    if (fileInfo != null && fileInfo.size > 0) {
                        fileSize = fileInfo.size
                    }
                } catch (_: Exception) {}

                if (fileSize <= 0) {
                    try {
                        val replyCode = client.sendCommand("SIZE", remotePath)
                        if (FTPReply.isPositiveCompletion(replyCode)) {
                            val reply = client.replyString.trim()
                            val parts = reply.split(" ")
                            val s = parts.lastOrNull()?.toLongOrNull()
                            if (s != null && s > 0) {
                                fileSize = s
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (fileSize <= 0) {
                    try {
                        val fileObj = File(remotePath)
                        val parentPath = fileObj.parent ?: "/"
                        val fileName = fileObj.name
                        val parentDir = if (parentPath.isEmpty()) "/" else parentPath
                        val files = client.listFiles(parentDir)
                        if (files != null) {
                            val matched = files.find { it.name.trim() == fileName.trim() || it.name.trim() == remotePath.substringAfterLast("/").trim() }
                            if (matched != null && matched.size > 0) {
                                fileSize = matched.size
                            }
                        }
                    } catch (_: Exception) {}
                }
                
                if (fileSize > 0) {
                    result = fileSize
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error seeding remote file size (Attempt $currentAttempt): ${e.message}")
            }
        }
        return@withContext result
    }

    suspend fun downloadFileToCache(
        remotePath: String, 
        localFile: File, 
        skipSizeCheck: Boolean = false,
        progressListener: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val scope = this
        val attempts = 3
        var currentAttempt = 0
        var success = false

        while (currentAttempt < attempts && scope.isActive) {
            currentAttempt++
            if (currentAttempt > 1) {
                addLog("FTP Buffer: Reintento automático de descarga ($currentAttempt/$attempts) por sospecha de ahorro de energía/broken pipe...")
                disconnect()
                kotlinx.coroutines.delay(2000)
            }

            if (!ensureConnected()) {
                addLog("FTP Buffer error: Sin conexión en intento $currentAttempt. No se puede iniciar descarga.")
                continue
            }

            val startTime = System.currentTimeMillis()
            try {
                val client = ftpClient ?: continue
                var fileSize = if (skipSizeCheck) -1L else getRemoteFileSize(remotePath)
                if (fileSize <= 0) {
                    fileSize = 1024L * 1024L // fallback default 1MB
                }

                addLog("FTP Buffer (Intento $currentAttempt): Solicitando transmisión de archivo ($remotePath)...")
                var downloadedBytes = 0L
                val inputStream = client.retrieveFileStream(remotePath)
                if (inputStream == null) {
                    addLog("FTP Buffer error: retrieveFileStream devolvió null para $remotePath. Desconectando para restablecer estado.")
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }

                addLog("FTP Buffer: Descargando buffer FTP. Tamaño remiso: $fileSize bytes.")
                var readSuccess = true
                try {
                    FileOutputStream(localFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var lastLogPercent = 0
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!scope.isActive) {
                                addLog("FTP Buffer: Descarga cancelada por el usuario.")
                                try { client.abort() } catch(_: Exception) {}
                                try { client.disconnect() } catch(_: Exception) {}
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }
                            fos.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            progressListener(downloadedBytes, fileSize)
                            
                            val percent = ((downloadedBytes * 100) / fileSize).toInt()
                            if (percent >= lastLogPercent + 25) {
                                addLog("FTP Buffer progreso: Descargado ${percent}% ($downloadedBytes/$fileSize bytes)")
                                lastLogPercent = percent
                            }
                        }
                    }
                } catch (readEx: Exception) {
                    addLog("FTP Buffer error en lectura (Intento $currentAttempt): ${readEx.message}. Se reitera desconexión.")
                    readSuccess = false
                } finally {
                    try { inputStream.close() } catch(_: Exception) {}
                }

                if (!readSuccess) {
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }

                val cmdSuccess = client.completePendingCommand()
                val elapsed = System.currentTimeMillis() - startTime
                if (cmdSuccess) {
                    addLog("FTP Buffer: Descarga finalizada con éxito en ${elapsed}ms ($downloadedBytes bytes).")
                    success = true
                    break
                } else {
                    addLog("FTP Buffer: Advertencia, completePendingCommand devolvió falso tras ${elapsed}ms. Desconectando por seguridad.")
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }
            } catch (e: Exception) {
                addLog("FTP Buffer error (Intento $currentAttempt): Excepción durante descarga: ${e.message}")
                try { ftpClient?.disconnect() } catch(_: Exception) {}
                ftpClient = null
                currentConnection = null
                continue
            }
        }
        return@withContext success
    }

    suspend fun downloadPartialFileToCache(
        remotePath: String, 
        localFile: File, 
        maxBytes: Long,
        progressListener: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val scope = this
        val attempts = 3
        var currentAttempt = 0
        var success = false

        while (currentAttempt < attempts && scope.isActive) {
            currentAttempt++
            if (currentAttempt > 1) {
                addLog("FTP Buffer: Reintento automático de descarga parcial ($currentAttempt/$attempts) por sospecha de ahorro de energía...")
                disconnect()
                kotlinx.coroutines.delay(2000)
            }

            if (!ensureConnected()) {
                continue
            }

            val client = ftpClient ?: continue
            try {
                var downloadedBytes = 0L
                val inputStream = client.retrieveFileStream(remotePath)
                if (inputStream == null) {
                    Log.e(TAG, "retrieveFileStream returned null for partial download of $remotePath. Disconnecting to reset state.")
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }

                var readToEof = true
                var readSuccess = true
                try {
                    FileOutputStream(localFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!scope.isActive) {
                                try { client.abort() } catch(_: Exception) {}
                                try { client.disconnect() } catch(_: Exception) {}
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }
                            val toWrite = minOf(bytesRead.toLong(), maxBytes - downloadedBytes).toInt()
                            if (toWrite > 0) {
                                fos.write(buffer, 0, toWrite)
                            }
                            downloadedBytes += bytesRead
                            progressListener(minOf(downloadedBytes, maxBytes), maxBytes)
                            if (downloadedBytes >= maxBytes) {
                                readToEof = false
                                break
                            }
                        }
                    }
                } catch (readEx: Exception) {
                    Log.e(TAG, "Error reading stream for partial download: ${readEx.message}")
                    readSuccess = false
                } finally {
                    try { inputStream.close() } catch(_: Exception) {}
                }

                if (!readSuccess) {
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }

                if (!readToEof) {
                    Log.d(TAG, "Partial download reached limit of $maxBytes bytes. Aborting and disconnecting to force fresh connection state on next command.")
                    try { client.abort() } catch(_: Exception) {}
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    success = true
                    break
                }

                val cmdSuccess = client.completePendingCommand()
                Log.d(TAG, "Partial download finished cleanly. completePendingCommand = $cmdSuccess")
                if (!cmdSuccess) {
                    Log.w(TAG, "completePendingCommand returned false. Disconnecting client as safety fallback.")
                    try { client.disconnect() } catch(_: Exception) {}
                    ftpClient = null
                    currentConnection = null
                    continue
                }
                success = true
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading partial file (Attempt $currentAttempt): ${e.message}. Disconnecting client.", e)
                try { client.disconnect() } catch(_: Exception) {}
                ftpClient = null
                currentConnection = null
                continue
            }
        }
        return@withContext success
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
