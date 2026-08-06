package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.StateMachineDriver
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.runtime.policy.RevocationEpochManager
import com.omnillm.runtime.policy.RevocationScope
import com.omnillm.runtime.policy.RevocationSubjectKind
import java.util.UUID

/**
 * Access-token lifecycle (TOKEN FSM + SEC-PROFILE / SEC-AUTH-NET §2).
 *
 * - 256-bit CSPRNG bearer, base64url no padding
 * - Durable store: HMAC-SHA-256 verifier only (never plaintext)
 * - One-time plaintext display via Secret Broker receipt
 * - Bound to registration, scopes, transport constraint, revocation epoch
 *
 * Single writer: runtime control plane (ADR-010).
 * Production: inject SQLite-backed [AccessTokenStore]; never keep plaintext.
 */
class TokenService(
    private val broker: SecretBroker,
    private val revocation: RevocationEpochManager = RevocationEpochManager(),
    private val store: AccessTokenStore = InMemoryAccessTokenStore(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

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

    data class IssueRequest(
        val registrationId: String,
        val principalId: PrincipalId,
        val scopes: Set<String>,
        val transportConstraint: TransportConstraint,
        val ttlSeconds: Long,
        val profile: AccessProfile,
        val label: String? = null,
        val clientId: String? = null,
        /** When null, bind to current principal revocation epoch. */
        val revocationEpoch: Long? = null,
        /** One-time display TTL for plaintext receipt (default 5 min). */
        val plaintextReceiptTtlSeconds: Long = 300L,
    )

    data class IssuedTokenView(
        val tokenId: String,
        /** Present only on the issue path once; store must not retain it. */
        val plaintextOnce: String,
        val issuanceKey: String,
        val scopes: Set<String>,
        val transportConstraint: TransportConstraint,
        val expiresAtEpochMs: Long,
        val revocationEpoch: Long,
        val metadata: TokenMetadata,
    )

    /**
     * Issue a scoped bearer token. Plaintext returned once; only HMAC verifier
     * is retained. TOKEN: ISSUING → ACTIVE.
     */
    fun issue(request: IssueRequest): OmniResult<IssuedTokenView> {
        val scopeCheck = validateScopes(request.scopes, request.profile)
        if (scopeCheck is OmniResult.Err) return scopeCheck

        if (request.ttlSeconds !in MIN_TTL_SECONDS..MAX_TTL_SECONDS) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "ttlSeconds out of range",
                    details = mapOf(
                        "min" to MIN_TTL_SECONDS.toString(),
                        "max" to MAX_TTL_SECONDS.toString(),
                    ),
                ),
            )
        }
        // LOCAL_ADMIN_HTTP requires explicit local issuance (catalog).
        if (request.profile == AccessProfile.LOCAL_ADMIN_HTTP &&
            request.transportConstraint != TransportConstraint.LOOPBACK_ONLY
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "LOCAL_ADMIN_HTTP tokens are loopback-only",
                ),
            )
        }
        if (request.profile == AccessProfile.LAN_CLIENT &&
            request.transportConstraint != TransportConstraint.LAN_ONLY
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "LAN_CLIENT tokens must be LAN_ONLY"),
            )
        }

        val revScope = RevocationScope(request.principalId.value, RevocationSubjectKind.PRINCIPAL)
        revocation.ensureActive(revScope)
        val epoch = request.revocationEpoch ?: revocation.currentEpoch(revScope)

        val mint = broker.mintBearerMaterial()
        val tokenId = UUID.randomUUID().toString()
        val now = clockMs()
        val expires = now + request.ttlSeconds * 1000L
        val issuanceKey = VaultSecretBroker.newIssuanceKey()

        val issuing = AccessTokenRecord(
            tokenId = tokenId,
            registrationId = request.registrationId,
            principalId = request.principalId.value,
            state = StateMachines.TOKEN.initial, // ISSUING
            verifier = mint.verifier,
            verifierAlgorithm = mint.verifierAlgorithm,
            verifierKeyVersion = mint.verifierKeyVersion,
            transportConstraint = request.transportConstraint,
            scopes = request.scopes.toSet(),
            revocationEpoch = epoch,
            issuedAtEpochMs = now,
            expiresAtEpochMs = expires,
            label = request.label,
            clientId = request.clientId,
            updatedAtEpochMs = now,
        )

        val committed = when (
            val step = StateMachineDriver.transition(
                StateMachines.TOKEN,
                issuing.state,
                "ISSUE_COMMITTED",
                GuardEvaluator.of("issuanceValid" to true),
            )
        ) {
            is TransitionOutcome.Accepted -> issuing.copy(state = step.to, updatedAtEpochMs = clockMs())
            is TransitionOutcome.Rejected -> {
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "token issue rejected: ${step.reason}"),
                )
            }
        }

        val receiptExpiry = now + request.plaintextReceiptTtlSeconds * 1000L
        when (
            val staged = broker.stageOneTimePlaintext(
                issuanceKey = issuanceKey,
                tokenId = tokenId,
                plaintext = mint.plaintextOnceSafe(),
                expiresAtEpochMs = receiptExpiry,
            )
        ) {
            is OmniResult.Err -> return staged
            is OmniResult.Ok -> Unit
        }

        store.upsert(committed)
        // Also track TOKEN-subject revocation at epoch 0.
        revocation.ensureActive(RevocationScope(tokenId, RevocationSubjectKind.TOKEN))

        return OmniResult.ok(
            IssuedTokenView(
                tokenId = tokenId,
                plaintextOnce = mint.plaintext,
                issuanceKey = issuanceKey,
                scopes = committed.scopes,
                transportConstraint = committed.transportConstraint,
                expiresAtEpochMs = expires,
                revocationEpoch = epoch,
                metadata = committed.metadata(),
            ),
        )
    }

    /**
     * Verify presented bearer plaintext (constant-time HMAC compare).
     * Does not accept loopback tokens on LAN — caller supplies transport constraint expected.
     */
    fun authenticate(
        bearerPlaintext: String,
        listener: TransportConstraint,
        nowEpochMs: Long = clockMs(),
    ): OmniResult<AuthenticatedToken> {
        if (bearerPlaintext.isBlank()) {
            return OmniResult.err(OmniError.UNAUTHORIZED(message = "empty token"))
        }
        val presented = broker.computeTokenVerifier(bearerPlaintext)
            ?: return OmniResult.err(OmniError.UNAUTHORIZED(message = "verifier unavailable"))

        var match: AccessTokenRecord? = null
        for (rec in store.listAll()) {
            if (CryptoPrimitives.constantTimeEquals(presented, rec.verifier)) {
                match = rec
                break
            }
        }
        val rec = match
            ?: return OmniResult.err(OmniError.UNAUTHORIZED(message = "unknown token"))

        if (rec.state != "ACTIVE") {
            return OmniResult.err(
                OmniError.UNAUTHORIZED(
                    message = "token not active",
                    details = mapOf("state" to rec.state),
                ),
            )
        }
        if (nowEpochMs >= rec.expiresAtEpochMs) {
            // Soft-expire: attempt TOKEN EXPIRE transition when still ACTIVE.
            expireIfDue(rec.tokenId, nowEpochMs)
            return OmniResult.err(OmniError.UNAUTHORIZED(message = "token expired"))
        }

        // Transport fence (access-control-catalog invariants).
        if (listener == TransportConstraint.LAN_ONLY &&
            rec.transportConstraint == TransportConstraint.LOOPBACK_ONLY
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "loopback-only token rejected on LAN listener",
                    details = mapOf("tokenId" to rec.tokenId),
                ),
            )
        }
        if (listener == TransportConstraint.LOOPBACK_ONLY &&
            rec.transportConstraint == TransportConstraint.LAN_ONLY
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "LAN token not accepted on loopback listener",
                    details = mapOf("tokenId" to rec.tokenId),
                ),
            )
        }

        // Principal revocation epoch fence.
        val principalScope = RevocationScope(rec.principalId, RevocationSubjectKind.PRINCIPAL)
        when (val epochCheck = revocation.requireCurrentEpoch(principalScope, rec.revocationEpoch)) {
            is OmniResult.Err -> return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "stale token revocation epoch",
                    details = epochCheck.error.details + mapOf("tokenId" to rec.tokenId),
                ),
            )
            is OmniResult.Ok -> Unit
        }
        val tokenScope = RevocationScope(rec.tokenId, RevocationSubjectKind.TOKEN)
        val tokenEpoch = revocation.currentEpoch(tokenScope)
        if (tokenEpoch > 0L) {
            // Any TOKEN-subject epoch bump means revoked/fenced.
            return OmniResult.err(
                OmniError.UNAUTHORIZED(
                    message = "token revoked",
                    details = mapOf("tokenId" to rec.tokenId, "epoch" to tokenEpoch.toString()),
                ),
            )
        }

        store.upsert(rec.copy(lastSeenAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs))
        return OmniResult.ok(
            AuthenticatedToken(
                tokenId = rec.tokenId,
                principalId = rec.principalId,
                registrationId = rec.registrationId,
                scopes = rec.scopes,
                revocationEpoch = rec.revocationEpoch,
                transportConstraint = rec.transportConstraint,
                clientId = rec.clientId,
                expiresAtEpochMs = rec.expiresAtEpochMs,
            ),
        )
    }

    fun get(tokenId: String): AccessTokenRecord? = store.get(tokenId)

    fun listMetadata(): List<TokenMetadata> =
        store.listAll().map { it.metadata() }.sortedBy { it.issuedAtEpochMs }

    /** Exposed for control-plane wiring / diagnostics (no verifier leakage). */
    fun accessTokenStore(): AccessTokenStore = store

    /**
     * Revoke token: TOKEN ACTIVE → REVOCATION_REQUESTED → DRAINING → REVOKED,
     * plus principal/token revocation epoch fence (SEC-006).
     */
    fun revoke(
        tokenId: String,
        actor: PrincipalId,
        reason: String,
        authorised: Boolean = true,
    ): OmniResult<AccessTokenRecord> {
        if (!authorised) {
            return OmniResult.err(OmniError.FORBIDDEN(message = "token revoke not authorised"))
        }
        val rec = store.get(tokenId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "token not found"))
        if (rec.state in TERMINAL) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "token already terminal",
                    details = mapOf("state" to rec.state),
                ),
            )
        }

        // Bump TOKEN-subject revocation epoch (fence API).
        val fence = revocation.revokeAndFence(
            scope = RevocationScope(tokenId, RevocationSubjectKind.TOKEN),
            actorPrincipalId = actor,
            reason = reason,
            authorised = authorised,
        )
        if (fence is OmniResult.Err) return fence

        var current = rec
        // ACTIVE → REVOCATION_REQUESTED
        when (
            val step = StateMachineDriver.transition(
                StateMachines.TOKEN,
                current.state,
                "REVOKE",
                GuardEvaluator.of("epochCurrent" to true),
            )
        ) {
            is TransitionOutcome.Accepted -> {
                current = current.copy(state = step.to, updatedAtEpochMs = clockMs())
                store.upsert(current)
            }
            is TransitionOutcome.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "token REVOKE rejected: ${step.reason}"),
                )
        }
        // REVOCATION_REQUESTED → DRAINING
        when (
            val step = StateMachineDriver.transition(
                StateMachines.TOKEN,
                current.state,
                "BEGIN_DRAIN",
                GuardEvaluator.ALWAYS_TRUE,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                current = current.copy(state = step.to, updatedAtEpochMs = clockMs())
                store.upsert(current)
            }
            is TransitionOutcome.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "token BEGIN_DRAIN rejected: ${step.reason}"),
                )
        }
        // DRAINING → REVOKED
        when (
            val step = StateMachineDriver.transition(
                StateMachines.TOKEN,
                current.state,
                "DRAIN_COMPLETE",
                GuardEvaluator.of(
                    "noOldUse" to true,
                    "terminalTargetRevoked" to true,
                    "terminalTargetExpired" to false,
                ),
            )
        ) {
            is TransitionOutcome.Accepted -> {
                current = current.copy(state = step.to, updatedAtEpochMs = clockMs())
                store.upsert(current)
                broker.erasePlaintextReceipt(tokenId)
            }
            is TransitionOutcome.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "token DRAIN_COMPLETE rejected: ${step.reason}"),
                )
        }
        return OmniResult.ok(current)
    }

    fun requireScope(auth: AuthenticatedToken, required: AccessScope): OmniResult<Unit> {
        if ("*" in auth.scopes) return OmniResult.ok(Unit)
        if (required.id !in auth.scopes) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "missing required scope",
                    details = mapOf("requiredScope" to required.id),
                ),
            )
        }
        return OmniResult.ok(Unit)
    }

    fun takePlaintextOnce(issuanceKey: String): OmniResult<String> =
        broker.takePlaintextOnce(issuanceKey, clockMs())

    fun revocationManager(): RevocationEpochManager = revocation

    // ----- Internals --------------------------------------------------------

    private fun expireIfDue(tokenId: String, nowEpochMs: Long) {
        synchronized(lock) {
            val rec = store.get(tokenId) ?: return
            if (rec.state != "ACTIVE" || nowEpochMs < rec.expiresAtEpochMs) return
            var current = rec
            when (
                val step = StateMachineDriver.transition(
                    StateMachines.TOKEN,
                    current.state,
                    "EXPIRE",
                    GuardEvaluator.of("epochCurrent" to true),
                )
            ) {
                is TransitionOutcome.Accepted -> {
                    current = current.copy(state = step.to, updatedAtEpochMs = nowEpochMs)
                    store.upsert(current)
                }
                is TransitionOutcome.Rejected -> return
            }
            when (
                val step = StateMachineDriver.transition(
                    StateMachines.TOKEN,
                    current.state,
                    "BEGIN_DRAIN",
                    GuardEvaluator.ALWAYS_TRUE,
                )
            ) {
                is TransitionOutcome.Accepted -> {
                    current = current.copy(state = step.to, updatedAtEpochMs = nowEpochMs)
                    store.upsert(current)
                }
                is TransitionOutcome.Rejected -> return
            }
            when (
                val step = StateMachineDriver.transition(
                    StateMachines.TOKEN,
                    current.state,
                    "DRAIN_COMPLETE",
                    GuardEvaluator.of(
                        "noOldUse" to true,
                        "terminalTargetRevoked" to false,
                        "terminalTargetExpired" to true,
                    ),
                )
            ) {
                is TransitionOutcome.Accepted -> {
                    store.upsert(current.copy(state = step.to, updatedAtEpochMs = nowEpochMs))
                }
                is TransitionOutcome.Rejected -> Unit
            }
        }
    }

    private fun validateScopes(
        scopes: Set<String>,
        profile: AccessProfile,
    ): OmniResult<Set<String>> {
        if (scopes.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "scopes must be non-empty"))
        }
        val resolved = linkedSetOf<String>()
        for (raw in scopes) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            if (id == "*") {
                // Wildcard only valid when profile is LOCAL_ADMIN.
                if (!AccessControlCatalog.PROFILES.any { it.id == profile && it.wildcardScopes }) {
                    return OmniResult.err(
                        OmniError.FORBIDDEN(message = "wildcard scope not allowed for profile"),
                    )
                }
                resolved += "*"
                continue
            }
            val scope = AccessScope.fromId(id)
                ?: return OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = "unknown scope (fail closed)",
                        details = mapOf("scope" to id),
                    ),
                )
            if (!AccessControlCatalog.profileAllowsScope(profile, scope)) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "scope not allowed for profile",
                        details = mapOf("scope" to id, "profile" to profile.id),
                    ),
                )
            }
            resolved += id
        }
        if (resolved.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "scopes must be non-empty"))
        }
        return OmniResult.ok(resolved)
    }

    companion object {
        const val MIN_TTL_SECONDS: Long = 60L
        const val MAX_TTL_SECONDS: Long = 31_536_000L
        private val TERMINAL: Set<String> = StateMachines.TOKEN.terminal
    }
}

data class AuthenticatedToken(
    val tokenId: String,
    val principalId: String,
    val registrationId: String,
    val scopes: Set<String>,
    val revocationEpoch: Long,
    val transportConstraint: TokenService.TransportConstraint,
    val clientId: String?,
    val expiresAtEpochMs: Long,
)

/** Helper so callers do not confuse mint fields. */
private fun BearerMint.plaintextOnceSafe(): String = plaintext
