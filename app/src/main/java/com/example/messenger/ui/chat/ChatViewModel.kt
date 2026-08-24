package com.example.messenger.ui.chat

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.data.api.ApiService
import com.example.messenger.data.api.WebSocketClient
import com.example.messenger.data.api.WsEvent
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.model.MessageDto
import com.example.messenger.data.model.ParticipantDto
import com.example.messenger.data.model.ParticipantRequest
import com.example.messenger.data.model.WsIncoming
import com.example.messenger.data.signal.SignalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.UntrustedIdentityException
import java.io.File

private const val ENCRYPTED_HISTORY_PLACEHOLDER =
    "🔒 Сообщение зашифровано (доступно только в момент получения)"
private const val DECRYPT_FAILED_PLACEHOLDER = "⚠️ Не удалось расшифровать сообщение"

data class ChatUiState(
    val title: String = "",
    val chatType: String = "public", // "public" | "dm" | "group"
    val messages: List<MessageDto> = emptyList(),
    val myEmail: String = "",
    val onlineCount: Int = 0,
    val loading: Boolean = true,
    val connectionError: String? = null,
    val draft: String = "",
    val uploading: Boolean = false,
    val participants: List<ParticipantDto> = emptyList(),
    val iAmAdmin: Boolean = false,
    val participantError: String? = null,
    val leftGroup: Boolean = false
)

