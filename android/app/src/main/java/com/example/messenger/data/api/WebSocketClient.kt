package com.example.messenger.data.api

import com.example.messenger.data.model.WsIncoming
import com.example.messenger.data.model.WsOutgoing
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.*

/**
 * Соответствует эндпоинту `/ws/{chat_id}?token=...` в main.py.
 * Сервер закрывает соединение с кодом 4401 (неавторизован) или 4403 (нет доступа) —
 * оба случая пробрасываются наружу через onClosed, чтобы UI мог среагировать.
 */
class WebSocketClient(private val client: OkHttpClient) {

    private var socket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun connect(chatId: String, token: String): Flow<WsEvent> = callbackFlow {
        val httpBase = RetrofitClient.baseUrl
        val wsBase = httpBase.replaceFirst("http", "ws").trimEnd('/')
        val url = "$wsBase/ws/$chatId?token=$token"

        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(WsEvent.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { json.decodeFromString<WsIncoming>(text) }
                    .onSuccess { trySend(WsEvent.Message(it)) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                trySend(WsEvent.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                trySend(WsEvent.Failure(t.message ?: "Соединение потеряно"))
                close()
            }
        }

        socket = client.newWebSocket(request, listener)

        awaitClose {
            socket?.close(1000, "closing")
            socket = null
        }
    }

    /** Публичные/групповые чаты — открытый текст, как раньше. */
    fun send(text: String, fileUrl: String? = null) {
        val payload = json.encodeToString(
            WsOutgoing.serializer(),
            WsOutgoing(text = text, fileUrl = fileUrl)
        )
        socket?.send(payload)
    }

    /** dm-чаты — сервер ждёт ciphertext+messageType вместо text (см. /ws/{chat_id} в main.py). */
    fun sendEncrypted(ciphertext: String, messageType: String, fileUrl: String? = null) {
        val payload = json.encodeToString(
            WsOutgoing.serializer(),
            WsOutgoing(ciphertext = ciphertext, messageType = messageType, fileUrl = fileUrl)
        )
        socket?.send(payload)
    }

    fun disconnect() {
        socket?.close(1000, "closing")
        socket = null
    }
}

sealed class WsEvent {
    object Open : WsEvent()
    data class Message(val data: WsIncoming) : WsEvent()
    data class Closed(val code: Int, val reason: String) : WsEvent()
    data class Failure(val message: String) : WsEvent()
}
