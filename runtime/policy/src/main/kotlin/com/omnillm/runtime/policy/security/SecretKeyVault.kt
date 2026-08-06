package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Purpose-scoped Secret Broker key vault (SEC-PROFILE §5 / SEC-AUTH-NET §5).
 *
 * Production Android: [AndroidKeystoreSecretKeyVault] (non-exportable HMAC/AES).
 * JVM / tests: [InMemorySecretKeyVault] or [EncryptedBlobSecretKeyVault]
 * (AES-GCM wrapped key bytes + metadata; never log key material).
 *
 * Workers / UI / companion must never enumerate aliases or export key bytes.
 */
interface SecretKeyVault {
    fun active(purpose: SecurityProfile.KeyPurpose): BrokerKeyMaterial?

    fun get(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): BrokerKeyMaterial?

    fun installNew(
        purpose: SecurityProfile.KeyPurpose,
        reason: String,
        retirePrevious: Boolean,
    ): OmniResult<BrokerKeyMaterial>

    /** All known versions for a purpose (ACTIVE + RETIRED), never REVOKED for verify. */
    fun list(purpose: SecurityProfile.KeyPurpose): List<BrokerKeyMetadata>
}

/**
 * In-process key vault. Suitable for pure unit tests.
 * Not crash-durable — production must use Keystore or encrypted blob vault.
 */
class InMemorySecretKeyVault(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : SecretKeyVault {

    private val byPurpose = ConcurrentHashMap<SecurityProfile.KeyPurpose, MutableMap<Int, BrokerKeyMaterial>>()
    private val activeVersion = ConcurrentHashMap<SecurityProfile.KeyPurpose, Int>()
    private val versionSeq = AtomicInteger(0)
    private val lock = Any()

    override fun active(purpose: SecurityProfile.KeyPurpose): BrokerKeyMaterial? {
        val v = activeVersion[purpose] ?: return null
        val key = byPurpose[purpose]?.get(v) ?: return null
        return if (key.state == SecurityProfile.KeyState.ACTIVE) key else null
    }

    override fun get(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): BrokerKeyMaterial? {
        val key = byPurpose[purpose]?.get(keyVersion) ?: return null
        return if (key.state == SecurityProfile.KeyState.REVOKED) null else key
    }

    override fun installNew(
        purpose: SecurityProfile.KeyPurpose,
        reason: String,
        retirePrevious: Boolean,
    ): OmniResult<BrokerKeyMaterial> {
        if (reason.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "rotation reason required"))
        }
        synchronized(lock) {
            val map = byPurpose.getOrPut(purpose) { linkedMapOf() }
            if (retirePrevious) {
                activeVersion[purpose]?.let { prevV ->
                    map[prevV]?.let { prev ->
                        map[prevV] = prev.copy(
                            state = SecurityProfile.KeyState.RETIRED,
                            rotationReason = reason,
                        )
                    }
                }
            }
            val version = versionSeq.incrementAndGet()
            val material = BrokerKeyMaterial(
                purpose = purpose,
                keyVersion = version,
                state = SecurityProfile.KeyState.ACTIVE,
                createdAtEpochMs = clockMs(),
                rotationReason = reason,
                keyBytes = CryptoPrimitives.randomSecretKeyBytes(),
            )
            map[version] = material
            activeVersion[purpose] = version
            return OmniResult.ok(material)
        }
    }

    override fun list(purpose: SecurityProfile.KeyPurpose): List<BrokerKeyMetadata> =
        byPurpose[purpose]?.values?.map { it.metadata() }?.sortedBy { it.keyVersion }.orEmpty()
}

/**
 * Crash-durable key vault for non-Android hosts and JVM integration tests.
 *
 * Stores AES-GCM-wrapped key bytes via [EncryptedKeyBlobStore]. Master wrapping
 * key is process-supplied (Android Keystore wrapping key, or test CSPRNG seed).
 * DB / blob store holds ciphertext only — never plaintext key material in SQL.
 */
