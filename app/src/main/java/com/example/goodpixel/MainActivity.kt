package com.example.goodpixel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.goodpixel.theme.GoodPixelTheme
import com.example.goodpixel.ui.main.MainScreen
import com.example.goodpixel.ui.notilog.NotiLogScreen

enum class MainAppTab {
    NOTI_LOG, // デフォルト：HOME画面アイコンから起動した時は通知一覧
    SETTINGS  // 設定画面
}

class MainActivity : ComponentActivity() {

    private var currentTab by mutableStateOf(MainAppTab.NOTI_LOG)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        enableEdgeToEdge()
        setContent {
            GoodPixelTheme {
                BackHandler(enabled = currentTab == MainAppTab.SETTINGS) {
                    currentTab = MainAppTab.NOTI_LOG
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentTab) {
                        MainAppTab.NOTI_LOG -> {
                            NotiLogScreen(
                                onBack = { finish() },
                                showBackButton = false,
                                onOpenSettings = { currentTab = MainAppTab.SETTINGS }
                            )
                        }
                        MainAppTab.SETTINGS -> {
                            MainScreen(
                                onBack = { currentTab = MainAppTab.NOTI_LOG }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("open_tab") == "settings") {
            currentTab = MainAppTab.SETTINGS
        } else {
            currentTab = MainAppTab.NOTI_LOG
        }
    }
}
