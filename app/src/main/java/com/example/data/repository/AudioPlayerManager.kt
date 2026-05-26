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

    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var audioManager: AudioManager? = null
    private var appContext: Context? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    // Player State FlowS
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentTrack = MutableStateFlow<PlaylistItem?>(null)
    val currentTrack: StateFlow<PlaylistItem?> = _currentTrack

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _bufferingProgress = MutableStateFlow(0f)
    val bufferingProgress: StateFlow<Float> = _bufferingProgress

    private val _repeatMode = MutableStateFlow(PlaybackRepeatMode.ALL)
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

    fun toggleRecordingProtectedMode() {
        _isRecordingProtectedMode.value = !_isRecordingProtectedMode.value
    }

    fun setRecordingProtectedMode(enabled: Boolean) {
        _isRecordingProtectedMode.value = enabled
    }

    private var radioRecordingJob: Job? = null
    private var radioStartTimeMs = 0L
    private var radioPauseTimeMs = 0L
    private var radioRecordingFile: File? = null

    // Queue State
    private var currentPlaylist = mutableListOf<PlaylistItem>()
    private var currentIndex = -1

    // EQ / Audio FX States
    private val _isEqEnabled = MutableStateFlow(false)
    val isEqEnabled: StateFlow<Boolean> = _isEqEnabled

    private val _equalizerBands = MutableStateFlow<List<EqualizerBand>>(emptyList())
    val equalizerBands: StateFlow<List<EqualizerBand>> = _equalizerBands

    private val _boostLevel = MutableStateFlow(0f) // 0 to 1000 mB boost
    val boostLevel: StateFlow<Float> = _boostLevel

    private val _systemVolume = MutableStateFlow(0)
    val systemVolume: StateFlow<Int> = _systemVolume

    private val _maxSystemVolume = MutableStateFlow(15)
    val maxSystemVolume: StateFlow<Int> = _maxSystemVolume

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
    private var wasPlayingBeforeFocusLoss = false
    var hasPromptedResume = false
    private var pendingSeekPosition: Long = 0L

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausePlayback()
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

    init {
        scope.launch {
            combine(currentTrack, isPlaying) { track, playing ->
                track to playing
            }.collect { (track, playing) ->
                updateNotification(track, playing)
                updateMediaSessionState(playing, track)
            }
        }
    }

    private fun loadCoverBitmap(context: Context, track: PlaylistItem): android.graphics.Bitmap? {
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
                    val coverCacheFile = java.io.File(context.cacheDir, "ftp_cover_$hash.mp3")
                    if (coverCacheFile.exists() && coverCacheFile.length() > 0) {
                        retriever.setDataSource(coverCacheFile.absolutePath)
                    } else {
                        val cachedFile = java.io.File(context.cacheDir, "current_playing_media.mp3")
                        if (cachedFile.exists() && cachedFile.length() > 0) {
                            retriever.setDataSource(cachedFile.absolutePath)
                        } else {
                            return null
                        }
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

                val builder = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(track.fileName)
                    .setContentText(if (track.playlistId == -2) "Radio En Linea Streaming" else "Pista Local/FTP")
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
                            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.fileName)
                            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, if (track.playlistId == -2) "Radio En Linea Streaming" else "Pista Local/FTP")
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

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                    if (permissionCheck == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                } else {
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                }
            }
        }
    }

    fun initialize(context: Context) {
        try {
            appContext = context.applicationContext
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
                currentIndex = 0
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

    fun playTrack(context: Context, track: PlaylistItem, playlist: List<PlaylistItem> = listOf(track), initialPositionMs: Long = 0L) {
        if (_isRecordingProtectedMode.value && _currentTrack.value?.filePath != track.filePath) {
            try {
                Toast.makeText(context, "⚠️ Modo Seguro Activo: Desactívalo en el reproductor para cambiar de emisora o música.", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            return
        }
        initialize(context)
        currentPlaylist = playlist.toMutableList()
        currentIndex = currentPlaylist.indexOfFirst { it.filePath == track.filePath }
        if (currentIndex == -1) {
            currentPlaylist.add(track)
            currentIndex = currentPlaylist.size - 1
        }

        pendingSeekPosition = initialPositionMs

        // Save to recently played database table
        scope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val recent = RecentTrack(
                    filePath = track.filePath,
                    fileName = track.fileName,
                    playlistId = track.playlistId,
                    fileSize = track.fileSize,
                    durationText = track.durationText,
                    playedAt = System.currentTimeMillis()
                )
                db.recentTrackDao().insertRecentTrack(recent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save track to recently played database: ${e.message}")
            }
        }

        loadAndPlay(context, track)
    }

    fun stopPlayback() {
        _isPlaying.value = false
        _currentTrack.value = null
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
        radioRecordingJob?.cancel()
        radioRecordingJob = null
        try {
            radioRecordingFile?.let {
                if (it.exists()) {
                    it.delete()
                }
            }
        } catch (_: Exception) {}
        radioRecordingFile = null
        _totalRecordedBytes.value = 0L
        _radioElapsedTimeSec.value = 0
        _radioPlayPositionSec.value = 0
        _isRadioLiveMode.value = true
        _isRecordingProtectedMode.value = false
        radioStartTimeMs = 0L
        radioPauseTimeMs = 0L
    }

    fun stopRadioStream() {
        _isPlaying.value = false
        _isBuffering.value = false
        _isRecordingProtectedMode.value = false
        stopProgressUpdates()
        stopRadioRecording()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
    }

    private fun startRadioRecording(context: Context, url: String) {
        stopRadioRecording()
        _isRadioLiveMode.value = true
        _radioElapsedTimeSec.value = 0
        _radioPlayPositionSec.value = 0
        radioStartTimeMs = System.currentTimeMillis()

        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val bufferLimitMins = prefs.getInt("radio_buffer_limit_minutes", 120) // default 120 minutes
        val bufferLimitSecs = bufferLimitMins * 60

        radioRecordingJob = scope.launch(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "timeshift_temp.mp3")
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                tempFile.createNewFile()
                radioRecordingFile = tempFile
            } catch (e: Exception) {
                Log.e(TAG, "Error creating radio temp recording file: ${e.message}")
                return@launch
            }

            var bytesWritten = 0L

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
                            Log.e(TAG, "Read error, attempting reconnect...: ${e.message}")
                            -2 // custom indicator for error read
                        }

                        if (read == -1 || read == -2) {
                            break // break inner loop to trigger reconnect/retry
                        }

                        outputStream.write(buffer, 0, read)
                        bytesWritten += read
                        _totalRecordedBytes.value = bytesWritten

                        val elapsed = ((System.currentTimeMillis() - radioStartTimeMs) / 1000).toInt()
                        _radioElapsedTimeSec.value = elapsed

                        if (elapsed >= bufferLimitSecs) {
                            Log.d(TAG, "Radio timeshift buffer limit reached ($bufferLimitMins min). Stopping further recording.")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Radio recording connection error: ${e.message}")
                } finally {
                    try { outputStream?.close() } catch(_: Exception) {}
                    try { inputStream?.close() } catch(_: Exception) {}
                    try { connection?.disconnect() } catch(_: Exception) {}
                }

                // If still active, wait 3 seconds and retry
                if (radioRecordingJob?.isActive == true) {
                    val elapsed = ((System.currentTimeMillis() - radioStartTimeMs) / 1000).toInt()
                    if (elapsed >= bufferLimitSecs) {
                        break
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

        if (clampedSec >= _radioElapsedTimeSec.value - 3) {
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
                        _isPlaying.value = true
                        mp.start()
                        startProgressUpdates()
                    }
                    setOnErrorListener { _, _, _ ->
                        _isBuffering.value = false
                        true
                    }
                    prepareAsync()
                }
                mediaPlayer = player
            } catch(e: Exception) {
                _isBuffering.value = false
                Log.e(TAG, "Error returning to live stream: ${e.message}")
            }
        }
    }

    fun playRadioTimeShiftAt(positionMs: Long) {
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
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    val fis = java.io.FileInputStream(tempFile)
                    setDataSource(fis.fd)
                    fis.close()

                    setOnPreparedListener { mp ->
                        _isBuffering.value = false
                        _isPlaying.value = true
                        mp.seekTo(positionMs.toInt())
                        mp.start()
                        _currentPosition.value = positionMs
                        startProgressUpdates()
                    }
                    setOnErrorListener { _, _, _ ->
                        _isBuffering.value = false
                        true
                    }
                    prepareAsync()
                }
                mediaPlayer = player
            } catch (e: Exception) {
                _isBuffering.value = false
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

            val targetDir = if (isCustomActive) {
                File(customDirPath!!)
            } else {
                File(context.filesDir, "grabaciones")
            }
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val targetFile = File(targetDir, outputFileName)
            tempFile.copyTo(targetFile, overwrite = true)

            // Save to database
            scope.launch(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(context)
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
                                artist = "Podcast Radio",
                                album = "Radio Grabaciones",
                                size = targetFile.length(),
                                dateModified = System.currentTimeMillis()
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

    private fun loadAndPlay(context: Context, track: PlaylistItem) {
        _isBuffering.value = true
        _bufferingProgress.value = 0f
        _currentTrack.value = track
        _isPlaying.value = false
        stopProgressUpdates()
        savePlaybackState(context, track)

        if (track.playlistId == -2) {
            val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
            val autoTimeshift = prefs.getBoolean("auto_timeshift_enabled", false)
            if (autoTimeshift) {
                startRadioRecording(context, track.filePath)
            } else {
                stopRadioRecording()
            }
        } else {
            stopRadioRecording()
        }

        // Read saved individual track position if no specific pending seek position was defined (skip for streams)
        if (track.playlistId != -2 && pendingSeekPosition == 0L) {
            try {
                val prefs = context.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
                pendingSeekPosition = prefs.getLong("pos_${track.filePath}", 0L)
            } catch (_: Exception) {}
        } else if (track.playlistId == -2) {
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
                                val fis = java.io.FileInputStream(file)
                                setDataSource(fis.fd)
                                fis.close()
                            } else {
                                setDataSource(resolvedPath)
                            }
                        }
                    } else {
                        val file = File(resolvedPath)
                        if (resolvedPath.startsWith("/") && file.exists()) {
                            _isBuffering.value = false
                            val fis = java.io.FileInputStream(file)
                            setDataSource(fis.fd)
                            fis.close()
                        } else {
                            // Download file to cache securely
                            val tempFile = File(context.cacheDir, "current_playing_media.mp3")
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }

                            var downloadSuccess = FtpClientManager.downloadFileToCache(
                                remotePath = resolvedPath,
                                localFile = tempFile,
                                progressListener = { downloaded, total ->
                                    if (total > 0) {
                                        _bufferingProgress.value = downloaded.toFloat() / total.toFloat()
                                    }
                                }
                            )

                            if (!downloadSuccess) {
                                Log.d(TAG, "Download failed or not connected. Demanding auto-reconnect...")
                                val reconnected = FtpClientManager.autoReconnect(context)
                                if (reconnected) {
                                    Log.d(TAG, "Reconnected successfully! Retrying download...")
                                    downloadSuccess = FtpClientManager.downloadFileToCache(
                                        remotePath = resolvedPath,
                                        localFile = tempFile,
                                        progressListener = { downloaded, total ->
                                            if (total > 0) {
                                                _bufferingProgress.value = downloaded.toFloat() / total.toFloat()
                                            }
                                        }
                                    )
                                }
                            }

                            if (!downloadSuccess) {
                                _isBuffering.value = false
                                Log.e(TAG, "Failed to download media for streaming.")
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    try {
                                        android.widget.Toast.makeText(context, "❌ Error de conexión FTP. No se pudo reproducir el archivo.", android.widget.Toast.LENGTH_LONG).show()
                                    } catch (_: Exception) {}
                                }
                                return@launch
                            }
                            _isBuffering.value = false
                            val fis = java.io.FileInputStream(tempFile)
                            setDataSource(fis.fd)
                            fis.close()
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
                Log.e(TAG, "Error playing track: ${e.message}", e)
            }
        }
    }

    fun pausePlayback() {
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
            
            if (_isRadioLiveMode.value) {
                _isRadioLiveMode.value = false
                val elapsed = ((radioPauseTimeMs - radioStartTimeMs) / 1000).toInt()
                _radioPlayPositionSec.value = elapsed
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
        _isBuffering.value = false
        setupAudioFx(mp.audioSessionId)
        
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
        _isPlaying.value = false
        stopProgressUpdates()

        // Clear saved position for this finished track
        appContext?.let { ctx ->
            try {
                val prefs = ctx.getSharedPreferences("ftp_hub_playback", Context.MODE_PRIVATE)
                val current = _currentTrack.value
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
        _isPlaying.value = false
        stopProgressUpdates()
        _isBuffering.value = false

        val current = _currentTrack.value
        if (current != null && current.playlistId == -2) {
            appContext?.let { ctx ->
                scope.launch {
                    kotlinx.coroutines.delay(2000)
                    Log.i(TAG, "Attempting auto-reconnect for radio stream...")
                    loadAndPlay(ctx, current)
                }
            }
        }
        return true
    }

    fun togglePlayPause(context: Context? = null) {
        val track = _currentTrack.value
        if (_isPlaying.value) {
            pausePlayback()
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

    fun playNext(context: Context?) {
        if (_isRecordingProtectedMode.value) {
            context?.let { ctx ->
                try {
                    Toast.makeText(ctx, "⚠️ Modo Seguro Activo: Desactívalo para cambiar de reproducción.", Toast.LENGTH_SHORT).show()
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

        currentIndex = nextIndex
        context?.let { loadAndPlay(it, currentPlaylist[nextIndex]) }
    }

    fun playPrevious(context: Context?) {
        if (_isRecordingProtectedMode.value) {
            context?.let { ctx ->
                try {
                    Toast.makeText(ctx, "⚠️ Modo Seguro Activo: Desactívalo para cambiar de reproducción.", Toast.LENGTH_SHORT).show()
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
        currentIndex = prevIndex
        context?.let { loadAndPlay(it, currentPlaylist[prevIndex]) }
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
                                    _radioPlayPositionSec.value = player.currentPosition / 1000
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                mediaPlayer?.let { player ->
                    if (player.isPlaying) {
                        val pos = player.currentPosition.toLong()
                        _currentPosition.value = pos
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
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    // Audio FX Management (Equalizer & Boost)
    private fun setupAudioFx(audioSessionId: Int) {
        try {
            // Setup LoudnessEnhancer for Volume Booster!
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain((_boostLevel.value).toInt()) // Initial target gain
                setEnabled(true)
            }

            // Setup Equalizer
            val eq = Equalizer(0, audioSessionId).apply {
                enabled = _isEqEnabled.value
            }
            equalizer = eq

            // Query native bands
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

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring Audio Fx structures: ${e.message}")
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
    }

    fun setAudioBoost(boostValuePercent: Float) {
        // Boost is mapped between 0f (0mB) and 1.0f (2000mB loudness boost)
        val mB = (boostValuePercent * 2000f)
        _boostLevel.value = mB
        try {
            loudnessEnhancer?.setTargetGain(mB.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Error applying target boost gain: ${e.message}")
        }
    }
}
