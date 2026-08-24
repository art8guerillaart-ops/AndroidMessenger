package com.example.messenger.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmailRequest(val email: String)

@Serializable
data class VerifyRequest(val email: String, val code: String)

@Serializable
data class AuthResponse(
    val message: String,
    val token: String,
    val email: String
)

@Serializable
data class ChatDto(
    @SerialName("chat_id") val chatId: String,
    val title: String,
    @SerialName("chat_type") val chatType: String // "public" | "dm" | "group"
)

@Serializable
data class MessageDto(
    val id: Int,
    val sender: String,
    val text: String?,
    val ciphertext: String? = null,
    @SerialName("message_type") val messageType: String? = null, // "prekey" | "signal", только для dm
    @SerialName("file_url") val fileUrl: String?,
    val time: String
)

@Serializable
data class DmRequest(@SerialName("peer_email") val peerEmail: String)

@Serializable
data class ChatCreateRequest(@SerialName("chat_id") val chatId: String, val title: String)

@Serializable
data class ParticipantRequest(val email: String)

@Serializable
data class ParticipantDto(val email: String, @SerialName("is_admin") val isAdmin: Boolean)

@Serializable
data class UploadResponse(@SerialName("file_url") val fileUrl: String, val filename: String)

@Serializable
data class SimpleMessage(val message: String)

// Приходит по WebSocket
@Serializable
data class WsIncoming(
    val type: String, // "message" | "presence"
    val sender: String? = null,
    val text: String? = null,
    val ciphertext: String? = null,
    @SerialName("message_type") val messageType: String? = null, // "prekey" | "signal", только для dm
    @SerialName("file_url") val fileUrl: String? = null,
    @SerialName("online_users") val onlineUsers: List<String>? = null
)

// Отправляем по WebSocket.
// Для dm-чатов сервер ждёт ciphertext+messageType вместо text (см. /ws/{chat_id} в main.py) —
// поля нулевые по умолчанию и не попадают в JSON благодаря encodeDefaults=false у Json{}.
@Serializable
data class WsOutgoing(
    val text: String? = null,
    val ciphertext: String? = null,
    @SerialName("message_type") val messageType: String? = null,
    @SerialName("file_url") val fileUrl: String? = null
)

// ---- Signal Protocol: ключи (E2E для dm) ----

@Serializable
data class SignedPreKeyEntryDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String,
    val signature: String
)

@Serializable
data class OneTimePreKeyEntryDto(
    @SerialName("key_id") val keyId: Int,
    @SerialName("public_key") val publicKey: String
)

@Serializable
data class PublishKeysRequest(
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_prekey") val signedPreKey: SignedPreKeyEntryDto,
    @SerialName("one_time_prekeys") val oneTimePreKeys: List<OneTimePreKeyEntryDto>
)

@Serializable
data class KeyBundleResponse(
    @SerialName("registration_id") val registrationId: Int,
    @SerialName("identity_key") val identityKey: String,
    @SerialName("signed_prekey") val signedPreKey: SignedPreKeyEntryDto,
    @SerialName("one_time_prekey") val oneTimePreKey: OneTimePreKeyEntryDto? = null
)

@Serializable
data class KeyCountResponse(@SerialName("one_time_prekeys") val oneTimePreKeys: Int)
