package com.example.data.repository

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import com.example.data.model.PlaylistItem
import com.example.data.model.RecentTrack
import com.example.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.example.data.model.LocalMusicTrack
import android.widget.Toast

enum class PlaybackRepeatMode {
    NONE, ONE, ALL
}

object AudioPlayerManager : MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    private const val TAG = "AudioPlayerManager"

    val coverImageMemoryCache = android.util.LruCache<String, android.graphics.Bitmap>(300)
    val pathsWithNoCover = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    fun getScannedCoverFile(context: Context, hash: Int, ext: String): File {
        val dir = File(context.filesDir, "scanned_covers")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "ftp_cover_${hash}.$ext")
    }

    private var mediaPlayer: MediaPlayer? = null
    var activeService: android.app.Service? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioManager: AudioManager? = null
    private var appContext: Context? = null

    @Volatile
    private var userPressedPause = false
    private var lastPauseTapTime = 0L
    private var autoReconnectMonitorJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null
    private var currentDownloadJob: Job? = null

    // Player State FlowS
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentTrack = MutableStateFlow<PlaylistItem?>(null)
    val currentTrack: StateFlow<PlaylistItem?> = _currentTrack

    private val _playlistQueue = MutableStateFlow<List<PlaylistItem>>(emptyList())
    val playlistQueue: StateFlow<List<PlaylistItem>> = _playlistQueue

    private val _playbackJumpToPlayerTrigger = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackJumpToPlayerTrigger: kotlinx.coroutines.flow.Flow<Unit> = _playbackJumpToPlayerTrigger

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _bufferingProgress = MutableStateFlow(0f)
    val bufferingProgress: StateFlow<Float> = _bufferingProgress

    private val _repeatMode = MutableStateFlow(PlaybackRepeatMode.NONE)
    val repeatMode: StateFlow<PlaybackRepeatMode> = _repeatMode

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled

    // Radio TimeShift and Recording State Variables
    private val _isRadioLiveMode = MutableStateFlow(true)
    val isRadioLiveMode: StateFlow<Boolean> = _isRadioLiveMode

    private val _radioElapsedTimeSec = MutableStateFlow(0)
    val radioElapsedTimeSec: StateFlow<Int> = _radioElapsedTimeSec

    private val _radioPlayPositionSec = MutableStateFlow(0)
    val radioPlayPositionSec: StateFlow<Int> = _radioPlayPositionSec

    private val _totalRecordedBytes = MutableStateFlow(0L)
    val totalRecordedBytes: StateFlow<Long> = _totalRecordedBytes

    private val _isRecordingProtectedMode = MutableStateFlow(false)
    val isRecordingProtectedMode: StateFlow<Boolean> = _isRecordingProtectedMode

    private val _radioBufferLimitMins = MutableStateFlow(120)
    val radioBufferLimitMins: StateFlow<Int> = _radioBufferLimitMins

    fun updateRadioBufferLimitMins(context: Context, minutes: Int) {
        _radioBufferLimitMins.value = minutes
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("radio_buffer_limit_minutes", minutes).apply()
        Log.i("AudioPlayerManager", "Radio recording buffer limit updated dynamically to: $minutes minutes.")
    }

    private val _isDownloadingActiveTrack = MutableStateFlow(false)
    val isDownloadingActiveTrack: StateFlow<Boolean> = _isDownloadingActiveTrack

    // Prefetch values for Next-Track FTP
    private val _prefetchState = MutableStateFlow<String>("Inactivo") // "Inactivo", "Descargando", "Completado", "Error", "Desactivado"
    val prefetchState: StateFlow<String> = _prefetchState

    private val _prefetchProgress = MutableStateFlow<Float>(0f)
    val prefetchProgress: StateFlow<Float> = _prefetchProgress

    private val _prefetchTrackName = MutableStateFlow<String>("")
    val prefetchTrackName: StateFlow<String> = _prefetchTrackName

    private val _radioSongTitle = MutableStateFlow<String?>(null)
    val radioSongTitle: StateFlow<String?> = _radioSongTitle

    private val _radioSongArtist = MutableStateFlow<String?>(null)
    val radioSongArtist: StateFlow<String?> = _radioSongArtist

    private val _radioMetadataEnabled = MutableStateFlow(true)
    val radioMetadataEnabled: StateFlow<Boolean> = _radioMetadataEnabled

    private val _radioDownloadSpeedKbps = MutableStateFlow(0f)
    val radioDownloadSpeedKbps: StateFlow<Float> = _radioDownloadSpeedKbps

    private val _radioSignalStability = MutableStateFlow(1.0f)
    val radioSignalStability: StateFlow<Float> = _radioSignalStability

    private val _radioHealthHistory = MutableStateFlow<List<Float>>(List(15) { 1.0f })
    val radioHealthHistory: StateFlow<List<Float>> = _radioHealthHistory

    private val _isRadioReconnecting = MutableStateFlow(false)
    val isRadioReconnecting: StateFlow<Boolean> = _isRadioReconnecting

    fun setRadioMetadataEnabled(context: Context, enabled: Boolean) {
        _radioMetadataEnabled.value = enabled
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("radio_metadata_enabled", enabled).apply()
        
        // If disabled, clear the current song metadata immediately
        if (!enabled) {
            _radioSongTitle.value = null
            _radioSongArtist.value = null
            stopRadioMetadataFetcher()
        } else {
            // If playing radio, start fetching
            val current = _currentTrack.value
            if (current != null && current.playlistId == -2) {
                startRadioMetadataFetcher(current.filePath)
            }
        }
        
        // Force update notification/Widget if currently playing
        val current = _currentTrack.value
        val playing = _isPlaying.value
        if (current != null && current.playlistId == -2) {
            updateNotification(current, playing)
            updateMediaSessionState(playing, current)
            updateWidget()
        }
    }

    fun toggleRecordingProtectedMode() {
        _isRecordingProtectedMode.value = !_isRecordingProtectedMode.value
    }

    fun setRecordingProtectedMode(enabled: Boolean) {
        _isRecordingProtectedMode.value = enabled
    }

    private var radioRecordingJob: Job? = null
    private var radioWatchdogJob: Job? = null
    @Volatile
    private var radioStartTimeMs = 0L
    @Volatile
    private var radioPauseTimeMs = 0L
    private var radioRecordingFile: File? = null

    // Queue State
    private var currentPlaylist = mutableListOf<PlaylistItem>()
    private var currentIndex = -1
    private var isPreloadingSiblings = false

    // EQ / Audio FX States
    private val _isEqEnabled = MutableStateFlow(false)
    val isEqEnabled: StateFlow<Boolean> = _isEqEnabled

    private val _equalizerBands = MutableStateFlow<List<EqualizerBand>>(emptyList())
    val equalizerBands: StateFlow<List<EqualizerBand>> = _equalizerBands

    private val _currentPresetName = MutableStateFlow<String>("Plana")
    val currentPresetName: StateFlow<String> = _currentPresetName

    private val _boostLevel = MutableStateFlow(0f) // 0 to 1000 mB boost
    val boostLevel: StateFlow<Float> = _boostLevel

    private val _systemVolume = MutableStateFlow(0)
    val systemVolume: StateFlow<Int> = _systemVolume

    private val _maxSystemVolume = MutableStateFlow(15)
    val maxSystemVolume: StateFlow<Int> = _maxSystemVolume

    // Sleep Timer states
    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingSec = MutableStateFlow(0L)
    val sleepTimerRemainingSec: StateFlow<Long> = _sleepTimerRemainingSec

    private val _sleepTimerOriginalMin = MutableStateFlow(0)
    val sleepTimerOriginalMin: StateFlow<Int> = _sleepTimerOriginalMin

    data class EqualizerBand(
        val bandId: Int,
        val centerFrequencyHz: Int,
        val currentLevelMb: Int, // millibels
        val minLevelMb: Int,
        val maxLevelMb: Int
    )

    private const val CHANNEL_ID = "media_playback_channel_v2"
    private const val NOTIFICATION_ID = 8899
    private var receiverRegistered = false

    private val mediaReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                "com.example.action.PLAY_PAUSE" -> {
                    togglePlayPause()
                }
                "com.example.action.NEXT" -> {
                    playNext(context)
                }
                "com.example.action.PREVIOUS" -> {
                    playPrevious(context)
                }
                "android.media.VOLUME_CHANGED_ACTION" -> {
                    updateSystemVolumeState()
                }
            }
        }
    }

    private var mediaSession: android.media.session.MediaSession? = null
    @Volatile
    private var wasPlayingBeforeFocusLoss = false
    var hasPromptedResume = false
    private var pendingSeekPosition: Long = 0L

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = _isPlaying.value
                pausePlayback(byUser = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = _isPlaying.value
                pausePlayback(byUser = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.2f, 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f)
                if (wasPlayingBeforeFocusLoss) {
                    resumePlayback()
                    wasPlayingBeforeFocusLoss = false
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val focusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Android O+ uses focusRequest, simple abandon works or best-effort callback clearance is okay
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun updateMediaSessionState(playing: Boolean, track: PlaylistItem?) {
        val session = mediaSession ?: return
        val stateBuilder = android.media.session.PlaybackState.Builder()
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or
                android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                android.media.session.PlaybackState.ACTION_SEEK_TO or
                android.media.session.PlaybackState.ACTION_STOP
            )
        val state = if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED
        stateBuilder.setState(state, _currentPosition.value, 1.0f)
        session.setPlaybackState(stateBuilder.build())
    }

    private val _currentSourceText = MutableStateFlow("FTP Hub Player")
    val currentSourceText: StateFlow<String> = _currentSourceText

    private val _currentSourceIconId = MutableStateFlow(com.example.R.drawable.ic_widget_music)
    val currentSourceIconId: StateFlow<Int> = _currentSourceIconId

    init {
        scope.launch {
            combine(currentTrack, isPlaying, isRadioLiveMode) { track, playing, live ->
                Triple(track, playing, live)
            }.collect { (track, playing, live) ->
                appContext?.let { ctx ->
                    updateSourceMetadata(ctx, track, live)
                }
                updateNotification(track, playing)
                updateMediaSessionState(playing, track)
                updateWidget()
            }
        }
    }

    private fun updateSourceMetadata(context: Context, track: PlaylistItem?, liveRadioMode: Boolean) {
        if (track == null) {
            _currentSourceText.value = "Sin reproducción"
            _currentSourceIconId.value = com.example.R.drawable.ic_widget_music
            return
        }

        val filePath = track.filePath
        if (track.playlistId == -2) {
            // Radio Online
            if (!liveRadioMode) {
                _currentSourceText.value = "Radio Online (Timeshift) 📻"
            } else {
                _currentSourceText.value = "Radio Online 📻"
            }
            _currentSourceIconId.value = com.example.R.drawable.ic_widget_radio
        } else if (filePath.startsWith("/") || filePath.startsWith("content://") || filePath.startsWith("file://")) {
            // Almacenamiento Interno
            _currentSourceText.value = "Almacenamiento Interno 📱"
            _currentSourceIconId.value = com.example.R.drawable.ic_widget_phone
        } else if (filePath.startsWith("ftp://")) {
            // FTP Connection – Parse active connection ID
            try {
                val base = filePath.substring(6) // skip ftp://
                val slashIdx = base.indexOf('/')
                val connIdStr = if (slashIdx != -1) base.substring(0, slashIdx) else base
                val connId = connIdStr.toIntOrNull()
                if (connId != null) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val db = com.example.data.database.AppDatabase.getDatabase(context)
                            val conn = db.ftpConnectionDao().getConnectionById(connId)
                            if (conn != null) {
                                val connectedHost = FtpClientManager.getLastConnectedHost() ?: conn.host ?: ""
                                val isLocalIp = connectedHost == conn.localIp
                                val label = if (isLocalIp) "Servidor FTP (Local) 🏠" else "Servidor FTP (Remoto) 🌐"
                                _currentSourceText.value = label
                            } else {
                                _currentSourceText.value = "Servidor FTP (Remoto) 🌐"
                            }
                        } catch (e: Exception) {
                            _currentSourceText.value = "Servidor FTP (Remoto) 🌐"
                        }
                        _currentSourceIconId.value = com.example.R.drawable.ic_widget_server
                        withContext(Dispatchers.Main) {
                            updateWidget()
                        }
                    }
                    _currentSourceIconId.value = com.example.R.drawable.ic_widget_server
                    return
                }
            } catch (_: Exception) {}
            _currentSourceText.value = "Servidor FTP (Remoto) 🌐"
            _currentSourceIconId.value = com.example.R.drawable.ic_widget_server
        } else {
            _currentSourceText.value = "FTP Hub Player"
            _currentSourceIconId.value = com.example.R.drawable.ic_widget_music
        }
    }

    private fun updateWidget() {
        appContext?.let { ctx ->
            try {
                com.example.ui.widget.AudioWidgetProvider.updateAllWidgets(ctx)
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating widget: ${e.message}")
            }
        }
    }

    fun loadCoverBitmap(context: Context, track: PlaylistItem): android.graphics.Bitmap? {
        if (track.playlistId == -2) {
            try {
                val prefs = context.getSharedPreferences("ftp_hub_prefs", Context.MODE_PRIVATE)
                val jsonStr = prefs.getString("online_radios", null)
                if (jsonStr != null) {
                    val array = org.json.JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val url = obj.getString("url")
                        if (url == track.filePath) {
                            val logoUriStr = if (obj.has("logoUri")) obj.optString("logoUri", null) else null
                            if (!logoUriStr.isNullOrBlank()) {
                                if (logoUriStr.startsWith("content://")) {
                                    val uri = android.net.Uri.parse(logoUriStr)
                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                        return android.graphics.BitmapFactory.decodeStream(stream)
                                    }
                                } else {
                                    val f = java.io.File(logoUriStr)
                                    if (f.exists() && f.canRead()) {
                                        return android.graphics.BitmapFactory.decodeFile(logoUriStr)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Notification load logo error", e)
            }
            return null
        } else {
            val path = track.filePath
            val retriever = android.media.MediaMetadataRetriever()
            try {
                if (path.startsWith("content://")) {
                    val uri = android.net.Uri.parse(path)
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    }
                } else if (path.startsWith("ftp://")) {
                    val hash = path.hashCode()
                    val ext = getExtension(path)
                    val coverCacheFile = getScannedCoverFile(context, hash, ext)
                    if (coverCacheFile.exists() && coverCacheFile.length() > 0) {
                        try {
                            val bmp = android.graphics.BitmapFactory.decodeFile(coverCacheFile.absolutePath)
                            if (bmp != null) return bmp
                        } catch (_: Exception) {}
                    }
                    val cachedFile = java.io.File(context.cacheDir, "current_playing_media.mp3")
                    if (cachedFile.exists() && cachedFile.length() > 0) {
                        retriever.setDataSource(cachedFile.absolutePath)
                    } else {
                        return null
                    }
                } else {
                    val f = java.io.File(path)
                    if (f.exists() && f.canRead()) {
                        retriever.setDataSource(path)
                    } else {
                        return null
                    }
                }
                val art = retriever.embeddedPicture
                if (art != null) {
                    return android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                }
            } catch (e: Exception) {
                // ignore
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun updateNotification(track: PlaylistItem?, playing: Boolean) {
        val context = appContext ?: return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (track == null) {
            notificationManager.cancel(NOTIFICATION_ID)
            try {
                activeService?.stopForeground(true)
                activeService = null
                val serviceIntent = android.content.Intent(context, com.example.service.MyMediaBrowserService::class.java)
                context.stopService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service on track null: ${e.message}")
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            val largeIconBitmap = loadCoverBitmap(context, track)
            
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (track != _currentTrack.value) {
                    return@withContext
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        CHANNEL_ID,
                        "Reproducción Multimedia",
                        android.app.NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Controles de reproducción activos de FTP Media Hub"
                        setShowBadge(false)
                    }
                    notificationManager.createNotificationChannel(channel)
                }

                val playPauseIntent = android.content.Intent("com.example.action.PLAY_PAUSE").apply {
                    `package` = context.packageName
                }
                val playPausePendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 101, playPauseIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val nextIntent = android.content.Intent("com.example.action.NEXT").apply {
                    `package` = context.packageName
                }
                val nextPendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 102, nextIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val prevIntent = android.content.Intent("com.example.action.PREVIOUS").apply {
                    `package` = context.packageName
                }
                val prevPendingIntent = android.app.PendingIntent.getBroadcast(
                    context, 103, prevIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val openIntent = android.content.Intent(context, javaClass.classLoader?.loadClass("com.example.MainActivity") ?: context.javaClass).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val openPendingIntent = android.app.PendingIntent.getActivity(
                    context, 104, openIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val sourceText = when {
                    track.playlistId == -2 -> "Radio Online"
                    track.filePath.startsWith("ftp://") -> "Servidor FTP"
                    else -> "Almacenamiento Interno"
                }

                val isRadioWithMetadata = track.playlistId == -2 && _radioMetadataEnabled.value && !_radioSongTitle.value.isNullOrBlank()
                val notificationTitle = if (isRadioWithMetadata) _radioSongTitle.value!! else track.fileName
                val notificationArtist = if (isRadioWithMetadata) (_radioSongArtist.value ?: "Radio Online") else sourceText

                val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationArtist)
                    .setContentIntent(openPendingIntent)
                    .setOngoing(playing)
                    .setAutoCancel(false)
                    .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_TRANSPORT)

                if (largeIconBitmap != null) {
                    builder.setLargeIcon(largeIconBitmap)
                }

                // Update active system MediaSession details with thumbnail/cover in real-time
                mediaSession?.let { session ->
                    try {
                        session.isActive = true
                        val metadataBuilder = android.media.MediaMetadata.Builder()
                            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, notificationTitle)
                            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, notificationArtist)
                            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, _duration.value)

                        if (largeIconBitmap != null) {
                            metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, largeIconBitmap)
                            metadataBuilder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, largeIconBitmap)
                        }
                        session.setMetadata(metadataBuilder.build())
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating media session metadata", e)
                    }
                }

                builder.addAction(
                    android.R.drawable.ic_media_previous,
                    "Anterior",
                    prevPendingIntent
                )
                builder.addAction(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pausar" else "Reproducir",
                    playPausePendingIntent
                )
                builder.addAction(
                    android.R.drawable.ic_media_next,
                    "Siguiente",
                    nextPendingIntent
                )

                try {
                    val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2)
                    mediaSession?.sessionToken?.let { token ->
                        mediaStyle.setMediaSession(android.support.v4.media.session.MediaSessionCompat.Token.fromToken(token))
                    }
                    builder.setStyle(mediaStyle)
                } catch (_: Exception) {}

                val notification = builder.build()
                var hasNotificationPermission = true
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                    hasNotificationPermission = (permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED)
                }

                if (hasNotificationPermission) {
                    try {
                        val serviceIntent = android.content.Intent(context, com.example.service.MyMediaBrowserService::class.java)
                        if (playing) {
                            try {
                                context.startService(serviceIntent)
                            } catch (se: Exception) {
                                Log.e(TAG, "Failed startService: ${se.message}")
                            }

                            val service = activeService
                            if (service != null) {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    service.startForeground(
                                        NOTIFICATION_ID,
                                        notification,
                                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                                    )
                                } else {
                                    service.startForeground(NOTIFICATION_ID, notification)
                                }
                            } else {
                                notificationManager.notify(NOTIFICATION_ID, notification)
                            }
                        } else {
                            val service = activeService
                            if (service != null) {
                                service.stopForeground(false)
                            }
                            notificationManager.notify(NOTIFICATION_ID, notification)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error managing foreground service transitions: ${e.message}")
                        notificationManager.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        }
    }

    private fun startAutoReconnectMonitor() {
        autoReconnectMonitorJob?.cancel()
        autoReconnectMonitorJob = scope.launch(Dispatchers.IO) {
            var bufferingSeconds = 0
            var silentCutSeconds = 0
            var stoppedSeconds = 0
            var consecutiveFailures = 0
            
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                val track = _currentTrack.value
                val playing = _isPlaying.value
                val buffering = _isBuffering.value
                
                if (track != null && track.playlistId == -2 && _isRadioLiveMode.value && !userPressedPause && !wasPlayingBeforeFocusLoss) {
                    var playerIsReallyPlaying = false
                    try {
                        playerIsReallyPlaying = mediaPlayer?.isPlaying == true
                    } catch (_: Exception) {}
                    
                    val ctx = appContext
                    if (ctx != null) {
                        val prefs = ctx.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                        val autoReconnectLive = prefs.getBoolean("radio_auto_reconnect_live", true)
                        val reconnectPaused = prefs.getBoolean("radio_reconnect_paused_enabled", false)
                        val forceResume = prefs.getBoolean("radio_force_resume_on_buffer_freeze", true)
                        
                        var shouldReconnect = false
                        var reason = ""
                        
                        // Scenario A: Stuck buffering (playing=true, buffering=true, but taking more than 15 seconds to prepare)
                        if (playing && buffering) {
                            bufferingSeconds++
                            if (bufferingSeconds >= 15) {
                                shouldReconnect = true
                                reason = "Buffer/Preparing stuck (15s)"
                            }
                        } else {
                            bufferingSeconds = 0
                        }
                        
                        // Scenario B: Silent cut (playing=true, buffering=false, but player is NOT really playing)
                        if (playing && !buffering && !playerIsReallyPlaying) {
                            silentCutSeconds++
                            if (silentCutSeconds >= 3) {
                                shouldReconnect = true
                                reason = "Silent cut - expected playing but stopped (3s)"
                            }
                        } else {
                            silentCutSeconds = 0
                        }
                        
                        // Scenario C: Completely stopped but not paused by user (playing=false, expected playing, stays for 3s)
                        if (!playing) {
                            stoppedSeconds++
                            if (stoppedSeconds >= 3) {
                                shouldReconnect = true
                                reason = "Stopped state without user pause (3s)"
                            }
                        } else {
                            stoppedSeconds = 0
                        }
                        
                        if (playerIsReallyPlaying && !buffering) {
                            consecutiveFailures = 0
                        }
                        
                        if (shouldReconnect && (autoReconnectLive || reconnectPaused || forceResume || consecutiveFailures < 3)) {
                            Log.i("AudioPlayerManager", "Auto-reconnect monitor: Reconnecting... Reason: $reason, consecutiveFailures: $consecutiveFailures")
                            
                            bufferingSeconds = 0
                            silentCutSeconds = 0
                            stoppedSeconds = 0
                            consecutiveFailures++
                            
                            if (consecutiveFailures > 6) {
                                Log.w("AudioPlayerManager", "Auto-reconnect: Extreme retry limit reached. Backing off 10s.")
                                kotlinx.coroutines.delay(9000)
                                continue
                            }
                            
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                _isBuffering.value = true
                                _isPlaying.value = true
                                try {
                                    val toastMsg = if (reason.contains("Buffer")) {
                                        "🔄 Conexión inestable: Reintentando..."
                                    } else {
                                        "🔄 Señal interrumpida: Reanudando..."
                                    }
                                    android.widget.Toast.makeText(ctx, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                                } catch (_: Exception) {}
                                ensureRadioReconnected(ctx)
                            }
                        }
                    }
                } else {
                    bufferingSeconds = 0
                    silentCutSeconds = 0
                    stoppedSeconds = 0
                    consecutiveFailures = 0
                }
            }
        }
    }

    fun initialize(context: Context) {
        try {
            appContext = context.applicationContext
            val initPrefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            _radioMetadataEnabled.value = initPrefs.getBoolean("radio_metadata_enabled", true)
            if (autoReconnectMonitorJob == null || autoReconnectMonitorJob?.isActive == false) {
                startAutoReconnectMonitor()
            }
            if (!receiverRegistered) {
                val filter = android.content.IntentFilter().apply {
                    addAction("com.example.action.PLAY_PAUSE")
                    addAction("com.example.action.NEXT")
                    addAction("com.example.action.PREVIOUS")
                    addAction("android.media.VOLUME_CHANGED_ACTION")
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.applicationContext.registerReceiver(
                        mediaReceiver,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    context.applicationContext.registerReceiver(
                        mediaReceiver,
                        filter
                    )
                }
                receiverRegistered = true
            }
            if (audioManager == null) {
                audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                updateSystemVolumeState()
            }
            if (mediaSession == null) {
                mediaSession = android.media.session.MediaSession(context.applicationContext, "FTPMediaHubSession").apply {
                    setCallback(object : android.media.session.MediaSession.Callback() {
                        override fun onPlay() {
                            resumePlayback()
                        }
                        override fun onPause() {
                            pausePlayback()
                        }
                        override fun onSkipToNext() {
                            playNext(appContext)
                        }
                        override fun onSkipToPrevious() {
                            playPrevious(appContext)
                        }
                        override fun onSeekTo(pos: Long) {
                            seekTo(pos)
                        }
                        override fun onStop() {
                            stopPlayback()
                        }
                    })
                    val stateBuilder = android.media.session.PlaybackState.Builder()
                        .setActions(
                            android.media.session.PlaybackState.ACTION_PLAY or
                            android.media.session.PlaybackState.ACTION_PAUSE or
                            android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                            android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                            android.media.session.PlaybackState.ACTION_SEEK_TO or
                            android.media.session.PlaybackState.ACTION_STOP
                        )
                    setPlaybackState(stateBuilder.build())
                    isActive = true
                }
            }
            // Restore playback status on startup
            restorePlaybackState(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioManager: ${e.message}", e)
        }
    }

    fun getSessionTokenCompat(): android.support.v4.media.session.MediaSessionCompat.Token? {
        val session = mediaSession ?: return null
        return android.support.v4.media.session.MediaSessionCompat.Token.fromToken(session.sessionToken)
    }

    private fun savePlaybackState(context: Context, track: PlaylistItem) {
        try {
            val prefs = context.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("track_fileName", track.fileName)
                putString("track_filePath", track.filePath)
                putLong("track_fileSize", track.fileSize)
                putInt("track_playlistId", track.playlistId)
                apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving playback state: ${e.message}")
        }
    }

    private fun safeDecode(path: String): String {
        return try {
            java.net.URLDecoder.decode(path, "UTF-8")
        } catch (_: Exception) {
            path
        }
    }

    private suspend fun preloadSiblings(context: Context, track: PlaylistItem) = withContext(Dispatchers.IO) {
        isPreloadingSiblings = true
        try {
                val uri = track.filePath
                val decodedUri = safeDecode(uri)
                val lastSlash = decodedUri.lastIndexOf('/')
                val parentPathDecoded = if (lastSlash >= 0) decodedUri.substring(0, lastSlash) else ""

                if (parentPathDecoded.isNotEmpty()) {
                    val db = AppDatabase.getDatabase(context)
                    val allTracks = db.localMusicTrackDao().getAllLocalTracksOnce()
                    val siblingTracks = allTracks.filter { storedTrack ->
                        val storedDecoded = safeDecode(storedTrack.filePath)
                        val storedSlash = storedDecoded.lastIndexOf('/')
                        val storedParent = if (storedSlash >= 0) storedDecoded.substring(0, storedSlash) else ""
                        storedParent == parentPathDecoded
                    }

                    if (siblingTracks.isNotEmpty()) {
                        val audioItems = siblingTracks.map { storedTrack ->
                            PlaylistItem(
                                playlistId = 0,
                                fileName = storedTrack.title.ifBlank { storedTrack.filePath.substringAfterLast('/') },
                                filePath = storedTrack.filePath,
                                fileSize = storedTrack.size,
                                durationText = storedTrack.durationText
                            )
                        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName })

                        withContext(Dispatchers.Main) {
                            currentPlaylist = audioItems.toMutableList()
                            _playlistQueue.value = audioItems.toList()
                            val decodedTarget = safeDecode(track.filePath)
                            val idx = audioItems.indexOfFirst { safeDecode(it.filePath) == decodedTarget }
                            if (idx != -1) {
                                currentIndex = idx
                            }
                            isPreloadingSiblings = false
                        }
                        return@withContext
                    }
                }

                // Fallback to manual directory fetching if database did not yield any siblings
                if (uri.startsWith("ftp://")) {
                    val parts = uri.substring(6).split("/", limit = 2)
                    val ftpConnId = parts.getOrNull(0)?.toIntOrNull()
                    val remainingPathEncoded = "/" + (parts.getOrNull(1) ?: "")
                    val remainingPath = safeDecode(remainingPathEncoded)
                    
                    val lastSlashIdx = remainingPath.lastIndexOf('/')
                    val parentPath = if (lastSlashIdx <= 0) "/" else remainingPath.substring(0, lastSlashIdx)
                    
                    if (ftpConnId != null) {
                        val db = AppDatabase.getDatabase(context)
                        val targetConn = db.ftpConnectionDao().getAllConnectionsOnce().firstOrNull { it.id == ftpConnId }
                        if (targetConn != null) {
                            FtpClientManager.connect(targetConn)
                            val ftpFiles = FtpClientManager.listFiles(parentPath)
                            val audioItems = ftpFiles.filter { !it.isDirectory && it.isAudio }.map { audioFile ->
                                PlaylistItem(
                                    playlistId = 0,
                                    fileName = audioFile.name,
                                    filePath = "ftp://$ftpConnId${audioFile.path}",
                                    fileSize = audioFile.size
                                )
                            }
                            if (audioItems.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    currentPlaylist = audioItems.toMutableList()
                                    _playlistQueue.value = audioItems.toList()
                                    val decodedTarget = safeDecode(track.filePath)
                                    val idx = audioItems.indexOfFirst { safeDecode(it.filePath) == decodedTarget }
                                    if (idx != -1) {
                                        currentIndex = idx
                                    }
                                    isPreloadingSiblings = false
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    isPreloadingSiblings = false
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                    isPreloadingSiblings = false
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isPreloadingSiblings = false
                        }
                    }
                } else {
                    // Local directory
                    val localFile = File(decodedUri)
                    val parentDir = localFile.parentFile
                    if (parentDir != null && parentDir.isDirectory) {
                        val localAudios = parentDir.listFiles()?.filter { f ->
                            f.isFile && (f.name.endsWith(".mp3", ignoreCase = true) || f.name.endsWith(".wav", ignoreCase = true) || f.name.endsWith(".ogg", ignoreCase = true) || f.name.endsWith(".m4a", ignoreCase = true) || f.name.endsWith(".flac", ignoreCase = true) || f.name.endsWith(".aac", ignoreCase = true))
                        }?.sortedWith(compareBy { it.name.lowercase() }) ?: emptyList()
                        
                        val audioItems = localAudios.map { f ->
                            PlaylistItem(
                                playlistId = 0,
                                fileName = f.name,
                                filePath = f.absolutePath,
                                fileSize = f.length()
                            )
                        }
                        if (audioItems.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                currentPlaylist = audioItems.toMutableList()
                                _playlistQueue.value = audioItems.toList()
                                val decodedTarget = safeDecode(track.filePath)
                                val idx = audioItems.indexOfFirst { safeDecode(it.filePath) == decodedTarget }
                                if (idx != -1) {
                                    currentIndex = idx
                                }
                                isPreloadingSiblings = false
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isPreloadingSiblings = false
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            isPreloadingSiblings = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preloading folder audio items: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isPreloadingSiblings = false
                }
            }
        }

    private fun restorePlaybackState(context: Context) {
        try {
            val prefs = context.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
            val path = prefs.getString("track_filePath", null)
            if (path != null) {
                val name = prefs.getString("track_fileName", "Restored Track") ?: "Restored Track"
                val size = prefs.getLong("track_fileSize", 0L)
                val pId = prefs.getInt("track_playlistId", -1)
                val pos = prefs.getLong("track_position", 0L)
                
                val restored = PlaylistItem(
                    id = 0,
                    playlistId = pId,
                    fileName = name,
                    filePath = path,
                    fileSize = size
                )
                _currentTrack.value = restored
                _currentPosition.value = pos
                currentPlaylist = mutableListOf(restored)
                _playlistQueue.value = currentPlaylist.toList()
                currentIndex = 0
                userPressedPause = true
                if (pId != -2) {
                    scope.launch {
                        preloadSiblings(context, restored)
                    }
                }

                // Auto-play / Auto-resume restored track on application launch
                val settingsPrefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                val autoPlayOnStart = settingsPrefs.getBoolean("auto_play_on_startup", false)
                if (autoPlayOnStart) {
                    FtpClientManager.addLog("Launch: Auto-reproducir al iniciar activado. Restableciendo pista: ${restored.fileName}")
                    scope.launch(Dispatchers.Main) {
                        kotlinx.coroutines.delay(1200) // Delay to ensure app/UI is ready
                        if (pId == -2) {
                            reconnectRadioStream(context, playImmediately = true)
                        } else {
                            playTrack(context, restored)
                            if (pos > 0) {
                                seekTo(pos)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring playback state: ${e.message}")
        }
    }

    private fun updateSystemVolumeState() {
        try {
            audioManager?.let {
                _systemVolume.value = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                _maxSystemVolume.value = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update system volume: ${e.message}", e)
        }
    }

    fun setSystemVolume(level: Int) {
        try {
            audioManager?.let {
                it.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                _systemVolume.value = level
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set system volume: ${e.message}", e)
        }
    }

    fun updateCurrentTrackNameAndUrl(oldUrl: String, newName: String, newUrl: String) {
        val current = _currentTrack.value ?: return
        if (current.playlistId == -2 && current.filePath == oldUrl) {
            _currentTrack.value = current.copy(fileName = newName, filePath = newUrl)
        }
    }

    fun playTrack(context: Context, track: PlaylistItem, playlist: List<PlaylistItem> = listOf(track), initialPositionMs: Long = 0L) {
        userPressedPause = false
        _playbackJumpToPlayerTrigger.tryEmit(Unit)
        if (_isRecordingProtectedMode.value && _currentTrack.value?.filePath != track.filePath) {
            try {
                Toast.makeText(context, "⚠️ Modo Seguro Activo", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        initialize(context)
        try {
            val prefs = context.getSharedPreferences("track_play_counts", Context.MODE_PRIVATE)
            val currentCount = prefs.getInt(track.filePath, 0)
            prefs.edit().putInt(track.filePath, currentCount + 1).apply()
        } catch (_: Exception) {}
        
        val newPlaylist = playlist.toMutableList()
        val targetIndex = newPlaylist.indexOfFirst { it.filePath == track.filePath }
        val finalIndex = if (targetIndex == -1) {
            newPlaylist.add(track)
            newPlaylist.size - 1
        } else {
            targetIndex
        }

        pendingSeekPosition = initialPositionMs

        // Save to recently played database table
        scope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                
                var trackLogoUri: String? = null
                try {
                    if (track.playlistId == -2) {
                        val settingsPrefs = context.getSharedPreferences("ftp_hub_prefs", Context.MODE_PRIVATE)
                        val jsonStr = settingsPrefs.getString("online_radios", null)
                        if (jsonStr != null) {
                            val array = org.json.JSONArray(jsonStr)
                            for (intI in 0 until array.length()) {
                                val obj = array.getJSONObject(intI)
                                val url = obj.getString("url")
                                if (url == track.filePath) {
                                    trackLogoUri = if (obj.has("logoUri")) obj.optString("logoUri", null) else null
                                    break
                                }
                            }
                        }
                    } else {
                        val matchingLoc = db.localMusicTrackDao().getAllLocalTracksOnce().find { it.filePath == track.filePath }
                        trackLogoUri = matchingLoc?.logoUri
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error looking up logoUri for recent track: ${e.message}")
                }

                val recent = RecentTrack(
                    filePath = track.filePath,
                    fileName = track.fileName,
                    playlistId = track.playlistId,
                    fileSize = track.fileSize,
                    durationText = track.durationText,
                    playedAt = System.currentTimeMillis(),
                    logoUri = trackLogoUri
                )
                db.recentTrackDao().insertRecentTrack(recent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save track to recently played database: ${e.message}")
            }
        }

        transitionDownloadJob?.cancel()
        prefetchJob?.cancel()

        // Core sequential setup coroutine on Main thread to avoid socket contention with play initialization
        scope.launch(Dispatchers.Main) {
            val isPendingPreload = playlist.isEmpty() || (playlist.size == 1 && playlist.first().filePath == track.filePath)
            if (isPendingPreload) {
                preloadSiblings(context, track)
            } else {
                currentPlaylist = newPlaylist
                _playlistQueue.value = newPlaylist.toList()
                currentIndex = finalIndex
            }

            val finalPlaylist = currentPlaylist.toList()
            val finalIdx = currentIndex

            val cachedFile = getCachedFileForTrack(context, track)
            val isCached = cachedFile != null && cachedFile.exists() && cachedFile.length() > 0
            val isCurrentPlaying = _isPlaying.value && mediaPlayer != null

            val sysPrefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val ultraFastEnabled = sysPrefs.getBoolean("ultra_fast_loading_enabled", false)

            if (isCurrentPlaying && !isCached && track.playlistId != -2 && track.filePath.startsWith("ftp://") && !ultraFastEnabled) {
                // Keep current playlist and index visual-state matching the currently active track
                val oldPlayingTrack = _currentTrack.value
                if (oldPlayingTrack != null) {
                    val found = currentPlaylist.indexOfFirst { it.filePath == oldPlayingTrack.filePath }
                    if (found != -1) {
                        currentIndex = found
                    }
                }
                
                _prefetchTrackName.value = track.fileName
                _prefetchState.value = "Descargando"
                _prefetchProgress.value = 0f
                _isDownloadingActiveTrack.value = true
                _bufferingProgress.value = 0f
                
                transitionDownloadJob = scope.launch(Dispatchers.Main) {
                    try {
                        val success = downloadTrackToCache(context, track) { progress ->
                            _prefetchProgress.value = progress
                            _bufferingProgress.value = progress
                        }
                        if (success) {
                            _prefetchState.value = "Completado"
                            currentPlaylist = finalPlaylist.toMutableList()
                            _playlistQueue.value = finalPlaylist
                            currentIndex = finalIdx
                            loadAndPlay(context, track)
                        } else {
                            _prefetchState.value = "Error"
                            currentPlaylist = finalPlaylist.toMutableList()
                            _playlistQueue.value = finalPlaylist
                            currentIndex = finalIdx
                            loadAndPlay(context, track)
                        }
                    } finally {
                        _isDownloadingActiveTrack.value = false
                    }
                }
            } else {
                _prefetchState.value = "Inactivo"
                currentPlaylist = finalPlaylist.toMutableList()
                _playlistQueue.value = finalPlaylist
                currentIndex = finalIdx
                loadAndPlay(context, track)
            }
        }
    }

    fun stopPlayback() {
        userPressedPause = true
        _isPlaying.value = false
        _isRadioReconnecting.value = false
        _currentTrack.value = null
        prefetchedTrackPath = null
        _isDownloadingActiveTrack.value = false
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        transitionDownloadJob?.cancel()
        transitionDownloadJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        stopRadioMetadataFetcher()
        scope.launch(Dispatchers.IO) {
            try {
                FtpClientManager.disconnect()
            } catch (_: Exception) {}
        }
        stopProgressUpdates()
        stopRadioRecording()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        releaseAudioFx()

        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
        abandonAudioFocus()
    }

    fun stopRadioRecording() {
        RadioLogger.log("stopRadioRecording - Deteniendo grabación de timeshift de radio y limpiando archivos temporales...")
        radioRecordingJob?.cancel()
        radioRecordingJob = null
        radioWatchdogJob?.cancel()
        radioWatchdogJob = null
        try {
            radioRecordingFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        } catch (_: Exception) {}
        radioRecordingFile = null
        appContext?.let { ctx ->
            try {
                val cacheTemp = File(ctx.cacheDir, "timeshift_temp.mp3")
                if (cacheTemp.exists()) {
                    cacheTemp.delete()
                }
            } catch (_: Exception) {}
        }
        _totalRecordedBytes.value = 0L
        _radioElapsedTimeSec.value = 0
        _radioPlayPositionSec.value = 0
        _isRadioLiveMode.value = true
        _isRecordingProtectedMode.value = false
        radioStartTimeMs = 0L
        radioPauseTimeMs = 0L
    }

    fun stopRadioStream() {
        userPressedPause = true
        _isPlaying.value = false
        _isBuffering.value = false
        _isRecordingProtectedMode.value = false
        stopProgressUpdates()
        stopRadioRecording()
        stopRadioMetadataFetcher()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
    }

    private fun startRadioRecording(context: Context, url: String, keepExistingFile: Boolean = false) {
        RadioLogger.log("startRadioRecording - Iniciando grabación de radio. URL: $url, conservarArchivoExistente: $keepExistingFile")
        if (keepExistingFile && radioRecordingJob != null && radioRecordingFile != null) {
            RadioLogger.log("startRadioRecording - Manteniendo archivo de grabación y job de recopilación actual.")
            return
        }
        if (!keepExistingFile) {
            stopRadioRecording()
        }
        _isRadioLiveMode.value = true
        if (!keepExistingFile) {
            _radioElapsedTimeSec.value = 0
            _radioPlayPositionSec.value = 0
            radioStartTimeMs = System.currentTimeMillis()
        }

        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val bufferLimitMins = prefs.getInt("radio_buffer_limit_minutes", 120) // default 120 minutes
        _radioBufferLimitMins.value = bufferLimitMins

        radioWatchdogJob?.cancel()
        radioWatchdogJob = scope.launch(Dispatchers.IO) {
            var lastBytes = 0L
            var freezeCount = 0
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                val isPlayingCurrently = _isPlaying.value
                val isRadioCurrent = _currentTrack.value?.playlistId == -2
                val currentBytes = _totalRecordedBytes.value
                
                if (isRadioCurrent && isPlayingCurrently) {
                    val bytesDownloadedThisSecond = currentBytes - lastBytes
                    val speedKBs = bytesDownloadedThisSecond.toFloat() / 1024f
                    _radioDownloadSpeedKbps.value = speedKBs
                    
                    if (currentBytes == lastBytes) {
                        freezeCount++
                        val nextStability = (_radioSignalStability.value - 0.2f).coerceIn(0f, 1f)
                        _radioSignalStability.value = nextStability
                        
                        if (freezeCount >= 5) {
                            RadioLogger.log("Watchdog: Alerta de congelamiento de buffer detectada (5 segundos sin recibir datos). Bytes recibidos: $currentBytes")
                            val forceResume = prefs.getBoolean("radio_force_resume_on_buffer_freeze", false)
                            if (forceResume) {
                                RadioLogger.log("Watchdog: Forzando reanudación automática de la transmisión por congelamiento...")
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    reconnectRadioStream(context, playImmediately = true)
                                }
                            }
                            freezeCount = 0
                        }
                    } else {
                        freezeCount = 0
                        val nextStability = (_radioSignalStability.value + 0.15f).coerceIn(0f, 1f)
                        _radioSignalStability.value = nextStability
                        lastBytes = currentBytes
                    }
                    
                    val currentHistory = _radioHealthHistory.value.toMutableList()
                    if (currentHistory.size >= 15) {
                        currentHistory.removeAt(0)
                    }
                    currentHistory.add(_radioSignalStability.value)
                    _radioHealthHistory.value = currentHistory
                } else {
                    _radioDownloadSpeedKbps.value = 0f
                    _radioSignalStability.value = 1.0f
                    freezeCount = 0
                    
                    val currentHistory = _radioHealthHistory.value.toMutableList()
                    if (currentHistory.size >= 15) {
                        currentHistory.removeAt(0)
                    }
                    currentHistory.add(1.0f)
                    _radioHealthHistory.value = currentHistory
                }
            }
        }

        radioRecordingJob = scope.launch(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "timeshift_temp.mp3")
            try {
                if (!keepExistingFile) {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    tempFile.createNewFile()
                } else if (!tempFile.exists()) {
                    tempFile.createNewFile()
                }
                radioRecordingFile = tempFile
            } catch (e: Exception) {
                Log.e(TAG, "Error creating radio temp recording file: ${e.message}")
                return@launch
            }

            var bytesWritten = if (keepExistingFile) tempFile.length() else 0L

            while (radioRecordingJob?.isActive == true) {
                var inputStream: java.io.InputStream? = null
                var outputStream: FileOutputStream? = null
                var connection: HttpURLConnection? = null
                try {
                    val resolvedUrl = resolveRadioStreamUrl(url)
                    val urlObj = URL(resolvedUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 20000
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    connection.connect()

                    inputStream = BufferedInputStream(connection.inputStream)
                    outputStream = FileOutputStream(tempFile, true) // Open in append mode!

                    val buffer = ByteArray(16384)

                    while (radioRecordingJob?.isActive == true) {
                        val read = try {
                            inputStream.read(buffer)
                        } catch (e: Exception) {
                            RadioLogger.log("Grabación timeshift: Error al leer datos del streaming de radio: ${e.message}")
                            -2 // custom indicator for error read
                        }

                        if (read == -1 || read == -2) {
                            RadioLogger.log("Grabación timeshift: Flujo finalizado o error detectado (read=$read). Intentando reconectar flujo...")
                            break // break inner loop to trigger reconnect/retry
                        }

                        outputStream.write(buffer, 0, read)
                        bytesWritten += read
                        _totalRecordedBytes.value = bytesWritten

                        val startTime = radioStartTimeMs
                        if (startTime > 0L) {
                            val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                            _radioElapsedTimeSec.value = elapsed

                            val dynamicLimitSecs = _radioBufferLimitMins.value * 60
                            if (elapsed >= dynamicLimitSecs) {
                                RadioLogger.log("Grabación timeshift: Se alcanzó el límite del buffer (${_radioBufferLimitMins.value} min). Deteniendo grabación.")
                                return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    RadioLogger.log("Grabación timeshift: Fallo en la conexión de grabación de streaming: ${e.message}")
                } finally {
                    try { outputStream?.close() } catch(_: Exception) {}
                    try { inputStream?.close() } catch(_: Exception) {}
                    try { connection?.disconnect() } catch(_: Exception) {}
                }

                // If still active, wait 3 seconds and retry
                if (radioRecordingJob?.isActive == true) {
                    val startTime = radioStartTimeMs
                    if (startTime > 0L) {
                        val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                        val dynamicLimitSecs = _radioBufferLimitMins.value * 60
                        if (elapsed >= dynamicLimitSecs) {
                            break
                        }
                    }
                    Log.d(TAG, "Reconnecting radio stream in 3 seconds to keep a permanent connection...")
                    kotlinx.coroutines.delay(3000)
                }
            }
        }
    }

    fun seekRadioTimeShift(positionSec: Int) {
        val clampedSec = positionSec.coerceIn(0, _radioElapsedTimeSec.value)
        _radioPlayPositionSec.value = clampedSec

        if (clampedSec >= _radioElapsedTimeSec.value - 12) {
            goLiveRadio()
        } else {
            playRadioTimeShiftAt(clampedSec * 1000L)
        }
    }

    fun goLiveRadio() {
        val track = _currentTrack.value ?: return
        _isRadioLiveMode.value = true
        _radioPlayPositionSec.value = _radioElapsedTimeSec.value

        scope.launch {
            try {
                _isBuffering.value = true
                mediaPlayer?.let {
                    try { if (it.isPlaying) it.stop() } catch(_: Exception) {}
                    it.release()
                }
                mediaPlayer = null

                val resolvedPath = resolveRadioStreamUrl(track.filePath)
                val player = MediaPlayer().apply {
                    if (appContext != null) {
                        try {
                            setWakeMode(appContext!!, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed setWakeMode on player: ${e.message}")
                        }
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    try {
                        val uri = android.net.Uri.parse(resolvedPath)
                        val headers = java.util.HashMap<String, String>().apply {
                            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            put("Connection", "keep-alive")
                            put("Accept", "*/*")
                        }
                        setDataSource(appContext!!, uri, headers)
                    } catch (ex: Exception) {
                        setDataSource(resolvedPath)
                    }
                    setOnPreparedListener { mp ->
                        _isBuffering.value = false
                        setupAudioFx(mp.audioSessionId)
                        _isPlaying.value = true
                        mp.start()
                        startProgressUpdates()
                    }
                    setOnErrorListener(this@AudioPlayerManager)
                    prepareAsync()
                }
                mediaPlayer = player
            } catch(e: Exception) {
                _isBuffering.value = false
                _isPlaying.value = false
                Log.e(TAG, "Error returning to live stream: ${e.message}")
            }
        }
    }

    fun playRadioTimeShiftAt(positionMs: Long) {
        userPressedPause = false
        val track = _currentTrack.value ?: return
        _isRadioLiveMode.value = false

        scope.launch {
            try {
                _isBuffering.value = true
                mediaPlayer?.let {
                    try { if (it.isPlaying) it.stop() } catch(_: Exception) {}
                    it.release()
                }
                mediaPlayer = null

                val tempFile = File(appContext!!.cacheDir, "timeshift_temp.mp3")
                if (!tempFile.exists()) {
                    _isBuffering.value = false
                    return@launch
                }

                val player = MediaPlayer().apply {
                    if (appContext != null) {
                        try {
                            setWakeMode(appContext!!, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed setWakeMode on player: ${e.message}")
                        }
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(tempFile.absolutePath)

                    setOnPreparedListener { mp ->
                        _isBuffering.value = false
                        setupAudioFx(mp.audioSessionId)
                        _isPlaying.value = true
                        mp.start()
                        scope.launch {
                            kotlinx.coroutines.delay(150)
                            try {
                                mp.seekTo(positionMs.toInt())
                                _currentPosition.value = positionMs
                            } catch (e: Exception) {
                                Log.e(TAG, "seekTo failed: ${e.message}")
                            }
                        }
                        startProgressUpdates()
                    }
                    setOnErrorListener(this@AudioPlayerManager)
                    prepareAsync()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                _isBuffering.value = false
                _isPlaying.value = false
                Log.e(TAG, "Error starting radio Timeshift play: ${e.message}")
            }
        }
    }

    fun saveRadioRecording(context: Context, customName: String? = null): Boolean {
        val current = _currentTrack.value ?: return false
        val tempFile = radioRecordingFile
        if (tempFile == null || !tempFile.exists() || tempFile.length() == 0L) {
            return false
        }

        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val cleanName = (customName ?: current.fileName).replace(Regex("[^a-zA-Z0-9_]"), "_")
            val outputFileName = "Grabacion_${cleanName}_${timestamp}.mp3"

            val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val customDirPath = prefs.getString("recording_destination_dir", null)
            val isCustomActive = !customDirPath.isNullOrBlank()

            val targetBaseDir = if (isCustomActive) {
                File(customDirPath!!)
            } else {
                File(context.filesDir, "Grabaciones")
            }
            val targetDir = File(targetBaseDir, "radio")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, outputFileName)
            tempFile.copyTo(targetFile, overwrite = true)

            // Preservar podmarks de la radio en la grabación física (USER REQUEST)
            try {
                val podmarksPrefs = context.getSharedPreferences("player_podmarks", Context.MODE_PRIVATE)
                val jsonStr = podmarksPrefs.getString("podmarks_list", "[]") ?: "[]"
                val originalArray = org.json.JSONArray(jsonStr)
                val newArray = org.json.JSONArray(jsonStr) // start as copy of original
                
                var copiedCount = 0
                for (i in 0 until originalArray.length()) {
                    val obj = originalArray.getJSONObject(i)
                    val p_path = obj.optString("filePath", "")
                    if (p_path == current.filePath) {
                        // Duplicate this podmark pointing to the new file!
                        val duplicatedObj = org.json.JSONObject()
                        duplicatedObj.put("trackName", "Grabación: ${current.fileName} ($timestamp)")
                        duplicatedObj.put("filePath", targetFile.absolutePath)
                        duplicatedObj.put("positionMs", obj.optLong("positionMs", 0L))
                        duplicatedObj.put("timestampText", obj.optString("timestampText", "00:00"))
                        duplicatedObj.put("createdAt", obj.optString("createdAt", ""))
                        if (obj.has("logoUri")) {
                            duplicatedObj.put("logoUri", obj.optString("logoUri", null))
                        }
                        newArray.put(duplicatedObj)
                        copiedCount++
                    }
                }
                if (copiedCount > 0) {
                    podmarksPrefs.edit().putString("podmarks_list", newArray.toString()).apply()
                    Log.d(TAG, "Duplicated $copiedCount podmarks from radio stream to physical recording $outputFileName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error copying podmarks for saved radio recording: ${e.message}")
            }

            // Save to database
            scope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(context)
                    
                    var radioLogoUri: String? = null
                    var radioStationName: String? = null
                    try {
                        val settingsPrefs = context.getSharedPreferences("ftp_hub_prefs", Context.MODE_PRIVATE)
                        val jsonStr = settingsPrefs.getString("online_radios", null)
                        if (jsonStr != null) {
                            val array = org.json.JSONArray(jsonStr)
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                val url = obj.getString("url")
                                if (url == current.filePath) {
                                    radioLogoUri = if (obj.has("logoUri")) obj.optString("logoUri", null) else null
                                    radioStationName = if (obj.has("name")) obj.optString("name", null) else null
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error looking up radio details: ${e.message}")
                    }

                    if (!isCustomActive) {
                        val name = "Grabaciones de Radio"
                        var playlist = db.playlistDao().getPlaylistByName(name)
                        val playlistId = if (playlist == null) {
                            db.playlistDao().insertPlaylist(com.example.data.model.Playlist(name = name))
                        } else {
                            playlist.id.toLong()
                        }

                        db.playlistDao().insertPlaylistItem(
                            com.example.data.model.PlaylistItem(
                                playlistId = playlistId.toInt(),
                                fileName = "Grabación: ${current.fileName} ($timestamp)",
                                filePath = targetFile.absolutePath,
                                fileSize = targetFile.length(),
                                durationText = "Radio Rec"
                            )
                        )
                    }

                    db.localMusicTrackDao().insertTracks(
                        listOf(
                            LocalMusicTrack(
                                filePath = targetFile.absolutePath,
                                title = "Grabación: ${current.fileName} ($timestamp)",
                                artist = radioStationName ?: "Podcast Radio",
                                album = "Radio Grabaciones",
                                size = targetFile.length(),
                                dateModified = System.currentTimeMillis(),
                                logoUri = radioLogoUri
                            )
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving recording db entry: ${e.message}")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy radio recording: ${e.message}")
            return false
        }
    }

    fun resolveRadioStreamUrl(url: String): String {
        return url
    }

    private fun cleanupPastCacheExceptCurrent(context: Context, currentTrack: PlaylistItem) {
        scope.launch(Dispatchers.IO) {
            try {
                val currentFile = getCachedFileForTrack(context, currentTrack)
                val cacheDir = File(context.cacheDir, "ftp_track_cache")
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    val files = cacheDir.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (currentFile == null || file.absolutePath != currentFile.absolutePath) {
                                file.delete()
                                Log.d(TAG, "Deleted past cached FTP track file: ${file.name}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up past cache files: ${e.message}")
            }
        }
    }

    private fun loadAndPlay(context: Context, track: PlaylistItem) {
        prefetchJob?.cancel()
        cleanupPastCacheExceptCurrent(context, track)
        val cachedFile = getCachedFileForTrack(context, track)
        val isCached = cachedFile != null && cachedFile.exists() && cachedFile.length() > 0
        
        val filePath = track.filePath
        val isHttp = filePath.startsWith("http://") || filePath.startsWith("https://")
        val isLocalDeviceFile = !isHttp && (
            filePath.startsWith("/storage/") || 
            filePath.startsWith("/sdcard/") || 
            filePath.startsWith("content://")
        )
        val isRemoteFtp = !isHttp && !isLocalDeviceFile && filePath.startsWith("ftp://")
        
        val needsBufferingIndicator = isHttp || (isRemoteFtp && !isCached)
        
        _isBuffering.value = needsBufferingIndicator
        _bufferingProgress.value = 0f
        _currentTrack.value = track
        _isPlaying.value = false
        stopProgressUpdates()
        savePlaybackState(context, track)

        if (track.playlistId != -2 && !isPreloadingSiblings && currentPlaylist.isNotEmpty() && currentPlaylist.size > 1 && currentIndex == currentPlaylist.size - 1) {
            try {
                Toast.makeText(context, "⚠️ Última pista de la reproducción", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }

        if (_prefetchState.value != "Desactivado") {
            _prefetchState.value = "Inactivo"
            _prefetchProgress.value = 0f
            _prefetchTrackName.value = ""
        }

        if (track.playlistId == -2) {
            val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val autoTimeshift = prefs.getBoolean("auto_timeshift_enabled", false)
            if (autoTimeshift) {
                startRadioRecording(context, track.filePath)
            } else {
                stopRadioRecording()
            }
            startRadioMetadataFetcher(track.filePath)
        } else {
            stopRadioRecording()
            stopRadioMetadataFetcher()
        }

        // Read saved individual track position if no specific pending seek position was defined (skip for streams)
        val settingsPrefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val rememberPosEnabled = settingsPrefs.getBoolean("remember_track_position_enabled", true)
        if (rememberPosEnabled && track.playlistId != -2 && pendingSeekPosition == 0L) {
            try {
                val prefs = context.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
                pendingSeekPosition = prefs.getLong("pos_${track.filePath}", 0L)
            } catch (_: Exception) {}
        } else if (track.playlistId == -2 || !rememberPosEnabled) {
            pendingSeekPosition = 0L
        }

        // Release current media session
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        releaseAudioFx()

        scope.launch {
            try {
                var resolvedPath = resolveRadioStreamUrl(track.filePath)
                var ftpConnectionId: Int? = null
                
                if (resolvedPath.startsWith("ftp://")) {
                    val parts = resolvedPath.substring(6).split("/", limit = 2)
                    ftpConnectionId = parts.getOrNull(0)?.toIntOrNull()
                    val remoteSubPath = "/" + (parts.getOrNull(1) ?: "")
                    resolvedPath = remoteSubPath
                    
                    if (ftpConnectionId != null) {
                        val db = AppDatabase.getDatabase(context)
                        val targetConn = db.ftpConnectionDao().getAllConnectionsOnce().firstOrNull { it.id == ftpConnectionId }
                        if (targetConn != null) {
                            FtpClientManager.connect(targetConn)
                        }
                    }
                }

                val isHttp = resolvedPath.startsWith("http://") || resolvedPath.startsWith("https://")
                val isLocalDeviceFile = !isHttp && (
                    resolvedPath.startsWith("/storage/") || 
                    resolvedPath.startsWith("/sdcard/") || 
                    resolvedPath.startsWith("content://")
                )

                val player = MediaPlayer().apply {
                    try {
                        setWakeMode(context.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed setWakeMode on player: ${e.message}")
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    
                    if (isHttp) {
                        try {
                            val uri = android.net.Uri.parse(resolvedPath)
                            val headers = java.util.HashMap<String, String>().apply {
                                put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                put("Connection", "keep-alive")
                                put("Accept", "*/*")
                            }
                            setDataSource(context.applicationContext, uri, headers)
                        } catch (ex: Exception) {
                            setDataSource(resolvedPath)
                        }
                        // Keep _isBuffering = true to show loading indicator during async preparation
                    } else if (isLocalDeviceFile) {
                        _isBuffering.value = false
                        if (resolvedPath.startsWith("content://")) {
                            setDataSource(context, android.net.Uri.parse(resolvedPath))
                        } else {
                            val file = File(resolvedPath)
                            if (file.exists() && file.canRead()) {
                                setDataSource(file.absolutePath)
                            } else {
                                setDataSource(resolvedPath)
                            }
                        }
                    } else {
                        val file = File(resolvedPath)
                        if (resolvedPath.startsWith("/") && file.exists()) {
                            _isBuffering.value = false
                            setDataSource(file.absolutePath)
                        } else {
                            val targetCachedFile = getCachedFileForTrack(context, track)
                            val ext = getExtension(resolvedPath)
                            val prefetchFile = File(context.cacheDir, "prefetched_playing_media.$ext")
                            var downloadSuccess = false

                            if (targetCachedFile != null && targetCachedFile.exists() && targetCachedFile.length() > 0) {
                                Log.d(TAG, "Unique Cache HIT! Playing cached track for $resolvedPath directly.")
                                downloadSuccess = true
                            } else if (prefetchedTrackPath == resolvedPath && prefetchFile.exists()) {
                                Log.d(TAG, "Prefetch Cache HIT! Moving to unique target cache file for $resolvedPath.")
                                try {
                                    if (targetCachedFile != null) {
                                        if (targetCachedFile.exists()) {
                                            targetCachedFile.delete()
                                        }
                                        prefetchFile.renameTo(targetCachedFile)
                                        downloadSuccess = true
                                    }
                                    prefetchedTrackPath = null // Reset
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error moving prefetch file, downloading on demand: ${e.message}")
                                }
                            }

                            if (downloadSuccess && targetCachedFile != null && targetCachedFile.exists()) {
                                _isBuffering.value = false
                                _isDownloadingActiveTrack.value = false
                                _prefetchProgress.value = 1f
                                _prefetchState.value = "Completado"
                                setDataSource(targetCachedFile.absolutePath)

                                // Trigger background download if ultraFastEnabled is true and cached file is partial
                                val ultraFastEnabled = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                                    .getBoolean("ultra_fast_loading_enabled", false)
                                 var actualSize = track.fileSize
                                 if (actualSize <= 0 && resolvedPath.startsWith("/")) {
                                     try {
                                         actualSize = FtpClientManager.getRemoteFileSize(resolvedPath)
                                     } catch (_: Exception) {}
                                 }
                                 if (actualSize > 0 && targetCachedFile.length() < actualSize) {
                                    Log.d(TAG, "Partial cache hit! Downloading the rest in the background...")
                                    currentDownloadJob = scope.launch(Dispatchers.IO) {
                                        try {
                                            val tempFullFile = File(context.cacheDir, "temp_full_ultra_${track.hashCode()}.$ext")
                                            if (tempFullFile.exists()) {
                                                tempFullFile.delete()
                                            }
                                            val fullSuccess = FtpClientManager.downloadFileToCache(
                                                remotePath = resolvedPath,
                                                localFile = tempFullFile,
                                                skipSizeCheck = false
                                            ) { _, _ -> }
                                            if (fullSuccess && tempFullFile.exists() && tempFullFile.length() > 0) {
                                                Log.d(TAG, "Seamlessly fully cached background file from partial-cache hit.")
                                                
                                                scope.launch(Dispatchers.Main) {
                                                    mediaPlayer?.let { player ->
                                                        try {
                                                            if (_currentTrack.value?.filePath == track.filePath) {
                                                                val currentPos = player.currentPosition
                                                                val isPlaying = player.isPlaying
                                                                
                                                                // Reset first to release file lock on targetCachedFile
                                                                player.reset()
                                                                
                                                                // Now perform rename on IO thread safely
                                                                withContext(Dispatchers.IO) {
                                                                    if (targetCachedFile.exists()) {
                                                                        targetCachedFile.delete()
                                                                    }
                                                                    tempFullFile.renameTo(targetCachedFile)
                                                                    extractAndSaveTrackMetadata(context, track.filePath, targetCachedFile)
                                                                }
                                                                
                                                                player.setDataSource(targetCachedFile.absolutePath)
                                                                player.prepare()
                                                                val newDur = try {
                                                                    player.duration.toLong()
                                                                } catch (_: Exception) {
                                                                    0L
                                                                }
                                                                if (newDur > 0L) {
                                                                    _duration.value = newDur
                                                                }
                                                                player.seekTo(currentPos)
                                                                if (isPlaying) {
                                                                    player.start()
                                                                }
                                                            } else {
                                                                // Current track has changed, just rename safely in IO
                                                                withContext(Dispatchers.IO) {
                                                                    if (targetCachedFile.exists()) {
                                                                        targetCachedFile.delete()
                                                                    }
                                                                    tempFullFile.renameTo(targetCachedFile)
                                                                }
                                                            }
                                                        } catch (ex: Exception) {
                                                            Log.e(TAG, "Error swapping live source from partial-cache hit: ${ex.message}")
                                                        }
                                                    } ?: run {
                                                        // MediaPlayer is null, safely rename in IO
                                                        withContext(Dispatchers.IO) {
                                                            if (targetCachedFile.exists()) {
                                                                targetCachedFile.delete()
                                                            }
                                                            tempFullFile.renameTo(targetCachedFile)
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (bgEx: Exception) {
                                            Log.e(TAG, "Background full download error from partial cache hit: ${bgEx.message}")
                                        }
                                    }
                                }
                            } else {
                                val ultraFastEnabled = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                                    .getBoolean("ultra_fast_loading_enabled", false)

                                if (ultraFastEnabled && targetCachedFile != null) {
                                    _isDownloadingActiveTrack.value = true
                                    _prefetchTrackName.value = track.fileName
                                    _prefetchState.value = "Descargando"
                                    _prefetchProgress.value = 0f
                                    _isBuffering.value = true
                                    _bufferingProgress.value = 0f
                                    val tempPartialFile = File(context.cacheDir, "partial_ultra_${track.hashCode()}.$ext")
                                    if (tempPartialFile.exists()) {
                                        tempPartialFile.delete()
                                    }
                                    
                                    // Download smaller partial chunk
                                    val sysPrefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                                    val bufferPref = sysPrefs.getString("ultra_fast_loading_buffer_size", "1mb") ?: "1mb"
                                    val targetBytes = when (bufferPref) {
                                        "512k" -> 512 * 1024L
                                        "1mb" -> 1024 * 1024L
                                        "3mb" -> 3 * 1024 * 1024L
                                        "5mb" -> 5 * 1024 * 1024L
                                        "10mb" -> 10 * 1024 * 1024L
                                        else -> 1024 * 1024L
                                    }
                                    val partialSuccess = FtpClientManager.downloadPartialFileToCache(resolvedPath, tempPartialFile, targetBytes) { downloaded, total ->
                                        if (total > 0) {
                                            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                            _prefetchProgress.value = progress
                                            _bufferingProgress.value = progress
                                        }
                                    }
                                    if (partialSuccess && tempPartialFile.exists() && tempPartialFile.length() > 0) {
                                        if (targetCachedFile.exists()) {
                                            targetCachedFile.delete()
                                        }
                                        tempPartialFile.renameTo(targetCachedFile)
                                        
                                        scope.launch(Dispatchers.Main) {
                                            Toast.makeText(context, "⚡ Reproducción Instantánea activa. Completando descarga en segundo plano...", Toast.LENGTH_SHORT).show()
                                        }
                                        
                                        _prefetchState.value = "Completado"
                                        _prefetchProgress.value = 1f
                                        _isDownloadingActiveTrack.value = false
                                        _isBuffering.value = false
                                        setDataSource(targetCachedFile.absolutePath)
                                        
                                        // Fire background full download
                                        currentDownloadJob = scope.launch(Dispatchers.IO) {
                                            try {
                                                val tempFullFile = File(context.cacheDir, "temp_full_ultra_${track.hashCode()}.$ext")
                                                if (tempFullFile.exists()) {
                                                    tempFullFile.delete()
                                                }
                                                val fullSuccess = FtpClientManager.downloadFileToCache(
                                                    remotePath = resolvedPath,
                                                    localFile = tempFullFile,
                                                    skipSizeCheck = false
                                                ) { downloaded, total ->
                                                    if (total > 0) {
                                                        val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                                        _bufferingProgress.value = progress
                                                    }
                                                }
                                                if (fullSuccess && tempFullFile.exists() && tempFullFile.length() > 0) {
                                                    
                                                    scope.launch(Dispatchers.Main) {
                                                        Toast.makeText(context, "✅ Sincronizado por completo en segundo plano.", Toast.LENGTH_SHORT).show()
                                                        
                                                        // Hot-swap media content seamlessly if this track is still playing and active
                                                        mediaPlayer?.let { player ->
                                                            try {
                                                                if (_currentTrack.value?.filePath == track.filePath) {
                                                                    val currentPos = player.currentPosition
                                                                    val isPlaying = player.isPlaying
                                                                    
                                                                    // Reset first to release file lock on targetCachedFile
                                                                    player.reset()
                                                                    
                                                                    // Now perform rename on IO thread safely
                                                                    withContext(Dispatchers.IO) {
                                                                        if (targetCachedFile.exists()) {
                                                                            targetCachedFile.delete()
                                                                        }
                                                                        tempFullFile.renameTo(targetCachedFile)
                                                                    }
                                                                    
                                                                    player.setDataSource(targetCachedFile.absolutePath)
                                                                    player.prepare()
                                                                    val newDur = try {
                                                                        player.duration.toLong()
                                                                    } catch (_: Exception) {
                                                                        0L
                                                                    }
                                                                    if (newDur > 0L) {
                                                                        _duration.value = newDur
                                                                    }
                                                                    player.seekTo(currentPos)
                                                                    if (isPlaying) {
                                                                        player.start()
                                                                    }
                                                                } else {
                                                                    // Current track has changed, just rename safely in IO
                                                                    withContext(Dispatchers.IO) {
                                                                        if (targetCachedFile.exists()) {
                                                                            targetCachedFile.delete()
                                                                        }
                                                                        tempFullFile.renameTo(targetCachedFile)
                                                                    }
                                                                }
                                                            } catch (ex: Exception) {
                                                                Log.e(TAG, "Error swapping live source: ${ex.message}")
                                                            }
                                                        } ?: run {
                                                            // MediaPlayer is null, safely rename in IO
                                                            withContext(Dispatchers.IO) {
                                                                if (targetCachedFile.exists()) {
                                                                    targetCachedFile.delete()
                                                                }
                                                                tempFullFile.renameTo(targetCachedFile)
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (bgEx: Exception) {
                                                Log.e(TAG, "Background full download error: ${bgEx.message}")
                                            }
                                        }
                                    } else {
                                        // Fallback to full download
                                        _isDownloadingActiveTrack.value = true
                                        _prefetchTrackName.value = track.fileName
                                        _prefetchState.value = "Descargando"
                                        _prefetchProgress.value = 0f
                                        val success = downloadTrackToCache(context, track) { progress ->
                                            _bufferingProgress.value = progress
                                            _prefetchProgress.value = progress
                                        }
                                        _isDownloadingActiveTrack.value = false
                                        _isBuffering.value = false
                                        if (success && targetCachedFile.exists()) {
                                            _prefetchState.value = "Completado"
                                            _prefetchProgress.value = 1f
                                            setDataSource(targetCachedFile.absolutePath)
                                        } else {
                                            _prefetchState.value = "Error"
                                            throw Exception("Failed to play track from FTP on-demand download")
                                        }
                                    }
                                } else {
                                    _isDownloadingActiveTrack.value = true
                                    _prefetchTrackName.value = track.fileName
                                    _prefetchState.value = "Descargando"
                                    _prefetchProgress.value = 0f
                                    _isBuffering.value = true
                                    _bufferingProgress.value = 0f
                                    val success = downloadTrackToCache(context, track) { progress ->
                                        _bufferingProgress.value = progress
                                        _prefetchProgress.value = progress
                                    }
                                    _isDownloadingActiveTrack.value = false
                                    _isBuffering.value = false
                                    if (success && targetCachedFile != null && targetCachedFile.exists()) {
                                        _prefetchState.value = "Completado"
                                        _prefetchProgress.value = 1f
                                        setDataSource(targetCachedFile.absolutePath)
                                    } else {
                                        _prefetchState.value = "Error"
                                        throw Exception("Failed to play track from FTP on-demand download")
                                    }
                                }
                            }
                        }
                    }

                    setOnPreparedListener(this@AudioPlayerManager)
                    setOnCompletionListener(this@AudioPlayerManager)
                    setOnErrorListener(this@AudioPlayerManager)
                    prepareAsync()
                }
                mediaPlayer = player

            } catch (e: Exception) {
                _isBuffering.value = false
                _isPlaying.value = false
                Log.e(TAG, "Error playing track: ${e.message}", e)
            }
        }
    }

    fun ensureRadioReconnected(context: Context) {
        val track = _currentTrack.value ?: return
        if (track.playlistId != -2) return
        
        Log.i(TAG, "ensureRadioReconnected - Live mode: ${_isRadioLiveMode.value}")
        if (_isRadioLiveMode.value) {
            reconnectRadioStream(context, playImmediately = true)
        } else {
            val resumePosMs = _radioPlayPositionSec.value * 1000L
            val tempFile = File(context.cacheDir, "timeshift_temp.mp3")
            val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val autoTimeshift = prefs.getBoolean("auto_timeshift_enabled", false)
            val timeshiftOnPause = prefs.getBoolean("timeshift_on_pause_enabled", true)
            if (autoTimeshift || timeshiftOnPause || radioRecordingJob == null) {
                startRadioRecording(context, track.filePath, keepExistingFile = true)
            }
            if (tempFile.exists() && tempFile.length() > 0) {
                playRadioTimeShiftAt(resumePosMs)
            } else {
                goLiveRadio()
            }
        }
    }

    fun reconnectRadioStream(context: Context, playImmediately: Boolean) {
        val track = _currentTrack.value ?: return
        if (track.playlistId != -2) return

        Log.d(TAG, "reconnectRadioStream - playImmediately: $playImmediately, isRadioLiveMode: ${_isRadioLiveMode.value}")
        
        initialize(context)
        
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val autoTimeshift = prefs.getBoolean("auto_timeshift_enabled", false)
        val reconnectPaused = prefs.getBoolean("radio_reconnect_paused_enabled", false)
        val timeshiftOnPause = prefs.getBoolean("timeshift_on_pause_enabled", true)

        if (!_isRadioLiveMode.value) {
            Log.d(TAG, "reconnectRadioStream - Running in Timeshift (non-live). Just restarting recording thread.")
            if (autoTimeshift || reconnectPaused || timeshiftOnPause) {
                startRadioRecording(context, track.filePath, keepExistingFile = true)
            }
            return
        }

        userPressedPause = !playImmediately
        _playbackJumpToPlayerTrigger.tryEmit(Unit)

        // Reset/recreate the media player
        mediaPlayer?.let {
            try { if (it.isPlaying) it.stop() } catch(_: Exception) {}
            it.release()
        }
        mediaPlayer = null
        releaseAudioFx()
        
        if (autoTimeshift || reconnectPaused || timeshiftOnPause) {
            // Keep the existing file and job running!
            startRadioRecording(context, track.filePath, keepExistingFile = true)
        }
        
        _isBuffering.value = true
        _isPlaying.value = playImmediately
        _isRadioReconnecting.value = true
        
        scope.launch(Dispatchers.Main) {
            try {
                val resolvedPath = resolveRadioStreamUrl(track.filePath)
                val player = MediaPlayer().apply {
                    try {
                        setWakeMode(context.applicationContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed setWakeMode on player: ${e.message}")
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    
                    try {
                        val uri = android.net.Uri.parse(resolvedPath)
                        val headers = java.util.HashMap<String, String>().apply {
                            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            put("Connection", "keep-alive")
                            put("Accept", "*/*")
                        }
                        setDataSource(context.applicationContext, uri, headers)
                    } catch (ex: Exception) {
                        setDataSource(resolvedPath)
                    }
                    
                    setOnPreparedListener { mp ->
                        _isRadioReconnecting.value = false
                        _isBuffering.value = false
                        setupAudioFx(mp.audioSessionId)
                        if (playImmediately) {
                            try {
                                mp.start()
                                _isPlaying.value = true
                                startProgressUpdates()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error starting player after prepared: ${e.message}")
                            }
                        } else {
                            _isPlaying.value = false
                            stopProgressUpdates()
                        }
                    }
                    setOnCompletionListener(this@AudioPlayerManager)
                    setOnErrorListener(this@AudioPlayerManager)
                    prepareAsync()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                _isRadioReconnecting.value = false
                _isBuffering.value = false
                _isPlaying.value = false
                Log.e(TAG, "Error reconnecting radio stream: ${e.message}")
            }
        }
    }

    fun pausePlayback(byUser: Boolean = true) {
        if (byUser) {
            userPressedPause = true
            _isRadioReconnecting.value = false
        }
        val current = _currentTrack.value
        if (current != null && current.playlistId == -2) {
            radioPauseTimeMs = System.currentTimeMillis()
            
            if (radioRecordingJob == null) {
                appContext?.let { ctx ->
                    val prefs = ctx.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                    val timeshiftOnPause = prefs.getBoolean("timeshift_on_pause_enabled", true)
                    if (timeshiftOnPause) {
                        startRadioRecording(ctx, current.filePath)
                    }
                }
            }

            mediaPlayer?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.pause()
                    }
                } catch (_: Exception) {}
            }
            _isPlaying.value = false
            stopProgressUpdates()
            
            val prefs = appContext?.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val autoTimeshift = prefs?.getBoolean("auto_timeshift_enabled", false) ?: false
            val timeshiftOnPause = prefs?.getBoolean("timeshift_on_pause_enabled", true) ?: true
            val hasBuffer = autoTimeshift || timeshiftOnPause || radioRecordingJob != null

            if (hasBuffer && _isRadioLiveMode.value) {
                _isRadioLiveMode.value = false
                val elapsed = if (radioStartTimeMs > 0L) {
                    ((radioPauseTimeMs - radioStartTimeMs) / 1000).toInt()
                } else {
                    0
                }
                _radioPlayPositionSec.value = elapsed
            } else if (!hasBuffer) {
                _isRadioLiveMode.value = true
                _radioPlayPositionSec.value = 0
                _radioElapsedTimeSec.value = 0
            } else {
                mediaPlayer?.let { player ->
                    try {
                        _radioPlayPositionSec.value = player.currentPosition / 1000
                    } catch (_: Exception) {}
                }
            }
            return
        }

        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
                stopProgressUpdates()

                // Save dynamic immediate position
                if (current != null && current.playlistId != -2) {
                    try {
                        val pos = player.currentPosition.toLong()
                        _currentPosition.value = pos
                        appContext?.let { ctx ->
                            val prefs = ctx.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
                            val edit = prefs.edit()
                            edit.putLong("track_position", pos)
                            edit.putLong("pos_${current.filePath}", pos)
                            edit.apply()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun resumePlayback() {
        userPressedPause = false
        val current = _currentTrack.value
        if (current != null && current.playlistId == -2) {
            if (requestAudioFocus()) {
                val resumePosMs = _radioPlayPositionSec.value * 1000L
                val tempFile = File(appContext!!.cacheDir, "timeshift_temp.mp3")
                if (tempFile.exists() && tempFile.length() > 0 && radioRecordingJob != null) {
                    playRadioTimeShiftAt(resumePosMs)
                } else {
                    mediaPlayer?.let { player ->
                        if (!player.isPlaying) {
                            player.start()
                            _isPlaying.value = true
                            startProgressUpdates()
                        }
                    } ?: run {
                        appContext?.let { ctx -> playTrack(ctx, current) }
                    }
                }
            }
            return
        }

        if (requestAudioFocus()) {
            mediaPlayer?.let { player ->
                if (!player.isPlaying) {
                    player.start()
                    _isPlaying.value = true
                    startProgressUpdates()
                }
            }
        }
    }

    override fun onPrepared(mp: MediaPlayer) {
        _isRadioReconnecting.value = false
        _isBuffering.value = false
        setupAudioFx(mp.audioSessionId)
        
        if (_prefetchState.value != "Desactivado") {
            _prefetchState.value = "Inactivo"
            _prefetchProgress.value = 0f
            _prefetchTrackName.value = ""
        }
        
        val streamDuration = try {
            mp.duration.toLong()
        } catch (_: Exception) {
            -1L
        }
        
        val isLiveStream = _currentTrack.value?.playlistId == -2 || streamDuration <= 0L

        if (requestAudioFocus()) {
            if (!isLiveStream && pendingSeekPosition > 0L) {
                try {
                    mp.seekTo(pendingSeekPosition.toInt())
                } catch (_: Exception) {}
                _currentPosition.value = pendingSeekPosition
            }
            pendingSeekPosition = 0L
            
            try {
                mp.start()
                _isPlaying.value = true
                _duration.value = if (streamDuration > 0) streamDuration else 0L
                startProgressUpdates()
            } catch (e: Exception) {
                _isPlaying.value = false
                Log.e(TAG, "Error starting mediaPlayer: ${e.message}")
            }
        } else {
            if (!isLiveStream && pendingSeekPosition > 0L) {
                try {
                    mp.seekTo(pendingSeekPosition.toInt())
                } catch (_: Exception) {}
                _currentPosition.value = pendingSeekPosition
            }
            pendingSeekPosition = 0L
            _isPlaying.value = false
            _duration.value = if (streamDuration > 0) streamDuration else 0L
        }
    }

    override fun onCompletion(mp: MediaPlayer) {
        val current = _currentTrack.value
        if (current != null && current.playlistId == -2) {
            if (userPressedPause) {
                Log.i(TAG, "onCompletion: userPressedPause is true, ignoring radio auto-reconnect.")
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressUpdates()
                return
            }
            if (_isRadioLiveMode.value) {
                appContext?.let { context ->
                    _isBuffering.value = true
                    _isPlaying.value = true
                    scope.launch {
                        kotlinx.coroutines.delay(1000)
                        Log.i(TAG, "Radio live stream onCompletion called (EOF on connection drop). Auto-reconnecting...")
                        loadAndPlay(context, current)
                    }
                }
            } else {
                val currentPlayPosSec = _radioPlayPositionSec.value
                val totalElapsedSec = _radioElapsedTimeSec.value
                if (currentPlayPosSec < totalElapsedSec - 12) {
                    Log.i(TAG, "Timeshift player hit EOF of prepared size, but buffer has more content. Re-preparing timeshift player at $currentPlayPosSec sec...")
                    playRadioTimeShiftAt(currentPlayPosSec * 1000L)
                } else {
                    Log.i(TAG, "Timeshift caught up or reached end. Going LIVE...")
                    goLiveRadio()
                }
            }
            return
        }

        _isPlaying.value = false
        stopProgressUpdates()

        // Clear saved position for this finished track
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
                val edit = prefs.edit()
                edit.putLong("track_position", 0L)
                if (current != null) {
                    edit.putLong("pos_${current.filePath}", 0L)
                }
                edit.apply()
            } catch (_: Exception) {}
        }

        // Play next or loop current automatically!
        appContext?.let { context ->
            if (_repeatMode.value == PlaybackRepeatMode.ONE) {
                _currentTrack.value?.let { loadAndPlay(context, it) }
            } else {
                playNext(context)
            }
        }
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        Log.e(TAG, "MediaPlayer error: what = $what, extra = $extra")

        val current = _currentTrack.value
        if (current != null && current.filePath.startsWith("ftp://") && current.playlistId != -2) {
            if (userPressedPause) {
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressUpdates()
                return true
            }
            val interceptedPos = try { mp.currentPosition.toLong() } catch (_: Exception) { _currentPosition.value }
            Log.i(TAG, "FTP stream error detected. Auto-reconnecting and resuming from position: $interceptedPos ms")
            
            appContext?.let { ctx ->
                _isBuffering.value = true
                _isPlaying.value = true
                scope.launch {
                    kotlinx.coroutines.delay(2000)
                    if (_currentTrack.value?.filePath == current.filePath) {
                        playTrack(ctx, current, initialPositionMs = interceptedPos)
                    }
                }
                return true
            }
        }

        if (current != null && current.playlistId == -2) {
            if (userPressedPause) {
                Log.i(TAG, "onError: userPressedPause is true, ignoring radio auto-reconnect.")
                _isPlaying.value = false
                _isBuffering.value = false
                stopProgressUpdates()
                return true
            }
            appContext?.let { ctx ->
                val prefs = ctx.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
                val autoReconnectLive = prefs.getBoolean("radio_auto_reconnect_live", true)
                val reconnectPaused = prefs.getBoolean("radio_reconnect_paused_enabled", false)

                if (autoReconnectLive || reconnectPaused) {
                    _isBuffering.value = true
                    _isPlaying.value = true
                    scope.launch {
                        kotlinx.coroutines.delay(2000)
                        Log.i(TAG, "Attempting auto-reconnect for radio stream on error (reconnectPaused: $reconnectPaused)...")
                        ensureRadioReconnected(ctx)
                    }
                    return true
                }
            }
        }

        _isPlaying.value = false
        stopProgressUpdates()
        _isBuffering.value = false
        return true
    }

    fun startSleepTimer(minutes: Int, context: Context) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerRemainingSec.value = 0L
            _sleepTimerOriginalMin.value = 0
            return
        }
        _sleepTimerOriginalMin.value = minutes
        val totalSeconds = minutes * 60L
        _sleepTimerRemainingSec.value = totalSeconds
        
        sleepTimerJob = scope.launch(Dispatchers.Main) {
            var remaining = totalSeconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _sleepTimerRemainingSec.value = remaining
            }
            // Time is up!
            pausePlayback()
            Toast.makeText(context, "⏲️ Temporizador de apagado completado. Reproducción pausada.", Toast.LENGTH_LONG).show()
            _sleepTimerRemainingSec.value = 0L
            _sleepTimerOriginalMin.value = 0
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingSec.value = 0L
        _sleepTimerOriginalMin.value = 0
    }

    fun togglePlayPause(context: Context? = null) {
        val track = _currentTrack.value
        if (_isPlaying.value) {
            val ctx = context ?: appContext
            val prefs = ctx?.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val twoTapsEnabled = prefs?.getBoolean("two_taps_to_pause_enabled", false) ?: false
            if (twoTapsEnabled) {
                val now = System.currentTimeMillis()
                if (now - lastPauseTapTime < 1000) {
                    pausePlayback()
                    lastPauseTapTime = 0L
                } else {
                    lastPauseTapTime = now
                    try {
                        Toast.makeText(ctx, "Pulsa otra vez para pausar ⏸️", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            } else {
                pausePlayback()
            }
        } else {
            val player = mediaPlayer
            if (player == null || (track != null && track.playlistId == -2 && radioRecordingJob == null)) {
                val ctx = context ?: appContext
                if (track != null && ctx != null) {
                    playTrack(ctx, track)
                }
            } else {
                resumePlayback()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { player ->
            player.seekTo(positionMs.toInt())
            _currentPosition.value = positionMs
            updateMediaSessionState(_isPlaying.value, _currentTrack.value)
        }
    }

    fun skipSeconds(seconds: Int) {
        val track = _currentTrack.value
        if (track != null && track.playlistId == -2) {
            val currentPosSec = _radioPlayPositionSec.value
            val newPosSec = currentPosSec + seconds
            val maxLimitSec = if (_radioElapsedTimeSec.value > 0) _radioElapsedTimeSec.value else 0
            val clampedPosSec = newPosSec.coerceIn(0, maxLimitSec)
            seekRadioTimeShift(clampedPosSec)
        } else {
            mediaPlayer?.let { player ->
                try {
                    val duration = player.duration
                    if (duration > 0) {
                        val currentPos = player.currentPosition
                        val newPos = currentPos + (seconds * 1000)
                        val clampedPos = newPos.coerceIn(0, duration)
                        seekTo(clampedPos.toLong())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error seeking: ${e.message}")
                }
            }
        }
    }

    fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value
    }

    fun toggleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            PlaybackRepeatMode.NONE -> PlaybackRepeatMode.ALL
            PlaybackRepeatMode.ALL -> PlaybackRepeatMode.ONE
            PlaybackRepeatMode.ONE -> PlaybackRepeatMode.NONE
        }
    }

    private var transitionDownloadJob: kotlinx.coroutines.Job? = null
    private var prefetchJob: kotlinx.coroutines.Job? = null

    private fun getExtension(path: String): String {
        val lastDot = path.lastIndexOf('.')
        if (lastDot != -1) {
            val ext = path.substring(lastDot + 1).lowercase()
            if (ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac")) {
                return ext
            }
        }
        return "mp3"
    }

    fun getCachedFileForTrack(context: Context, track: PlaylistItem): File? {
        if (track.playlistId == -2) return null // Radio streams cannot be cached this way
        val filePath = track.filePath
        
        // Only cache remote FTP files
        var resolvedPath = filePath
        if (resolvedPath.startsWith("ftp://")) {
            val parts = resolvedPath.substring(6).split("/", limit = 2)
            resolvedPath = "/" + (parts.getOrNull(1) ?: "")
        }
        
        val isHttp = resolvedPath.startsWith("http://") || resolvedPath.startsWith("https://")
        val isLocalDeviceFile = !isHttp && (
            resolvedPath.startsWith("/storage/") || 
            resolvedPath.startsWith("/sdcard/") || 
            resolvedPath.startsWith("content://")
        )
        
        if (isHttp || isLocalDeviceFile) return null
        
        val cacheDir = File(context.cacheDir, "ftp_track_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val hash = resolvedPath.hashCode().toString()
        val ext = getExtension(resolvedPath)
        return File(cacheDir, "track_$hash.$ext")
    }

    private suspend fun downloadTrackToCache(context: Context, track: PlaylistItem, onProgress: (Float) -> Unit): Boolean {
        var resolvedPath = track.filePath
        var ftpConnectionId: Int? = null
        
        if (resolvedPath.startsWith("ftp://")) {
            val parts = resolvedPath.substring(6).split("/", limit = 2)
            ftpConnectionId = parts.getOrNull(0)?.toIntOrNull()
            resolvedPath = "/" + (parts.getOrNull(1) ?: "")
            
            if (ftpConnectionId != null) {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val targetConn = db.ftpConnectionDao().getAllConnectionsOnce().firstOrNull { it.id == ftpConnectionId }
                    if (targetConn != null) {
                        FtpClientManager.connect(targetConn)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Background connect error: ${e.message}")
                }
            }
        }
        
        val cachedFile = getCachedFileForTrack(context, track) ?: return false
        val ext = getExtension(track.filePath)
        val tempDownloadFile = File(context.cacheDir, "temp_bg_download_${track.filePath.hashCode()}.$ext")
        if (tempDownloadFile.exists()) {
            tempDownloadFile.delete()
        }
        
        var success = FtpClientManager.downloadFileToCache(
            remotePath = resolvedPath,
            localFile = tempDownloadFile,
            progressListener = { downloaded, total ->
                if (total > 0) {
                    val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }
        )
        
        if (!success) {
            // Retry auto-reconnect
            val reconnected = FtpClientManager.autoReconnect(context)
            if (reconnected) {
                success = FtpClientManager.downloadFileToCache(
                    remotePath = resolvedPath,
                    localFile = tempDownloadFile,
                    progressListener = { downloaded, total ->
                        if (total > 0) {
                            val progress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            onProgress(progress)
                        }
                    }
                )
            }
        }
        
        if (success && tempDownloadFile.exists()) {
            if (cachedFile.exists()) {
                cachedFile.delete()
            }
            tempDownloadFile.renameTo(cachedFile)
            extractAndSaveTrackMetadata(context, track.filePath, cachedFile)
            return true
        } else {
            if (tempDownloadFile.exists()) {
                tempDownloadFile.delete()
            }
            return false
        }
    }

    fun playNext(context: Context?) {
        if (_isRecordingProtectedMode.value) {
            context?.let { ctx ->
                try {
                    Toast.makeText(ctx, "⚠️ Modo Seguro Activo", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            return
        }
        if (currentPlaylist.isEmpty()) return
        val nextIndex = if (_isShuffleEnabled.value) {
            val count = currentPlaylist.size
            if (count > 1) {
                var rand = (0 until count).random()
                while (rand == currentIndex) {
                    rand = (0 until count).random()
                }
                rand
            } else 0
        } else {
            (currentIndex + 1) % currentPlaylist.size
        }

        if (_repeatMode.value == PlaybackRepeatMode.NONE && nextIndex == 0 && !_isShuffleEnabled.value) {
            // Stop playback at end of playlist
            _isPlaying.value = false
            return
        }

        val nextTrack = currentPlaylist[nextIndex]
        val ctx = context ?: appContext ?: return
        
        transitionDownloadJob?.cancel()
        prefetchJob?.cancel()
        
        val cachedFile = getCachedFileForTrack(ctx, nextTrack)
        val isCached = cachedFile != null && cachedFile.exists() && cachedFile.length() > 0
        val isCurrentPlaying = _isPlaying.value && mediaPlayer != null
        
        val sysPrefs = ctx.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val ultraFastEnabled = sysPrefs.getBoolean("ultra_fast_loading_enabled", false)
        
        if (isCurrentPlaying && !isCached && nextTrack.playlistId != -2 && nextTrack.filePath.startsWith("ftp://") && !ultraFastEnabled) {
            _isDownloadingActiveTrack.value = true
            _prefetchTrackName.value = nextTrack.fileName
            _prefetchState.value = "Descargando"
            _prefetchProgress.value = 0f
            
            transitionDownloadJob = scope.launch(Dispatchers.Main) {
                try {
                    val success = downloadTrackToCache(ctx, nextTrack) { progress ->
                        _prefetchProgress.value = progress
                        _bufferingProgress.value = progress
                    }
                    if (success) {
                        _prefetchState.value = "Completado"
                        currentIndex = nextIndex
                        loadAndPlay(ctx, nextTrack)
                    } else {
                        _prefetchState.value = "Error"
                        currentIndex = nextIndex
                        loadAndPlay(ctx, nextTrack)
                    }
                } finally {
                    _isDownloadingActiveTrack.value = false
                }
            }
        } else {
            _prefetchState.value = "Inactivo"
            currentIndex = nextIndex
            loadAndPlay(ctx, nextTrack)
        }
    }

    fun playPrevious(context: Context?) {
        if (_isRecordingProtectedMode.value) {
            context?.let { ctx ->
                try {
                    Toast.makeText(ctx, "⚠️ Modo Seguro Activo", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            return
        }
        if (currentPlaylist.isEmpty()) return
        val prevIndex = if (_isShuffleEnabled.value) {
            val count = currentPlaylist.size
            if (count > 1) {
                var rand = (0 until count).random()
                while (rand == currentIndex) {
                    rand = (0 until count).random()
                }
                rand
            } else 0
        } else {
            var idx = currentIndex - 1
            if (idx < 0) idx = currentPlaylist.size - 1
            idx
        }

        val prevTrack = currentPlaylist[prevIndex]
        val ctx = context ?: appContext ?: return
        
        transitionDownloadJob?.cancel()
        prefetchJob?.cancel()
        
        val cachedFile = getCachedFileForTrack(ctx, prevTrack)
        val isCached = cachedFile != null && cachedFile.exists() && cachedFile.length() > 0
        val isCurrentPlaying = _isPlaying.value && mediaPlayer != null
        
        val sysPrefs = ctx.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val ultraFastEnabled = sysPrefs.getBoolean("ultra_fast_loading_enabled", false)
        
        if (isCurrentPlaying && !isCached && prevTrack.playlistId != -2 && prevTrack.filePath.startsWith("ftp://") && !ultraFastEnabled) {
            _isDownloadingActiveTrack.value = true
            _prefetchTrackName.value = prevTrack.fileName
            _prefetchState.value = "Descargando"
            _prefetchProgress.value = 0f
            
            transitionDownloadJob = scope.launch(Dispatchers.Main) {
                try {
                    val success = downloadTrackToCache(ctx, prevTrack) { progress ->
                        _prefetchProgress.value = progress
                        _bufferingProgress.value = progress
                    }
                    if (success) {
                        _prefetchState.value = "Completado"
                        currentIndex = prevIndex
                        loadAndPlay(ctx, prevTrack)
                    } else {
                        _prefetchState.value = "Error"
                        currentIndex = prevIndex
                        loadAndPlay(ctx, prevTrack)
                    }
                } finally {
                    _isDownloadingActiveTrack.value = false
                }
            }
        } else {
            _prefetchState.value = "Inactivo"
            currentIndex = prevIndex
            loadAndPlay(ctx, prevTrack)
        }
    }

    fun playTrackAtIndex(context: Context, index: Int) {
        if (_isRecordingProtectedMode.value) {
            try {
                Toast.makeText(context, "⚠️ Modo Seguro Activo", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        if (index < 0 || index >= currentPlaylist.size) return
        val track = currentPlaylist[index]
        
        transitionDownloadJob?.cancel()
        prefetchJob?.cancel()
        
        val cachedFile = getCachedFileForTrack(context, track)
        val isCached = cachedFile != null && cachedFile.exists() && cachedFile.length() > 0
        val isCurrentPlaying = _isPlaying.value && mediaPlayer != null
        
        if (isCurrentPlaying && !isCached && track.playlistId != -2 && track.filePath.startsWith("ftp://")) {
            _prefetchTrackName.value = track.fileName
            _prefetchState.value = "Descargando"
            _prefetchProgress.value = 0f
            
            transitionDownloadJob = scope.launch(Dispatchers.Main) {
                val success = downloadTrackToCache(context, track) { progress ->
                    _prefetchProgress.value = progress
                }
                if (success) {
                    _prefetchState.value = "Completado"
                    currentIndex = index
                    loadAndPlay(context, track)
                } else {
                    _prefetchState.value = "Error"
                    currentIndex = index
                    loadAndPlay(context, track)
                }
            }
        } else {
            _prefetchState.value = "Inactivo"
            currentIndex = index
            loadAndPlay(context, track)
        }
    }

    private fun startProgressUpdates() {
        progressJob = scope.launch {
            while (true) {
                val current = _currentTrack.value
                val isRadio = current != null && current.playlistId == -2
                if (isRadio) {
                    if (_isRadioLiveMode.value) {
                        _radioPlayPositionSec.value = _radioElapsedTimeSec.value
                    } else {
                        mediaPlayer?.let { player ->
                            try {
                                if (player.isPlaying) {
                                    val posSec = player.currentPosition / 1000
                                    _radioPlayPositionSec.value = posSec
                                    
                                    // Catch up with live stream buffer automatically!
                                    if (posSec >= _radioElapsedTimeSec.value - 10) {
                                        Log.i(TAG, "Caught up with live stream buffer in Timeshift. Going LIVE...")
                                        goLiveRadio()
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                mediaPlayer?.let { player ->
                    try {
                        val actuallyPlaying = player.isPlaying
                        if (actuallyPlaying) {
                            val pos = player.currentPosition.toLong()
                            _currentPosition.value = pos
                            updateWidget()
                            if (!isRadio) {
                                appContext?.let { ctx ->
                                    try {
                                        val prefs = ctx.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
                                        val edit = prefs.edit()
                                        edit.putLong("track_position", pos)
                                        if (current != null) {
                                            edit.putLong("pos_${current.filePath}", pos)
                                        }
                                        edit.apply()
                                    } catch (_: Exception) {}
                                }

                                // Prefetch next track if < 40 seconds left (40000 ms) and it is FTP remote
                                val dur = _duration.value
                                if (dur > 40000 && pos > (dur - 40000) && _prefetchState.value == "Inactivo") {
                                    checkAndPrefetchNextTrack()
                                }
                            }
                        } else if (_isPlaying.value && !_isBuffering.value) {
                            // Inconsistency detected: state is playing but player is paused/stuck. Setting _isPlaying = false.
                            if (!isRadio) {
                                _isPlaying.value = false
                            } else {
                                RadioLogger.log("Corrección de inconsistencia bloqueada: El reproductor de radio retornó isPlaying=false temporalmente, pero se ignoró la pausa forzada por estar en radio/timeshift.")
                            }
                        }
                    } catch (e: Exception) {
                        if (_isPlaying.value && !_isBuffering.value && !isRadio) {
                            _isPlaying.value = false
                        }
                    }
                }

                // Global correction if there is no player but _isPlaying is still true when not buffering
                if (mediaPlayer == null && !_isBuffering.value && _isPlaying.value) {
                    _isPlaying.value = false
                }

                delay(1000)
            }
        }
    }

    private var prefetchedTrackPath: String? = null

    private fun checkAndPrefetchNextTrack() {
        val context = appContext
        if (context != null) {
            val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("ftp_prefetch_enabled", true)
            if (!isEnabled) {
                _prefetchState.value = "Desactivado"
                _prefetchProgress.value = 0f
                _prefetchTrackName.value = ""
                return
            }
        }

        if (currentPlaylist.isEmpty()) return
        
        // Find adjacent tracks
        val count = currentPlaylist.size
        val nextIndex = (currentIndex + 1) % count
        var prevIndex = currentIndex - 1
        if (prevIndex < 0) prevIndex = count - 1
        
        val nextTrack = currentPlaylist.getOrNull(nextIndex)
        val prevTrack = currentPlaylist.getOrNull(prevIndex)
        
        val appCtx = appContext ?: return
        
        prefetchJob?.cancel()
        currentDownloadJob?.cancel()
        currentDownloadJob = null
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                // 1. Process next track
                if (nextTrack != null && nextTrack.playlistId != -2 && nextTrack.filePath.startsWith("ftp://")) {
                    val nextCachedFile = getCachedFileForTrack(appCtx, nextTrack)
                    if (nextCachedFile != null && !nextCachedFile.exists()) {
                        Log.d(TAG, "Prefetch: Starting connection check for next track ${nextTrack.fileName}")
                        _prefetchTrackName.value = nextTrack.fileName
                        _prefetchState.value = "Conectando"
                        _prefetchProgress.value = 0f
                        
                        // Perform connection test on FTP
                        var isConnected = false
                        try {
                            isConnected = FtpClientManager.ensureConnected()
                        } catch (e: Exception) {
                            Log.e(TAG, "Prefetch test connection exception: ${e.message}")
                        }
                        
                        if (!isConnected) {
                            Log.d(TAG, "Prefetch: FTP Not connected. Attempting auto reconnection...")
                            _prefetchState.value = "Reconectando"
                            try {
                                isConnected = FtpClientManager.autoReconnect(appCtx)
                            } catch (e: Exception) {
                                Log.e(TAG, "Prefetch auto reconnect exception: ${e.message}")
                            }
                        }
                        
                        if (isConnected) {
                            Log.d(TAG, "Prefetch: FTP connection verified successfully. Waiting for 30s limit...")
                            _prefetchState.value = "Conectado"
                            delay(1000)
                            _prefetchState.value = "Esperando carga"
                            
                            // Wait for 30 seconds limit
                            while (prefetchJob?.isActive == true) {
                                val currentPos = mediaPlayer?.let { 
                                    try {
                                        if (it.isPlaying) it.currentPosition else -1 
                                    } catch (_: Exception) { -1 } 
                                } ?: -1
                                if (currentPos == -1) {
                                    // MediaPlayer not active or not playing, wait briefly
                                    delay(500)
                                    continue
                                }
                                val remainingMs = _duration.value - currentPos
                                if (remainingMs <= 30000) {
                                    break
                                }
                                delay(500)
                            }
                            
                            Log.d(TAG, "Prefetch: Reached 30 seconds limit. Initiating download...")
                            _prefetchState.value = "Descargando"
                            
                            val success = downloadTrackToCache(appCtx, nextTrack) { progress ->
                                _prefetchProgress.value = progress
                            }
                            if (success) {
                                _prefetchState.value = "Completado"
                                _prefetchProgress.value = 1f
                                Log.d(TAG, "Prefetch Adjacent (Full): Finished preloading next track.")
                            } else {
                                _prefetchState.value = "Error"
                            }
                        } else {
                            Log.e(TAG, "Prefetch: Failed to connect to FTP.")
                            _prefetchState.value = "Error"
                        }
                    } else if (nextCachedFile != null && nextCachedFile.exists()) {
                        _prefetchTrackName.value = nextTrack.fileName
                        _prefetchState.value = "Completado"
                        _prefetchProgress.value = 1f
                    }
                }
                
                // 2. Process previous track (low-profile preloading)
                if (prevTrack != null && prevTrack.playlistId != -2 && prevTrack.filePath.startsWith("ftp://")) {
                    val prevCachedFile = getCachedFileForTrack(appCtx, prevTrack)
                    if (prevCachedFile != null && !prevCachedFile.exists()) {
                        Log.d(TAG, "Prefetch Adjacent: Preloading previous track ${prevTrack.fileName}")
                        downloadTrackToCache(appCtx, prevTrack) { _ -> }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prefetch adjacent tracks error: ${e.message}")
                _prefetchState.value = "Error"
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // Audio FX Management (Equalizer & Boost)
    private fun setupAudioFx(audioSessionId: Int) {
        // 1. Setup LoudnessEnhancer (Volume Booster) with separate try-catch
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(_boostLevel.value.toInt())
                enabled = true
            }
            Log.d(TAG, "LoudnessEnhancer created successfully for audioSessionId $audioSessionId with gain ${_boostLevel.value}")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring LoudnessEnhancer: ${e.message}")
        }

        // 2. Setup Equalizer with separate try-catch
        try {
            val eq = Equalizer(0, audioSessionId).apply {
                enabled = _isEqEnabled.value
            }
            equalizer = eq

            val bandsCount = eq.numberOfBands
            val range = eq.bandLevelRange // short[2] min max in mB
            val minMb = range[0].toInt()
            val maxMb = range[1].toInt()

            val bandsList = mutableListOf<EqualizerBand>()
            for (i in 0 until bandsCount) {
                val centerFreqHz = eq.getCenterFreq(i.toShort()) / 1000 // milliHz to Hz
                bandsList.add(
                    EqualizerBand(
                        bandId = i,
                        centerFrequencyHz = centerFreqHz,
                        currentLevelMb = eq.getBandLevel(i.toShort()).toInt(),
                        minLevelMb = minMb,
                        maxLevelMb = maxMb
                    )
                )
            }
            _equalizerBands.value = bandsList
            Log.d(TAG, "Equalizer configured successfully for audioSessionId $audioSessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Equalizer: ${e.message}")
        }
    }

    private fun releaseAudioFx() {
        try {
            equalizer?.release()
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
        equalizer = null
        loudnessEnhancer = null
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        _isEqEnabled.value = enabled
        try {
            equalizer?.enabled = enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error setting EQ status: ${e.message}")
        }
    }

    fun setBandGain(bandId: Int, levelMb: Int) {
        // Adjust state
        val updatedList = _equalizerBands.value.map { band ->
            if (band.bandId == bandId) {
                band.copy(currentLevelMb = levelMb)
            } else band
        }
        _equalizerBands.value = updatedList
        _currentPresetName.value = "Personalizado"

        // Apply to hardware
        try {
            equalizer?.setBandLevel(bandId.toShort(), levelMb.toShort())
        } catch (e: Exception) {
            Log.e(TAG, "Error modifying band level: ${e.message}")
        }
    }

    fun setSubwooferPreset() {
        if (_equalizerBands.value.isEmpty()) return
        // Custom Bass Preset: Boost low frequency bands by half range, drop highs
        val updated = _equalizerBands.value.mapIndexed { index, band ->
            val level = when (index) {
                0 -> (band.maxLevelMb * 0.7f).toInt() // Sub bass
                1 -> (band.maxLevelMb * 0.5f).toInt() // Mid bass
                2 -> 0                                // Vocal mid
                3 -> (band.minLevelMb * 0.2f).toInt() // Upper high
                else -> (band.minLevelMb * 0.4f).toInt() // High treble
            }
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), level.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = level)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Mega Bass"
    }

    fun setVocalPreset() {
        if (_equalizerBands.value.isEmpty()) return
        // Vocal Preset: Boost mid frequencies
        val updated = _equalizerBands.value.mapIndexed { index, band ->
            val level = when (index) {
                0 -> (band.minLevelMb * 0.2f).toInt()
                1 -> 0
                2 -> (band.maxLevelMb * 0.6f).toInt() // High vocals
                3 -> (band.maxLevelMb * 0.5f).toInt()
                else -> (band.minLevelMb * 0.1f).toInt()
            }
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), level.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = level)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Vocal"
    }

    fun setFlatPreset() {
        if (_equalizerBands.value.isEmpty()) return
        val updated = _equalizerBands.value.map { band ->
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), 0.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = 0)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Plana"
    }

    fun setMetalRockPreset() {
        if (_equalizerBands.value.isEmpty()) return
        // Metal/Rock: V-shape boosting bass and treble
        val updated = _equalizerBands.value.mapIndexed { index, band ->
            val level = when (index) {
                0 -> (band.maxLevelMb * 0.6f).toInt()
                1 -> (band.maxLevelMb * 0.3f).toInt()
                2 -> (band.minLevelMb * 0.2f).toInt()
                3 -> (band.maxLevelMb * 0.4f).toInt()
                else -> (band.maxLevelMb * 0.6f).toInt()
            }
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), level.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = level)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Metal/Rock"
    }

    fun setVozClaraPreset() {
        if (_equalizerBands.value.isEmpty()) return
        // Saca brillo a las frecuencias medias y disminuye graves
        val updated = _equalizerBands.value.mapIndexed { index, band ->
            val level = when (index) {
                0 -> (band.minLevelMb * 0.4f).toInt()
                1 -> (band.minLevelMb * 0.1f).toInt()
                2 -> (band.maxLevelMb * 0.7f).toInt()
                3 -> (band.maxLevelMb * 0.5f).toInt()
                else -> 0
            }
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), level.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = level)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Voz Clara"
    }

    fun setDynamicBassBoostPreset() {
        if (_equalizerBands.value.isEmpty()) return
        // Much higher sub bass boost, flat high
        val updated = _equalizerBands.value.mapIndexed { index, band ->
            val level = when (index) {
                0 -> (band.maxLevelMb * 0.9f).toInt()
                1 -> (band.maxLevelMb * 0.6f).toInt()
                2 -> (band.minLevelMb * 0.1f).toInt()
                3 -> 0
                else -> 0
            }
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), level.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = level)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Dynamic Bass"
    }

    fun setAmbientalPreset() {
        if (_equalizerBands.value.isEmpty()) return
        // Spatial depth sound
        val updated = _equalizerBands.value.mapIndexed { index, band ->
            val level = when (index) {
                0 -> (band.maxLevelMb * 0.5f).toInt()
                1 -> (band.maxLevelMb * 0.1f).toInt()
                2 -> 0
                3 -> (band.minLevelMb * 0.2f).toInt()
                else -> (band.maxLevelMb * 0.7f).toInt()
            }
            try {
                equalizer?.setBandLevel(band.bandId.toShort(), level.toShort())
            } catch (_: Exception) {}
            band.copy(currentLevelMb = level)
        }
        _equalizerBands.value = updated
        _currentPresetName.value = "Ambiental"
    }

    fun setAudioBoost(boostValuePercent: Float) {
        // Boost is mapped between 0f (0mB) and 1.0f (6000mB loudness boost)
        val mB = (boostValuePercent * 6000f)
        _boostLevel.value = mB
        try {
            if (loudnessEnhancer == null) {
                mediaPlayer?.audioSessionId?.let { sessionId ->
                    if (sessionId != 0) {
                        try {
                            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                                enabled = true
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            loudnessEnhancer?.setTargetGain(mB.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Error applying target boost gain: ${e.message}")
        }
    }

    private var radioMetadataFetchJob: Job? = null

    private fun startRadioMetadataFetcher(url: String) {
        radioMetadataFetchJob?.cancel()
        _radioSongTitle.value = null
        _radioSongArtist.value = null
        
        val context = appContext ?: return
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("radio_metadata_enabled", true)) {
            return
        }

        radioMetadataFetchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val isPlayingCurrently = _isPlaying.value
                val isRadioCurrent = _currentTrack.value?.playlistId == -2
                if (!isPlayingCurrently || !isRadioCurrent) {
                    delay(3000)
                    continue
                }

                try {
                    val resolvedUrl = resolveRadioStreamUrl(url)
                    val urlObj = URL(resolvedUrl)
                    val connection = urlObj.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.setRequestProperty("Icy-MetaData", "1")
                    connection.connect()

                    val metaintHeader = connection.getHeaderField("icy-metaint")
                    val metaint = metaintHeader?.toIntOrNull() ?: 0

                    if (metaint > 0) {
                        val inputStream = connection.inputStream
                        // Skip metaint bytes
                        var skipped = 0L
                        val buffer = ByteArray(4096)
                        while (skipped < metaint && isActive) {
                            val toRead = java.lang.Math.min(buffer.size.toLong(), metaint - skipped).toInt()
                            val read = inputStream.read(buffer, 0, toRead)
                            if (read == -1) break
                            skipped += read
                        }

                        if (skipped >= metaint) {
                            val metadataLengthByte = inputStream.read()
                            if (metadataLengthByte != -1) {
                                val metadataLength = metadataLengthByte * 16
                                if (metadataLength > 0) {
                                    val metaBuffer = ByteArray(metadataLength)
                                    var readMeta = 0
                                    while (readMeta < metadataLength && isActive) {
                                        val r = inputStream.read(metaBuffer, readMeta, metadataLength - readMeta)
                                        if (r == -1) break
                                        readMeta += r
                                    }

                                    if (readMeta == metadataLength) {
                                        val metaString = String(metaBuffer, java.nio.charset.Charset.forName("UTF-8"))
                                        Log.d(TAG, "Parsed raw ICY metadata: $metaString")
                                        // Format is StreamTitle='Artist - Song';StreamUrl='';...
                                        val streamTitleKey = "StreamTitle="
                                        val index = metaString.indexOf(streamTitleKey)
                                        if (index != -1) {
                                            var start = index + streamTitleKey.length
                                            if (start < metaString.length) {
                                                val quoteChar = metaString[start]
                                                val titleData = if (quoteChar == '\'' || quoteChar == '"') {
                                                    start++
                                                    val end = metaString.indexOf(quoteChar, start)
                                                    if (end != -1) metaString.substring(start, end) else ""
                                                } else {
                                                    val end = metaString.indexOf(';', start)
                                                    if (end != -1) metaString.substring(start, end) else ""
                                                }

                                                if (titleData.isNotBlank()) {
                                                    // Parse Artist and Title
                                                    val parts = titleData.split(" - ", limit = 2)
                                                    val artist = if (parts.size > 1) parts[0].trim() else ""
                                                    val song = if (parts.size > 1) parts[1].trim() else parts[0].trim()
                                                    
                                                    withContext(Dispatchers.Main) {
                                                        _radioSongTitle.value = song
                                                        _radioSongArtist.value = if (artist.isNotEmpty()) artist else null
                                                        
                                                        // Force update notification/Widget if currently playing
                                                        val current = _currentTrack.value
                                                        if (current != null && current.playlistId == -2) {
                                                            updateNotification(current, true)
                                                            updateMediaSessionState(true, current)
                                                            updateWidget()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        inputStream.close()
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error fetching ICY metadata: ${e.message}")
                }

                // Check again in 25 seconds
                delay(25000)
            }
        }
    }

    private suspend fun extractAndSaveTrackMetadata(context: Context, trackPath: String, localFile: File) {
        if (!localFile.exists() || localFile.length() <= 0) return
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(localFile.absolutePath)
            val durMsStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            var durationTxt = "03:00"
            durMsStr?.toLongOrNull()?.let { ms ->
                val secs = (ms / 1000) % 60
                val mins = (ms / 1000) / 60
                durationTxt = String.format("%02d:%02d", mins, secs)
            }
            val title = trackPath.substringAfterLast('/').substringBeforeLast('.')
            val artistSelect = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) 
                ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "Desconocido"
            val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "Álbum Remoto"
            
            val hash = trackPath.hashCode()
            val ext = getExtension(trackPath)
            
            val art = retriever.embeddedPicture
            if (art != null) {
                val coverFile = getScannedCoverFile(context, hash, ext)
                coverFile.writeBytes(art)
                try {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.size)
                    if (bmp != null) {
                        coverImageMemoryCache.put(trackPath, bmp)
                    }
                } catch (_: Exception) {}
            } else {
                pathsWithNoCover.add(trackPath)
            }
            
            val db = AppDatabase.getDatabase(context)
            val updatedTrack = LocalMusicTrack(
                filePath = trackPath,
                title = title,
                artist = artistSelect,
                album = album,
                genre = "Streaming FTP",
                year = "N/A",
                durationText = durationTxt,
                size = localFile.length(),
                dateModified = System.currentTimeMillis()
            )
            db.localMusicTrackDao().insertTracks(listOf(updatedTrack))
            Log.d(TAG, "Successfully extracted and saved metadata/cover for $trackPath")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting metadata in playback: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun stopRadioMetadataFetcher() {
        radioMetadataFetchJob?.cancel()
        radioMetadataFetchJob = null
        _radioSongTitle.value = null
        _radioSongArtist.value = null
    }
}

@android.annotation.TargetApi(android.os.Build.VERSION_CODES.M)
class ProgressiveFtpMediaDataSource(
    private val file: File,
    private val totalSize: Long,
    private val getDownloadedBytes: () -> Long,
    private val getDownloadFinished: () -> Boolean
) : android.media.MediaDataSource() {

    private var randomAccessFile: java.io.RandomAccessFile? = null

    private fun getRaf(): java.io.RandomAccessFile {
        var raf = randomAccessFile
        if (raf == null) {
            raf = java.io.RandomAccessFile(file, "r")
            randomAccessFile = raf
        }
        return raf
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= totalSize) {
            return -1 // End of file
        }

        val endPos = minOf(totalSize, position + size)
        val needed = endPos - position
        if (needed <= 0) return 0

        val startTime = System.currentTimeMillis()
        // Wait until enough bytes are available
        while (getDownloadedBytes() < endPos && !getDownloadFinished()) {
            if (System.currentTimeMillis() - startTime > 15000) {
                // Timeout to avoid blocking indefinitely if download stalled
                break
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {}
        }

        val currentDownloaded = getDownloadedBytes()
        if (position >= currentDownloaded) {
            // We can't satisfy this read yet or download stopped/failed
            return if (getDownloadFinished()) -1 else 0
        }

        val available = currentDownloaded - position
        val toRead = minOf(size.toLong(), available).toInt()
        if (toRead <= 0) return -1

        return try {
            val raf = getRaf()
            raf.seek(position)
            raf.read(buffer, offset, toRead)
        } catch (e: Exception) {
            android.util.Log.e("ProgressiveDataSource", "Error reading at position $position: ${e.message}")
            -1
        }
    }

    override fun getSize(): Long {
        return totalSize
    }

    @Synchronized
    override fun close() {
        try {
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null
    }
}
