package com.example.messenger.data.signal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Все методы синхронные (не suspend) — так же, как и методы Signal Protocol
 * store-интерфейсов (IdentityKeyStore/PreKeyStore/...), которые libsignal
 * вызывает напрямую, без корутин. Вызывать эти методы можно только не с
 * главного потока — вызывающая сторона (SessionBuilder/SessionCipher в
 * Шаге 5) должна работать на Dispatchers.IO.
 */
@Dao
interface SignalKeyDao {

    @Query("SELECT * FROM local_identity WHERE id = 0")
    fun getLocalIdentity(): LocalIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLocalIdentity(entity: LocalIdentityEntity)

    @Query("UPDATE local_identity SET nextPreKeyId = :value WHERE id = 0")
    fun updateNextPreKeyId(value: Int)

    @Query("UPDATE local_identity SET currentSignedPreKeyId = :value WHERE id = 0")
    fun updateCurrentSignedPreKeyId(value: Int)

    @Query("SELECT identityKey FROM remote_identities WHERE name = :name AND deviceId = :deviceId")
    fun getRemoteIdentityKey(name: String, deviceId: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRemoteIdentity(entity: RemoteIdentityEntity)

    @Query("SELECT record FROM prekeys WHERE keyId = :keyId")
    fun getPreKey(keyId: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPreKey(entity: PreKeyEntity)

    @Query("DELETE FROM prekeys WHERE keyId = :keyId")
    fun deletePreKey(keyId: Int)

    @Query("SELECT COUNT(*) FROM prekeys")
    fun countPreKeys(): Int

    @Query("SELECT record FROM signed_prekeys WHERE keyId = :keyId")
    fun getSignedPreKey(keyId: Int): ByteArray?

    @Query("SELECT record FROM signed_prekeys")
    fun getAllSignedPreKeys(): List<ByteArray>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSignedPreKey(entity: SignedPreKeyEntity)

    @Query("DELETE FROM signed_prekeys WHERE keyId = :keyId")
    fun deleteSignedPreKey(keyId: Int)

    @Query("SELECT record FROM sessions WHERE name = :name AND deviceId = :deviceId")
    fun getSession(name: String, deviceId: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSession(entity: SessionEntity)

    @Query("DELETE FROM sessions WHERE name = :name AND deviceId = :deviceId")
    fun deleteSession(name: String, deviceId: Int)

    @Query("DELETE FROM sessions WHERE name = :name")
    fun deleteAllSessionsForName(name: String)
}
