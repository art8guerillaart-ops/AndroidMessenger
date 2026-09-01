package com.example.messenger.ui.chatlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.data.api.ApiService
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.model.ChatCreateRequest
import com.example.messenger.data.model.ChatDto
import com.example.messenger.data.model.DmRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatListUiState(
    val chats: List<ChatDto> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val dmError: String? = null,
    val groupError: String? = null,
    val deleteChatError: String? = null,
    val query: String = "",
    val sessionExpired: Boolean = false
) {
    val filteredChats: List<ChatDto>
        get() = if (query.isBlank()) chats
        else chats.filter { it.title.contains(query, ignoreCase = true) }
}

class ChatListViewModel(
    private val api: ApiService,
    private val session: SessionManager
) : ViewModel() {

    companion object {
        private const val TAG = "ChatListViewModel"
    }

    private val _state = MutableStateFlow(ChatListUiState())
    val state: StateFlow<ChatListUiState> = _state.asStateFlow()

    fun onQueryChange(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun loadChats() {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { api.getChats(token) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        _state.value = _state.value.copy(
                            chats = response.body().orEmpty(),
                            loading = false
                        )
                    } else {
                        Log.d(TAG, "loadChats: HTTP ${response.code()} — ${response.errorBody()?.string()}")
                        if (response.code() == 401) {
                            // Токен невалиден/истёк (сервер вернул 401) — разлогиниваем
                            // на клиенте, иначе экран навечно виснет на ошибке загрузки
                            // без выхода к логину.
                            session.clear()
                            _state.value = _state.value.copy(loading = false, sessionExpired = true)
                        } else {
                            _state.value = _state.value.copy(loading = false, error = "Не удалось загрузить чаты")
                        }
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = "Нет соединения с сервером")
                }
        }
    }

    fun startDm(peerEmail: String, onCreated: (chatId: String, title: String) -> Unit) {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(dmError = null)
            runCatching { api.startDm(token, DmRequest(peerEmail)) }
                .onSuccess { response ->
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        val chatId = body["chat_id"].orEmpty()
                        val title = body["title"].orEmpty()
                        loadChats()
                        onCreated(chatId, title)
                    } else {
                        _state.value = _state.value.copy(dmError = "Пользователь с таким email ещё не регистрировался")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(dmError = "Нет соединения с сервером")
                }
        }
    }

    fun createGroup(title: String, onCreated: (chatId: String, title: String) -> Unit) {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(groupError = null)
            val chatId = "chat_" + title.replace(Regex("[^a-zA-Z0-9]"), "_") + "_" + System.currentTimeMillis()
            runCatching { api.createChat(token, ChatCreateRequest(chatId, title)) }
                .onSuccess { response ->
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        val realId = body["chat_id"] ?: chatId
                        loadChats()
                        onCreated(realId, title)
                    } else {
                        _state.value = _state.value.copy(groupError = "Не удалось создать чат")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(groupError = "Нет соединения с сервером")
                }
        }
    }

    /** Для DM удаляет переписку целиком у обоих участников (см. DELETE /api/chats/{id} в main.py). */
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(deleteChatError = null)
            runCatching { api.deleteChat(token, chatId) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        loadChats()
                    } else {
                        _state.value = _state.value.copy(deleteChatError = "Не удалось удалить чат")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(deleteChatError = "Нет соединения с сервером")
                }
        }
    }
}
