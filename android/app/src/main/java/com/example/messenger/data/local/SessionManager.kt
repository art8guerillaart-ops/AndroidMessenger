package com.example.messenger.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "session")

/**
 * Хранит токен сессии (X-Session-Token) и email пользователя между запусками приложения,
 * аналог того, как веб-версия хранит их в localStorage/переменных на клиенте.
 */
class SessionManager(private val context: Context) {

    private val tokenKey = stringPreferencesKey("session_token")
    private val emailKey = stringPreferencesKey("user_email")

    val tokenFlow: Flow<String?> = context.dataStore.data.map { it[tokenKey] }
    val emailFlow: Flow<String?> = context.dataStore.data.map { it[emailKey] }

    suspend fun currentToken(): String? = tokenFlow.first()
    suspend fun currentEmail(): String? = emailFlow.first()

    suspend fun save(token: String, email: String) {
        context.dataStore.edit {
            it[tokenKey] = token
            it[emailKey] = email
        }
    }

    suspend fun clear() {
        context.dataStore.edit {
            it.remove(tokenKey)
            it.remove(emailKey)
        }
    }
}
