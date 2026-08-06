package com.omnillm.runtime.policy.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Durable access-token verifier store (SEC-AUTH-NET §2 / SEC-PROFILE §3).
 *
 * Stores HMAC-SHA-256 verifiers + metadata only — never bearer plaintext.
 * Production: SQLDelight / SQLite via control-plane sole writer (ADR-010).
 * Tests: [InMemoryAccessTokenStore].
 */
interface AccessTokenStore {
    fun get(tokenId: String): TokenService.AccessTokenRecord?

    fun listAll(): List<TokenService.AccessTokenRecord>

    /** Insert or replace the full durable record (verifier + state + epochs). */
    fun upsert(record: TokenService.AccessTokenRecord)

    fun delete(tokenId: String): Boolean
}

/** Process-local token store (unit tests / bootstrap until SQLite attaches). */
class InMemoryAccessTokenStore : AccessTokenStore {
    private val tokens = ConcurrentHashMap<String, TokenService.AccessTokenRecord>()

    override fun get(tokenId: String): TokenService.AccessTokenRecord? = tokens[tokenId]

    override fun listAll(): List<TokenService.AccessTokenRecord> =
        tokens.values.toList()

    override fun upsert(record: TokenService.AccessTokenRecord) {
        tokens[record.tokenId] = record
    }

    override fun delete(tokenId: String): Boolean = tokens.remove(tokenId) != null

    fun clear() {
        tokens.clear()
    }
}
