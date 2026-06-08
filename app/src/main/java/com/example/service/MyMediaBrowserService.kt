package com.example.service

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.example.data.repository.AudioPlayerManager
import android.util.Log

class MyMediaBrowserService : MediaBrowserServiceCompat() {

    override fun onCreate() {
        super.onCreate()
        Log.d("MyMediaBrowserService", "onCreate: Initializing AudioPlayerManager and setting Session Token")
        AudioPlayerManager.activeService = this
        AudioPlayerManager.initialize(applicationContext)
        AudioPlayerManager.getSessionTokenCompat()?.let { token ->
            sessionToken = token
            Log.d("MyMediaBrowserService", "onCreate: Session Token associated successfully")
        } ?: Log.e("MyMediaBrowserService", "onCreate: Failed to retrieve Session Token Compat")
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioPlayerManager.activeService = null
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot? {
        Log.d("MyMediaBrowserService", "onGetRoot: clientPackageName = $clientPackageName")
        // Always permit connections from system elements or Android Auto to ensure compatibility.
        // Returning default "root_media_hub" node
        return BrowserRoot("root_media_hub", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d("MyMediaBrowserService", "onLoadChildren: parentId = $parentId")
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        val current = AudioPlayerManager.currentTrack.value
        if (current != null) {
            val description = android.support.v4.media.MediaDescriptionCompat.Builder()
                .setMediaId(current.filePath)
                .setTitle(current.fileName)
                .setSubtitle(if (current.playlistId == -2) "Radio En Vivo" else "FTP Media Hub")
                .build()
            items.add(MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
        }
        result.sendResult(items)
    }
}
