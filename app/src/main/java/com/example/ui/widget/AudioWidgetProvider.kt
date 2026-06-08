package com.example.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.example.R
import com.example.data.repository.AudioPlayerManager

class AudioWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "AudioWidgetProvider"
        const val ACTION_PLAY_PAUSE = "com.example.action.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.example.action.PREVIOUS"
        const val ACTION_NEXT = "com.example.action.NEXT"
        const val ACTION_GO_LIVE = "com.example.action.GO_LIVE"
        const val ACTION_REWIND = "com.example.action.REWIND"
        const val ACTION_FAST_FORWARD = "com.example.action.FAST_FORWARD"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, AudioWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (appWidgetIds.isEmpty()) return
            
            val intent = Intent(context, AudioWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val currentTrack = AudioPlayerManager.currentTrack.value
        val isPlaying = AudioPlayerManager.isPlaying.value
        val isRadio = currentTrack != null && currentTrack.playlistId == -2
        val progressPercent = if (isRadio) {
            val totalSec = AudioPlayerManager.radioElapsedTimeSec.value
            val playSec = AudioPlayerManager.radioPlayPositionSec.value
            if (totalSec > 0) {
                (playSec * 100 / totalSec).coerceIn(0, 100)
            } else {
                0
            }
        } else {
            if (AudioPlayerManager.duration.value > 0) {
                (AudioPlayerManager.currentPosition.value * 100 / AudioPlayerManager.duration.value).toInt().coerceIn(0, 100)
            } else {
                0
            }
        }

        // Fetch user selected widget customization configurations
        val themeConfigs = context.getSharedPreferences("app_theme_configs", Context.MODE_PRIVATE)
        val style = themeConfigs.getString("theme_widget_color_style", "match_app") ?: "match_app"
        val isDarkApp = themeConfigs.getBoolean("theme_is_dark", true)
        val accentModeApp = themeConfigs.getString("theme_accent_mode", "blue") ?: "blue"
        val customHueApp = themeConfigs.getFloat("theme_custom_hue", 180f)

        val widgetBgColor: Int
        val widgetTitleColor: Int
        val widgetStatusColor: Int
        val widgetAccentColor: Int

        when (style) {
            "light_slate" -> {
                widgetBgColor = 0xFFFFFFFF.toInt()
                widgetTitleColor = 0xFF1E293B.toInt()
                widgetStatusColor = 0xFF64748B.toInt()
                widgetAccentColor = 0xFF00ADB5.toInt()
            }
            "dark_cyber" -> {
                widgetBgColor = 0xFF11141A.toInt()
                widgetTitleColor = 0xFFFFFFFF.toInt()
                widgetStatusColor = 0xFF8F949F.toInt()
                widgetAccentColor = 0xFF00ADB5.toInt()
            }
            "blue_neon" -> {
                widgetBgColor = 0xFF11141A.toInt()
                widgetTitleColor = 0xFFFFFFFF.toInt()
                widgetStatusColor = 0xFF8F949F.toInt()
                widgetAccentColor = 0xFF00ADB5.toInt()
            }
            "purple_neon" -> {
                widgetBgColor = 0xFF11141A.toInt()
                widgetTitleColor = 0xFFFFFFFF.toInt()
                widgetStatusColor = 0xFF8F949F.toInt()
                widgetAccentColor = 0xFFD0BCFF.toInt()
            }
            "green_neon" -> {
                widgetBgColor = 0xFF11141A.toInt()
                widgetTitleColor = 0xFFFFFFFF.toInt()
                widgetStatusColor = 0xFF8F949F.toInt()
                widgetAccentColor = 0xFF3DDC84.toInt()
            }
            "red_neon" -> {
                widgetBgColor = 0xFF11141A.toInt()
                widgetTitleColor = 0xFFFFFFFF.toInt()
                widgetStatusColor = 0xFF8F949F.toInt()
                widgetAccentColor = 0xFFE23E57.toInt()
            }
            else -> { // match_app: syncs with App accent hue and dark mode!
                widgetBgColor = if (isDarkApp) 0xFF11141A.toInt() else 0xFFFFFFFF.toInt()
                widgetTitleColor = if (isDarkApp) 0xFFFFFFFF.toInt() else 0xFF1E293B.toInt()
                widgetStatusColor = if (isDarkApp) 0xFF8F949F.toInt() else 0xFF64748B.toInt()
                widgetAccentColor = when (accentModeApp) {
                    "purple" -> 0xFFD0BCFF.toInt()
                    "green" -> 0xFF3DDC84.toInt()
                    "red" -> 0xFFE23E57.toInt()
                    "custom" -> {
                        val hsv = floatArrayOf(customHueApp, 0.85f, 0.95f)
                        android.graphics.Color.HSVToColor(hsv)
                    }
                    else -> 0xFF00ADB5.toInt() // Cyan/blue
                }
            }
        }

        for (appWidgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_audio_player)

            // Apply customized backgrounds & texts
            views.setInt(R.id.widget_root, "setBackgroundColor", widgetBgColor)
            views.setTextColor(R.id.widget_track_title, widgetTitleColor)
            views.setTextColor(R.id.widget_track_status, widgetStatusColor)

            // Tint the player controller image buttons
            views.setInt(R.id.widget_btn_prev, "setColorFilter", widgetAccentColor)
            views.setInt(R.id.widget_btn_play_pause, "setColorFilter", widgetAccentColor)
            views.setInt(R.id.widget_btn_next, "setColorFilter", widgetAccentColor)

            // Track detailed info
            if (currentTrack != null) {
                views.setTextViewText(R.id.widget_track_title, currentTrack.fileName)
                val verbAction = if (isPlaying) "Reproduciendo" else "Pausado"
                val sourceDesc = AudioPlayerManager.currentSourceText.value
                val statusText = "$verbAction ● $sourceDesc"
                views.setTextViewText(R.id.widget_track_status, statusText)
                views.setProgressBar(R.id.widget_progress_bar, 100, progressPercent, false)

                val coverBitmap = try {
                    AudioPlayerManager.loadCoverBitmap(context, currentTrack)
                } catch (e: Exception) {
                    null
                }

                if (coverBitmap != null) {
                    views.setImageViewBitmap(R.id.widget_track_icon, coverBitmap)
                    views.setInt(R.id.widget_track_icon, "setColorFilter", android.graphics.Color.TRANSPARENT)
                } else {
                    val fallbackIcon = AudioPlayerManager.currentSourceIconId.value
                    views.setImageViewResource(R.id.widget_track_icon, fallbackIcon)
                    views.setInt(R.id.widget_track_icon, "setColorFilter", widgetAccentColor)
                }
            } else {
                views.setTextViewText(R.id.widget_track_title, "Sin reproducción")
                views.setTextViewText(R.id.widget_track_status, "Abre FTP Hub Player")
                views.setProgressBar(R.id.widget_progress_bar, 100, 0, false)
                views.setImageViewResource(R.id.widget_track_icon, R.drawable.ic_widget_music)
                views.setInt(R.id.widget_track_icon, "setColorFilter", widgetAccentColor)
            }

            // Button Icon Selection
            val playPauseIcon = if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
            views.setImageViewResource(R.id.widget_btn_play_pause, playPauseIcon)

            // Dynamic visibility for Go Live button
            val showingGoLive = currentTrack != null && currentTrack.playlistId == -2 && !AudioPlayerManager.isRadioLiveMode.value
            views.setViewVisibility(R.id.widget_btn_go_live, if (showingGoLive) android.view.View.VISIBLE else android.view.View.GONE)

            // Setup Widget Button Intent hooks
            views.setOnClickPendingIntent(R.id.widget_btn_prev, getPendingSelfIntent(context, ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, getPendingSelfIntent(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_btn_next, getPendingSelfIntent(context, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_btn_go_live, getPendingSelfIntent(context, ACTION_GO_LIVE))
            views.setOnClickPendingIntent(R.id.widget_btn_rewind_progress, getPendingSelfIntent(context, ACTION_REWIND))
            views.setOnClickPendingIntent(R.id.widget_btn_fastforward_progress, getPendingSelfIntent(context, ACTION_FAST_FORWARD))

            // Clicking key details takes user to MainActivity showing list/player
            val mainIntent = Intent(context, com.example.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_player", true)
            }
            val mainPendingIntent = PendingIntent.getActivity(
                context, 100, mainIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_track_icon, mainPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_track_title, mainPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_track_status, mainPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "AudioWidgetProvider intent received: ${intent.action}")

        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                AudioPlayerManager.togglePlayPause(context)
                updateAllWidgets(context)
            }
            ACTION_PREVIOUS -> {
                AudioPlayerManager.playPrevious(context)
                updateAllWidgets(context)
            }
            ACTION_NEXT -> {
                AudioPlayerManager.playNext(context)
                updateAllWidgets(context)
            }
            ACTION_GO_LIVE -> {
                AudioPlayerManager.goLiveRadio()
                updateAllWidgets(context)
            }
            ACTION_REWIND -> {
                AudioPlayerManager.skipSeconds(-30)
                updateAllWidgets(context)
            }
            ACTION_FAST_FORWARD -> {
                AudioPlayerManager.skipSeconds(30)
                updateAllWidgets(context)
            }
        }
    }

    private fun getPendingSelfIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, javaClass).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
