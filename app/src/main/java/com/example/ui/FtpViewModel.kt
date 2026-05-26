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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class FtpViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "FtpViewModel"
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

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    // Connection process state
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _activeConnection = MutableStateFlow<FtpConnection?>(null)
    val activeConnection: StateFlow<FtpConnection?> = _activeConnection

    // File Browser States
    private val _currentPath = MutableStateFlow("/")
    val currentPath: StateFlow<String> = _currentPath

    private val _filesList = MutableStateFlow<List<FtpFileItem>>(emptyList())
    val filesList: StateFlow<List<FtpFileItem>> = _filesList

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles

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
    }

    // Local Scan Directory Folder state
    private val _localScanDirectory = MutableStateFlow("")
    val localScanDirectory: StateFlow<String> = _localScanDirectory

    // Online radios state
    private val _onlineRadios = MutableStateFlow<List<OnlineRadio>>(emptyList())
    val onlineRadios: StateFlow<List<OnlineRadio>> = _onlineRadios

    // Scan Folders logic
    private val _scanFolders = MutableStateFlow<List<ScanFolder>>(emptyList())
    val scanFolders: StateFlow<List<ScanFolder>> = _scanFolders

    init {
        // Initialize player and ftp client managers
        AudioPlayerManager.initialize(application)
        FtpClientManager.initialize(application)
        
        // Load persistent configs and radios
        loadLocalScanDirectory()
        loadOnlineRadios()
        loadScanFolders()

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
        val jsonStr = prefs.getString("online_radios", null)
        val list = mutableListOf<OnlineRadio>()
        var needSave = false
        if (jsonStr != null) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.getString("name")
                    val url = obj.getString("url")
                    val logoUri = if (obj.has("logoUri")) obj.optString("logoUri", null) else null
                    list.add(OnlineRadio(name, url, logoUri))
                }
                
                // Overwrite old non-functioning defaults with working spanish defaults
                val hasOldRadio = list.any { 
                    it.url.contains("aspro.fm") || it.url.contains("infomaniak") || it.url.contains("ibizaglobalradio") || it.url.contains("digitalhits.cat")
                }
                if (hasOldRadio || list.isEmpty()) {
                    list.clear()
                    list.add(OnlineRadio("Cadena 100", "https://shoutcast.cope.es/cadena100.mp3"))
                    list.add(OnlineRadio("Europa FM", "https://live-europafm.ondacero.es/europafm.mp3"))
                    list.add(OnlineRadio("Digital Hits FM", "https://dhits.frilab.com:8443/dhits"))
                    needSave = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading online radios: ${e.message}")
            }
        } else {
            // Provide gorgeous defaults instantly!
            list.add(OnlineRadio("Cadena 100", "https://shoutcast.cope.es/cadena100.mp3"))
            list.add(OnlineRadio("Europa FM", "https://live-europafm.ondacero.es/europafm.mp3"))
            list.add(OnlineRadio("Digital Hits FM", "https://dhits.frilab.com:8443/dhits"))
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

    fun scanAllFolders(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                val tracks = mutableListOf<com.example.data.model.LocalMusicTrack>()
                val folders = _scanFolders.value
                val allowedExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                
                for (folder in folders) {
                    if (folder.isFtp) {
                        val connId = folder.ftpConnectionId
                        if (connId != null) {
                            val conn = connectionDao.getAllConnectionsOnce().firstOrNull { it.id == connId }
                            if (conn != null) {
                                try {
                                    val isConnected = FtpClientManager.connect(conn)
                                    if (isConnected) {
                                        val ftpPrefix = "ftp://$connId"
                                        val remotePath = folder.path.removePrefix(ftpPrefix)
                                        
                                        suspend fun traverseFtp(path: String, depth: Int) {
                                            if (depth > 4) return
                                            val list = FtpClientManager.listFiles(path)
                                            for (item in list) {
                                                if (item.isDirectory) {
                                                    traverseFtp(item.path, depth + 1)
                                                } else {
                                                    val ext = item.name.substringAfterLast('.', "").lowercase()
                                                    if (ext in allowedExts) {
                                                        tracks.add(
                                                            com.example.data.model.LocalMusicTrack(
                                                                filePath = "ftp://$connId${item.path}",
                                                                title = item.name.substringBeforeLast('.'),
                                                                artist = "Servidor FTP: ${folder.connectionName ?: conn.name}",
                                                                album = path.substringAfterLast('/', "Música Remota"),
                                                                genre = "Streaming FTP",
                                                                year = "N/A",
                                                                durationText = "00:00",
                                                                size = item.size,
                                                                dateModified = 0L
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        traverseFtp(remotePath, 0)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed scanning FTP scan folder ${folder.path}: ${e.message}")
                                }
                            }
                        }
                    } else {
                        val customPath = folder.path
                        val isSafUri = customPath.startsWith("content://")
                        if (isSafUri) {
                            try {
                                val treeUri = Uri.parse(customPath)
                                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                if (rootDoc != null && rootDoc.exists() && rootDoc.isDirectory) {
                                    fun traverseDocFolder(doc: DocumentFile) {
                                        doc.listFiles().forEach { child ->
                                            if (child.isDirectory) {
                                                traverseDocFolder(child)
                                            } else if (child.isFile) {
                                                val name = child.name ?: ""
                                                val ext = name.substringAfterLast('.', "").lowercase()
                                                if (ext in allowedExts) {
                                                    val title = name.substringBeforeLast('.')
                                                    tracks.add(
                                                        com.example.data.model.LocalMusicTrack(
                                                            filePath = child.uri.toString(),
                                                            title = title,
                                                            artist = "Dispositivo Local",
                                                            album = "Desconocido",
                                                            genre = "Local SAF",
                                                            year = "Desconocido",
                                                            durationText = "00:00",
                                                            size = child.length(),
                                                            dateModified = child.lastModified()
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    traverseDocFolder(rootDoc)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed SAF local scan for ${folder.path}: ${e.message}")
                            }
                        } else {
                            try {
                                val scanDir = File(customPath)
                                if (scanDir.exists() && scanDir.isDirectory) {
                                    scanDir.walkTopDown().forEach { file ->
                                        if (file.isFile && file.exists()) {
                                            val ext = file.extension.lowercase()
                                            if (ext in allowedExts) {
                                                var title = file.nameWithoutExtension
                                                var artist = "Dispositivo Local"
                                                var album = "Desconocido"
                                                var durationTxt = "03:00"

                                                val mmr = android.media.MediaMetadataRetriever()
                                                try {
                                                    mmr.setDataSource(file.absolutePath)
                                                    title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension
                                                    artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Dispositivo Local"
                                                    album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Desconocido"
                                                    val durMsStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                                    durMsStr?.toLongOrNull()?.let { ms ->
                                                        val secs = (ms / 1000) % 60
                                                        val mins = (ms / 1000) / 60
                                                        durationTxt = String.format("%02d:%02d", mins, secs)
                                                    }
                                                } catch (ex: Exception) {
                                                    Log.w(TAG, "Error metadata for ${file.name}: ${ex.message}")
                                                } finally {
                                                    try {
                                                        mmr.release()
                                                    } catch (e: Exception) {}
                                                }

                                                tracks.add(
                                                    com.example.data.model.LocalMusicTrack(
                                                        filePath = file.absolutePath,
                                                        title = title,
                                                        artist = artist,
                                                        album = album,
                                                        genre = "Local File",
                                                        year = "Desconocido",
                                                        durationText = durationTxt,
                                                        size = file.length(),
                                                        dateModified = file.lastModified()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed walk scan for ${folder.path}: ${e.message}")
                            }
                        }
                    }
                }

                localMusicDao.clearAllLocalTracks()
                if (tracks.isNotEmpty()) {
                    localMusicDao.insertTracks(tracks)
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "¡Escaneo completado! ${tracks.size} canciones encontradas en total.", android.widget.Toast.LENGTH_LONG).show()
                    }
                } else {
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "No se encontraron canciones de audio en las carpetas de escaneo.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing unified library scan: ${e.message}", e)
                launch(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Error en el escaneo de bibliotecas: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                _isScanning.value = false
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
            val existingKeys = existing.map { "${it.host}:${it.port}:${it.username}".lowercase() }.toSet()
            val addedKeys = mutableSetOf<String>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.getString("name")
                val host = obj.getString("host")
                val port = obj.getInt("port")
                val username = obj.getString("username")
                val password = obj.getString("password")
                val musicFolder = obj.optString("musicFolder", "/")
                
                val key = "$host:$port:$username".lowercase()
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
    fun createConnection(name: String, host: String, port: Int, user: String, pass: String, musicFolder: String = "/") {
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
                    musicFolder = musicFolder
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
            FtpClientManager.disconnect()
            _isConnected.value = false
            _activeConnection.value = null
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
        viewModelScope.launch(Dispatchers.IO) {
            _isScanning.value = true
            try {
                updateLocalScanDirectory(customPath)
                val tracks = mutableListOf<com.example.data.model.LocalMusicTrack>()

                val isSafUri = customPath.startsWith("content://")

                if (isSafUri) {
                    val treeUri = Uri.parse(customPath)
                    val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                    if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory) {
                        launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "La ruta de SAF no es una carpeta transitable válida.", android.widget.Toast.LENGTH_LONG).show()
                        }
                        _isScanning.value = false
                        return@launch
                    }

                    val allowedExts = setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")
                    fun traverseDocFolder(doc: DocumentFile) {
                        doc.listFiles().forEach { child ->
                            if (child.isDirectory) {
                                traverseDocFolder(child)
                            } else if (child.isFile) {
                                val name = child.name ?: ""
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext in allowedExts) {
                                    val title = name.substringBeforeLast('.')
                                    tracks.add(
                                        com.example.data.model.LocalMusicTrack(
                                            filePath = child.uri.toString(),
                                            title = title,
                                            artist = "Desconocido",
                                            album = "Desconocido",
                                            genre = "Desconocido",
                                            year = "Desconocido",
                                            durationText = "03:00",
                                            size = child.length(),
                                            dateModified = child.lastModified()
                                        )
                                    )
                                }
                            }
                        }
                    }
                    traverseDocFolder(rootDoc)

                } else {
                    // 1. Try MediaStore (highly compatible & fast)
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

                                    val secs = (durationMs / 1000) % 60
                                    val mins = (durationMs / 1000) / 60
                                    val durationTxt = String.format("%02d:%02d", mins, secs)

                                    tracks.add(
                                        com.example.data.model.LocalMusicTrack(
                                            filePath = filePath,
                                            title = title,
                                            artist = artist,
                                            album = album,
                                            genre = "Desconocido",
                                            year = "Desconocido",
                                            durationText = durationTxt,
                                            size = size,
                                            dateModified = dateMod
                                        )
                                    )
                                }
                            }
                        }
                    } catch (msException: Exception) {
                        Log.e(TAG, "MediaStore query failed: ${msException.message}", msException)
                    }

                    // 2. Fallback to direct walkTopDown file scan if MediaStore list is empty
                    if (tracks.isEmpty() && customPath.isNotBlank()) {
                        val scanDir = File(customPath)
                        if (scanDir.exists() && scanDir.isDirectory) {
                            scanDir.walkTopDown().forEach { file ->
                                if (file.isFile && file.exists()) {
                                    val ext = file.extension.lowercase()
                                    if (ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")) {
                                        var title = file.nameWithoutExtension
                                        var artist = "Desconocido"
                                        var album = "Desconocido"
                                        var durationTxt = "03:00"

                                        val mmr = android.media.MediaMetadataRetriever()
                                        try {
                                            mmr.setDataSource(file.absolutePath)
                                            title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.nameWithoutExtension
                                            artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Desconocido"
                                            album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Desconocido"
                                            val durMsStr = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            durMsStr?.toLongOrNull()?.let { ms ->
                                                val secs = (ms / 1000) % 60
                                                val mins = (ms / 1000) / 60
                                                durationTxt = String.format("%02d:%02d", mins, secs)
                                            }
                                        } catch (ex: Exception) {
                                            Log.w(TAG, "Error metadata for ${file.name}: ${ex.message}")
                                        } finally {
                                            try {
                                                mmr.release()
                                            } catch (e: Exception) {}
                                        }

                                        tracks.add(
                                            com.example.data.model.LocalMusicTrack(
                                                filePath = file.absolutePath,
                                                title = title,
                                                artist = artist,
                                                album = album,
                                                genre = "Desconocido",
                                                year = "Desconocido",
                                                durationText = durationTxt,
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
                _isScanning.value = false
            }
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
