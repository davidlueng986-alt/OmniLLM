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
import java.util.concurrent.atomic.AtomicBoolean

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
        val lastSeenAt: Instant? = null,
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
     *
     * SEC-08: default TTL is 1 hour ([BOOTSTRAP_ADMIN_TTL_SECONDS]) instead of the
     * previous 24h, and is parameterizable so a future config-catalog wiring can
     * drive it. Display side must use single-peek semantics ([BootstrapTokenDisplay]).
     */
    fun issueBootstrapAdmin(
        scopes: Set<String> = BOOTSTRAP_SCOPES,
        ttlSeconds: Long = BOOTSTRAP_ADMIN_TTL_SECONDS,
    ): IssuedToken =
        issue(
            principalId = "http-local-admin",
            scopes = scopes,
            ttlSeconds = ttlSeconds,
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
                lastSeenAt = m.lastSeenAtEpochMs?.let { Instant.ofEpochMilli(it) },
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
            lastSeenAt = rec.lastSeenAtEpochMs?.let { Instant.ofEpochMilli(it) },
        )
    }

    /** One-time plaintext re-display via Secret Broker receipt (if still staged). */
    fun takePlaintextOnce(issuanceKey: String): String? =
        when (val r = tokens.takePlaintextOnce(issuanceKey)) {
            is OmniResult.Ok -> r.value
            is OmniResult.Err -> null
        }

    /**
     * SEC-08: wipe the staged plaintext receipt so the plaintext can never be
     * re-fetched via [takePlaintextOnce] (used after first UI display).
     */
    fun erasePlaintextReceipt(issuanceKey: String) {
        broker.erasePlaintextReceipt(issuanceKey)
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
        /** SEC-08: bootstrap admin token default lifetime — 1 hour (was 24h). */
        const val BOOTSTRAP_ADMIN_TTL_SECONDS: Long = 3_600L

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

/**
 * Single-peek display state for the bootstrap admin token plaintext (SEC-08).
 *
 * The plaintext is exposed to the UI exactly once — the first
 * [takePlaintextOnce] returns it and immediately drops the in-memory reference
 * (minimizing the plaintext window). Afterwards only [masked] is available and
 * the staged Secret Broker receipt must be erased by the caller so
 * [LoopbackTokenService.takePlaintextOnce] cannot resurrect it.
 */
class BootstrapTokenDisplay(
    plaintext: String,
) {
    @Volatile
    private var retained: String? = plaintext

    private val suffix: String = plaintext.takeLast(4)
    private val shown = AtomicBoolean(false)

    /** First call returns the plaintext once; all later calls return null. */
    @Synchronized
    fun takePlaintextOnce(): String? {
        if (!shown.compareAndSet(false, true)) return null
        val value = retained
        retained = null
        return value
    }

    fun hasBeenShown(): Boolean = shown.get()

    /** True while the plaintext is still retained in memory. */
    fun isRetained(): Boolean = retained != null

    /** Masked form safe for repeat display (suffix only, never the secret). */
    fun masked(): String = "bootstrap-••••-$suffix"
}
