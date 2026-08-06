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
import java.util.UUID

/**
 * Pairing challenge broker (SEC-PROFILE lanPairing / aidlRegistration,
 * PAIRING_CHALLENGE FSM, SEC-AUTH-NET §1 / §4).
 *
 * - LAN_HMAC: 192-bit CSPRNG secret (encrypted at rest), channel binding fields,
 *   TTL 300s, max 5 attempts, one-time consume → scoped bearer token
 * - AIDL_REGISTRATION: no crypto proof; bound to observed UID/user + local approval
 *
 * Fail closed on TTL, attempt limit, epoch change, SPKI mismatch, or replay.
 * Production injects SQLite-backed [PairingChallengeStore] (ADR-010).
 */
class PairingChallengeService(
    private val broker: SecretBroker,
    private val tokenService: TokenService,
    private val store: PairingChallengeStore = InMemoryPairingChallengeStore(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private fun put(rec: PairingChallengeRecord) = store.upsert(rec)

    private fun getRec(challengeId: String): PairingChallengeRecord? = store.get(challengeId)

    enum class ChallengeKind {
        /** Observed Binder principal + local approval; no pairing secret. */
        AIDL_REGISTRATION,

        /** Channel-bound LAN HMAC pairing (OmniLLM-LAN-Pairing-1). */
        LAN_HMAC,
    }

    data class PairingChallengeRecord(
        val challengeId: String,
        val kind: ChallengeKind,
        val state: String,
        val principalId: String?,
        val observedUid: Int?,
        val androidUserId: Int?,
        val protocolLabel: String?,
        val requestedScopes: Set<String>,
        val serverSpkiSha256: String?,
        val connectionEpoch: Long?,
        /** base64url server nonce (channel binding). */
        val serverNonceBase64Url: String?,
        val secretEncrypted: EncryptedRecord?,
        val attemptsRemaining: Int,
        val expiresAtEpochMs: Long,
        val approvedAtEpochMs: Long?,
        val consumedAtEpochMs: Long?,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        /** One-time secret plaintext for QR display — wiped after first take or consume. */
        val secretPlaintextOnce: String?,
    ) {
        init {
            require(challengeId.isNotBlank())
            require(StateMachines.PAIRING_CHALLENGE.isKnownState(state)) {
                "unknown PAIRING_CHALLENGE state: $state"
            }
            require(attemptsRemaining in 0..SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS)
            require(requestedScopes.isNotEmpty())
            when (kind) {
                ChallengeKind.LAN_HMAC -> {
                    require(protocolLabel == SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL)
                    require(serverSpkiSha256 != null && serverSpkiSha256.matches(HEX64))
                    require(connectionEpoch != null && connectionEpoch >= 0L)
                    require(serverNonceBase64Url != null)
                    val live = state == "PENDING" || state == "APPROVED"
                    if (live) {
                        require(secretEncrypted != null) {
                            "LAN_HMAC live challenge must hold encrypted pairing secret"
                        }
                    }
                }
                ChallengeKind.AIDL_REGISTRATION -> {
                    require(secretEncrypted == null)
                    require(observedUid != null)
                    require(androidUserId != null)
                }
            }
        }

        fun view(includeSecretOnce: Boolean = false): PairingChallengeView =
            PairingChallengeView(
                challengeId = challengeId,
                kind = kind,
                state = state,
                requestedScopes = requestedScopes,
                serverSpkiSha256 = serverSpkiSha256,
                connectionEpoch = connectionEpoch,
                serverNonceBase64Url = serverNonceBase64Url,
                protocolLabel = protocolLabel,
                attemptsRemaining = attemptsRemaining,
                expiresAtEpochMs = expiresAtEpochMs,
                approvedAtEpochMs = approvedAtEpochMs,
                secretPlaintextOnce = if (includeSecretOnce) secretPlaintextOnce else null,
            )

        companion object {
            private val HEX64 = Regex("^[0-9a-f]{64}$")
        }
    }

    data class PairingChallengeView(
        val challengeId: String,
        val kind: ChallengeKind,
        val state: String,
        val requestedScopes: Set<String>,
        val serverSpkiSha256: String?,
        val connectionEpoch: Long?,
        val serverNonceBase64Url: String?,
        val protocolLabel: String?,
        val attemptsRemaining: Int,
        val expiresAtEpochMs: Long,
        val approvedAtEpochMs: Long?,
        val secretPlaintextOnce: String?,
    )

    data class CreateLanChallengeRequest(
        val requestedScopes: Set<String>,
        val serverSpkiSha256: String,
        val connectionEpoch: Long,
        val ttlSeconds: Int = SecurityProfile.LAN_PAIRING_TTL_SECONDS,
        val maxAttempts: Int = SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS,
    )

    data class CreateAidLChallengeRequest(
        val observedUid: Int,
        val androidUserId: Int,
        val principalId: PrincipalId,
        val requestedScopes: Set<String>,
        val ttlSeconds: Int = SecurityProfile.AIDL_CHALLENGE_TTL_SECONDS,
    )

    data class LanExchangeRequest(
        val challengeId: String,
        val observedSpkiSha256: String,
        val observedConnectionEpoch: Long,
        val clientPublicKey: String,
        val proofBase64Url: String,
        val registrationId: String,
        val principalId: PrincipalId,
    )

    data class LanExchangeResult(
        val challengeId: String,
        val token: TokenService.IssuedTokenView,
    )

    // ----- Create -----------------------------------------------------------

    fun createLanChallenge(request: CreateLanChallengeRequest): OmniResult<PairingChallengeView> {
        val scopes = validateLanScopes(request.requestedScopes)
        if (scopes is OmniResult.Err) return scopes
        if (!request.serverSpkiSha256.matches(HEX64)) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "serverSpkiSha256 must be lower-case hex SHA-256"),
            )
        }
        if (request.connectionEpoch < 0L) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "connectionEpoch invalid"))
        }
        val ttl = request.ttlSeconds.coerceIn(1, SecurityProfile.LAN_PAIRING_TTL_SECONDS)
        val attempts = request.maxAttempts.coerceIn(1, SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS)
        val challengeId = UUID.randomUUID().toString()
        val now = clockMs()
        val expires = now + ttl * 1000L
        val serverNonce = CryptoPrimitives.encodeBase64Url(
            CryptoPrimitives.randomBytes(SecurityProfile.PAIRING_SECRET_BYTES),
        )

        val minted = when (val m = broker.mintPairingSecret(challengeId, expires)) {
            is OmniResult.Err -> return m
            is OmniResult.Ok -> m.value
        }

        val rec = PairingChallengeRecord(
            challengeId = challengeId,
            kind = ChallengeKind.LAN_HMAC,
            state = StateMachines.PAIRING_CHALLENGE.initial,
            principalId = null,
            observedUid = null,
            androidUserId = null,
            protocolLabel = SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL,
            requestedScopes = (scopes as OmniResult.Ok).value,
            serverSpkiSha256 = request.serverSpkiSha256.lowercase(),
            connectionEpoch = request.connectionEpoch,
            serverNonceBase64Url = serverNonce,
            secretEncrypted = minted.encrypted,
            attemptsRemaining = attempts,
            expiresAtEpochMs = expires,
            approvedAtEpochMs = null,
            consumedAtEpochMs = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            secretPlaintextOnce = minted.plaintextBase64Url,
        )
        put(rec)
        return OmniResult.ok(rec.view(includeSecretOnce = true))
    }

    fun createAidLChallenge(request: CreateAidLChallengeRequest): OmniResult<PairingChallengeView> {
        val scopes = validateProfileScopes(request.requestedScopes, AccessProfile.APP_CLIENT)
        if (scopes is OmniResult.Err) return scopes
        val challengeId = UUID.randomUUID().toString()
        val now = clockMs()
        val expires = now + request.ttlSeconds.coerceIn(1, SecurityProfile.AIDL_CHALLENGE_TTL_SECONDS) * 1000L
        val rec = PairingChallengeRecord(
            challengeId = challengeId,
            kind = ChallengeKind.AIDL_REGISTRATION,
            state = StateMachines.PAIRING_CHALLENGE.initial,
            principalId = request.principalId.value,
            observedUid = request.observedUid,
            androidUserId = request.androidUserId,
            protocolLabel = null,
            requestedScopes = (scopes as OmniResult.Ok).value,
            serverSpkiSha256 = null,
            connectionEpoch = null,
            serverNonceBase64Url = null,
            secretEncrypted = null,
            attemptsRemaining = SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS,
            expiresAtEpochMs = expires,
            approvedAtEpochMs = null,
            consumedAtEpochMs = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
            secretPlaintextOnce = null,
        )
        put(rec)
        return OmniResult.ok(rec.view())
    }

    // ----- Approve / reject (local UI) --------------------------------------

    fun approve(
        challengeId: String,
        actorIsLocalUi: Boolean,
        nowEpochMs: Long = clockMs(),
    ): OmniResult<PairingChallengeView> {
        if (!actorIsLocalUi) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "pairing approval requires LOCAL_UI"),
            )
        }
        val rec = getRec(challengeId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "pairing challenge not found"))
        expireIfDue(rec, nowEpochMs)?.let { return OmniResult.err(it) }
        val current = getRec(challengeId)!!
        if (current.attemptsRemaining <= 0) {
            return failClosedInvalidate(current, "attempt budget exhausted")
        }
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.PAIRING_CHALLENGE,
                current.state,
                "APPROVE",
                GuardEvaluator.of(
                    "identityBound" to true,
                    "withinAttemptAndTimeLimit" to (nowEpochMs < current.expiresAtEpochMs && current.attemptsRemaining > 0),
                    "approvalLocal" to actorIsLocalUi,
                ),
            )
        ) {
            is TransitionOutcome.Accepted -> {
                val next = current.copy(
                    state = step.to,
                    approvedAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                    secretPlaintextOnce = null,
                )
                put(next)
                OmniResult.ok(next.view())
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "approve rejected: ${step.reason}"),
                )
        }
    }

    fun reject(
        challengeId: String,
        actorIsLocalUi: Boolean,
        nowEpochMs: Long = clockMs(),
    ): OmniResult<PairingChallengeView> {
        if (!actorIsLocalUi) {
            return OmniResult.err(OmniError.FORBIDDEN(message = "pairing reject requires LOCAL_UI"))
        }
        val rec = getRec(challengeId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "pairing challenge not found"))
        return when (
            val step = StateMachineDriver.transition(
                StateMachines.PAIRING_CHALLENGE,
                rec.state,
                "REJECT",
                GuardEvaluator.of("identityBound" to true),
            )
        ) {
            is TransitionOutcome.Accepted -> {
                val next = rec.copy(
                    state = step.to,
                    updatedAtEpochMs = nowEpochMs,
                    secretPlaintextOnce = null,
                    secretEncrypted = null,
                )
                put(next)
                OmniResult.ok(next.view())
            }
            is TransitionOutcome.Rejected ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "reject rejected: ${step.reason}"),
                )
        }
    }

    // ----- LAN exchange (consume + issue token) -----------------------------

    fun completeLanExchange(request: LanExchangeRequest): OmniResult<LanExchangeResult> {
        val now = clockMs()
        val rec = getRec(request.challengeId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "pairing challenge not found"))

        if (rec.kind != ChallengeKind.LAN_HMAC) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "challenge is not LAN_HMAC"),
            )
        }
        expireIfDue(rec, now)?.let { return OmniResult.err(it) }
        val current = getRec(request.challengeId)!!

        if (current.state != "APPROVED") {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "challenge not approved by local UI",
                    details = mapOf("state" to current.state),
                ),
            )
        }
        if (current.attemptsRemaining <= 0) {
            return failClosedInvalidate(current, "attempt budget exhausted")
        }

        if (!CryptoPrimitives.constantTimeEqualsHex(
                current.serverSpkiSha256!!,
                request.observedSpkiSha256.lowercase(),
            )
        ) {
            return recordFailedAttempt(current, "SPKI fingerprint mismatch (possible MITM)")
        }
        if (current.connectionEpoch != request.observedConnectionEpoch) {
            return failClosedInvalidate(
                current,
                "connection epoch mismatch — re-pair required",
            )
        }

        val secretBytes = when (val d = broker.decryptRecord(current.secretEncrypted!!)) {
            is OmniResult.Err -> return recordFailedAttempt(current, "pairing secret unavailable")
            is OmniResult.Ok -> d.value
        }

        val proofBytes = CryptoPrimitives.decodeBase64Url(request.proofBase64Url)
            ?: return recordFailedAttempt(current, "malformed pairing proof")

        val transcript = LanPairingTranscript(
            protocolLabel = current.protocolLabel!!,
            serverSpkiSha256 = current.serverSpkiSha256,
            connectionEpoch = current.connectionEpoch!!,
            challengeId = current.challengeId,
            serverNonce = current.serverNonceBase64Url!!,
            clientPublicKey = request.clientPublicKey,
            requestedScopes = current.requestedScopes,
            issuedAtEpochMs = current.createdAtEpochMs,
            expiresAtEpochMs = current.expiresAtEpochMs,
        )

        when (val v = broker.verifyLanPairingProof(transcript, secretBytes, proofBytes)) {
            is OmniResult.Err -> {
                CryptoPrimitives.wipe(secretBytes)
                return recordFailedAttempt(current, v.error.message ?: "pairing proof invalid")
            }
            is OmniResult.Ok -> Unit
        }
        CryptoPrimitives.wipe(secretBytes)

        val consumed = when (
            val step = StateMachineDriver.transition(
                StateMachines.PAIRING_CHALLENGE,
                current.state,
                "CONSUME",
                GuardEvaluator.of(
                    "identityBound" to true,
                    "withinAttemptAndTimeLimit" to true,
                ),
            )
        ) {
            is TransitionOutcome.Accepted -> current.copy(
                state = step.to,
                consumedAtEpochMs = now,
                updatedAtEpochMs = now,
                secretPlaintextOnce = null,
                secretEncrypted = null,
            )
            is TransitionOutcome.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(message = "consume rejected: ${step.reason}"),
                )
        }
        put(consumed)

        val issued = tokenService.issue(
            TokenService.IssueRequest(
                registrationId = request.registrationId,
                principalId = request.principalId,
                scopes = current.requestedScopes,
                transportConstraint = TokenService.TransportConstraint.LAN_ONLY,
                ttlSeconds = DEFAULT_LAN_TOKEN_TTL_SECONDS,
                profile = AccessProfile.LAN_CLIENT,
                label = "lan-pairing",
                clientId = request.registrationId,
            ),
        )
        return when (issued) {
            is OmniResult.Err -> issued
            is OmniResult.Ok -> OmniResult.ok(
                LanExchangeResult(
                    challengeId = request.challengeId,
                    token = issued.value,
                ),
            )
        }
    }

    fun get(challengeId: String): PairingChallengeView? =
        getRec(challengeId)?.view()

    fun takeSecretPlaintextOnce(challengeId: String): OmniResult<String> {
        val rec = getRec(challengeId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "pairing challenge not found"))
        val secret = rec.secretPlaintextOnce
            ?: return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "pairing secret already displayed or erased"),
            )
        put(rec.copy(secretPlaintextOnce = null, updatedAtEpochMs = clockMs()))
        return OmniResult.ok(secret)
    }

    fun pairingChallengeStore(): PairingChallengeStore = store

    // ----- Internals --------------------------------------------------------

    private fun expireIfDue(
        rec: PairingChallengeRecord,
        nowEpochMs: Long,
    ): OmniError? {
        if (nowEpochMs < rec.expiresAtEpochMs) return null
        if (rec.state in StateMachines.PAIRING_CHALLENGE.terminal) {
            return OmniError.FORBIDDEN(
                message = "challenge terminal",
                details = mapOf("state" to rec.state),
            )
        }
        when (
            val step = StateMachineDriver.transition(
                StateMachines.PAIRING_CHALLENGE,
                rec.state,
                "EXPIRE",
                GuardEvaluator.ALWAYS_TRUE,
            )
        ) {
            is TransitionOutcome.Accepted -> {
                put(
                    rec.copy(
                        state = step.to,
                        updatedAtEpochMs = nowEpochMs,
                        secretPlaintextOnce = null,
                        secretEncrypted = null,
                    ),
                )
            }
            is TransitionOutcome.Rejected -> Unit
        }
        return OmniError.FORBIDDEN(
            message = "challenge expired",
            details = mapOf("state" to "EXPIRED"),
        )
    }

    private fun recordFailedAttempt(
        rec: PairingChallengeRecord,
        reason: String,
    ): OmniResult.Err {
        val remaining = (rec.attemptsRemaining - 1).coerceAtLeast(0)
        val next = rec.copy(
            attemptsRemaining = remaining,
            updatedAtEpochMs = clockMs(),
        )
        put(next)
        if (remaining <= 0) {
            return failClosedInvalidate(next, reason)
        }
        return OmniResult.Err(
            OmniError.UNAUTHORIZED(
                message = reason,
                details = mapOf(
                    "attemptsRemaining" to remaining.toString(),
                    "challengeId" to rec.challengeId,
                ),
            ),
        )
    }

    private fun failClosedInvalidate(
        rec: PairingChallengeRecord,
        reason: String,
    ): OmniResult.Err {
        val event = when (rec.state) {
            "PENDING" -> "REJECT"
            "APPROVED" -> "EXPIRE"
            else -> null
        }
        val invalidated = if (event == null || rec.state in StateMachines.PAIRING_CHALLENGE.terminal) {
            rec.copy(
                attemptsRemaining = 0,
                secretPlaintextOnce = null,
                secretEncrypted = null,
                updatedAtEpochMs = clockMs(),
            )
        } else {
            when (
                val step = StateMachineDriver.transition(
                    StateMachines.PAIRING_CHALLENGE,
                    rec.state,
                    event,
                    GuardEvaluator.of("identityBound" to true),
                )
            ) {
                is TransitionOutcome.Accepted -> rec.copy(
                    state = step.to,
                    attemptsRemaining = 0,
                    secretPlaintextOnce = null,
                    secretEncrypted = null,
                    updatedAtEpochMs = clockMs(),
                )
                is TransitionOutcome.Rejected -> rec.copy(
                    attemptsRemaining = 0,
                    secretPlaintextOnce = null,
                    secretEncrypted = null,
                    updatedAtEpochMs = clockMs(),
                )
            }
        }
        put(invalidated)
        return OmniResult.Err(
            OmniError.FORBIDDEN(
                message = reason,
                details = mapOf(
                    "challengeId" to rec.challengeId,
                    "state" to invalidated.state,
                ),
            ),
        )
    }

    private fun validateLanScopes(requested: Set<String>): OmniResult<Set<String>> =
        validateProfileScopes(requested, AccessProfile.LAN_CLIENT)

    private fun validateProfileScopes(
        requested: Set<String>,
        profile: AccessProfile,
    ): OmniResult<Set<String>> {
        if (requested.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "scopes must be non-empty"))
        }
        val out = linkedSetOf<String>()
        for (raw in requested) {
            val id = raw.trim()
            if (id.isEmpty()) continue
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
            out += id
        }
        if (out.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "scopes must be non-empty"))
        }
        return OmniResult.ok(out)
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        const val DEFAULT_LAN_TOKEN_TTL_SECONDS: Long = 86_400L
    }
}
