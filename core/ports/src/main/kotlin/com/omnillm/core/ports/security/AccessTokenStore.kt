package com.omnillm.core.ports.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Durable access-token verifier store (SEC-AUTH-NET §2 / SEC-PROFILE §3).
 *
 * Stores HMAC-SHA-256 verifiers + metadata only — never bearer plaintext.
 * Production: SQLDelight / SQLite via control-plane sole writer (ADR-010)
 * (`:data:persistence` adapter). Tests: [InMemoryAccessTokenStore].
 */
interface AccessTokenStore {
    fun get(tokenId: String): AccessTokenRecord?

    fun listAll(): List<AccessTokenRecord>

    /** Insert or replace the full durable record (verifier + state + epochs). */
    fun upsert(record: AccessTokenRecord)

    fun delete(tokenId: String): Boolean
}

/** Process-local token store (unit tests / bootstrap until SQLite attaches). */
class InMemoryAccessTokenStore : AccessTokenStore {
    private val tokens = ConcurrentHashMap<String, AccessTokenRecord>()

    override fun get(tokenId: String): AccessTokenRecord? = tokens[tokenId]

    override fun listAll(): List<AccessTokenRecord> =
        tokens.values.toList()

    override fun upsert(record: AccessTokenRecord) {
        tokens[record.tokenId] = record
    }

    override fun delete(tokenId: String): Boolean = tokens.remove(tokenId) != null

    fun clear() {
        tokens.clear()
    }
}
