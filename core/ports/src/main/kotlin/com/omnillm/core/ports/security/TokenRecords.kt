package com.omnillm.core.ports.security

import com.omnillm.core.state.generated.StateMachines

/**
 * Durable access-token verifier record (SEC-AUTH-NET §2 / SEC-PROFILE §3).
 *
 * Holds HMAC-SHA-256 verifiers + metadata only — never bearer plaintext.
 * Port shape shared by `:runtime:policy` (TokenService) and `:data:persistence`
 * (SQLite adapter). The in-memory variant is for tests / bootstrap.
 */
data class AccessTokenRecord(
    val tokenId: String,
    val registrationId: String,
    val principalId: String,
    val state: String,
    val verifier: ByteArray,
    val verifierAlgorithm: String,
    val verifierKeyVersion: Int,
    val transportConstraint: TransportConstraint,
    val scopes: Set<String>,
    val revocationEpoch: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val label: String?,
    val clientId: String?,
    val updatedAtEpochMs: Long,
    val lastSeenAtEpochMs: Long? = null,
) {
    init {
        require(tokenId.isNotBlank())
        require(StateMachines.TOKEN.isKnownState(state)) { "unknown TOKEN state: $state" }
        require(verifier.isNotEmpty())
        require(scopes.isNotEmpty())
        require(revocationEpoch >= 0L)
        require(expiresAtEpochMs > issuedAtEpochMs)
    }

    /** Metadata safe for list/admin — no verifier/plaintext. */
    fun metadata(): TokenMetadata =
        TokenMetadata(
            tokenId = tokenId,
            registrationId = registrationId,
            principalId = principalId,
            state = state,
            transportConstraint = transportConstraint,
            scopes = scopes,
            revocationEpoch = revocationEpoch,
            issuedAtEpochMs = issuedAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            label = label,
            clientId = clientId,
            lastSeenAtEpochMs = lastSeenAtEpochMs,
        )
}

data class TokenMetadata(
    val tokenId: String,
    val registrationId: String,
    val principalId: String,
    val state: String,
    val transportConstraint: TransportConstraint,
    val scopes: Set<String>,
    val revocationEpoch: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val label: String?,
    val clientId: String?,
    val lastSeenAtEpochMs: Long?,
)

enum class TransportConstraint {
    /** Loopback admin / developer — never accepted on LAN listener. */
    LOOPBACK_ONLY,

    /** Channel-bound LAN pairing issued tokens. */
    LAN_ONLY,
}
