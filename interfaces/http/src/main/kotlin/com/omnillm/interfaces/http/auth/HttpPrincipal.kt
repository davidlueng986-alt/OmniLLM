package com.omnillm.interfaces.http.auth

/**
 * Authenticated HTTP principal (SEC-AUTH-NET §2, access-control-catalog HTTP_LOOPBACK / HTTP_LOCAL_ADMIN).
 * Built only after token verification — never from caller self-reported headers alone.
 */
data class HttpPrincipal(
    /** Stable subject id bound to the token (client / token subject). */
    val principalId: String,
    /** Opaque token id (not plaintext). */
    val tokenId: String,
    /** Granted scopes from access-control-catalog. */
    val scopes: Set<String>,
    /** Bound revocation epoch at authentication time. */
    val revocationEpoch: Long,
    /** True when token is loopback-only (must not be accepted on LAN listener). */
    val loopbackOnly: Boolean = true,
    /** Optional client registration id. */
    val clientId: String? = null,
) {
    init {
        require(principalId.isNotBlank()) { "principalId must be non-blank" }
        require(tokenId.isNotBlank()) { "tokenId must be non-blank" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }

    fun hasScope(required: String): Boolean {
        if ("*" in scopes) return true
        return required in scopes
    }
}

/**
 * Transport-facing token authentication port (SEC-PROFILE bearer verifier).
 * Implementations live in the control plane (HMAC verifier, constant-time compare).
 * Gateway never stores plaintext tokens.
 */
fun interface TokenAuthenticator {
    /**
     * Validate bearer secret presented on the wire.
     * @return principal on success; null when invalid/expired/revoked (map to UNAUTHORIZED).
     */
    fun authenticate(bearerToken: String, transport: HttpTransportKind): AuthResult
}

enum class HttpTransportKind {
    /** Cleartext literal loopback (127.0.0.1 / ::1). */
    LOOPBACK,
    /** TLS 1.3 LAN listener. */
    LAN_TLS13,
}

sealed class AuthResult {
    data class Ok(val principal: HttpPrincipal) : AuthResult()
    data class Unauthorized(val message: String = "invalid or missing bearer token") : AuthResult()
    data class Forbidden(
        val message: String,
        val details: Map<String, String> = emptyMap(),
    ) : AuthResult()
}
