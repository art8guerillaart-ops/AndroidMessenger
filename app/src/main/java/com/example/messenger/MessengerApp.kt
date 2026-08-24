package com.example.messenger

import android.app.Application
import com.example.messenger.data.api.RetrofitClient
import com.example.messenger.data.api.WebSocketClient
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.signal.AndroidSignalProtocolStore
import com.example.messenger.data.signal.SignalDatabase
import com.example.messenger.data.signal.SignalRepository

class MessengerApp : Application() {

    lateinit var sessionManager: SessionManager
        private set

    lateinit var webSocketClient: WebSocketClient
        private set

    lateinit var signalRepository: SignalRepository
        private set

    val api get() = RetrofitClient.api

    override fun onCreate() {
        super.onCreate()
        sessionManager = SessionManager(this)
        webSocketClient = WebSocketClient(RetrofitClient.okHttpClient)

        val signalStore = AndroidSignalProtocolStore(SignalDatabase.getInstance(this).signalKeyDao())
        signalRepository = SignalRepository(signalStore, api, sessionManager)
    }
}
