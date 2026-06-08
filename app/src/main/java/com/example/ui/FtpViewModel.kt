package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.model.FtpConnection
import com.example.data.model.FtpFileItem
import com.example.data.model.Playlist
import com.example.data.model.PlaylistItem
import com.example.data.repository.AudioPlayerManager
import com.example.data.repository.FtpClientManager
import com.example.data.repository.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class FtpViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "FtpViewModel"
    private val folderLogoMutex = kotlinx.coroutines.sync.Mutex()
    private val db = AppDatabase.getDatabase(application)
    private val connectionDao = db.ftpConnectionDao()
    private val playlistRepo = PlaylistRepository(db.playlistDao())
    private val localMusicDao = db.localMusicTrackDao()
    private val favoriteDao = db.favoriteTrackDao()
    private val recentTrackDao = db.recentTrackDao()

    // Saved connections flow
    val savedConnections: StateFlow<List<FtpConnection>> = connectionDao.getAllConnections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Scanned Local Music, Favorites and Recent Played
    val scannedLocalTracks: StateFlow<List<com.example.data.model.LocalMusicTrack>> = localMusicDao.getAllLocalTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteTracks: StateFlow<List<com.example.data.model.FavoriteTrack>> = favoriteDao.getAllFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTracks: StateFlow<List<com.example.data.model.RecentTrack>> = recentTrackDao.getRecentTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearRecentTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            recentTrackDao.clearAllRecentTracks()
        }
    }

    private var activeScanJob: kotlinx.coroutines.Job? = null

    fun stopScanning() {
        val job = activeScanJob
        if (job != null) {
            job.cancel()
            activeScanJob = null
        }
        setScanning(false)
        _totalFilesToScan.value = 0
        _currentScannedCount.value = 0
        addScannedFileLog("[CANCELADO] El usuario detuvo el escaneo de la biblioteca.")
    }

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _pendingDeletions = MutableStateFlow<List<com.example.data.model.LocalMusicTrack>>(emptyList())
    val pendingDeletions: StateFlow<List<com.example.data.model.LocalMusicTrack>> = _pendingDeletions

    fun clearPendingDeletions() {
        _pendingDeletions.value = emptyList()
    }

    fun confirmPendingDeletions() {
        val tracks = _pendingDeletions.value
        if (tracks.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                tracks.forEach {
                    localMusicDao.deleteTrackByPath(it.filePath)
                }
                _pendingDeletions.value = emptyList()
                checkForUnindexedFiles()
            }
        }
    }

    fun clearMusicLibrary(onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = localMusicDao.getAllLocalTracksOnce().size
            localMusicDao.clearAllLocalTracks()
            launch(Dispatchers.Main) {
                onComplete(count)
            }
        }
    }

    // Connection process state
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _ftpTimeoutRemainingSec = MutableStateFlow<Long>(0L)
    val ftpTimeoutRemainingSec: StateFlow<Long> = _ftpTimeoutRemainingSec

    private val _isViewingExplorer = MutableStateFlow(false)
    val isViewingExplorer: StateFlow<Boolean> = _isViewingExplorer

    fun setViewingExplorer(viewing: Boolean) {
        _isViewingExplorer.value = viewing
    }

    private val _activeConnection = MutableStateFlow<FtpConnection?>(null)
    val activeConnection: StateFlow<FtpConnection?> = _activeConnection

    val lastConnectedHost: StateFlow<String?> = FtpClientManager.lastConnectedHostFlow

    // File Browser States
    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath

    private val _filesList = MutableStateFlow<List<FtpFileItem>>(emptyList())
    val filesList: StateFlow<List<FtpFileItem>> = _filesList

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles

    private val _isRecursiveScanning = MutableStateFlow(false)
    val isRecursiveScanning: StateFlow<Boolean> = _isRecursiveScanning

    // Clipboard for File operations
    private val _clipboardPath = MutableStateFlow<String?>(null)
    val clipboardPath: StateFlow<String?> = _clipboardPath

    private val _isMoveAction = MutableStateFlow(false) // true if cutting, false if copying
    val isMoveAction: StateFlow<Boolean> = _isMoveAction

    // Image Viewer Overlay State
    private val _viewingImageLocalFile = MutableStateFlow<File?>(null)
    val viewingImageLocalFile: StateFlow<File?> = _viewingImageLocalFile

    private val _isViewingImage = MutableStateFlow(false)
    val isViewingImage: StateFlow<Boolean> = _isViewingImage

    private val _imageLoadingState = MutableStateFlow(false)
    val imageLoadingState: StateFlow<Boolean> = _imageLoadingState

    // Playlists Flow
    val playlists: StateFlow<List<Playlist>> = playlistRepo.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active multimedia subtab state
    private val _multimediaSubTab = MutableStateFlow(0)
    val multimediaSubTab: StateFlow<Int> = _multimediaSubTab

    fun setMultimediaSubTab(tab: Int) {
        _multimediaSubTab.value = tab
        if (tab == 1) {
            if (!_isConnected.value) {
                viewModelScope.launch {
                    val lastConn = savedConnections.value.firstOrNull()
                    if (lastConn != null) {
                        connectFtp(lastConn)
                    }
                }
            }
        }
    }

    // Persistent Navigation & Subtab states to survive theme changes / key-recompositions
    private val _currentMainTab = MutableStateFlow(2) // Default = Multimedia
    val currentMainTab: StateFlow<Int> = _currentMainTab

    fun setCurrentMainTab(tab: Int) {
        _currentMainTab.value = tab
    }

    private val _activeSettingsTab = MutableStateFlow(0) // Default = FTP Servidors
    val activeSettingsTab: StateFlow<Int> = _activeSettingsTab

    fun setActiveSettingsTab(tab: Int) {
        _activeSettingsTab.value = tab
    }

    // Local Scan Directory Folder state
    private val _localScanDirectory = MutableStateFlow("")
    val localScanDirectory: StateFlow<String> = _localScanDirectory

    private val _isDeepScanEnabled = MutableStateFlow(false)
    val isDeepScanEnabled: StateFlow<Boolean> = _isDeepScanEnabled

    private val _totalFilesToScan = MutableStateFlow(0)
    val totalFilesToScan: StateFlow<Int> = _totalFilesToScan

    private val _currentScannedCount = MutableStateFlow(0)
    val currentScannedCount: StateFlow<Int> = _currentScannedCount

    private fun loadDeepScanSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        _isDeepScanEnabled.value = prefs.getBoolean("deep_scan_enabled", false)
    }

    fun setDeepScanEnabled(enabled: Boolean) {
        _isDeepScanEnabled.value = enabled
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("deep_scan_enabled", enabled).apply()
    }

    private val _unindexedCheckInterval = MutableStateFlow("semanal")
    val unindexedCheckInterval: StateFlow<String> = _unindexedCheckInterval

    private fun loadUnindexedCheckInterval() {
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        _unindexedCheckInterval.value = prefs.getString("unindexed_check_interval", "semanal") ?: "semanal"
    }

    fun setUnindexedCheckInterval(interval: String) {
        _unindexedCheckInterval.value = interval
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("unindexed_check_interval", interval).apply()
        checkForUnindexedFiles()
    }

    // Scanned files log for real-time visualization
    private val _scannedFilesLog = MutableStateFlow<List<String>>(emptyList())
    val scannedFilesLog: StateFlow<List<String>> = _scannedFilesLog

    private val pendingLogs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private var logUpdateJob: kotlinx.coroutines.Job? = null

    fun clearScannedFilesLog() {
        pendingLogs.clear()
        _scannedFilesLog.value = emptyList()
    }

    fun addScannedFileLog(log: String) {
        pendingLogs.offer(log)
    }

    private fun startLogUpdater() {
        logUpdateJob?.cancel()
        clearScannedFilesLog()
        logUpdateJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                kotlinx.coroutines.delay(150)
                val newLogs = mutableListOf<String>()
                while (pendingLogs.isNotEmpty()) {
                    pendingLogs.poll()?.let { newLogs.add(it) }
                }
                if (newLogs.isNotEmpty()) {
                    val current = _scannedFilesLog.value
                    _scannedFilesLog.value = (current + newLogs).takeLast(100)
                }
            }
        }
    }

    private fun stopLogUpdater() {
        logUpdateJob?.cancel()
        logUpdateJob = null
        val newLogs = mutableListOf<String>()
        while (pendingLogs.isNotEmpty()) {
            pendingLogs.poll()?.let { newLogs.add(it) }
        }
        if (newLogs.isNotEmpty()) {
            val current = _scannedFilesLog.value
            _scannedFilesLog.value = (current + newLogs).takeLast(100)
        }
    }

    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
        if (scanning) {
            startLogUpdater()
        } else {
            stopLogUpdater()
        }
    }

    // Online radios state
    private val _onlineRadios = MutableStateFlow<List<OnlineRadio>>(emptyList())
    val onlineRadios: StateFlow<List<OnlineRadio>> = _onlineRadios

    val visualLogs: StateFlow<List<String>> = FtpClientManager.visualLogs

    fun clearVisualLogs() {
        FtpClientManager.clearLogs()
    }

    // Scan Folders logic
    private val _scanFolders = MutableStateFlow<List<ScanFolder>>(emptyList())
    val scanFolders: StateFlow<List<ScanFolder>> = _scanFolders

    private val _itemToPlaylist = MutableStateFlow<FtpFileItem?>(null)
    val itemToPlaylist: StateFlow<FtpFileItem?> = _itemToPlaylist

    fun setItemToPlaylist(item: FtpFileItem?) {
        _itemToPlaylist.value = item
    }

    init {
        // Initialize player and ftp client managers
        AudioPlayerManager.initialize(application)
        FtpClientManager.initialize(application)
        
        // Load persistent configs and radios
        loadLocalScanDirectory()
        loadDeepScanSetting()
        loadUnindexedCheckInterval()
        loadOnlineRadios()
        loadScanFolders()

        // AUTOMATIC RECORDINGS DIRECTORY MIGRATION (USER REQUEST)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = application.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE)
                val migrated = prefs.getBoolean("recordings_v2_migrated", false)
                if (!migrated) {
                    val customPath = prefs.getString("recording_destination_dir", "") ?: ""
                    Log.d("FtpViewModel", "Starting background recording files migration. Custom path was: '$customPath'")
                    
                    // 1. Determine old and new directories
                    val oldDefaultDir = File(application.filesDir, "grabaciones")
                    val newDefaultDir = File(application.filesDir, "Grabaciones")
                    
                    // Helper function to migrate files in a folder
                    fun migrateFolder(oldDir: File, newDir: File) {
                        if (oldDir.exists() && oldDir.isDirectory) {
                            val files = oldDir.listFiles()
                            if (files != null) {
                                for (file in files) {
                                    if (file.isFile) {
                                        // Decide subfolder based on user request ('radio' vs 'recortes')
                                        val isTrim = file.name.startsWith("Recorte_") || file.name.contains("recorte") || file.name.contains("clip")
                                        val subFolder = File(newDir, if (isTrim) "recortes" else "radio")
                                        if (!subFolder.exists()) {
                                            subFolder.mkdirs()
                                        }
                                        val targetFile = File(subFolder, file.name)
                                        try {
                                            file.copyTo(targetFile, overwrite = true)
                                            file.delete()
                                            Log.d("FtpViewModel", "Migrated file: ${file.name} to ${subFolder.name}")
                                        } catch (e: Exception) {
                                            Log.e("FtpViewModel", "Error migrating file ${file.name}: ${e.message}")
                                        }
                                    }
                                }
                            }
                            // Delete old directory if empty
                            try {
                                if (oldDir.listFiles()?.isEmpty() == true) {
                                    oldDir.delete()
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    // A. Migrate original default folder to new default folder
                    migrateFolder(oldDefaultDir, newDefaultDir)

                    // B. Handle custom folder migration if the user configured one
                    if (customPath.isNotBlank()) {
                        val customDir = File(customPath)
                        if (customDir.exists() && customDir.isDirectory) {
                            // If user custom folder was "/some/path/grabaciones", we can automatically modernize it to "/some/path/Grabaciones"
                            val parent = customDir.parentFile
                            val newCustomDir = if (customDir.name.lowercase() == "grabaciones" && parent != null) {
                                val modern = File(parent, "Grabaciones")
                                if (!modern.exists()) {
                                    modern.mkdirs()
                                }
                                modern
                            } else {
                                customDir
                            }

                            // Run migration inside this custom directory to restructure it into subfolders "radio" and "recortes"
                            val files = customDir.listFiles()
                            if (files != null) {
                                for (file in files) {
                                    if (file.isFile) {
                                        val isTrim = file.name.startsWith("Recorte_") || file.name.contains("recorte") || file.name.contains("clip")
                                        val subFolder = File(newCustomDir, if (isTrim) "recortes" else "radio")
                                        if (!subFolder.exists()) {
                                            subFolder.mkdirs()
                                        }
                                        val targetFile = File(subFolder, file.name)
                                        try {
                                            file.copyTo(targetFile, overwrite = true)
                                            file.delete()
                                            Log.d("FtpViewModel", "Migrated custom file: ${file.name} to ${subFolder.name}")
                                        } catch (e: Exception) {
                                            Log.e("FtpViewModel", "Error migrating custom file ${file.name}: ${e.message}")
                                        }
                                    }
                                }
                            }

                            // If we created a new modernized "Grabaciones" custom directory name and removed old, update preference!
                            if (newCustomDir.absolutePath != customPath) {
                                prefs.edit().putString("recording_destination_dir", newCustomDir.absolutePath).apply()
                                Log.d("FtpViewModel", "Updated recording_destination_dir preference to modernize name: ${newCustomDir.absolutePath}")
                                // Clean old folder if empty
                                try {
                                    if (customDir.listFiles()?.isEmpty() == true) {
                                        customDir.delete()
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    // Mark migration as successfully done
                    prefs.edit().putBoolean("recordings_v2_migrated", true).apply()
                    Log.i("FtpViewModel", "Folder structure migration successfully completed with no UI freezes!")
                }
            } catch (e: Exception) {
                Log.e("FtpViewModel", "Error during directory structured migration: ${e.message}")
            }
        }

        // Background loop to poll physical FTP connection state every 3 seconds to keep UI 100% in sync
        viewModelScope.launch(Dispatchers.IO) {
            var inactiveSeconds = 0L
            while (true) {
                kotlinx.coroutines.delay(3000)
                val isScanningNow = _isScanning.value
                val isConnectedNow = FtpClientManager.isConnected()
                val activeConnNow = FtpClientManager.getActiveConnection()

                if (isScanningNow) {
                    // While scanning is active, we bypass reported transient disconnections to prevent UI flickering
                    if (isConnectedNow) {
                        _isConnected.value = true
                    }
                    if (activeConnNow != null && _activeConnection.value != activeConnNow) {
                        _activeConnection.value = activeConnNow
                    }
                    inactiveSeconds = 0L
                    FtpClientManager.updateLastActiveTime()
                } else {
                    if (_isConnected.value != isConnectedNow) {
                        _isConnected.value = isConnectedNow
                    }
                    if (_activeConnection.value != activeConnNow) {
                        _activeConnection.value = activeConnNow
                    }

                    if (isConnectedNow) {
                        val isPlaying = AudioPlayerManager.isPlaying.value
                        val prefs = application.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE)
                        val timeoutMins = prefs.getInt("ftp_timeout_minutes", 0)
                        if (isPlaying) {
                            inactiveSeconds = 0L
                            _ftpTimeoutRemainingSec.value = 0L
                            FtpClientManager.updateLastActiveTime()
                        } else {
                            inactiveSeconds += 3
                            if (timeoutMins > 0) {
                                val timeoutSecs = timeoutMins * 60L
                                val remaining = (timeoutSecs - inactiveSeconds).coerceAtLeast(0L)
                                _ftpTimeoutRemainingSec.value = remaining
                                if (inactiveSeconds >= timeoutSecs) {
                                    _ftpTimeoutRemainingSec.value = 0L
                                    kotlin.runCatching {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            disconnectFtp()
                                            android.widget.Toast.makeText(
                                                application,
                                                "FTP Desconectado automáticamente por inactividad de $timeoutMins minutos ⏱️",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                    inactiveSeconds = 0L
                                }
                            } else {
                                inactiveSeconds = 0L
                                _ftpTimeoutRemainingSec.value = 0L
                            }
                        }
                    } else {
                        inactiveSeconds = 0L
                        _ftpTimeoutRemainingSec.value = 0L
                        // We are disconnected physically. Let's check if we should auto-reconnect!
                        val lastConn = FtpClientManager.getLastSelectedConnection()
                        if (lastConn != null) {
                            val prefs = application.getSharedPreferences("ftp_hub_settings", android.content.Context.MODE_PRIVATE)
                            val timeoutMins = prefs.getInt("ftp_timeout_minutes", 0)
                            val elapsedMs = System.currentTimeMillis() - FtpClientManager.getLastActiveTime()
                            val limitMs = timeoutMins * 60 * 1000L
                            
                            if (timeoutMins == 0 || elapsedMs < limitMs) {
                                Log.d("FtpViewModel", "FTP disconnected physically, but within timeout limit. Attempting auto-reconnect in background...")
                                // Attempt to reconnect in background
                                FtpClientManager.connect(lastConn)
                            } else {
                                // Exceeded inactivity timeout! Disconnect clean so it doesn't try again.
                                kotlin.runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        disconnectFtp()
                                        android.widget.Toast.makeText(
                                            application,
                                            "FTP Desconectado por inactividad de más de $timeoutMins minutos ⏱️",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Restore connections if empty and auto backup when updated securely
        viewModelScope.launch(Dispatchers.IO) {
            val dbConnections = connectionDao.getAllConnectionsOnce()
            if (dbConnections.isEmpty()) {
                restoreConnectionsFromPrefsSuspend()
            }
            
            // Continually back up when list changes
            savedConnections.collect { connections ->
                backupConnectionsToPrefs(connections)
            }
        }

        // Launch periodic unindexed files check (weekly by default, dynamically configured)
        viewModelScope.launch {
            kotlinx.coroutines.delay(10000) // 10s wait for startup settle
            while (true) {
                checkForUnindexedFiles()
                val interval = _unindexedCheckInterval.value
                val delayMs = when (interval) {
                    "diario" -> 24 * 60 * 60 * 1000L
                    "semanal" -> 7 * 24 * 60 * 60 * 1000L
                    "mensual" -> 30 * 24 * 60 * 60 * 1000L
                    else -> 7 * 24 * 60 * 60 * 1000L
                }
                kotlinx.coroutines.delay(delayMs)
            }
        }
    }

    private fun loadLocalScanDirectory() {
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        val defaultPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).absolutePath
        _localScanDirectory.value = prefs.getString("local_scan_directory", defaultPath) ?: defaultPath
    }

    fun updateLocalScanDirectory(path: String) {
        _localScanDirectory.value = path
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("local_scan_directory", path).apply()
    }

    private fun loadOnlineRadios() {
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        var jsonStr = prefs.getString("online_radios", null)
        
        // Try fallback file backup
        val file = java.io.File(getApplication<Application>().filesDir, "online_radios_backup.json")
        if (jsonStr.isNullOrBlank() && file.exists()) {
            try {
                jsonStr = file.readText()
            } catch (e: Exception) {
                Log.e(TAG, "Error reading online_radios backup: ${e.message}")
            }
        }
        
        val list = mutableListOf<OnlineRadio>()
        var needSave = false
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name")
                    val url = obj.getString("url")
                    val logoUri = if (obj.has("logoUri")) obj.optString("logoUri", null) else null
                    list.add(OnlineRadio(name, url, logoUri))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading online radios: ${e.message}")
            }
        } else {
            // Start empty by default!
            needSave = true
        }
        if (needSave) {
            saveOnlineRadiosList(list)
        }
        _onlineRadios.value = list
    }

    fun addOnlineRadio(name: String, url: String, logoUri: String? = null) {
        val updated = _onlineRadios.value.toMutableList()
        updated.add(OnlineRadio(name, url, logoUri))
        _onlineRadios.value = updated
        saveOnlineRadiosList(updated)
    }

    fun removeOnlineRadio(radio: OnlineRadio) {
        val updated = _onlineRadios.value.filter { it.url != radio.url }
        _onlineRadios.value = updated
        saveOnlineRadiosList(updated)
    }

    private fun saveOnlineRadiosList(list: List<OnlineRadio>) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
            val array = org.json.JSONArray()
            for (radio in list) {
                val obj = org.json.JSONObject().apply {
                    put("name", radio.name)
                    put("url", radio.url)
                    if (radio.logoUri != null) {
                        put("logoUri", radio.logoUri)
                    }
                }
                array.put(obj)
            }
            prefs.edit().putString("online_radios", array.toString()).apply()
            
            // ALSO save to a persistent file in internal storage so it doesn't clear easily
            val file = java.io.File(getApplication<Application>().filesDir, "online_radios_backup.json")
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving online radios: ${e.message}")
        }
    }

    fun editOnlineRadio(oldRadio: OnlineRadio, newName: String, newUrl: String, newLogoUri: String?) {
        val list = _onlineRadios.value.toMutableList()
        val index = list.indexOfFirst { it.url == oldRadio.url }
        if (index >= 0) {
            list[index] = OnlineRadio(newName, newUrl, newLogoUri)
            _onlineRadios.value = list
            saveOnlineRadiosList(list)
            
            // Instantly update player display info if edited radio is playing
            com.example.data.repository.AudioPlayerManager.updateCurrentTrackNameAndUrl(oldRadio.url, newName, newUrl)
        }
    }

    fun moveRadioUp(radio: OnlineRadio) {
        val list = _onlineRadios.value.toMutableList()
        val index = list.indexOfFirst { it.url == radio.url }
        if (index > 0) {
            val item = list.removeAt(index)
            list.add(index - 1, item)
            _onlineRadios.value = list
            saveOnlineRadiosList(list)
        }
    }

    fun moveRadioDown(radio: OnlineRadio) {
        val list = _onlineRadios.value.toMutableList()
        val index = list.indexOfFirst { it.url == radio.url }
        if (index >= 0 && index < list.size - 1) {
            val item = list.removeAt(index)
            list.add(index + 1, item)
            _onlineRadios.value = list
            saveOnlineRadiosList(list)
        }
    }

    // Scan foldered indexing methods
    private fun loadScanFolders() {
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("scan_folders", null)
        val list = mutableListOf<ScanFolder>()
        if (jsonStr != null) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        ScanFolder(
                            id = obj.getString("id"),
                            path = obj.getString("path"),
                            isFtp = obj.getBoolean("isFtp"),
                            ftpConnectionId = if (obj.has("ftpConnectionId")) obj.getInt("ftpConnectionId") else null,
                            connectionName = if (obj.has("connectionName")) obj.getString("connectionName") else null
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading scan folders: ${e.message}")
            }
        }
        
        if (list.isEmpty()) {
            val defaultPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC).absolutePath
            list.add(
                ScanFolder(
                    id = java.util.UUID.randomUUID().toString(),
                    path = defaultPath,
                    isFtp = false
                )
            )
            saveScanFoldersList(list)
        }
        _scanFolders.value = list
    }

    private fun saveScanFoldersList(list: List<ScanFolder>) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
            val array = org.json.JSONArray()
            for (f in list) {
                val obj = org.json.JSONObject().apply {
                    put("id", f.id)
                    put("path", f.path)
                    put("isFtp", f.isFtp)
                    f.ftpConnectionId?.let { put("ftpConnectionId", it) }
                    f.connectionName?.let { put("connectionName", it) }
                }
                array.put(obj)
            }
            prefs.edit().putString("scan_folders", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving scan folders: ${e.message}")
        }
    }

    fun addLocalScanFolder(path: String) {
        val updated = _scanFolders.value.toMutableList()
        if (updated.none { it.path == path && !it.isFtp }) {
            updated.add(
                ScanFolder(
                    id = java.util.UUID.randomUUID().toString(),
                    path = path,
                    isFtp = false
                )
            )
            _scanFolders.value = updated
            saveScanFoldersList(updated)
        }
    }

    fun addFtpScanFolder(connection: FtpConnection, path: String) {
        val updated = _scanFolders.value.toMutableList()
        val ftpPath = "ftp://${connection.id}$path"
        if (updated.none { it.path == ftpPath && it.isFtp }) {
            updated.add(
                ScanFolder(
                    id = java.util.UUID.randomUUID().toString(),
                    path = ftpPath,
                    isFtp = true,
                    ftpConnectionId = connection.id,
                    connectionName = connection.name
                )
            )
            _scanFolders.value = updated
            saveScanFoldersList(updated)
        }
    }

    fun removeScanFolder(folder: ScanFolder) {
        val updated = _scanFolders.value.filter { it.id != folder.id }
        _scanFolders.value = updated
        saveScanFoldersList(updated)
    }

    fun scanAllFolders(
        context: android.content.Context,
        showOnlyFtp: Boolean? = null,
        targetFolderId: String? = null,
        forceDeepScan: Boolean = false
    ) {
        activeScanJob?.cancel()
        activeScanJob = viewModelScope.launch(Dispatchers.IO) {
            setScanning(true)
            _totalFilesToScan.value = 0
            _currentScannedCount.value = 0
            try {
                addScannedFileLog("🔍 FASE 1: Descubrimiento de archivos...")
                val folders = _scanFolders.value.filter {
                    if (targetFolderId != null) {
                        it.id == targetFolderId
                    } else {
                        when (showOnlyFtp) {
                            true -> it.isFtp
                            false -> !it.isFtp
                            null -> true
                        }
                    }
                }
                addScannedFileLog("📂 Analizando ${folders.size} carpetas de escaneo configuradas...")
                val allowedExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                val discoveredList = mutableListOf<DiscoveredTrack>()
                val successfullyScannedFolders = mutableListOf<ScanFolder>()

                // Helper to match a filePath to its respective ScanFolder
                fun findScanFolderForPath(filePath: String): ScanFolder? {
                    return folders.filter { folder ->
                        if (folder.isFtp) {
                            filePath.startsWith(folder.path)
                        } else {
                            filePath.startsWith(folder.path)
                        }
                    }.maxByOrNull { it.path.length }
                }

                // 1. GATHER ALL FILES CHUNKS (PRE-COUNTING STAGE - ULTRA-FAST DISCOVERY)
                for (folder in folders) {
                    if (!isActive) return@launch
                    if (folder.isFtp) {
                        val connId = folder.ftpConnectionId
                        if (connId != null) {
                            val conn = connectionDao.getAllConnectionsOnce().firstOrNull { it.id == connId }
                            if (conn != null) {
                                try {
                                    addScannedFileLog("🌐 Conectando a servidor FTP: ${conn.name} (${conn.host})...")
                                    var isConnected = FtpClientManager.connect(conn)
                                    if (isConnected) {
                                        val ftpPrefix = "ftp://$connId"
                                        val remotePath = folder.path.removePrefix(ftpPrefix)
                                        addScannedFileLog("📂 Conectado con éxito. Explorando: $remotePath")
                                        
                                        val folderDiscoveredTracks = mutableListOf<DiscoveredTrack>()
                                        
                                        suspend fun traverseFtp(path: String, depth: Int) {
                                            if (depth > 4) return
                                            addScannedFileLog("📁 Escaneando carpeta remota: $path")
                                            val list = FtpClientManager.listFiles(path)
                                            var foundInFolder = 0
                                            for (item in list) {
                                                if (item.isDirectory) {
                                                    traverseFtp(item.path, depth + 1)
                                                } else {
                                                    val ext = item.name.substringAfterLast('.', "").lowercase()
                                                    if (ext in allowedExts) {
                                                        foundInFolder++
                                                        addScannedFileLog("  🎵 Encontrado: ${item.name}")
                                                        folderDiscoveredTracks.add(
                                                            DiscoveredTrack(
                                                                isFtp = true,
                                                                filePath = "ftp://$connId${item.path}",
                                                                name = item.name,
                                                                size = item.size,
                                                                dateModified = 0L,
                                                                connectionName = folder.connectionName ?: conn.name,
                                                                ftpConnectionId = connId,
                                                                remotePath = item.path,
                                                                albumName = path.substringAfterLast('/', "Música Remota"),
                                                                scanFolderId = folder.id
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                            if (foundInFolder > 0) {
                                                addScannedFileLog("💡 ¡Descubiertas $foundInFolder pistas en $path!")
                                            }
                                        }
                                        
                                        traverseFtp(remotePath, 0)
                                        
                                        if (folderDiscoveredTracks.isEmpty()) {
                                            addScannedFileLog("⚠️ Se detectaron 0 pistas. Reintentando conexión y escaneo en un segundo intento...")
                                            try {
                                                FtpClientManager.disconnect()
                                            } catch (ex: Exception) {
                                                Log.w(TAG, "Error disconnecting prior to retry: ${ex.message}")
                                            }
                                            kotlinx.coroutines.delay(1500)
                                            isConnected = FtpClientManager.connect(conn)
                                            if (isConnected) {
                                                addScannedFileLog("🔄 Reconectado con éxito para el segundo intento de escaneo.")
                                                traverseFtp(remotePath, 0)
                                            } else {
                                                addScannedFileLog("❌ Falló la reconexión en el segundo intento.")
                                            }
                                        }
                                        
                                        if (folderDiscoveredTracks.isNotEmpty()) {
                                            discoveredList.addAll(folderDiscoveredTracks)
                                            successfullyScannedFolders.add(folder)
                                            addScannedFileLog("✅ Escaneado de carpeta FTP finalizado. Se descubrieron ${folderDiscoveredTracks.size} canciones.")
                                        } else {
                                            addScannedFileLog("⚠️ Omitiendo eliminación de pistas huérfanas/obsoletas para esta carpeta de escaneo para evitar el borrado de tu biblioteca (se detectaron 0 pistas de música). Asegúrate de que el servidor FTP esté encendido y que el puerto y la ruta sean válidos.")
                                        }
                                    } else {
                                        addScannedFileLog("❌ Error: No se pudo conectar al servidor FTP scan para: ${folder.path}")
                                        Log.e(TAG, "Could not connect to FTP scan folder: ${folder.path}")
                                    }
                                } catch (e: Exception) {
                                    addScannedFileLog("❌ Falló el escaneo FTP para ${folder.path}: ${e.message}")
                                    Log.e(TAG, "Failed scanning FTP scan folder ${folder.path}: ${e.message}")
                                }
                            }
                        }
                    } else {
                        val customPath = folder.path
                        val isSafUri = customPath.startsWith("content://")
                        if (isSafUri) {
                            try {
                                addScannedFileLog("📱 Accediendo a almacenamiento local SAF...")
                                val treeUri = Uri.parse(customPath)
                                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                                    addScannedFileLog("📂 Analizando árbol de archivos SAF de: ${rootDoc.name ?: "Raíz"}")
                                    fun traverseDocFolder(doc: DocumentFile) {
                                        addScannedFileLog("📁 Escaneando carpeta local SAF: ${doc.name ?: "Directorio"}")
                                        var foundInFolder = 0
                                        doc.listFiles().forEach { child ->
                                            if (child.isDirectory) {
                                                traverseDocFolder(child)
                                            } else if (child.isFile) {
                                                val name = child.name ?: ""
                                                val ext = name.substringAfterLast('.', "").lowercase()
                                                if (ext in allowedExts) {
                                                    foundInFolder++
                                                    addScannedFileLog("  🎵 Encontrado: $name")
                                                    discoveredList.add(
                                                        DiscoveredTrack(
                                                            isFtp = false,
                                                            isSaf = true,
                                                            filePath = child.uri.toString(),
                                                            name = name,
                                                            size = child.length(),
                                                            dateModified = child.lastModified(),
                                                            scanFolderId = folder.id
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                        if (foundInFolder > 0) {
                                            addScannedFileLog("💡 ¡Descubiertas $foundInFolder pistas locals en ${doc.name ?: "SAF"}!")
                                        }
                                    }
                                    traverseDocFolder(rootDoc)
                                    successfullyScannedFolders.add(folder)
                                }
                            } catch (e: Exception) {
                                addScannedFileLog("❌ Falló el escaneo SAF para SAF: ${e.message}")
                                Log.e(TAG, "Failed SAF local scan for ${folder.path}: ${e.message}")
                            }
                        } else {
                            try {
                                addScannedFileLog("📱 Accediendo a almacenamiento físico directo...")
                                val scanDir = File(customPath)
                                if (scanDir.exists() && scanDir.isDirectory) {
                                    addScannedFileLog("📂 Analizando archivos en ruta física: $customPath")
                                    var physicalTrackCount = 0
                                    scanDir.walkTopDown().forEach { file ->
                                        if (file.isFile && file.exists()) {
                                            val ext = file.extension.lowercase()
                                            if (ext in allowedExts) {
                                                physicalTrackCount++
                                                addScannedFileLog("  🎵 Descubierto físico: ${file.name}")
                                                discoveredList.add(
                                                    DiscoveredTrack(
                                                        isFtp = false,
                                                        isSaf = false,
                                                        filePath = file.absolutePath,
                                                        name = file.name,
                                                        size = file.length(),
                                                        dateModified = file.lastModified(),
                                                        scanFolderId = folder.id
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    addScannedFileLog("💡 ¡Recorridos completados! $physicalTrackCount pistas físicas encontradas.")
                                    successfullyScannedFolders.add(folder)
                                }
                            } catch (e: Exception) {
                                addScannedFileLog("❌ Falló de escaneo de disco en ${folder.path}: ${e.message}")
                                Log.e(TAG, "Failed walk scan for ${folder.path}: ${e.message}")
                            }
                        }
                    }
                }

                // 1.5. LOAD EXISTING METADATA CACHE
                addScannedFileLog("✨ Cargando caché de metadatos existentes para acelerar el proceso...")
                val existingTracks = try {
                    localMusicDao.getAllLocalTracksOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching cached tracks for rescan optimization: ${e.message}")
                    emptyList()
                }
                val existingTracksMap = existingTracks.associateBy { it.filePath }

                // 2. ENRICHMENT & PARSING STAGE
                val totalCount = discoveredList.size
                addScannedFileLog("✅ FASE 1 COMPLETADA: Se descubrieron un total de $totalCount canciones.")
                addScannedFileLog("🚀 FASE 2: Procesamiento y extracción/modificación de base de datos...")
                _totalFilesToScan.value = totalCount
                _currentScannedCount.value = 0
                val deepScanEnabled = forceDeepScan || _isDeepScanEnabled.value
                val tracksToInsert = mutableListOf<com.example.data.model.LocalMusicTrack>()

                for (idx in discoveredList.indices) {
                    if (!isActive) return@launch
                    val dt = discoveredList[idx]
                    val itemIndex = idx + 1
                    _currentScannedCount.value = itemIndex

                    val cachedTrack = existingTracksMap[dt.filePath]
                    if (cachedTrack != null && cachedTrack.size == dt.size && cachedTrack.durationText != "03:00") {
                        // VERIFICATION OMIT: Already indexed and scanned with proper duration metadata, skip downloading/processing
                        addScannedFileLog("[OMITIDO] ${dt.name}")
                        continue
                    }

                    if (dt.isFtp) {
                        var durationTxt = cachedTrack?.durationText ?: "03:00"
                        val title = dt.name.substringBeforeLast('.')
                        var artist = cachedTrack?.artist ?: "Servidor FTP: ${dt.connectionName}"
                        var album = cachedTrack?.album ?: (dt.albumName ?: "Música Remota")

                        if (deepScanEnabled) {
                            val hash = dt.filePath.hashCode()
                            val lastDot = dt.filePath.lastIndexOf('.')
                            val ext = if (lastDot != -1) {
                                val e = dt.filePath.substring(lastDot + 1).lowercase()
                                if (e in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")) e else "mp3"
                            } else "mp3"
                            val scanTempFile = java.io.File(context.cacheDir, "scan_dur_${hash}.$ext")
                            try {
                                 val success = FtpClientManager.downloadPartialFileToCache(
                                     remotePath = dt.remotePath!!,
                                     localFile = scanTempFile,
                                     maxBytes = 1024 * 1024L
                                 )
                                if (success && scanTempFile.exists() && scanTempFile.length() > 0) {
                                    val retriever = android.media.MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(scanTempFile.absolutePath)
                                        val durMsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                        durMsStr?.toLongOrNull()?.let { ms ->
                                            val secs = (ms / 1000) % 60
                                            val mins = (ms / 1000) / 60
                                            durationTxt = String.format("%02d:%02d", mins, secs)
                                        }
                                        val art = retriever.embeddedPicture
                                        if (art != null) {
                                            val coverFile = com.example.data.repository.AudioPlayerManager.getScannedCoverFile(context, hash, ext)
                                            coverFile.writeBytes(art)
                                        }
                                    } catch (_: Exception) {
                                    } finally {
                                        try { retriever.release() } catch (_: Exception) {}
                                    }
                                }
                            } catch (_: Exception) {
                            } finally {
                                try { if (scanTempFile.exists()) scanTempFile.delete() } catch (_: Exception) {}
                            }
                        }
                        addScannedFileLog("[FTP] ${dt.name}")

                        tracksToInsert.add(
                            com.example.data.model.LocalMusicTrack(
                                filePath = dt.filePath,
                                title = title,
                                artist = artist,
                                album = album,
                                genre = "Streaming FTP",
                                year = "N/A",
                                durationText = durationTxt,
                                size = dt.size,
                                dateModified = dt.dateModified
                            )
                        )
                    } else if (dt.isSaf) {
                        var durationTxt = cachedTrack?.durationText ?: "03:00"
                        val title = dt.name.substringBeforeLast('.')
                        var artist = cachedTrack?.artist ?: "Dispositivo Local"
                        var album = cachedTrack?.album ?: "Desconocido"

                        if (deepScanEnabled) {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                val uri = Uri.parse(dt.filePath)
                                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                    retriever.setDataSource(pfd.fileDescriptor)
                                    artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Dispositivo Local"
                                    album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Desconocido"
                                    val durMsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    durMsStr?.toLongOrNull()?.let { ms ->
                                        val secs = (ms / 1000) % 60
                                        val mins = (ms / 1000) / 60
                                        durationTxt = String.format("%02d:%02d", mins, secs)
                                    }
                                }
                            } catch (ex: Exception) {
                                Log.w(TAG, "Error parsing SAF metadata for ${dt.name}: ${ex.message}")
                            } finally {
                                try { retriever.release() } catch (e: Exception) {}
                            }
                        }
                        addScannedFileLog("[SAF] ${dt.name}")

                        tracksToInsert.add(
                            com.example.data.model.LocalMusicTrack(
                                filePath = dt.filePath,
                                title = title,
                                artist = artist,
                                album = album,
                                genre = "Local SAF",
                                year = "Desconocido",
                                durationText = durationTxt,
                                size = dt.size,
                                dateModified = dt.dateModified
                            )
                        )
                    } else {
                        val title = dt.name.substringBeforeLast('.')
                        var artist = cachedTrack?.artist ?: "Dispositivo Local"
                        var album = cachedTrack?.album ?: "Desconocido"
                        var durationTxt = cachedTrack?.durationText ?: "03:00"

                        if (deepScanEnabled) {
                            val mmr = android.media.MediaMetadataRetriever()
                            try {
                                mmr.setDataSource(dt.filePath)
                                artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Dispositivo Local"
                                album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Desconocido"
                                val durMsStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                durMsStr?.toLongOrNull()?.let { ms ->
                                    val secs = (ms / 1000) % 60
                                    val mins = (ms / 1000) / 60
                                    durationTxt = String.format("%02d:%02d", mins, secs)
                                }
                            } catch (ex: Exception) {
                                Log.w(TAG, "Error metadata for ${dt.name}: ${ex.message}")
                            } finally {
                                try { mmr.release() } catch (e: Exception) {}
                            }
                        }
                        addScannedFileLog("[Local] ${dt.name}")

                        tracksToInsert.add(
                            com.example.data.model.LocalMusicTrack(
                                filePath = dt.filePath,
                                title = title,
                                artist = artist,
                                album = album,
                                genre = "Local File",
                                year = "Desconocido",
                                durationText = durationTxt,
                                size = dt.size,
                                dateModified = dt.dateModified
                            )
                        )
                    }
                }

                // 3. SAFE SMART CLEANUP: Only remove tracks whose folders were successfully scanned but aren't found in discovered list
                val successfullyScannedIds = successfullyScannedFolders.map { it.id }.toSet()
                val discoveredPaths = discoveredList.map { it.filePath }.toSet()

                val tracksToDelete = existingTracks.filter { et ->
                    val mainFolder = findScanFolderForPath(et.filePath)
                    if (mainFolder != null) {
                        successfullyScannedIds.contains(mainFolder.id) && !discoveredPaths.contains(et.filePath)
                    } else {
                        false
                    }
                }

                if (tracksToDelete.isNotEmpty()) {
                    _pendingDeletions.value = tracksToDelete
                    Log.d(TAG, "Non-destructive scan: Found ${tracksToDelete.size} tracks pending deletion confirmation.")
                } else {
                    _pendingDeletions.value = emptyList()
                }

                if (tracksToInsert.isNotEmpty()) {
                    localMusicDao.insertTracks(tracksToInsert)
                }

                launch(Dispatchers.Main) {
                    val finalCountStr = if (tracksToInsert.isNotEmpty()) "Se añadieron/actualizaron ${tracksToInsert.size} pistas." else "Todo al día."
                    android.widget.Toast.makeText(context, "¡Escaneo completado! $finalCountStr", android.widget.Toast.LENGTH_LONG).show()
                }
                
                // Immediately check for unindexed files after completing scan
                checkForUnindexedFiles()
            } catch (e: Exception) {
                Log.e(TAG, "Error performing unified library scan: ${e.message}", e)
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error en el escaneo de bibliotecas: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                setScanning(false)
            }
        }
    }

    private fun backupConnectionsToPrefs(connections: List<FtpConnection>) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
            val array = org.json.JSONArray()
            for (conn in connections) {
                val obj = org.json.JSONObject().apply {
                    put("name", conn.name)
                    put("host", conn.host)
                    put("port", conn.port)
                    put("username", conn.username)
                    put("password", conn.password)
                    put("musicFolder", conn.musicFolder)
                }
                array.put(obj)
            }
            prefs.edit().putString("connections_backup_v2", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up connections: ${e.message}")
        }
    }

    private suspend fun restoreConnectionsFromPrefsSuspend() {
        val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_prefs", android.content.Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("connections_backup_v2", null) ?: return
        try {
            val array = org.json.JSONArray(jsonStr)
            val existing = connectionDao.getAllConnectionsOnce()
            val existingKeys = existing.map { "${it.name}:${it.host}:${it.port}:${it.username}".lowercase() }.toSet()
            val addedKeys = mutableSetOf<String>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val host = obj.getString("host")
                val port = obj.getInt("port")
                val username = obj.getString("username")
                val password = obj.getString("password")
                val musicFolder = obj.optString("musicFolder", "/")
                
                val key = "$name:$host:$port:$username".lowercase()
                if (key !in existingKeys && key !in addedKeys) {
                    addedKeys.add(key)
                    val profile = FtpConnection(
                        name = name,
                        host = host,
                        port = port,
                        username = username,
                        password = password,
                        musicFolder = musicFolder
                    )
                    connectionDao.insertConnection(profile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring connections: ${e.message}")
        }
    }

    // Connect Profile Management
    fun createConnection(name: String, host: String, port: Int, user: String, pass: String, musicFolder: String = "/", homeSsid: String = "", localIp: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = connectionDao.getAllConnectionsOnce()
            val key = "$host:$port:$user".lowercase()
            val exists = existing.any { "${it.host}:${it.port}:${it.username}".lowercase() == key }
            if (!exists) {
                val profile = FtpConnection(
                    name = name,
                    host = host,
                    port = port,
                    username = user,
                    password = pass,
                    musicFolder = musicFolder,
                    homeSsid = homeSsid,
                    localIp = localIp
                )
                connectionDao.insertConnection(profile)
            }
        }
    }

    fun removeConnection(connection: FtpConnection) {
        viewModelScope.launch(Dispatchers.IO) {
            connectionDao.deleteConnection(connection)
            if (_activeConnection.value?.id == connection.id) {
                disconnectFtp()
            }
        }
    }

    fun connectFtp(connection: FtpConnection) {
        viewModelScope.launch {
            _isConnecting.value = true
            _connectionError.value = null
            val success = FtpClientManager.connect(connection)
            if (success) {
                _isConnected.value = true
                _activeConnection.value = connection
                _isViewingExplorer.value = true
                val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_paths", android.content.Context.MODE_PRIVATE)
                val savedPath = prefs.getString("last_path_${connection.id}", connection.defaultPath) ?: connection.defaultPath
                _currentPath.value = savedPath
                loadFilesFromCurrentPath()
            } else {
                _isConnected.value = false
                _activeConnection.value = null
                _connectionError.value = "Imposible conectar con el servidor FTP. Revisa los datos de conexión."
            }
            _isConnecting.value = false
        }
    }

    fun disconnectFtp() {
        viewModelScope.launch {
            FtpClientManager.disconnectManual()
            _isConnected.value = false
            _activeConnection.value = null
            _isViewingExplorer.value = false
            _filesList.value = emptyList()
            _currentPath.value = "/"
        }
    }

    // Browsing Actions
    fun loadFilesFromCurrentPath() {
        val activeConn = _activeConnection.value
        val path = _currentPath.value
        if (activeConn != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val prefs = getApplication<Application>().getSharedPreferences("ftp_hub_paths", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString("last_path_${activeConn.id}", path).apply()
                } catch (_: Exception) {}
            }
        }
        viewModelScope.launch {
            _isLoadingFiles.value = true
            val items = FtpClientManager.listFiles(_currentPath.value)
            _filesList.value = items
            _isLoadingFiles.value = false
            
            // Background deep scan for details/metadata on the fly under-the-hood!
            deepScanFolderOnDemand(items)
        }
    }

    private val _unindexedFilesCount = MutableStateFlow(0)
    val unindexedFilesCount: StateFlow<Int> = _unindexedFilesCount

    fun checkForUnindexedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val folders = _scanFolders.value
                if (folders.isEmpty()) {
                    _unindexedFilesCount.value = 0
                    return@launch
                }
                
                val allowedExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                val discoveredPaths = mutableSetOf<String>()
                
                for (folder in folders) {
                    if (folder.isFtp) {
                        val connId = folder.ftpConnectionId
                        if (connId != null) {
                            val conn = connectionDao.getAllConnectionsOnce().firstOrNull { it.id == connId }
                            if (conn != null) {
                                val isConnected = FtpClientManager.connect(conn)
                                if (isConnected) {
                                    val ftpPrefix = "ftp://$connId"
                                    val remotePath = folder.path.removePrefix(ftpPrefix)
                                    
                                    suspend fun traverseFtp(path: String, depth: Int) {
                                        if (depth > 3) return // quick crawl limit
                                        val list = FtpClientManager.listFiles(path)
                                        for (item in list) {
                                            if (item.isDirectory) {
                                                traverseFtp(item.path, depth + 1)
                                            } else {
                                                val ext = item.name.substringAfterLast('.', "").lowercase()
                                                if (ext in allowedExts) {
                                                    discoveredPaths.add("ftp://$connId${item.path}")
                                                }
                                            }
                                        }
                                    }
                                    traverseFtp(remotePath, 0)
                                }
                            }
                        }
                    } else {
                        val customPath = folder.path
                        val isSafUri = customPath.startsWith("content://")
                        if (isSafUri) {
                            try {
                                val treeUri = Uri.parse(customPath)
                                val rootDoc = DocumentFile.fromTreeUri(getApplication(), treeUri)
                                if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                                    fun traverseDocFolder(doc: DocumentFile) {
                                        doc.listFiles().forEach { child ->
                                            if (child.isDirectory) {
                                                traverseDocFolder(child)
                                            } else if (child.isFile) {
                                                val name = child.name ?: ""
                                                val ext = name.substringAfterLast('.', "").lowercase()
                                                if (ext in allowedExts) {
                                                    discoveredPaths.add(child.uri.toString())
                                                }
                                            }
                                        }
                                    }
                                    traverseDocFolder(rootDoc)
                                }
                            } catch (_: Exception) {}
                        } else {
                            try {
                                val scanDir = File(customPath)
                                if (scanDir.exists() && scanDir.isDirectory) {
                                    scanDir.walkTopDown().forEach { file ->
                                        if (file.isFile && file.exists()) {
                                            val ext = file.extension.lowercase()
                                            if (ext in allowedExts) {
                                                discoveredPaths.add(file.absolutePath)
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                
                val existingTracks = localMusicDao.getAllLocalTracksOnce().map { it.filePath }.toSet()
                val unindexedCount = discoveredPaths.count { !existingTracks.contains(it) }
                _unindexedFilesCount.value = unindexedCount
                
                if (unindexedCount > 0) {
                    Log.d("FtpViewModel", "Quick scan complete: found $unindexedCount unindexed files.")
                }
            } catch (e: Exception) {
                Log.e("FtpViewModel", "Error in quick check for unindexed files: ${e.message}")
            }
        }
    }

    fun deepScanFolderOnDemand(items: List<FtpFileItem>) {
        val activeConn = _activeConnection.value ?: return
        val currentFiles = items.filter { it.isAudio }
        if (currentFiles.isEmpty()) return
        
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val connId = activeConn.id ?: return@launch
            val allowedExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
            val existingTracksMap = try {
                localMusicDao.getAllLocalTracksOnce().associateBy { it.filePath }
            } catch (_: Exception) {
                emptyMap()
            }
            
            val tracksToInsertOrUpdate = mutableListOf<com.example.data.model.LocalMusicTrack>()
            
            for (item in currentFiles) {
                val ext = item.name.substringAfterLast('.', "").lowercase()
                if (ext !in allowedExts) continue
                
                val filePath = "ftp://$connId${item.path}"
                val cachedTrack = existingTracksMap[filePath]
                val hash = filePath.hashCode()
                val coverFile = com.example.data.repository.AudioPlayerManager.getScannedCoverFile(context, hash, ext)
                val coverExists = coverFile.exists() && coverFile.length() > 0
                val skippedCoverBefore = com.example.data.repository.AudioPlayerManager.pathsWithNoCover.contains(filePath)
                
                // If we already have a valid duration (i.e. not "03:00") AND (have cover OR skipped before), we skip deep scanning this track on demand
                if (cachedTrack != null && cachedTrack.size == item.size && cachedTrack.durationText != "03:00" && (coverExists || skippedCoverBefore)) {
                    continue
                }
                
                var durationTxt = cachedTrack?.durationText ?: "03:00"
                var title = cachedTrack?.title ?: item.name.substringBeforeLast('.')
                var artist = cachedTrack?.artist ?: "Servidor FTP: ${activeConn.name}"
                var album = cachedTrack?.album ?: "Música Remota"
                
                val scanTempFile = java.io.File(context.cacheDir, "scan_dur_${hash}.$ext")
                try {
                    val success = FtpClientManager.downloadPartialFileToCache(
                        remotePath = item.path,
                        localFile = scanTempFile,
                        maxBytes = 1024 * 1024L
                    )
                    if (success && scanTempFile.exists() && scanTempFile.length() > 0) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(scanTempFile.absolutePath)
                            val durMsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            durMsStr?.toLongOrNull()?.let { ms ->
                                val secs = (ms / 1000) % 60
                                val mins = (ms / 1000) / 60
                                durationTxt = String.format("%02d:%02d", mins, secs)
                            }
                            
                            val art = retriever.embeddedPicture
                            if (art != null) {
                                val coverFile = com.example.data.repository.AudioPlayerManager.getScannedCoverFile(context, hash, ext)
                                coverFile.writeBytes(art)
                            } else {
                                com.example.data.repository.AudioPlayerManager.pathsWithNoCover.add(filePath)
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { retriever.release() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { if (scanTempFile.exists()) scanTempFile.delete() } catch (_: Exception) {}
                }
                
                val updatedTrack = com.example.data.model.LocalMusicTrack(
                    filePath = filePath,
                    title = title,
                    artist = artist,
                    album = album,
                    genre = "Streaming FTP",
                    year = "N/A",
                    durationText = durationTxt,
                    size = item.size,
                    dateModified = 0L
                )
                tracksToInsertOrUpdate.add(updatedTrack)
            }
            
            if (tracksToInsertOrUpdate.isNotEmpty()) {
                localMusicDao.insertTracks(tracksToInsertOrUpdate)
                Log.d("FtpViewModel", "On-demand folder deep scanned: ${tracksToInsertOrUpdate.size} tracks saved/updated.")
                
                // Refresh unindexed count
                checkForUnindexedFiles()
            }
        }
    }

    fun cleanOrphanedDataAndCache(context: android.content.Context, onComplete: (Int, Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            var dbTracksDeleted = 0
            var cacheFilesDeleted = 0
            try {
                val folders = _scanFolders.value
                val existingTracks = localMusicDao.getAllLocalTracksOnce()
                val activeDbConnections = connectionDao.getAllConnectionsOnce().map { it.id }.toSet()

                val toDeleteList = mutableListOf<com.example.data.model.LocalMusicTrack>()

                for (track in existingTracks) {
                    // Check if there is an active matching ScanFolder
                    val matchingFolder = folders.find { folder ->
                        track.filePath.startsWith(folder.path)
                    }

                    if (matchingFolder == null) {
                        // Orphaned / Removed scan folder
                        toDeleteList.add(track)
                        continue
                    }

                    // Check physical availability
                    if (track.filePath.startsWith("ftp://")) {
                        // Extract connection id
                        val uriStr = track.filePath.removePrefix("ftp://")
                        val nextSlashIdx = uriStr.indexOf('/')
                        val connIdStr = if (nextSlashIdx != -1) uriStr.substring(0, nextSlashIdx) else uriStr
                        val connId = connIdStr.toIntOrNull()
                        if (connId == null || !activeDbConnections.contains(connId)) {
                            toDeleteList.add(track)
                        }
                    } else if (track.filePath.startsWith("content://")) {
                        // SAF Local URI
                        try {
                            val uri = android.net.Uri.parse(track.filePath)
                            val documentFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                            if (documentFile == null || !documentFile.exists()) {
                                toDeleteList.add(track)
                            }
                        } catch (_: Exception) {
                            toDeleteList.add(track)
                        }
                    } else {
                        // Regular Local path
                        val file = java.io.File(track.filePath)
                        if (!file.exists()) {
                            toDeleteList.add(track)
                        }
                    }
                }

                // Delete those tracks from database
                for (track in toDeleteList) {
                    localMusicDao.deleteTrackByPath(track.filePath)
                    dbTracksDeleted++
                }

                // Gather updated list of active track hashes to preserve their covers
                val remainingTracks = localMusicDao.getAllLocalTracksOnce()
                val activeHashes = remainingTracks.map { it.filePath.hashCode() }.toSet()

                // Clean orphaned visual covers in files/scanned_covers
                val coversDir = java.io.File(context.filesDir, "scanned_covers")
                if (coversDir.exists() && coversDir.isDirectory) {
                    coversDir.listFiles()?.forEach { file ->
                        if (file.name.startsWith("ftp_cover_")) {
                            // Extract hash from "ftp_cover_12345.mp3"
                            val nameWithoutPrefix = file.name.removePrefix("ftp_cover_")
                            val dotIdx = nameWithoutPrefix.lastIndexOf('.')
                            val hashStr = if (dotIdx != -1) nameWithoutPrefix.substring(0, dotIdx) else nameWithoutPrefix
                            val hashVal = hashStr.toIntOrNull()
                            if (hashVal == null || !activeHashes.contains(hashVal)) {
                                if (file.delete()) {
                                    cacheFilesDeleted++
                                }
                            }
                        }
                    }
                }

                // Clean scan_dur_ file caches in cacheDir
                context.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("scan_dur_")) {
                        if (file.delete()) {
                            cacheFilesDeleted++
                        }
                    }
                }
                
                // Immediately check/update unindexed file state count
                checkForUnindexedFiles()
            } catch (e: Exception) {
                Log.e("FtpViewModel", "Error performing orphaned data cleanup: ${e.message}", e)
            } finally {
                viewModelScope.launch(Dispatchers.Main) {
                    onComplete(dbTracksDeleted, cacheFilesDeleted)
                }
            }
        }
    }

    fun playFolderRecursively(context: android.content.Context, activeConnId: Int, onComplete: () -> Unit) {
        val path = _currentPath.value
        viewModelScope.launch {
            _isRecursiveScanning.value = true
            try {
                // Fetch recursively
                val recursiveFiles = FtpClientManager.listFilesRecursive(path)
                val audioFiles = recursiveFiles.filter { it.isAudio }
                if (audioFiles.isNotEmpty()) {
                    val playlistPlaylists = audioFiles.map { audioFile ->
                        com.example.data.model.PlaylistItem(
                            id = 0,
                            playlistId = -1,
                            fileName = audioFile.name,
                            filePath = "ftp://$activeConnId${audioFile.path}",
                            fileSize = audioFile.size
                        )
                     }
                     AudioPlayerManager.playTrack(context, playlistPlaylists.first(), playlistPlaylists)
                     onComplete()
                } else {
                    android.widget.Toast.makeText(context, "No se encontraron audios en esta carpeta ni en sus subcarpetas.", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("FtpViewModel", "Error recursively scanning folder: ${e.message}", e)
                android.widget.Toast.makeText(context, "Error al escanear de forma recursiva.", android.widget.Toast.LENGTH_SHORT).show()
            } finally {
                _isRecursiveScanning.value = false
            }
        }
    }

    fun navigateIntoDirectory(dirName: String) {
        val path = _currentPath.value
        val newPath = if (path.endsWith("/")) "$path$dirName" else "$path/$dirName"
        _currentPath.value = newPath
        loadFilesFromCurrentPath()
    }

    fun navigateToPath(path: String) {
        _currentPath.value = path
        loadFilesFromCurrentPath()
    }

    fun navigateUp() {
        val path = _currentPath.value
        if (path == "/" || path.isEmpty()) return
        val segments = path.split("/").filter { it.isNotEmpty() }
        val newPath = if (segments.size <= 1) {
            "/"
        } else {
            "/" + segments.dropLast(1).joinToString("/")
        }
        _currentPath.value = newPath
        loadFilesFromCurrentPath()
    }

    fun navigateBreadcrumb(index: Int) {
        val path = _currentPath.value
        val segments = path.split("/").filter { it.isNotEmpty() }
        if (index < 0) {
            _currentPath.value = "/"
        } else {
            val subSegments = segments.take(index + 1)
            _currentPath.value = "/" + subSegments.joinToString("/")
        }
        loadFilesFromCurrentPath()
    }

    // File manipulation actions
    fun setClipboardElement(fileItem: FtpFileItem, isCut: Boolean) {
        _clipboardPath.value = fileItem.path
        _isMoveAction.value = isCut
    }

    fun clearClipboard() {
        _clipboardPath.value = null
        _isMoveAction.value = false
    }

    fun pasteClipboardCurrent() {
        val source = _clipboardPath.value ?: return
        val destDir = _currentPath.value
        val fileName = source.substringAfterLast("/")
        val destination = if (destDir.endsWith("/")) "$destDir$fileName" else "$destDir/$fileName"

        viewModelScope.launch {
            _isLoadingFiles.value = true
            val success = if (_isMoveAction.value) {
                FtpClientManager.rename(source, destination)
            } else {
                FtpClientManager.copyFile(source, destination)
            }

            if (success) {
                clearClipboard()
                loadFilesFromCurrentPath()
            } else {
                Log.e(TAG, "Failed clipboard action from '$source' to '$destination'")
            }
            _isLoadingFiles.value = false
        }
    }

    fun deleteFileItem(item: FtpFileItem) {
        viewModelScope.launch {
            _isLoadingFiles.value = true
            val success = if (item.isDirectory) {
                FtpClientManager.deleteDirectory(item.path)
            } else {
                FtpClientManager.deleteFile(item.path)
            }
            if (success) {
                loadFilesFromCurrentPath()
            }
            _isLoadingFiles.value = false
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            _isLoadingFiles.value = true
            val success = FtpClientManager.createDirectory(_currentPath.value, name)
            if (success) {
                loadFilesFromCurrentPath()
            }
            _isLoadingFiles.value = false
        }
    }

    // Image Viewer functions
    fun openImageViewer(item: FtpFileItem) {
        _isViewingImage.value = true
        _imageLoadingState.value = true
        viewModelScope.launch {
            try {
                _viewingImageLocalFile.value?.delete() // clear old image cache
                val localFile = File(getApplication<Application>().cacheDir, "current_view_image.jpg")
                val success = FtpClientManager.downloadFileToCache(item.path, localFile)
                if (success) {
                    _viewingImageLocalFile.value = localFile
                } else {
                    _isViewingImage.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error displaying photo: ${e.message}")
                _isViewingImage.value = false
            } finally {
                _imageLoadingState.value = false
            }
        }
    }

    fun closeImageViewer() {
        _isViewingImage.value = false
        _viewingImageLocalFile.value?.delete()
        _viewingImageLocalFile.value = null
    }

    // Playlist repository bindings
    fun getItemsForPlaylist(playlistId: Int) = playlistRepo.getItemsForPlaylist(playlistId)

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistRepo.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            playlistRepo.deletePlaylist(playlist)
        }
    }

    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch {
            playlistRepo.updatePlaylist(playlist.copy(name = newName))
        }
    }

    fun addFileToPlaylist(playlistId: Int, file: FtpFileItem) {
        viewModelScope.launch {
            playlistRepo.addToPlaylist(
                playlistId = playlistId,
                fileName = file.name,
                filePath = file.path,
                fileSize = file.size
            )
        }
    }

    fun createPlaylistAndAddFile(name: String, file: FtpFileItem) {
        viewModelScope.launch {
            val playlistId = playlistRepo.createPlaylist(name)
            playlistRepo.addToPlaylist(
                playlistId = playlistId.toInt(),
                fileName = file.name,
                filePath = file.path,
                fileSize = file.size
            )
        }
    }

    fun removePlaylistItem(item: PlaylistItem) {
        viewModelScope.launch {
            playlistRepo.removeFromPlaylist(item)
        }
    }

    fun downloadFileToDevice(context: android.content.Context, item: FtpFileItem) {
        viewModelScope.launch {
            android.widget.Toast.makeText(context, "Iniciando descarga de ${item.name}...", android.widget.Toast.LENGTH_SHORT).show()
            try {
                val targetDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val localFile = File(targetDir, item.name)
                val success = FtpClientManager.downloadFileToCache(item.path, localFile)
                if (success) {
                    android.widget.Toast.makeText(
                        context,
                        "Descargado con éxito en Descargas: ${item.name}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        "Error al guardar la descarga.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing full download: ${e.message}", e)
                android.widget.Toast.makeText(
                    context,
                    "Error de descarga: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Server Profile Edit & Duplicate
    fun updateConnection(connection: FtpConnection) {
        viewModelScope.launch(Dispatchers.IO) {
            connectionDao.updateConnection(connection)
            if (_activeConnection.value?.id == connection.id) {
                _activeConnection.value = connection
            }
        }
    }

    fun duplicateConnection(connection: FtpConnection) {
        viewModelScope.launch(Dispatchers.IO) {
            val dup = connection.copy(
                id = 0,
                name = "${connection.name} (Copia)"
            )
            connectionDao.insertConnection(dup)
        }
    }

    // Favorites Logic
    fun toggleFavorite(filePath: String, fileName: String, isLocal: Boolean, artist: String = "Desconocido") {
        viewModelScope.launch(Dispatchers.IO) {
            val exists = favoriteDao.isFavorite(filePath)
            if (exists) {
                com.example.data.repository.TrashManager.trashFavorite(
                    getApplication(),
                    filePath,
                    fileName,
                    isLocal,
                    artist
                )
                favoriteDao.deleteFavoriteByPath(filePath)
            } else {
                favoriteDao.insertFavorite(
                    com.example.data.model.FavoriteTrack(
                        filePath = filePath,
                        fileName = fileName,
                        isLocal = isLocal,
                        artist = artist
                    )
                )
            }
        }
    }

    // Local Music System Scanner
    fun scanLocalMusic(context: android.content.Context, customPath: String) {
        activeScanJob?.cancel()
        activeScanJob = viewModelScope.launch(Dispatchers.IO) {
            setScanning(true)
            _totalFilesToScan.value = 0
            _currentScannedCount.value = 0
            try {
                addScannedFileLog("🔍 FASE 1: Descubrimiento de música local...")
                addScannedFileLog("📂 Analizando ruta: $customPath ...")
                updateLocalScanDirectory(customPath)
                val tracks = mutableListOf<com.example.data.model.LocalMusicTrack>()

                val isSafUri = customPath.startsWith("content://")
                val allowedExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                val deepScanEnabled = _isDeepScanEnabled.value
                val discoveredList = mutableListOf<DiscoveredTrack>()

                if (isSafUri) {
                    addScannedFileLog("📱 Accediendo a almacenamiento local SAF...")
                    val treeUri = Uri.parse(customPath)
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                        launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "La ruta de SAF no es una carpeta transitable válida.", android.widget.Toast.LENGTH_LONG).show()
                        }
                        setScanning(false)
                        return@launch
                    }

                    fun traverseDocFolder(doc: DocumentFile) {
                        addScannedFileLog("📁 Escaneando carpeta local SAF: ${doc.name ?: "Directorio"}")
                        var foundInFolder = 0
                        doc.listFiles().forEach { child ->
                            if (child.isDirectory) {
                                traverseDocFolder(child)
                            } else if (child.isFile) {
                                val name = child.name ?: ""
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext in allowedExts) {
                                    foundInFolder++
                                    addScannedFileLog("  🎵 Encontrado: $name")
                                    discoveredList.add(
                                        DiscoveredTrack(
                                            isFtp = false,
                                            isSaf = true,
                                            filePath = child.uri.toString(),
                                            name = name,
                                            size = child.length(),
                                            dateModified = child.lastModified()
                                        )
                                    )
                                }
                            }
                        }
                        if (foundInFolder > 0) {
                            addScannedFileLog("💡 ¡Descubiertas $foundInFolder pistas locals en ${doc.name ?: "SAF"}!")
                        }
                    }
                    traverseDocFolder(rootDoc)

                } else {
                    // 1. Try MediaStore (highly compatible & fast)
                    addScannedFileLog("📁 Consultando la base de datos de medios nativa de Android (MediaStore)...")
                    try {
                        val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        val projection = arrayOf(
                            android.provider.MediaStore.Audio.Media.DATA,
                            android.provider.MediaStore.Audio.Media.TITLE,
                            android.provider.MediaStore.Audio.Media.ARTIST,
                            android.provider.MediaStore.Audio.Media.ALBUM,
                            android.provider.MediaStore.Audio.Media.DURATION,
                            android.provider.MediaStore.Audio.Media.SIZE,
                            android.provider.MediaStore.Audio.Media.DATE_MODIFIED
                        )

                        val cursor = context.contentResolver.query(uri, projection, null, null, null)
                        cursor?.use { c ->
                            val dataIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATA)
                            val titleIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.TITLE)
                            val artistIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.ARTIST)
                            val albumIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.ALBUM)
                            val durationIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DURATION)
                            val sizeIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.SIZE)
                            val dateModIndex = c.getColumnIndex(android.provider.MediaStore.Audio.Media.DATE_MODIFIED)

                            while (c.moveToNext()) {
                                if (dataIndex == -1) break
                                val filePath = c.getString(dataIndex) ?: continue

                                val matchesPath = customPath.isBlank() || filePath.startsWith(customPath, ignoreCase = true)

                                if (matchesPath) {
                                    val title = if (titleIndex != -1) c.getString(titleIndex) ?: "Desconocido" else "Desconocido"
                                    val artist = if (artistIndex != -1) c.getString(artistIndex) ?: "Desconocido" else "Desconocido"
                                    val album = if (albumIndex != -1) c.getString(albumIndex) ?: "Desconocido" else "Desconocido"
                                    val durationMs = if (durationIndex != -1) c.getLong(durationIndex) else 0L
                                    val size = if (sizeIndex != -1) c.getLong(sizeIndex) else 0L
                                    val dateMod = if (dateModIndex != -1) c.getLong(dateModIndex) * 1000 else 0L

                                    discoveredList.add(
                                        DiscoveredTrack(
                                            isFtp = false,
                                            isSaf = false,
                                            filePath = filePath,
                                            name = filePath.substringAfterLast('/'),
                                            size = size,
                                            dateModified = dateMod,
                                            albumName = album,
                                            connectionName = artist,
                                            remotePath = title, // custom indicator
                                            ftpConnectionId = durationMs.toInt()
                                        )
                                    )
                                }
                            }
                        }
                    } catch (msException: Exception) {
                        Log.e(TAG, "MediaStore query failed: ${msException.message}", msException)
                        addScannedFileLog("⚠️ Error al consultar MediaStore: ${msException.message}")
                    }

                    // 2. Fallback to direct walkTopDown file scan if MediaStore list is empty
                    if (discoveredList.isEmpty() && customPath.isNotBlank()) {
                        addScannedFileLog("⚠️ MediaStore vacía. Iniciando recorrido de disco directo...")
                        val scanDir = File(customPath)
                        if (scanDir.exists() && scanDir.isDirectory) {
                            var physicalTrackCount = 0
                            scanDir.walkTopDown().forEach { file ->
                                if (file.isFile && file.exists()) {
                                    val ext = file.extension.lowercase()
                                    if (ext in allowedExts) {
                                        physicalTrackCount++
                                        addScannedFileLog("  🎵 Descubierto físico: ${file.name}")
                                        discoveredList.add(
                                            DiscoveredTrack(
                                                isFtp = false,
                                                isSaf = false,
                                                filePath = file.absolutePath,
                                                name = file.name,
                                                size = file.length(),
                                                dateModified = file.lastModified()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val totalCount = discoveredList.size
                addScannedFileLog("✅ FASE 1 COMPLETADA: Se descubrieron un total de $totalCount canciones.")
                addScannedFileLog("🚀 FASE 2: Registrando información en base de datos...")
                _totalFilesToScan.value = totalCount
                _currentScannedCount.value = 0

                for (idx in discoveredList.indices) {
                    if (!isActive) return@launch
                    val dt = discoveredList[idx]
                    val itemIndex = idx + 1
                    _currentScannedCount.value = itemIndex

                    if (dt.isSaf) {
                        tracks.add(
                            com.example.data.model.LocalMusicTrack(
                                filePath = dt.filePath,
                                title = dt.name.substringBeforeLast('.'),
                                artist = "Desconocido",
                                album = "Desconocido",
                                genre = "Desconocido".also { addScannedFileLog("[SAF] ${dt.name}") },
                                year = "Desconocido",
                                durationText = "03:00",
                                size = dt.size,
                                dateModified = dt.dateModified
                            )
                        )
                    } else {
                        val isFromMediaStore = dt.remotePath != null
                        if (isFromMediaStore) {
                            val durationMs = dt.ftpConnectionId?.toLong() ?: 0L
                            val secs = (durationMs / 1000) % 60
                            val mins = (durationMs / 1000) / 60
                            val durationTxt = String.format("%02d:%02d", mins, secs)

                            tracks.add(
                                com.example.data.model.LocalMusicTrack(
                                    filePath = dt.filePath,
                                    title = dt.name.substringBeforeLast('.'),
                                    artist = dt.connectionName ?: "Desconocido",
                                    album = dt.albumName ?: "Desconocido",
                                    genre = "Desconocido".also { addScannedFileLog("[Media] ${dt.remotePath} - ${dt.connectionName}") },
                                    year = "Desconocido",
                                    durationText = durationTxt,
                                    size = dt.size,
                                    dateModified = dt.dateModified
                                )
                            )
                        } else {
                            val title = dt.name.substringBeforeLast('.')
                            var artist = "Desconocido"
                            var album = "Desconocido"
                            var durationTxt = "03:00"

                            if (deepScanEnabled) {
                                val mmr = android.media.MediaMetadataRetriever()
                                try {
                                    mmr.setDataSource(dt.filePath)
                                    artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Desconocido"
                                    album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Desconocido"
                                    val durMsStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    durMsStr?.toLongOrNull()?.let { ms ->
                                        val secs = (ms / 1000) % 60
                                        val mins = (ms / 1000) / 60
                                        durationTxt = String.format("%02d:%02d", mins, secs)
                                    }
                                } catch (ex: Exception) {
                                    Log.w(TAG, "Error metadata for ${dt.name}: ${ex.message}")
                                } finally {
                                    try {
                                        mmr.release()
                                    } catch (e: Exception) {}
                                }
                            }

                            tracks.add(
                                com.example.data.model.LocalMusicTrack(
                                    filePath = dt.filePath,
                                    title = title,
                                    artist = artist,
                                    album = album,
                                    genre = "Desconocido".also { addScannedFileLog("[Local] ${dt.name}") },
                                    year = "Desconocido",
                                    durationText = durationTxt,
                                    size = dt.size,
                                    dateModified = dt.dateModified
                            )
                        )
                    }
                }
            }

                if (tracks.isNotEmpty()) {
                    localMusicDao.clearAllLocalTracks()
                    localMusicDao.insertTracks(tracks)
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "¡Escaneo completado! ${tracks.size} canciones encontradas.", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "No se encontraron canciones de audio en el directorio indicado.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning music: ${e.message}", e)
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error en el escaneo: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                setScanning(false)
            }
        }
    }

    // --- STREAMS IMPORT SERVICE ---
    private val _parsedImportChannels = MutableStateFlow<List<OnlineRadio>>(emptyList())
    val parsedImportChannels: StateFlow<List<OnlineRadio>> = _parsedImportChannels

    private val _isImportLoading = MutableStateFlow(false)
    val isImportLoading: StateFlow<Boolean> = _isImportLoading

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage

    fun clearImportState() {
        _parsedImportChannels.value = emptyList()
        _isImportLoading.value = false
        _importMessage.value = null
    }

    fun fetchAndParseM3u(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImportLoading.value = true
            _importMessage.value = "Descargando lista M3U..."
            try {
                val content = java.net.URL(url).readText()
                val parsed = parseM3uContent(content)
                _parsedImportChannels.value = parsed
                _importMessage.value = "Se cargaron ${parsed.size} emisoras desde M3U."
            } catch (e: Exception) {
                Log.e("FtpViewModel", "Error fetching/parsing M3U: ${e.message}")
                _importMessage.value = "Error al descargar M3U: ${e.localizedMessage}"
            } finally {
                _isImportLoading.value = false
            }
        }
    }

    fun parseRawM3u(content: String) {
        _isImportLoading.value = true
        try {
            val parsed = parseM3uContent(content)
            _parsedImportChannels.value = parsed
            _importMessage.value = "Se cargaron ${parsed.size} emisoras desde texto local."
        } catch (e: Exception) {
            _importMessage.value = "Error de procesado M3U: ${e.localizedMessage}"
        } finally {
            _isImportLoading.value = false
        }
    }

    fun fetchAndParseTdtChannels(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isImportLoading.value = true
            _importMessage.value = "Descargando lista TDTChannels..."
            try {
                val content = java.net.URL(url).readText()
                val parsed = parseTdtChannelsContent(content)
                _parsedImportChannels.value = parsed
                _importMessage.value = "Se cargaron ${parsed.size} emisoras desde TDTChannels."
            } catch (e: Exception) {
                Log.e("FtpViewModel", "Error fetching/parsing TDTChannels: ${e.message}")
                _importMessage.value = "Error al descargar TDTChannels: ${e.localizedMessage}"
            } finally {
                _isImportLoading.value = false
            }
        }
    }

    fun importSelectedRadios(selected: List<OnlineRadio>) {
        if (selected.isEmpty()) return
        val current = _onlineRadios.value.toMutableList()
        val existingUrls = current.map { it.url }.toSet()
        val toAdd = selected.filter { it.url !in existingUrls }
        current.addAll(toAdd)
        _onlineRadios.value = current
        saveOnlineRadiosList(current)
    }

    private fun parseM3uContent(content: String): List<OnlineRadio> {
        val items = mutableListOf<OnlineRadio>()
        val lines = content.lines().map { it.trim() }
        var currentName = ""
        var currentLogo: String? = null

        for (line in lines) {
            if (line.startsWith("#EXTINF:")) {
                val lastComma = line.lastIndexOf(',')
                currentName = if (lastComma != -1 && lastComma < line.length - 1) {
                    line.substring(lastComma + 1).trim()
                } else {
                    ""
                }
                
                val logoMatcher = java.util.regex.Pattern.compile("tvg-logo=\"([^\"]+)\"").matcher(line)
                if (logoMatcher.find()) {
                    currentLogo = logoMatcher.group(1)
                } else {
                    currentLogo = null
                }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val useName = if (currentName.isBlank()) {
                    val idx = line.lastIndexOf('/')
                    if (idx != -1 && idx < line.length - 1) line.substring(idx + 1) else "Radio Online"
                } else {
                    currentName
                }
                items.add(OnlineRadio(name = useName, url = line, logoUri = currentLogo))
                currentName = ""
                currentLogo = null
            }
        }
        return items
    }

    private fun parseTdtChannelsContent(content: String): List<OnlineRadio> {
        val items = mutableListOf<OnlineRadio>()
        try {
            val root = org.json.JSONObject(content)
            if (root.has("countries")) {
                val countries = root.getJSONArray("countries")
                for (i in 0 until countries.length()) {
                    val country = countries.getJSONObject(i)
                    val countryName = country.optString("name", "")
                    if (country.has("ambits")) {
                        val ambits = country.getJSONArray("ambits")
                        for (j in 0 until ambits.length()) {
                            val ambit = ambits.getJSONObject(j)
                            val ambitName = ambit.optString("name", "")
                            if (ambit.has("channels")) {
                                val channels = ambit.getJSONArray("channels")
                                for (k in 0 until channels.length()) {
                                    val channel = channels.getJSONObject(k)
                                    val name = channel.optString("name", "")
                                    val logo = channel.optString("logo", "")
                                    if (channel.has("options")) {
                                        val options = channel.getJSONArray("options")
                                        if (options.length() > 0) {
                                            val firstOption = options.getJSONObject(0)
                                            val url = firstOption.optString("url", "")
                                            if (url.isNotBlank()) {
                                                val groupName = if (ambitName.isNotBlank()) "$countryName - $ambitName" else countryName
                                                items.add(
                                                    OnlineRadio(
                                                        name = "$name ($groupName)",
                                                        url = url,
                                                        logoUri = if (logo.isNotBlank()) logo else null
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FtpViewModel", "parseTdtChannelsContent error", e)
        }
        return items
    }

    fun getCachedFolderLogo(context: android.content.Context, folderPath: String): java.io.File? {
        if (!folderPath.startsWith("ftp://")) {
            val localDir = java.io.File(folderPath)
            if (localDir.exists() && localDir.isDirectory) {
                val localFiles = localDir.listFiles() ?: emptyArray()
                val possibleExtensions = setOf("jpg", "png", "jpeg", "webp", "bmp")
                var foundImage = localFiles.find { file ->
                    if (file.isDirectory) return@find false
                    val baseName = file.nameWithoutExtension.lowercase()
                    val ext = file.extension.lowercase()
                    val isImage = ext in possibleExtensions
                    val isNameMatch = baseName == "front" || baseName == "delantera" || 
                                      baseName == "cover" || baseName == "folder" || baseName == "portada" ||
                                      baseName.startsWith("front_") || baseName.startsWith("delantera_") ||
                                      baseName.startsWith("cover_") || baseName.startsWith("folder_") ||
                                      baseName.startsWith("portada_")
                    isImage && isNameMatch
                }
                if (foundImage == null) {
                    foundImage = localFiles.find { file ->
                        if (file.isDirectory) return@find false
                        val ext = file.extension.lowercase()
                        ext in possibleExtensions
                    }
                }
                if (foundImage != null) {
                    return foundImage
                }
            }
            return null
        }
        val hash = folderPath.hashCode().toString()
        val cacheDir = java.io.File(context.cacheDir, "ftp_folder_logos")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val possibleExtensions = listOf("jpg", "png", "jpeg", "webp", "bmp")
        for (ext in possibleExtensions) {
            val file = java.io.File(cacheDir, "logo_${hash}.$ext")
            if (file.exists() && file.length() > 0) {
                return file
            }
        }
        return null
    }

    suspend fun fetchAndCacheFolderLogo(context: android.content.Context, folderPath: String): java.io.File? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        folderLogoMutex.withLock {
            try {
                if (!folderPath.startsWith("ftp://")) {
                    val localDir = java.io.File(folderPath)
                    if (localDir.exists() && localDir.isDirectory) {
                        val localFiles = localDir.listFiles() ?: emptyArray()
                        val possibleExtensions = setOf("jpg", "png", "jpeg", "webp", "bmp")
                        var foundImage = localFiles.find { file ->
                            if (file.isDirectory) return@find false
                            val baseName = file.nameWithoutExtension.lowercase()
                            val ext = file.extension.lowercase()
                            val isImage = ext in possibleExtensions
                            val isNameMatch = baseName == "front" || baseName == "delantera" || 
                                              baseName == "cover" || baseName == "folder" || baseName == "portada" ||
                                              baseName.startsWith("front_") || baseName.startsWith("delantera_") ||
                                              baseName.startsWith("cover_") || baseName.startsWith("folder_") ||
                                              baseName.startsWith("portada_")
                            isImage && isNameMatch
                        }
                        if (foundImage == null) {
                            foundImage = localFiles.find { file ->
                                if (file.isDirectory) return@find false
                                val ext = file.extension.lowercase()
                                ext in possibleExtensions
                            }
                        }
                        if (foundImage != null) {
                            return@withLock foundImage
                        }
                    }
                    return@withLock null
                }

                var ftpConnectionId: Int? = null
                var activeFolderPath = folderPath

                if (folderPath.startsWith("ftp://")) {
                    val parts = folderPath.substring(6).split("/", limit = 2)
                    ftpConnectionId = parts.getOrNull(0)?.toIntOrNull()
                    activeFolderPath = "/" + (parts.getOrNull(1) ?: "")
                    
                    if (ftpConnectionId != null) {
                        val targetConn = connectionDao.getConnectionById(ftpConnectionId)
                        if (targetConn != null) {
                            com.example.data.repository.FtpClientManager.connect(targetConn)
                        }
                    }
                }

                if (!com.example.data.repository.FtpClientManager.ensureConnected()) return@withLock null
                
                val files = com.example.data.repository.FtpClientManager.listFiles(activeFolderPath)
                if (files.isEmpty()) return@withLock null
                
                val possibleExtensions = setOf("jpg", "png", "jpeg", "webp", "bmp")
                var logoFileItem = files.find { file ->
                    if (file.isDirectory) return@find false
                    val baseName = file.name.substringBeforeLast('.').lowercase()
                    val ext = file.extension.lowercase()
                    val isImage = ext in possibleExtensions
                    val isNameMatch = baseName == "front" || baseName == "delantera" || 
                                      baseName == "cover" || baseName == "folder" || baseName == "portada" ||
                                      baseName.startsWith("front_") || baseName.startsWith("delantera_") ||
                                      baseName.startsWith("cover_") || baseName.startsWith("folder_") ||
                                      baseName.startsWith("portada_")
                    isImage && isNameMatch
                }
                
                // Fallback: If no specifically-named cover is found, find the first available image file in the directory
                if (logoFileItem == null) {
                    logoFileItem = files.find { file ->
                        if (file.isDirectory) return@find false
                        val ext = file.extension.lowercase()
                        ext in possibleExtensions
                    }
                }
                
                if (logoFileItem != null) {
                    val hash = folderPath.hashCode().toString()
                    val cacheDir = java.io.File(context.cacheDir, "ftp_folder_logos")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    
                    val localFile = java.io.File(cacheDir, "logo_${hash}.${logoFileItem.extension}")
                    val success = com.example.data.repository.FtpClientManager.downloadFileToCache(logoFileItem.path, localFile, skipSizeCheck = true)
                    if (success && localFile.exists() && localFile.length() > 0) {
                        return@withLock localFile
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FtpViewModel", "Error fetching/caching folder logo for $folderPath: ${e.message}")
            }
            return@withLock null
        }
    }
}

data class OnlineRadio(val name: String, val url: String, val logoUri: String? = null)

data class ScanFolder(
    val id: String,
    val path: String,
    val isFtp: Boolean,
    val ftpConnectionId: Int? = null,
    val connectionName: String? = null
)

data class DiscoveredTrack(
    val isFtp: Boolean,
    val isSaf: Boolean = false,
    val filePath: String,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val connectionName: String? = null,
    val ftpConnectionId: Int? = null,
    val remotePath: String? = null,
    val albumName: String? = null,
    val scanFolderId: String? = null
)
