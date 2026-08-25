package com.example.messenger.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger.data.api.ApiService
import com.example.messenger.data.local.PreferencesManager
import com.example.messenger.data.local.SessionManager
import com.example.messenger.data.model.NicknameUpdateRequest
import com.example.messenger.notifications.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val email: String = "",
    val nicknameInput: String = "",
    val nicknameLoading: Boolean = false,
    val nicknameError: String? = null,
    val nicknameSaved: Boolean = false,
    val notificationSoundUri: String? = null,
    val vibrationEnabled: Boolean = true,
    val darkThemeEnabled: Boolean = false,
    val loggedOut: Boolean = false
)

class SettingsViewModel(
    private val api: ApiService,
    private val session: SessionManager,
    private val preferences: PreferencesManager,
    private val notificationHelper: NotificationHelper
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(email = session.currentEmail().orEmpty())
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                notificationSoundUri = preferences.currentNotificationSoundUri(),
                vibrationEnabled = preferences.currentVibrationEnabled(),
                darkThemeEnabled = preferences.currentDarkThemeEnabled()
            )
        }
        loadProfile()
    }

    fun onDarkThemeToggle(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setDarkThemeEnabled(enabled)
            _state.value = _state.value.copy(darkThemeEnabled = enabled)
        }
    }

    fun onNotificationSoundPicked(uri: Uri?) {
        viewModelScope.launch {
            val uriString = uri?.toString()
            preferences.setNotificationSoundUri(uriString)
            notificationHelper.applyChannelSettings(uri, _state.value.vibrationEnabled)
            _state.value = _state.value.copy(notificationSoundUri = uriString)
        }
    }

    fun onVibrationToggle(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setVibrationEnabled(enabled)
            val soundUri = _state.value.notificationSoundUri?.let(Uri::parse)
            notificationHelper.applyChannelSettings(soundUri, enabled)
            _state.value = _state.value.copy(vibrationEnabled = enabled)
        }
    }

    private fun loadProfile() {
        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            runCatching { api.getProfile(token) }
                .onSuccess { response ->
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        _state.value = _state.value.copy(nicknameInput = body.nickname.orEmpty())
                    }
                }
        }
    }

    fun onNicknameInputChange(value: String) {
        _state.value = _state.value.copy(nicknameInput = value, nicknameError = null, nicknameSaved = false)
    }

    fun saveNickname() {
        val value = _state.value.nicknameInput.trim()
        if (value.isEmpty()) {
            _state.value = _state.value.copy(nicknameError = "Введите никнейм")
            return
        }
        if (value.length > 30) {
            _state.value = _state.value.copy(nicknameError = "Максимум 30 символов")
            return
        }

        viewModelScope.launch {
            val token = session.currentToken() ?: return@launch
            _state.value = _state.value.copy(nicknameLoading = true, nicknameError = null, nicknameSaved = false)
            runCatching { api.updateProfile(token, NicknameUpdateRequest(value)) }
                .onSuccess { response ->
                    val body = response.body()
                    if (response.isSuccessful && body != null) {
                        _state.value = _state.value.copy(
                            nicknameInput = body.nickname.orEmpty(),
                            nicknameLoading = false,
                            nicknameSaved = true
                        )
                    } else {
                        _state.value = _state.value.copy(nicknameLoading = false, nicknameError = "Не удалось сохранить никнейм")
                    }
                }
                .onFailure {
                    _state.value = _state.value.copy(nicknameLoading = false, nicknameError = "Нет соединения с сервером")
                }
        }
    }

    fun logout() {
        viewModelScope.launch {
            val token = session.currentToken()
            if (token != null) runCatching { api.logout(token) }
            session.clear()
            _state.value = _state.value.copy(loggedOut = true)
        }
    }
}
