package com.example.ui

import android.content.Context
import android.widget.Toast as AndroidToast

object Toast {
    const val LENGTH_SHORT = 0 // AndroidToast.LENGTH_SHORT is 0
    const val LENGTH_LONG = 1  // AndroidToast.LENGTH_LONG is 1

    @JvmStatic
    fun makeText(context: Context, text: CharSequence, duration: Int): CustomToastWrapper {
        return CustomToastWrapper(context, text, duration)
    }
}

class CustomToastWrapper(private val context: Context, private val text: CharSequence, private val duration: Int) {
    fun show() {
        val prefs = context.getSharedPreferences("ftp_hub_settings", Context.MODE_PRIVATE)
        val isSilent = prefs.getBoolean("silent_mode_enabled", false)
        if (!isSilent) {
            val systemDuration = if (duration == 1) AndroidToast.LENGTH_LONG else AndroidToast.LENGTH_SHORT
            AndroidToast.makeText(context, text, systemDuration).show()
        }
    }
}
