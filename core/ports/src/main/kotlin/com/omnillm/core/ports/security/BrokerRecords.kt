package com.omnillm.core.ports.security

/**
 * Public metadata of a purpose-scoped secret-broker key — no key material.
 * Port shape shared by `:runtime:policy` (Secret Broker) and `:data:persistence`
 * (SQLite `secret_broker_keys` adapter).
 */
data class BrokerKeyMetadata(
    val purpose: SecurityProfile.KeyPurpose,
    val keyVersion: Int,
    val state: SecurityProfile.KeyState,
    val createdAtEpochMs: Long,
    val rotationReason: String?,
)

/**
 * Encrypted classified record (AES-256-GCM envelope metadata + ciphertext).
 *
 * Never stores plaintext. Used for pending LAN pairing secrets, TLS key wraps
 * and AI content-report draft queues (FEAT-AI-CONTENT-REPORT §6). The binary
 * envelope codec (`EncryptedRecordCodec`) lives in `:runtime:policy`;
 * SQLite adapters in `:data:persistence` persist the fields directly.
 */
data class EncryptedRecord(
    val profileId: String,
    val recordType: String,
    val recordId: String,
    val schemaVersion: Int,
    val keyVersion: Int,
    val expiresAtEpochMs: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)
