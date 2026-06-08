package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

object AppThemeManager {
    var themeVersion by mutableStateOf(0)
    
    var isDark by mutableStateOf(true)
    var accentMode by mutableStateOf("blue") // "blue", "purple", "green", "red", "custom"
    var customHue by mutableStateOf(180f) // Hue from 0 to 360
    var iconStyle by mutableStateOf("retro_color") // "retro_color", "modern_color", "neutral"
    var widgetColorStyle by mutableStateOf("match_app") // "match_app", "dark_cyber", "light_slate", "blue_neon", "purple_neon", "green_neon", "red_neon"
    
    fun init(context: Context) {
        val prefs = context.getSharedPreferences("app_theme_configs", Context.MODE_PRIVATE)
        isDark = prefs.getBoolean("theme_is_dark", true)
        accentMode = prefs.getString("theme_accent_mode", "blue") ?: "blue"
        customHue = prefs.getFloat("theme_custom_hue", 180f)
        iconStyle = prefs.getString("theme_icon_style", "retro_color") ?: "retro_color"
        widgetColorStyle = prefs.getString("theme_widget_color_style", "match_app") ?: "match_app"
        themeVersion++
    }
    
    fun updateTheme(context: Context, dark: Boolean, accent: String) {
        isDark = dark
        accentMode = accent
        val prefs = context.getSharedPreferences("app_theme_configs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("theme_is_dark", dark)
            .putString("theme_accent_mode", accent)
            .apply()
        themeVersion++
    }

    fun updateIconStyle(context: Context, style: String) {
        iconStyle = style
        val prefs = context.getSharedPreferences("app_theme_configs", Context.MODE_PRIVATE)
        prefs.edit().putString("theme_icon_style", style).apply()
        themeVersion++
    }

    fun updateCustomHue(context: Context, hue: Float) {
        customHue = hue
        val prefs = context.getSharedPreferences("app_theme_configs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("theme_custom_hue", hue).apply()
        themeVersion++
    }

    fun updateWidgetColorStyle(context: Context, style: String) {
        widgetColorStyle = style
        val prefs = context.getSharedPreferences("app_theme_configs", Context.MODE_PRIVATE)
        prefs.edit().putString("theme_widget_color_style", style).apply()
        themeVersion++
        try {
            com.example.ui.widget.AudioWidgetProvider.updateAllWidgets(context)
        } catch (_: Exception) {}
    }

    val CyberTeal: Color
        get() = when (accentMode) {
            "purple" -> Color(0xFFD0BCFF) // Lila / Neon Purple
            "green" -> Color(0xFF3DDC84) // Neon Green Android style
            "red" -> Color(0xFFE23E57) // Neon Red Cyber style
            "custom" -> Color.hsv(customHue, 0.85f, 0.95f)
            else -> Color(0xFF00ADB5) // Neon Blue/Teal
        }
        
    val CyberDark: Color
        get() = if (isDark) Color(0xFF11141A) else Color(0xFFF1F5F9)
        
    val CyberSurface: Color
        get() = if (isDark) Color(0xFF1A1F29) else Color(0xFFFFFFFF)
        
    val CyberCharcoal: Color
        get() = if (isDark) Color(0xFF222831) else Color(0xFFE2E8F0)
        
    val CyberGrey: Color
        get() = if (isDark) Color(0xFF393E46) else Color(0xFFCBD5E1)
        
    val CyberLight: Color
        get() = if (isDark) Color(0xFFEEEEEE) else Color(0xFF0F172A)
        
    val WarningHotPink: Color
        get() = Color(0xFFE23E57)
        
    val SoftTeal: Color
        get() = when (accentMode) {
            "purple" -> Color(0xFFE1BEE7)
            "green" -> Color(0xFFC8E6C9)
            "red" -> Color(0xFFFFCDD2)
            "custom" -> Color.hsv(customHue, 0.40f, 0.95f)
            else -> Color(0xFF80DEEA)
        }
}

val CyberTeal: Color get() = AppThemeManager.CyberTeal
val CyberDark: Color get() = AppThemeManager.CyberDark
val CyberSurface: Color get() = AppThemeManager.CyberSurface
val CyberCharcoal: Color get() = AppThemeManager.CyberCharcoal
val CyberGrey: Color get() = AppThemeManager.CyberGrey
val CyberLight: Color get() = AppThemeManager.CyberLight
val WarningHotPink: Color get() = AppThemeManager.WarningHotPink
val SoftTeal: Color get() = AppThemeManager.SoftTeal

val Purple80: Color get() = CyberTeal
val PurpleGrey80: Color get() = CyberGrey
val Pink80: Color get() = WarningHotPink

val Purple40: Color get() = CyberTeal
val PurpleGrey40: Color get() = CyberCharcoal
val Pink40: Color get() = WarningHotPink
