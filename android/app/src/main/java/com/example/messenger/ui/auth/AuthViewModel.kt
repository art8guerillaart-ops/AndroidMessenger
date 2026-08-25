package com.example.messenger.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.data.api.ApiService
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.model.EmailRequest
import com.example.messenger.data.model.VerifyRequest
import com.example.messenger.data.signal.SignalRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthStep { EMAIL, CODE }

data class AuthUiState(
    val step: AuthStep = AuthStep.EMAIL,
    val email: String = "",
    val code: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val statusHint: String? = null,
    val loggedIn: Boolean = false
)

class AuthViewModel(
    private val api: ApiService,
    private val session: SessionManager,
    private val signalRepository: SignalRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) {
        _state.value = _state.value.copy(email = value, error = null)
    }

    fun onCodeChange(value: String) {
        _state.value = _state.value.copy(code = value, error = null)
    }

    fun sendCode() {
        val email = _state.value.email.trim()
        if (email.isEmpty()) {
            _state.value = _state.value.copy(error = "Введите email")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { api.sendCode(EmailRequest(email)) }
                .onSuccess { response ->
                    if (response.isSuccessful) {
                        _state.value = _state.value.copy(
                            loading = false,
                            step = AuthStep.CODE,
                            statusHint = null
                        )
                    } else {
                        _state.value = _state.value.copy(loading = false, error = "Не удалось отправить код")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = "Нет соединения с сервером")
                }
        }
    }

    fun verifyCode() {
        val email = _state.value.email.trim()
        val code = _state.value.code.trim()
        if (code.isEmpty()) {
            _state.value = _state.value.copy(error = "Введите код")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { api.verifyCode(VerifyRequest(email, code)) }
                .onSuccess { response ->
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        session.save(body.token, body.email)
                        // Генерация/публикация Signal-ключей не должна блокировать вход:
                        // если это не сработает сейчас, dm-переписки просто не смогут
                        // установить сессию, пока ключи не появятся — остальное приложение
                        // (публичные/групповые чаты) от этого не зависит.
                        runCatching { signalRepository.registerIfNeeded() }
                        _state.value = _state.value.copy(loading = false, loggedIn = true)
                    } else {
                        _state.value = _state.value.copy(loading = false, error = "Неверный код")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = "Нет соединения с сервером")
                }
        }
    }
}
