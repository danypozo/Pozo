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

object RadioLogger {
    private const val TAG = "RadioLogger"
    
    private val _isLoggingActive = MutableStateFlow(false)
    val isLoggingActive: StateFlow<Boolean> = _isLoggingActive
    
    private val _secondsRemaining = MutableStateFlow(0)
    val secondsRemaining: StateFlow<Int> = _secondsRemaining
    
    private var loggerJob: Job? = null
    private var currentLogFile: File? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    fun toggleLogging(context: Context) {
        if (_isLoggingActive.value) {
            stopLogging()
        } else {
            startLogging(context)
        }
    }
    
    fun startLogging(context: Context) {
        if (_isLoggingActive.value) return
        
        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(logsDir, "radio_log_$timestamp.txt")
            currentLogFile = file
            
            // Build initial information
            val headerBuilder = StringBuilder()
            headerBuilder.append("[${timestamp}] --- INICIO LOG DE DEPURACIÓN (5 MINUTOS) ---\n")
            headerBuilder.append("[${timestamp}] Dispositivo: ${android.os.Build.MODEL} (SDK ${android.os.Build.VERSION.SDK_INT})\n")
            headerBuilder.append("[${timestamp}] Versión de Android: ${android.os.Build.VERSION.RELEASE}\n")
            headerBuilder.append("[${timestamp}] Estado Inicial Radio: isLiveMode = ${AudioPlayerManager.isRadioLiveMode.value}\n")
            headerBuilder.append("--------------------------------------------------\n")
            
            file.writeText(headerBuilder.toString())
            
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
            
            log("Log de 5 minutos iniciado con éxito. Archivo: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando logger: ${e.message}")
        }
    }
    
    fun stopLogging() {
        if (!_isLoggingActive.value) return
        _isLoggingActive.value = false
        _secondsRemaining.value = 0
        loggerJob?.cancel()
        loggerJob = null
        
        val file = currentLogFile
        if (file != null && file.exists()) {
            scope.launch {
                try {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    FileWriter(file, true).use { writer ->
                        writer.append("\n[$timestamp] --- FIN LOG DE DEPURACIÓN (TIEMPO EXPIRADO O MANUAL) ---\n")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizando archivo log: ${e.message}")
                }
                currentLogFile = null
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
            val file = currentLogFile
            if (file != null && file.exists()) {
                scope.launch {
                    try {
                        FileWriter(file, true).use { writer ->
                            writer.append(formattedLine).append("\n")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error escribiendo en archivo log: ${e.message}")
                    }
                }
            }
        }
    }
    
    fun getLogFiles(context: Context): List<File> {
        val logsDir = File(context.filesDir, "logs")
        if (!logsDir.exists()) return emptyList()
        return logsDir.listFiles()?.filter { it.isFile && it.name.startsWith("radio_log_") }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    fun clearLogs(context: Context) {
        val logsDir = File(context.filesDir, "logs")
        if (logsDir.exists()) {
            logsDir.listFiles()?.forEach { it.delete() }
        }
        log("Todos los archivos de logs de radio han sido eliminados.")
    }
}
