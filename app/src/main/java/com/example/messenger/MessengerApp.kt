package com.example.messenger

import android.app.Application
import android.net.Uri
import com.example.messenger.data.api.RetrofitClient
import com.example.messenger.data.api.WebSocketClient
import com.example.messenger.data.local.PreferencesManager
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.signal.AndroidSignalProtocolStore
import com.example.messenger.data.signal.SignalDatabase
import com.example.messenger.data.signal.SignalRepository
import com.example.messenger.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MessengerApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sessionManager: SessionManager
        private set

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    lateinit var webSocketClient: WebSocketClient
        private set

    lateinit var signalRepository: SignalRepository
        private set

    val api get() = RetrofitClient.api

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(this)
        preferencesManager = PreferencesManager(this)
        notificationHelper = NotificationHelper(this, preferencesManager)
        webSocketClient = WebSocketClient(RetrofitClient.okHttpClient)

        val signalStore = AndroidSignalProtocolStore(SignalDatabase.getInstance(this).signalKeyDao())
        signalRepository = SignalRepository(signalStore, api, sessionManager)

        // Канал уведомлений должен существовать до первого сообщения — создаём его
        // сразу с сохранёнными настройками звука/вибрации (по умолчанию — системные).
        applicationScope.launch {
            val soundUri = preferencesManager.currentNotificationSoundUri()?.let(Uri::parse)
            val vibrationEnabled = preferencesManager.currentVibrationEnabled()
            notificationHelper.applyChannelSettings(soundUri, vibrationEnabled)
        }
    }
}
