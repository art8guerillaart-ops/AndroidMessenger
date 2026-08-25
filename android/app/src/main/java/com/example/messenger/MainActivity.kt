package com.example.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.messenger.ui.navigation.MessengerNavGraph
import com.example.messenger.ui.theme.MessengerTheme

class MainActivity : ComponentActivity() {

    // Без этого разрешения (Android 13+) уведомления о сообщениях просто не покажутся —
    // молча игнорируем отказ, это не блокирует остальной функционал приложения.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val app = application as MessengerApp

        setContent {
            // Читаем из DataStore при старте; т.к. это Flow, переключатель в Настройках
            // применяется сразу же, без перезапуска экрана.
            val isDarkTheme by app.preferencesManager.darkThemeEnabledFlow.collectAsState(initial = false)
            MessengerTheme(isDarkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MessengerNavGraph(app = app)
                }
            }
        }
    }
}
