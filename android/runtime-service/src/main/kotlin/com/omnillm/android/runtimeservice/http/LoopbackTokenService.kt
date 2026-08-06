package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.runtime.policy.security.InMemorySecretBroker
import com.omnillm.runtime.policy.security.SecretBroker
import com.omnillm.runtime.policy.security.TokenService
import java.time.Instant

/**
 * Control-plane loopback token issuer / verifier (SEC-PROFILE, SEC-AUTH-NET §2).
 *
 * Delegates crypto and durable verifier storage to [TokenService] / [SecretBroker]:
 * - Bearer: 256-bit CSPRNG, base64url no padding
 * - Store: HMAC-SHA-256 verifier only (no plaintext)
 * - Constant-time compare on verify
 * - Loopback-only tokens rejected on LAN by transport constraint
 * - One-time plaintext display path via Secret Broker issuance receipt
 *
 * Production: inject plane [TokenService] from [PolicyModule.SecurityStack]
 * (Keystore-wrapped vault + SQLite verifiers). Default [InMemorySecretBroker]
 * is for unit tests only.
 */
class LoopbackTokenService(
    private val clock: () -> Instant = { Instant.now() },
    secretBroker: SecretBroker = InMemorySecretBroker { clock().toEpochMilli() },
    tokenService: TokenService? = null,
) : TokenAuthenticator {

    private val broker: SecretBroker = secretBroker
    private val tokens: TokenService = tokenService
        ?: TokenService(
            broker = broker,
            clockMs = { clock().toEpochMilli() },
        )

    data class TokenRecord(
        val tokenId: String,
        val principalId: String,
        val clientId: String?,
        val scopes: Set<String>,
        val expiresAt: Instant,
        val loopbackOnly: Boolean,
        val label: String?,
        val revoked: Boolean,
        val issuedAt: Instant,
        val revocationEpoch: Long,
    )

    data class IssuedToken(
        val tokenId: String,
        val plaintext: String,
        val scopes: Set<String>,
        val expiresAt: Instant,
        val loopbackOnly: Boolean,
        val label: String?,
        val issuanceKey: String,
        val revocationEpoch: Long,
    )

    fun issue(
        principalId: String,
        scopes: Set<String>,
        ttlSeconds: Long,
        loopbackOnly: Boolean = true,
        label: String? = null,
        clientId: String? = null,
        profile: AccessProfile = if (loopbackOnly) {
            AccessProfile.LOCAL_ADMIN_HTTP
        } else {
            AccessProfile.LAN_CLIENT
        },
        registrationId: String = clientId ?: "reg-$principalId",
    ): IssuedToken {
        val constraint = if (loopbackOnly) {
            TokenService.TransportConstraint.LOOPBACK_ONLY
        } else {
            TokenService.TransportConstraint.LAN_ONLY
        }
        // LOCAL_ADMIN_HTTP profile scopes when issuing broad bootstrap tokens.
        val resolvedProfile = when {
            loopbackOnly && scopes.containsAll(BOOTSTRAP_SCOPES) -> AccessProfile.LOCAL_ADMIN_HTTP
            loopbackOnly && profile == AccessProfile.LAN_CLIENT -> AccessProfile.DEVELOPER_CLIENT
            else -> profile
        }
        return when (
            val result = tokens.issue(
                TokenService.IssueRequest(
                    registrationId = registrationId,
                    principalId = PrincipalId.parse(principalId),
                    scopes = scopes,
                    transportConstraint = constraint,
                    ttlSeconds = ttlSeconds,
                    profile = resolvedProfile,
                    label = label,
                    clientId = clientId,
                ),
            )
        ) {
            is OmniResult.Ok -> {
                val v = result.value
                IssuedToken(
                    tokenId = v.tokenId,
                    plaintext = v.plaintextOnce,
                    scopes = v.scopes,
                    expiresAt = Instant.ofEpochMilli(v.expiresAtEpochMs),
                    loopbackOnly = loopbackOnly,
                    label = label,
                    issuanceKey = v.issuanceKey,
                    revocationEpoch = v.revocationEpoch,
                )
            }
            is OmniResult.Err -> error(
                "token issue failed: ${result.error.code} ${result.error.message}",
            )
        }
    }

    /**
     * Bootstrap a local admin token for first-run / UI (tokens.manage + broad scopes).
     * Plaintext returned once; store only verifier.
     */
    fun issueBootstrapAdmin(scopes: Set<String> = BOOTSTRAP_SCOPES): IssuedToken =
        issue(
            principalId = "http-local-admin",
            scopes = scopes,
            ttlSeconds = 86_400L,
            loopbackOnly = true,
            label = "bootstrap",
            clientId = "local-admin",
            profile = AccessProfile.LOCAL_ADMIN_HTTP,
        )

    fun listMetadata(): List<TokenRecord> =
        tokens.listMetadata().map { m ->
            TokenRecord(
                tokenId = m.tokenId,
                principalId = m.principalId,
                clientId = m.clientId,
                scopes = m.scopes,
                expiresAt = Instant.ofEpochMilli(m.expiresAtEpochMs),
                loopbackOnly = m.transportConstraint ==
                    TokenService.TransportConstraint.LOOPBACK_ONLY,
                label = m.label,
                revoked = m.state == "REVOKED",
                issuedAt = Instant.ofEpochMilli(m.issuedAtEpochMs),
                revocationEpoch = m.revocationEpoch,
            )
        }

    fun revoke(tokenId: String): Boolean {
        return when (
            tokens.revoke(
                tokenId = tokenId,
                actor = PrincipalId.parse("http-local-admin"),
                reason = "admin-revoke",
                authorised = true,
            )
        ) {
            is OmniResult.Ok -> true
            is OmniResult.Err -> false
        }
    }

    fun get(tokenId: String): TokenRecord? {
        val rec = tokens.get(tokenId) ?: return null
        return TokenRecord(
            tokenId = rec.tokenId,
            principalId = rec.principalId,
            clientId = rec.clientId,
            scopes = rec.scopes,
            expiresAt = Instant.ofEpochMilli(rec.expiresAtEpochMs),
            loopbackOnly = rec.transportConstraint ==
                TokenService.TransportConstraint.LOOPBACK_ONLY,
            label = rec.label,
            revoked = rec.state == "REVOKED",
            issuedAt = Instant.ofEpochMilli(rec.issuedAtEpochMs),
            revocationEpoch = rec.revocationEpoch,
        )
    }

    /** One-time plaintext re-display via Secret Broker receipt (if still staged). */
    fun takePlaintextOnce(issuanceKey: String): String? =
        when (val r = tokens.takePlaintextOnce(issuanceKey)) {
            is OmniResult.Ok -> r.value
            is OmniResult.Err -> null
        }

    fun tokenService(): TokenService = tokens

    fun secretBroker(): SecretBroker = broker

    override fun authenticate(bearerToken: String, transport: HttpTransportKind): AuthResult {
        val listener = when (transport) {
            HttpTransportKind.LOOPBACK -> TokenService.TransportConstraint.LOOPBACK_ONLY
            HttpTransportKind.LAN_TLS13 -> TokenService.TransportConstraint.LAN_ONLY
        }
        return when (val result = tokens.authenticate(bearerToken, listener)) {
            is OmniResult.Ok -> {
                val a = result.value
                AuthResult.Ok(
                    HttpPrincipal(
                        principalId = a.principalId,
                        tokenId = a.tokenId,
                        scopes = a.scopes,
                        revocationEpoch = a.revocationEpoch,
                        loopbackOnly = a.transportConstraint ==
                            TokenService.TransportConstraint.LOOPBACK_ONLY,
                        clientId = a.clientId,
                    ),
                )
            }
            is OmniResult.Err -> {
                val err = result.error
                when (err.code.name) {
                    "FORBIDDEN" -> AuthResult.Forbidden(
                        message = err.message ?: "forbidden",
                        details = err.details,
                    )
                    else -> AuthResult.Unauthorized(err.message ?: "invalid or missing bearer token")
                }
            }
        }
    }

    companion object {
        /**
         * Broad loopback local-admin scopes for bootstrap
         * (`LOCAL_ADMIN_HTTP` in access-control-catalog.yaml).
         */
        val BOOTSTRAP_SCOPES: Set<String> = setOf(
            "assets.create",
            "assets.delete-own",
            "assets.read-own",
            "clients.manage",
            "clients.read",
            "content-reports.propose",
            "content-reports.manage-own",
            "content-reports.read-own",
            "commands.read-own",
            "diagnostics.export",
            "inference.cancel",
            "inference.create",
            "inference.read-own",
            "jobs.manage",
            "jobs.read-all",
            "jobs.read-own",
            "lan.manage",
            "metrics.read-detail",
            "metrics.read-summary",
            "models.manage",
            "models.read",
            "settings.read",
            "settings.write",
            "tokens.manage",
        )
    }
}
