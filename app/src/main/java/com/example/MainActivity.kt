package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.ui.FtpViewModel
import com.example.ui.MainAppScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  private val ftpViewModel: FtpViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.ui.theme.AppThemeManager.init(applicationContext)
    enableEdgeToEdge()

    // Check if started from widget to open reproducer screen
    val openPlayer = intent?.getBooleanExtra("open_player", false) == true
    if (openPlayer) {
      ftpViewModel.setCurrentMainTab(3)
    }

    setContent {
      MyApplicationTheme {
        MainAppScreen(viewModel = ftpViewModel)
      }
    }
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val openPlayer = intent.getBooleanExtra("open_player", false) == true
    if (openPlayer) {
      ftpViewModel.setCurrentMainTab(3)
    }
  }
}