class ChatViewModel(
    private val chatId: String,
    initialTitle: String,
    initialChatType: String,
    private val api: ApiService,
    private val session: SessionManager,
    private val webSocketClient: WebSocketClient,
    private val signalRepository: SignalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(title = initialTitle, chatType = initialChatType))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    // Для dm-чатов сервер отдаёт заголовок = email собеседника (см. GET /api/chats в main.py) —
    // так что initialTitle можно использовать напрямую как адрес для Signal-сессии.
    private val peerEmail: String = initialTitle

    private var nextTempId = -1

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(myEmail = session.currentEmail().orEmpty())
        }
        loadHistory()
        connectSocket()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            runCatching { api.getMessages(token, chatId) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        val isDm = _state.value.chatType == "dm"
                        // Историю dm-сообщений нельзя расшифровать постфактум: Signal-сессия
                        // продвигается только вперёд (forward secrecy), и даже собственные
                        // отправленные сообщения нельзя расшифровать своим же SessionCipher —
                        // это свойство протокола, а не недоработка. Полноценная история потребовала
                        // бы отдельного локального хранилища расшифрованных сообщений, что вне
                        // scope этой задачи — здесь просто плейсхолдер вместо шифротекста.
                        val messages = response.body().orEmpty().map { msg ->
                            if (isDm && msg.ciphertext != null) msg.copy(text = ENCRYPTED_HISTORY_PLACEHOLDER)
                            else msg
                        }
                        _state.value = _state.value.copy(messages = messages, loading = false)
                    } else {
                        _state.value = _state.value.copy(loading = false)
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, connectionError = "Не удалось загрузить историю")
                }
        }
    }

    private fun connectSocket() {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            webSocketClient.connect(chatId, token).collect { event ->
                when (event) {
                    is WsEvent.Message -> {
                        val incoming = event.data
                        if (incoming.type == "message") {
                            if (_state.value.chatType == "dm") {
                                handleIncomingDmMessage(incoming)
                            } else {
                                appendMessage(sender = incoming.sender.orEmpty(), text = incoming.text, fileUrl = incoming.fileUrl)
                            }
                        } else if (incoming.type == "presence") {
                            _state.value = _state.value.copy(onlineCount = incoming.onlineUsers?.size ?: 0)
                        }
                    }
                    is WsEvent.Failure -> {
                        _state.value = _state.value.copy(connectionError = event.message)
                    }
                    is WsEvent.Closed -> {
                        if (event.code == 4401) {
                            _state.value = _state.value.copy(connectionError = "Сессия истекла, войдите заново")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Своё же сообщение приходит обратно эхом от сервера (broadcast всем участникам чата,
     * включая отправителя) — для dm его НЕЛЬЗЯ расшифровать собственным SessionCipher
     * (шифрование и расшифровка используют разные, несимметричные цепочки ratchet).
     * Поэтому свои сообщения показываются оптимистично сразу при отправке (см. sendEncrypted()),
     * а здесь эхо от самого себя просто игнорируется.
     */
    private suspend fun handleIncomingDmMessage(incoming: WsIncoming) {
        val myEmail = _state.value.myEmail
        if (incoming.sender == myEmail) return

        val ciphertext = incoming.ciphertext
        val messageType = incoming.messageType
        val text = if (ciphertext != null && messageType != null) {
            val result = withContext(Dispatchers.IO) {
                runCatching { signalRepository.decrypt(incoming.sender.orEmpty(), ciphertext, messageType) }
            }
            result.onFailure { reportDecryptError(it) }
            result.getOrElse { DECRYPT_FAILED_PLACEHOLDER }
        } else null

        if (text == null && incoming.fileUrl == null) return
        appendMessage(sender = incoming.sender.orEmpty(), text = text, fileUrl = incoming.fileUrl)
    }

    private fun reportDecryptError(e: Throwable) {
        val reason = when (e) {
            is UntrustedIdentityException -> "identity собеседника изменилась — возможно, он переустановил приложение"
            is DuplicateMessageException -> "сообщение уже было обработано ранее (повтор/replay)"
            is InvalidKeyException -> "некорректный ключ в сообщении"
            else -> e.message ?: "неизвестная ошибка"
        }
        _state.value = _state.value.copy(connectionError = "Не удалось расшифровать сообщение: $reason")
    }

    private fun appendMessage(sender: String, text: String?, fileUrl: String?) {
        val msg = MessageDto(id = nextTempId--, sender = sender, text = text, fileUrl = fileUrl, time = "")
        _state.value = _state.value.copy(messages = _state.value.messages + msg)
    }

    fun onDraftChange(value: String) {
        _state.value = _state.value.copy(draft = value)
    }

    fun send() {
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        _state.value = _state.value.copy(draft = "")

        if (_state.value.chatType == "dm") {
            viewModelScope.launch { sendEncrypted(text, fileUrl = null) }
        } else {
            webSocketClient.send(text)
        }
    }

    /** Шифрует и отправляет текст (сообщение или имя файла) для dm-чата, затем показывает его локально. */
    private suspend fun sendEncrypted(text: String, fileUrl: String?) {
        try {
            withContext(Dispatchers.IO) {
                signalRepository.ensureSessionWith(peerEmail)
                val envelope = signalRepository.encrypt(peerEmail, text)
                webSocketClient.sendEncrypted(envelope.ciphertextBase64, envelope.messageType, fileUrl)
            }
            appendMessage(sender = _state.value.myEmail, text = text, fileUrl = fileUrl)
            // Оппортунистическая проверка остатка one-time prekeys (Шаг 6) — раз уж всё
            // равно есть повод обратиться к серверу. Не блокирует отправку при неудаче.
            runCatching { signalRepository.topUpOneTimePreKeysIfNeeded() }
        } catch (e: UntrustedIdentityException) {
            _state.value = _state.value.copy(connectionError = "Identity собеседника изменилась — сообщение не отправлено")
        } catch (e: InvalidKeyException) {
            _state.value = _state.value.copy(connectionError = "Некорректный ключ собеседника — сообщение не отправлено")
        } catch (e: Exception) {
            _state.value = _state.value.copy(connectionError = e.message ?: "Не удалось отправить сообщение")
        }
    }

    /**
     * Загружает файл (уже скопированный в кеш-файл вызывающей стороной, см. ChatScreen)
     * через POST /api/upload, затем шлёт результат в чат — как file.name + file_url,
     * повторяя логику uploadAndSendFile() из веб-версии. Для dm имя файла шифруется так же,
     * как обычное сообщение; сам файл (по ссылке file_url) — нет, это вне scope задачи.
     */
    fun uploadAndSend(file: File, mimeType: String?, displayName: String) {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(uploading = true)
            val mediaType = (mimeType ?: "application/octet-stream").toMediaTypeOrNull()
            val body = file.asRequestBody(mediaType)
            val part = MultipartBody.Part.createFormData("file", displayName, body)

            runCatching { api.uploadFile(token, part) }
                .onSuccess { response ->
                    val result = response.body()
                    if (response.isSuccessful && result != null) {
                        if (_state.value.chatType == "dm") {
                            sendEncrypted(result.filename, result.fileUrl)
                        } else {
                            webSocketClient.send(text = result.filename, fileUrl = result.fileUrl)
                        }
                    } else {
                        _state.value = _state.value.copy(connectionError = "Не удалось загрузить файл")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(connectionError = "Не удалось загрузить файл")
                }
            _state.value = _state.value.copy(uploading = false)
        }
    }

    fun loadParticipants() {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            val me = session.currentEmail().orEmpty()
            runCatching { api.getParticipants(token, chatId) }
                .onSuccess { response ->
                    val list = response.body().orEmpty()
                    _state.value = _state.value.copy(
                        participants = list,
                        iAmAdmin = list.any { it.email == me && it.isAdmin }
                    )
                }
        }
    }

    fun addParticipant(email: String) {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(participantError = null)
            runCatching { api.addParticipant(token, chatId, ParticipantRequest(email)) }
                .onSuccess { response ->
                    if (response.isSuccessful) loadParticipants()
                    else _state.value = _state.value.copy(participantError = "Не удалось добавить участника")
                }
                .onFailure {
                    _state.value = _state.value.copy(participantError = "Нет соединения с сервером")
                }
        }
    }

    fun removeParticipant(email: String) {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            val isSelf = email == _state.value.myEmail
            runCatching { api.removeParticipant(token, chatId, email) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        if (isSelf) {
                            _state.value = _state.value.copy(leftGroup = true)
                        } else {
                            loadParticipants()
                        }
                    } else {
                        _state.value = _state.value.copy(participantError = "Не удалось удалить участника")
                    }
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.disconnect()
    }
}
