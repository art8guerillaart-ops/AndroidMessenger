package com.example.messenger.data.signal

import android.util.Base64
import com.example.messenger.data.api.ApiService
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.model.OneTimePreKeyEntryDto
import com.example.messenger.data.model.PublishKeysRequest
import com.example.messenger.data.model.SignedPreKeyEntryDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

private const val ONE_TIME_PREKEY_BATCH = 100
private const val ONE_TIME_PREKEY_LOW_WATERMARK = 10

/** Результат SessionCipher.encrypt() в виде, готовом к отправке по WebSocket. */
data class EncryptedEnvelope(val ciphertextBase64: String, val messageType: String)

private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

/**
 * Единая точка входа для Signal Protocol на клиенте: регистрация ключей (Шаг 4),
 * установка сессии и шифрование/расшифровка (Шаг 5), фоновая ротация one-time
 * prekeys (Шаг 6). Обёртка над AndroidSignalProtocolStore (Шаг 3) + ApiService.
 */
class SignalRepository(
    private val store: AndroidSignalProtocolStore,
    private val api: ApiService,
    private val session: SessionManager
) {

    // -------------------------------------------------------------------
    // Шаг 4 — регистрация: identity key + signed prekey + 100 one-time prekeys
    // -------------------------------------------------------------------

    /**
     * Вызывается один раз после verify-code. Если ключи уже успешно опубликованы
     * на сервере — ничего не делает. Если локальный identity уже есть, но
     * publishKeys() в прошлый раз не долетел до сервера (обрыв сети и т.п.) —
     * повторяет попытку публикации, переиспользуя существующий identity, но со
     * свежими signed/one-time prekeys (см. LocalIdentityEntity.keysPublished —
     * одного hasLocalIdentity() недостаточно, т.к. identity сохраняется локально
     * раньше, чем сервер подтверждает приём ключей).
     */
    suspend fun registerIfNeeded() = withContext(Dispatchers.IO) {
        if (store.hasLocalIdentity() && store.isKeysPublished()) return@withContext
        val token = session.currentToken() ?: return@withContext

        if (!store.hasLocalIdentity()) {
            val identityKeyPair = IdentityKeyPair.generate()
            val registrationId = KeyHelper.generateRegistrationId(false)
            store.saveLocalIdentity(identityKeyPair, registrationId)
            store.setCurrentSignedPreKeyId(1)
        }

        val identityKeyPair = store.identityKeyPair
        val registrationId = store.localRegistrationId
        val signedEntry = generateAndStoreSignedPreKey(identityKeyPair, store.currentSignedPreKeyId())
        val oneTimeEntries = generateAndStoreOneTimePreKeys(ONE_TIME_PREKEY_BATCH)

        api.publishKeys(
            token,
            PublishKeysRequest(
                registrationId = registrationId,
                identityKey = identityKeyPair.publicKey.serialize().toBase64(),
                signedPreKey = signedEntry,
                oneTimePreKeys = oneTimeEntries
            )
        )
        store.markKeysPublished()
    }

    private fun generateAndStoreSignedPreKey(identityKeyPair: IdentityKeyPair, id: Int): SignedPreKeyEntryDto {
        val keyPair = Curve.generateKeyPair()
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature))
        return SignedPreKeyEntryDto(id, keyPair.publicKey.serialize().toBase64(), signature.toBase64())
    }

    private fun generateAndStoreOneTimePreKeys(count: Int): List<OneTimePreKeyEntryDto> =
        store.reserveOneTimePreKeyIds(count).map { id ->
            val keyPair = Curve.generateKeyPair()
            store.storePreKey(id, PreKeyRecord(id, keyPair))
            OneTimePreKeyEntryDto(id, keyPair.publicKey.serialize().toBase64())
        }

    // -------------------------------------------------------------------
    // Шаг 5 — установка сессии и шифрование/расшифровка
    // -------------------------------------------------------------------

    private fun addressOf(email: String) = SignalProtocolAddress(email, SIGNAL_DEVICE_ID)

    /** Если сессии с собеседником ещё нет — забирает его bundle и строит сессию. */
    suspend fun ensureSessionWith(peerEmail: String) = withContext(Dispatchers.IO) {
        val address = addressOf(peerEmail)
        if (store.containsSession(address)) return@withContext

        val token = session.currentToken() ?: error("Нет активной сессии")
        val response = api.getKeyBundle(token, peerEmail)
        val dto = response.body()
        if (!response.isSuccessful || dto == null) {
            error("У собеседника нет опубликованных ключей — он ещё не заходил в приложение")
        }

        val identityKey = IdentityKey(dto.identityKey.fromBase64())
        val signedPreKeyPublic = ECPublicKey(dto.signedPreKey.publicKey.fromBase64())
        val oneTimeId = dto.oneTimePreKey?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID
        val oneTimePublic = dto.oneTimePreKey?.let { ECPublicKey(it.publicKey.fromBase64()) }

        val bundle = PreKeyBundle(
            dto.registrationId,
            SIGNAL_DEVICE_ID,
            oneTimeId,
            oneTimePublic,
            dto.signedPreKey.keyId,
            signedPreKeyPublic,
            dto.signedPreKey.signature.fromBase64(),
            identityKey,
            // Kyber (post-quantum) — вне scope, см. AndroidSignalProtocolStore. kyberPreKeyId/
            // kyberPreKeyPublic здесь опциональны (Option на нативной стороне в этой версии
            // библиотеки — см. комментарий у зависимости в build.gradle.kts), а вот подпись —
            // обязательный byte[] даже при отсутствии самого ключа, null туда нельзя.
            PreKeyBundle.NULL_PRE_KEY_ID,
            null,
            ByteArray(0)
        )

        SessionBuilder(store, store, store, store, address).process(bundle)
    }

    /** Шифрует plaintext для собеседника. Сессия должна быть уже установлена (ensureSessionWith). */
    fun encrypt(peerEmail: String, plaintext: String): EncryptedEnvelope {
        val cipher = SessionCipher(store, store, store, store, store, addressOf(peerEmail))
        val message = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val type = if (message.type == CiphertextMessage.PREKEY_TYPE) "prekey" else "signal"
        return EncryptedEnvelope(message.serialize().toBase64(), type)
    }

    /** Расшифровывает входящее сообщение от [senderEmail]. Пробрасывает исключения libsignal как есть. */
    fun decrypt(senderEmail: String, ciphertextBase64: String, messageType: String): String {
        val cipher = SessionCipher(store, store, store, store, store, addressOf(senderEmail))
        val bytes = ciphertextBase64.fromBase64()
        val plaintext = when (messageType) {
            "prekey" -> cipher.decrypt(PreKeySignalMessage(bytes))
            "signal" -> cipher.decrypt(SignalMessage(bytes))
            else -> error("Неизвестный message_type: $messageType")
        }
        return String(plaintext, Charsets.UTF_8)
    }

    // -------------------------------------------------------------------
    // Шаг 6 — фоновая ротация one-time prekeys
    // -------------------------------------------------------------------

    /**
     * Опционально вызывается при активности в dm-чатах (см. ChatViewModel.send()).
     * Не периодическая задача (WorkManager и т.п. — вне scope этой задачи), а
     * оппортунистическая проверка "раз уж всё равно обращаемся к серверу".
     */
    suspend fun topUpOneTimePreKeysIfNeeded() = withContext(Dispatchers.IO) {
        if (!store.hasLocalIdentity()) return@withContext
        val token = session.currentToken() ?: return@withContext

        val remaining = api.getKeyCount(token).body()?.oneTimePreKeys ?: return@withContext
        if (remaining >= ONE_TIME_PREKEY_LOW_WATERMARK) return@withContext

        val identityKeyPair = store.identityKeyPair
        val signedId = store.currentSignedPreKeyId()
        val signedRecord = store.loadSignedPreKey(signedId)
        val signedEntry = SignedPreKeyEntryDto(
            signedId,
            signedRecord.keyPair.publicKey.serialize().toBase64(),
            signedRecord.signature.toBase64()
        )
        val freshOneTimeEntries = generateAndStoreOneTimePreKeys(ONE_TIME_PREKEY_BATCH)

        api.publishKeys(
            token,
            PublishKeysRequest(
                registrationId = store.localRegistrationId,
                identityKey = identityKeyPair.publicKey.serialize().toBase64(),
                signedPreKey = signedEntry,
                oneTimePreKeys = freshOneTimeEntries
            )
        )
    }
}
