package com.example.messenger.data.signal

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/** deviceId, с которым мы всегда адресуем и себя, и собеседников — multi-device вне scope. */
const val SIGNAL_DEVICE_ID = 1

/**
 * Реализация Signal Protocol store-интерфейсов поверх Room (см. SignalDatabase).
 *
 * Собраны в одном классе, а не в SignalProtocolStore (объединяющем маркер-интерфейсе
 * из libsignal), потому что тот требует ещё и SenderKeyStore (групповые чаты — вне
 * scope) — вместо этого SessionBuilder/SessionCipher создаются через конструкторы,
 * принимающие стор-интерфейсы по отдельности (см. Шаг 5).
 *
 * KyberPreKeyStore реализован тривиально (всегда пустой): SessionCipher в этой версии
 * библиотеки требует его пятым параметром конструктора независимо от того, используется
 * ли post-quantum (Kyber) ключевой обмен, а PQXDH явно вне scope задачи — мы никогда не
 * генерируем и не публикуем Kyber-ключи, поэтому store просто никогда ничего не хранит.
 */
class AndroidSignalProtocolStore(
    private val dao: SignalKeyDao
) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, SessionStore, KyberPreKeyStore {

    // -------------------------------------------------------------------
    // IdentityKeyStore
    // -------------------------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val row = dao.getLocalIdentity()
            ?: error("Локальный identity key ещё не сгенерирован — см. registerSignalIdentity() после verify-code")
        return IdentityKeyPair(row.identityKeyPair)
    }

    override fun getLocalRegistrationId(): Int {
        val row = dao.getLocalIdentity()
            ?: error("Локальный identity key ещё не сгенерирован — см. registerSignalIdentity() после verify-code")
        return row.registrationId
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val newBytes = identityKey.serialize()
        val existing = dao.getRemoteIdentityKey(address.name, address.deviceId)
        val changed = existing != null && !existing.contentEquals(newBytes)
        dao.upsertRemoteIdentity(RemoteIdentityEntity(address.name, address.deviceId, newBytes))
        return if (changed) IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        else IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        // Trust-on-first-use: если мы ещё не видели identity этого адреса — доверяем
        // (и saveIdentity() его запомнит). Если видели, но ключ ДРУГОЙ — не доверяем;
        // это ровно тот случай, из-за которого SessionBuilder/SessionCipher бросают
        // UntrustedIdentityException (см. обработку в Шаге 5). Сверка safety number
        // через UI сюда не входит (по scope), но сам механизм TOFU — часть протокола,
        // не UI-фича, поэтому реализован как есть.
        val existing = dao.getRemoteIdentityKey(address.name, address.deviceId) ?: return true
        return existing.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val bytes = dao.getRemoteIdentityKey(address.name, address.deviceId) ?: return null
        return IdentityKey(bytes)
    }

    // -------------------------------------------------------------------
    // PreKeyStore (наши one-time prekeys)
    // -------------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val bytes = dao.getPreKey(preKeyId) ?: throw InvalidKeyIdException("Нет one-time prekey с id=$preKeyId")
        return PreKeyRecord(bytes)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        dao.insertPreKey(PreKeyEntity(preKeyId, record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = dao.getPreKey(preKeyId) != null

    override fun removePreKey(preKeyId: Int) {
        dao.deletePreKey(preKeyId)
    }

    /** Не часть PreKeyStore — используется Шагом 6 (фоновая ротация) для проверки остатка. */
    fun countOwnOneTimePreKeys(): Int = dao.countPreKeys()

    // -------------------------------------------------------------------
    // SignedPreKeyStore (наш signed prekey)
    // -------------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val bytes = dao.getSignedPreKey(signedPreKeyId)
            ?: throw InvalidKeyIdException("Нет signed prekey с id=$signedPreKeyId")
        return SignedPreKeyRecord(bytes)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        dao.getAllSignedPreKeys().map { SignedPreKeyRecord(it) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        dao.insertSignedPreKey(SignedPreKeyEntity(signedPreKeyId, record.serialize()))
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = dao.getSignedPreKey(signedPreKeyId) != null

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        dao.deleteSignedPreKey(signedPreKeyId)
    }

    // -------------------------------------------------------------------
    // SessionStore
    // -------------------------------------------------------------------

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val bytes = dao.getSession(address.name, address.deviceId)
        // Контракт SessionStore: если сессии нет — вернуть свежую пустую запись
        // (а не null/исключение), из неё SessionBuilder и строит новую сессию.
        return if (bytes != null) SessionRecord(bytes) else SessionRecord()
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        addresses.map { address ->
            val bytes = dao.getSession(address.name, address.deviceId)
                ?: throw NoSessionException(address, "Нет сохранённой сессии для $address")
            SessionRecord(bytes)
        }.toMutableList()

    override fun getSubDeviceSessions(name: String): MutableList<Int> =
        mutableListOf() // одно устройство на пользователя — саб-девайсов не бывает

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        dao.upsertSession(SessionEntity(address.name, address.deviceId, record.serialize()))
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        dao.getSession(address.name, address.deviceId) != null

    override fun deleteSession(address: SignalProtocolAddress) {
        dao.deleteSession(address.name, address.deviceId)
    }

    override fun deleteAllSessions(name: String) {
        dao.deleteAllSessionsForName(name)
    }

    // -------------------------------------------------------------------
    // KyberPreKeyStore — тривиальная реализация, см. комментарий класса
    // -------------------------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        throw InvalidKeyIdException("Kyber prekeys не используются в этом приложении")

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = mutableListOf()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = Unit

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = false

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int) = Unit

    // -------------------------------------------------------------------
    // Вспомогательное для Шага 4 (генерация ключей после verify-code)
    // -------------------------------------------------------------------

    fun hasLocalIdentity(): Boolean = dao.getLocalIdentity() != null

    fun saveLocalIdentity(identityKeyPair: IdentityKeyPair, registrationId: Int) {
        dao.insertLocalIdentity(LocalIdentityEntity(0, identityKeyPair.serialize(), registrationId))
    }

    /** true только после того, как /api/keys/publish подтвердил приём ключей — см. LocalIdentityEntity.keysPublished. */
    fun isKeysPublished(): Boolean = dao.getLocalIdentity()?.keysPublished ?: false

    fun markKeysPublished() {
        dao.markKeysPublished()
    }

    fun currentSignedPreKeyId(): Int =
        dao.getLocalIdentity()?.currentSignedPreKeyId
            ?: error("Локальный identity key ещё не сгенерирован")

    fun setCurrentSignedPreKeyId(id: Int) {
        dao.updateCurrentSignedPreKeyId(id)
    }

    /**
     * Резервирует [count] следующих свободных id для one-time prekeys и сдвигает
     * счётчик — id никогда не переиспользуются (см. комментарий у nextPreKeyId).
     */
    fun reserveOneTimePreKeyIds(count: Int): IntRange {
        val row = dao.getLocalIdentity() ?: error("Локальный identity key ещё не сгенерирован")
        val start = row.nextPreKeyId
        dao.updateNextPreKeyId(start + count)
        return start until (start + count)
    }
}
