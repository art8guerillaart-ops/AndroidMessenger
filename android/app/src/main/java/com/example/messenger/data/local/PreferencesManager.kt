package com.example.messenger.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appPreferencesDataStore by preferencesDataStore(name = "app_preferences")

/**
 * Локальные настройки приложения — в отличие от SessionManager, не привязаны к аккаунту
 * и не очищаются при выходе (звук/вибрация уведомлений, тема и т.д.).
 */
class PreferencesManager(private val context: Context) {

    private val notificationSoundUriKey = stringPreferencesKey("notification_sound_uri")
    private val vibrationEnabledKey = booleanPreferencesKey("vibration_enabled")
    private val darkThemeEnabledKey = booleanPreferencesKey("dark_theme_enabled")
    private val mutedChatIdsKey = stringSetPreferencesKey("muted_chat_ids")

    val notificationSoundUriFlow: Flow<String?> =
        context.appPreferencesDataStore.data.map { it[notificationSoundUriKey] }
    val vibrationEnabledFlow: Flow<Boolean> =
        context.appPreferencesDataStore.data.map { it[vibrationEnabledKey] ?: true }
    val darkThemeEnabledFlow: Flow<Boolean> =
        context.appPreferencesDataStore.data.map { it[darkThemeEnabledKey] ?: false }
    val mutedChatIdsFlow: Flow<Set<String>> =
        context.appPreferencesDataStore.data.map { it[mutedChatIdsKey] ?: emptySet() }

    suspend fun currentNotificationSoundUri(): String? = notificationSoundUriFlow.first()
    suspend fun currentVibrationEnabled(): Boolean = vibrationEnabledFlow.first()
    suspend fun currentDarkThemeEnabled(): Boolean = darkThemeEnabledFlow.first()
    suspend fun isChatMuted(chatId: String): Boolean = mutedChatIdsFlow.first().contains(chatId)

    suspend fun setNotificationSoundUri(uri: String?) {
        context.appPreferencesDataStore.edit {
            if (uri != null) it[notificationSoundUriKey] = uri else it.remove(notificationSoundUriKey)
        }
    }

    suspend fun setVibrationEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { it[vibrationEnabledKey] = enabled }
    }

    suspend fun setDarkThemeEnabled(enabled: Boolean) {
        context.appPreferencesDataStore.edit { it[darkThemeEnabledKey] = enabled }
    }

    /** Переключает mute для чата и возвращает новое состояние (true — теперь замьючен). */
    suspend fun toggleChatMuted(chatId: String): Boolean {
        var nowMuted = false
        context.appPreferencesDataStore.edit { prefs ->
            val current = prefs[mutedChatIdsKey] ?: emptySet()
            nowMuted = chatId !in current
            prefs[mutedChatIdsKey] = if (nowMuted) current + chatId else current - chatId
        }
        return nowMuted
    }
}
