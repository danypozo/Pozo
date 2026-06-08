package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  // Read themeVersion to subscribe to theme change state updates
  val version = AppThemeManager.themeVersion
  val currentIsDark = AppThemeManager.isDark
  val currentColorScheme = if (currentIsDark) {
      darkColorScheme(
          primary = CyberTeal,
          secondary = CyberGrey,
          tertiary = WarningHotPink,
          background = CyberDark,
          surface = CyberSurface,
          onPrimary = CyberDark,
          onSecondary = CyberLight,
          onBackground = CyberLight,
          onSurface = CyberLight
      )
  } else {
      lightColorScheme(
          primary = CyberTeal,
          secondary = CyberGrey,
          tertiary = WarningHotPink,
          background = CyberDark,
          surface = CyberSurface,
          onPrimary = Color.White,
          onSecondary = CyberLight,
          onBackground = CyberLight,
          onSurface = CyberLight
      )
  }

  MaterialTheme(colorScheme = currentColorScheme, typography = Typography, content = content)
}
