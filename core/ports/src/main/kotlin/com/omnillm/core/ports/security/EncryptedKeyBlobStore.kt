package com.omnillm.core.ports.security

import java.util.concurrent.ConcurrentHashMap

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
