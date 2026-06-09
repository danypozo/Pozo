package com.example.data.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RadioLogFile(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val file: File? = null,
    val uriString: String? = null
) {
    fun readText(context: Context): String {
        return if (file != null) {
            try {
                file.readText()
            } catch (e: Exception) {
                "Error leyendo log local: ${e.message}"
            }
        } else if (uriString != null) {
            try {
                context.contentResolver.openInputStream(android.net.Uri.parse(uriString)).use { stream ->
                    stream?.bufferedReader()?.use { it.readText() } ?: ""
                }
            } catch (e: Exception) {
                "Error leyendo log SAF: ${e.message}"
            }
        } else {
            ""
        }
    }
}

object RadioLogger {
    private const val TAG = "RadioLogger"
    
    private val _isLoggingActive = MutableStateFlow(false)
    val isLoggingActive: StateFlow<Boolean> = _isLoggingActive
    
    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining
    
    private var loggerJob: Job? = null
    private var currentFile: File? = null
    private var currentUriString: String? = null
    private var appContextRef: java.lang.ref.WeakReference<Context>? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun getLogsDirectory(context: Context): File {
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val customPath = prefs.getString("radio_logs_destination_dir", null)
        val dir = if (!customPath.isNullOrBlank()) {
            File(customPath)
        } else {
            File(context.filesDir, "logs")
        }
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creacion de logsDir: ${e.message}")
        }
        return dir
    }
    
    fun toggleLogging(context: Context) {
        if (_isLoggingActive.value) {
            stopLogging()
        } else {
            startLogging(context)
        }
    }
    
    fun startLogging(context: Context) {
        if (_isLoggingActive.value) return
        appContextRef = java.lang.ref.WeakReference(context.applicationContext)
        val appContext = context.applicationContext
        
        try {
            val prefs = appContext.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val customUriStr = prefs.getString("radio_logs_destination_uri", null)
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val logFileName = "radio_log_$timestamp.txt"
            
            // Build initial information
            val headerBuilder = StringBuilder()
            headerBuilder.append("[${timestamp}] --- INICIO LOG DE DEPURACIÓN (5 MINUTOS) ---\n")
            headerBuilder.append("[${timestamp}] Dispositivo: ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            headerBuilder.append("[${timestamp}] Versión de Android: ${android.os.Build.VERSION.RELEASE}\n")
            headerBuilder.append("[${timestamp}] Estado Inicial Radio: isLiveMode = ${AudioPlayerManager.isRadioLiveMode.value}\n")
            headerBuilder.append("--------------------------------------------------\n")
            val headerText = headerBuilder.toString()
            
            if (!customUriStr.isNullOrBlank()) {
                try {
                    val uri = android.net.Uri.parse(customUriStr)
                    val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, uri)
                    if (document != null && document.exists()) {
                        val newFile = document.createFile("text/plain", logFileName)
                        if (newFile != null) {
                            currentUriString = newFile.uri.toString()
                            currentFile = null
                            appContext.contentResolver.openOutputStream(newFile.uri).use { stream ->
                                stream?.write(headerText.toByteArray())
                            }
                        } else {
                            fallbackToInternal(appContext, logFileName, headerText)
                        }
                    } else {
                        fallbackToInternal(appContext, logFileName, headerText)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FALLBACK: Error SAF al crear archivo de log: ${e.message}")
                    fallbackToInternal(appContext, logFileName, headerText)
                }
            } else {
                fallbackToInternal(appContext, logFileName, headerText)
            }
            
            _isLoggingActive.value = true
            _secondsRemaining.value = 300 // 5 minutes
            
            loggerJob?.cancel()
            loggerJob = scope.launch {
                while (_secondsRemaining.value > 0 && _isLoggingActive.value) {
                    delay(1000)
                    _secondsRemaining.value -= 1
                }
                if (_isLoggingActive.value) {
                    stopLogging()
                }
            }
            
            log("Log de 5 minutos iniciado con éxito. Archivo: $logFileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando logger: ${e.message}")
        }
    }
    
    private fun fallbackToInternal(context: Context, fileName: String, headerText: String) {
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
        val file = File(logsDir, fileName)
        currentFile = file
        currentUriString = null
        file.writeText(headerText)
    }
    
    fun stopLogging() {
        if (!_isLoggingActive.value) return
        _isLoggingActive.value = false
        _secondsRemaining.value = 0
        loggerJob?.cancel()
        loggerJob = null
        
        val file = currentFile
        val uriStr = currentUriString
        val ctx = appContextRef?.get()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val footer = "\n[$timestamp] --- FIN LOG DE DEPURACIÓN (TIEMPO EXPIRADO O MANUAL) ---\n"
        
        scope.launch {
            try {
                if (file != null && file.exists()) {
                    FileWriter(file, true).use { writer ->
                        writer.append(footer)
                    }
                } else if (uriStr != null && ctx != null) {
                    val uri = android.net.Uri.parse(uriStr)
                    ctx.contentResolver.openFileDescriptor(uri, "wa").use { pfd ->
                        if (pfd != null) {
                            java.io.FileOutputStream(pfd.fileDescriptor).use { stream ->
                                stream.write(footer.toByteArray())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizando log: ${e.message}")
            } finally {
                currentFile = null
                currentUriString = null
            }
        }
    }
    
    fun log(message: String) {
        val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val formattedLine = "[$timeStr] $message"
        
        // Always print to system logcat
        Log.i(TAG, formattedLine)
        
        // Propagate to visual logs in FTP console as well
        FtpClientManager.addLog("[RADIO_LOG] $message")
        
        if (_isLoggingActive.value) {
            val file = currentFile
            val uriStr = currentUriString
            val ctx = appContextRef?.get()
            
            if (file != null) {
                scope.launch {
                    try {
                        FileWriter(file, true).use { writer ->
                            writer.append(formattedLine).append("\n")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error escribiendo en archivo log local: ${e.message}")
                    }
                }
            } else if (uriStr != null && ctx != null) {
                scope.launch {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        ctx.contentResolver.openFileDescriptor(uri, "wa").use { pfd ->
                            if (pfd != null) {
                                java.io.FileOutputStream(pfd.fileDescriptor).use { stream ->
                                    stream.write((formattedLine + "\n").toByteArray())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error escribiendo en SAF log: ${e.message}")
                    }
                }
            }
        }
    }
    
    fun getLogFiles(context: Context): List<RadioLogFile> {
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val customUriStr = prefs.getString("radio_logs_destination_uri", null)
        
        val list = mutableListOf<RadioLogFile>()
        
        if (!customUriStr.isNullOrBlank()) {
            try {
                val uri = android.net.Uri.parse(customUriStr)
                val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                if (document != null && document.exists()) {
                    val files = document.listFiles()
                    if (files != null) {
                        for (doc in files) {
                            if (doc.isFile && doc.name != null && doc.name!!.startsWith("radio_log_")) {
                                list.add(
                                    RadioLogFile(
                                        name = doc.name!!,
                                        sizeBytes = doc.length(),
                                        lastModified = doc.lastModified(),
                                        uriString = doc.uri.toString()
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo logs SAF: ${e.message}")
            }
        }
        
        try {
            val logsDir = File(context.filesDir, "logs")
            if (logsDir.exists() && logsDir.isDirectory) {
                val files = logsDir.listFiles()
                if (files != null) {
                    for (f in files) {
                        if (f.isFile && f.name.startsWith("radio_log_")) {
                            list.add(
                                RadioLogFile(
                                    name = f.name,
                                    sizeBytes = f.length(),
                                    lastModified = f.lastModified(),
                                    file = f
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo logs locales: ${e.message}")
        }
        
        return list.sortedByDescending { it.lastModified }
    }
    
    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val customUriStr = prefs.getString("radio_logs_destination_uri", null)
        
        if (!customUriStr.isNullOrBlank()) {
            try {
                val uri = android.net.Uri.parse(customUriStr)
                val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                if (document != null && document.exists()) {
                    document.listFiles()?.forEach { doc ->
                        if (doc.isFile && doc.name?.startsWith("radio_log_") == true) {
                            doc.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error limpiando logs SAF: ${e.message}")
            }
        }
        
        try {
            val logsDir = File(context.filesDir, "logs")
            if (logsDir.exists()) {
                logsDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error limpiando logs locales: ${e.message}")
        }
        
        log("Todos los archivos de logs de radio han sido eliminados.")
    }
}
