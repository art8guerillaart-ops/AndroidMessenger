package com.example.messenger.data.signal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Собственная identity key пара устройства + registration id.
 * Одна строка на приложение (id всегда 0) — одно устройство на пользователя,
 * без multi-device.
 */
@Entity(tableName = "local_identity")
data class LocalIdentityEntity(
    @PrimaryKey val id: Int = 0,
    val identityKeyPair: ByteArray,
    val registrationId: Int,
    // Следующий свободный id для one-time prekey — монотонный счётчик, а не
    // "максимум среди хранящихся сейчас": свои one-time prekeys удаляются
    // локально по мере использования (см. SessionCipher.decrypt), поэтому
    // выводить следующий id из текущего набора строк нельзя — это привело бы
    // к повторной выдаче id, который уже опубликован и, возможно, ещё не
    // израсходован на сервере.
    val nextPreKeyId: Int = 1,
    val currentSignedPreKeyId: Int = 1,
    // false, пока /api/keys/publish не подтвердил приём ключей сервером — см.
    // AndroidSignalProtocolStore.isKeysPublished()/markKeysPublished() и
    // SignalRepository.registerIfNeeded(): identity генерируется и сохраняется
    // локально ДО сетевого вызова (это нужно PreKeyStore/SignedPreKeyStore уже
    // во время генерации), поэтому одного hasLocalIdentity() недостаточно, чтобы
    // считать регистрацию завершённой — если publishKeys() не долетел (обрыв
    // сети и т.п.), при следующем запуске нужно повторить попытку, а не молча
    // считать пользователя уже зарегистрированным навсегда.
    val keysPublished: Boolean = false
)

/**
 * Identity key собеседников, которые мы когда-либо видели — нужен для
 * trust-on-first-use проверки в isTrustedIdentity()/saveIdentity().
 * deviceId у нас всегда 1 (см. SignalAddress.DEVICE_ID), но храним его явно,
 * так как этого требует форма адреса SignalProtocolAddress.
 */
@Entity(tableName = "remote_identities", primaryKeys = ["name", "deviceId"])
data class RemoteIdentityEntity(
    val name: String,
    val deviceId: Int,
    val identityKey: ByteArray
)

/** Наши собственные one-time prekeys (то, что мы сгенерировали и опубликовали). */
@Entity(tableName = "prekeys")
data class PreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray
)

/** Наш собственный (текущий и, возможно, предыдущий) signed prekey. */
@Entity(tableName = "signed_prekeys")
data class SignedPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray
)

/** Сессия Double Ratchet с конкретным адресом (email + deviceId) собеседника. */
@Entity(tableName = "sessions", primaryKeys = ["name", "deviceId"])
data class SessionEntity(
    val name: String,
    val deviceId: Int,
    val record: ByteArray
)