class EncryptedBlobSecretKeyVault(
    private val store: EncryptedKeyBlobStore,
    private val masterKeyBytes: ByteArray,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : SecretKeyVault {

    init {
        require(masterKeyBytes.size == SecurityProfile.SECRET_KEY_BYTES) {
            "master wrapping key must be ${SecurityProfile.SECRET_KEY_BITS}-bit"
        }
    }

    private val lock = Any()
    private val versionSeq = AtomicInteger(store.maxVersion())

    override fun active(purpose: SecurityProfile.KeyPurpose): BrokerKeyMaterial? {
        val meta = store.activeMetadata(purpose) ?: return null
        return loadMaterial(meta)
    }

    override fun get(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): BrokerKeyMaterial? {
        val meta = store.getMetadata(purpose, keyVersion) ?: return null
        if (meta.state == SecurityProfile.KeyState.REVOKED) return null
        return loadMaterial(meta)
    }

    override fun installNew(
        purpose: SecurityProfile.KeyPurpose,
        reason: String,
        retirePrevious: Boolean,
    ): OmniResult<BrokerKeyMaterial> {
        if (reason.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "rotation reason required"))
        }
        synchronized(lock) {
            if (retirePrevious) {
                store.activeMetadata(purpose)?.let { prev ->
                    store.upsert(
                        prev.copy(
                            state = SecurityProfile.KeyState.RETIRED,
                            rotationReason = reason,
                        ),
                        ciphertext = store.getCiphertext(purpose, prev.keyVersion)
                            ?: return OmniResult.err(
                                OmniError.FORBIDDEN(message = "previous key blob missing"),
                            ),
                    )
                }
            }
            val version = versionSeq.incrementAndGet()
            val raw = CryptoPrimitives.randomSecretKeyBytes()
            val nonce = CryptoPrimitives.randomGcmNonce()
            val aad = vaultAad(purpose, version)
            val ct = CryptoPrimitives.aesGcmEncrypt(masterKeyBytes, nonce, raw, aad)
            val wrapped = nonce + ct
            val material = BrokerKeyMaterial(
                purpose = purpose,
                keyVersion = version,
                state = SecurityProfile.KeyState.ACTIVE,
                createdAtEpochMs = clockMs(),
                rotationReason = reason,
                keyBytes = raw,
            )
            store.upsert(
                BrokerKeyMetadata(
                    purpose = purpose,
                    keyVersion = version,
                    state = SecurityProfile.KeyState.ACTIVE,
                    createdAtEpochMs = material.createdAtEpochMs,
                    rotationReason = reason,
                ),
                ciphertext = wrapped,
            )
            return OmniResult.ok(material)
        }
    }

    override fun list(purpose: SecurityProfile.KeyPurpose): List<BrokerKeyMetadata> =
        store.listMetadata(purpose)

    private fun loadMaterial(meta: BrokerKeyMetadata): BrokerKeyMaterial? {
        val wrapped = store.getCiphertext(meta.purpose, meta.keyVersion) ?: return null
        if (wrapped.size <= SecurityProfile.GCM_NONCE_BYTES) return null
        val nonce = wrapped.copyOfRange(0, SecurityProfile.GCM_NONCE_BYTES)
        val ct = wrapped.copyOfRange(SecurityProfile.GCM_NONCE_BYTES, wrapped.size)
        val aad = vaultAad(meta.purpose, meta.keyVersion)
        val raw = CryptoPrimitives.aesGcmDecrypt(masterKeyBytes, nonce, ct, aad) ?: return null
        return BrokerKeyMaterial(
            purpose = meta.purpose,
            keyVersion = meta.keyVersion,
            state = meta.state,
            createdAtEpochMs = meta.createdAtEpochMs,
            rotationReason = meta.rotationReason,
            keyBytes = raw,
        )
    }

    private fun vaultAad(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): ByteArray =
        "profileId=${SecurityProfile.PROFILE_ID}\npurpose=${purpose.name}\nkeyVersion=$keyVersion"
            .toByteArray(Charsets.UTF_8)
}

/**
 * Persistence port for encrypted key blobs (metadata + ciphertext).
 * Implementations live in `:data:persistence` / control-plane SQLite.
 */
interface EncryptedKeyBlobStore {
    fun activeMetadata(purpose: SecurityProfile.KeyPurpose): BrokerKeyMetadata?

    fun getMetadata(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): BrokerKeyMetadata?

    fun listMetadata(purpose: SecurityProfile.KeyPurpose): List<BrokerKeyMetadata>

    fun getCiphertext(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): ByteArray?

    fun upsert(metadata: BrokerKeyMetadata, ciphertext: ByteArray)

    fun maxVersion(): Int
}

/** In-memory encrypted blob store for unit tests of [EncryptedBlobSecretKeyVault]. */
class InMemoryEncryptedKeyBlobStore : EncryptedKeyBlobStore {
    private data class Entry(val meta: BrokerKeyMetadata, val ciphertext: ByteArray)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val active = ConcurrentHashMap<SecurityProfile.KeyPurpose, Int>()

    private fun key(purpose: SecurityProfile.KeyPurpose, version: Int) = "${purpose.name}:$version"

    override fun activeMetadata(purpose: SecurityProfile.KeyPurpose): BrokerKeyMetadata? {
        val v = active[purpose] ?: return null
        return entries[key(purpose, v)]?.meta
    }

    override fun getMetadata(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): BrokerKeyMetadata? =
        entries[key(purpose, keyVersion)]?.meta

    override fun listMetadata(purpose: SecurityProfile.KeyPurpose): List<BrokerKeyMetadata> =
        entries.values.map { it.meta }.filter { it.purpose == purpose }.sortedBy { it.keyVersion }

    override fun getCiphertext(purpose: SecurityProfile.KeyPurpose, keyVersion: Int): ByteArray? =
        entries[key(purpose, keyVersion)]?.ciphertext

    override fun upsert(metadata: BrokerKeyMetadata, ciphertext: ByteArray) {
        entries[key(metadata.purpose, metadata.keyVersion)] = Entry(metadata, ciphertext.copyOf())
        if (metadata.state == SecurityProfile.KeyState.ACTIVE) {
            active[metadata.purpose] = metadata.keyVersion
        }
    }

    override fun maxVersion(): Int =
        entries.values.maxOfOrNull { it.meta.keyVersion } ?: 0
}
