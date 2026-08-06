package com.omnillm.features.lan.domain

import com.omnillm.core.state.StateMachineDriver
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.lan.LanFeatureModule

/**
 * Pure pairing challenge policy (PAIRING_CHALLENGE FSM + SEC-PROFILE lanPairing).
 *
 * Challenge material is high-entropy, one-time, TTL-bounded, attempt-bounded,
 * bound to TLS SPKI + connection epoch. Approval does not issue long-term secret;
 * CONSUME is atomic with registration/token issuance.
 */
object PairingChallengePolicy {

    val CHALLENGE_STATES: Set<String> = StateMachines.PAIRING_CHALLENGE.states
    val CHALLENGE_TERMINAL: Set<String> = StateMachines.PAIRING_CHALLENGE.terminal

    const val TTL_SECONDS: Int = LanFeatureModule.PAIRING_TTL_SECONDS
    const val MAX_ATTEMPTS: Int = LanFeatureModule.PAIRING_MAX_ATTEMPTS
    const val PROTOCOL_LABEL: String = LanFeatureModule.PAIRING_PROTOCOL_LABEL

    data class ChallengeSnapshot(
        val challengeId: String,
        val state: String,
        val connectionEpoch: Long,
        val serverSpkiSha256: String,
        val requestedScopes: Set<String>,
        val expiresAtEpochMs: Long,
        val attemptsRemaining: Int,
        val protocolLabel: String = PROTOCOL_LABEL,
    ) {
        init {
            require(challengeId.isNotBlank())
            require(state in CHALLENGE_STATES) { "unknown PAIRING_CHALLENGE state: $state" }
            require(connectionEpoch >= 0L)
            require(serverSpkiSha256.matches(HEX64))
            require(requestedScopes.isNotEmpty())
            require(attemptsRemaining >= 0)
        }
    }

    data class ExchangeProof(
        val challengeId: String,
        val observedSpkiSha256: String,
        val observedConnectionEpoch: Long,
        val proofValid: Boolean,
        val nowEpochMs: Long,
    )

    /**
     * Evaluate whether a pairing exchange may consume the challenge.
     * Fail closed on any mismatch (FEAT-LAN acceptance: MITM / replay / epoch).
     */
    fun evaluateExchange(
        challenge: ChallengeSnapshot,
        proof: ExchangeProof,
    ): PairingDecision {
        if (challenge.challengeId != proof.challengeId) {
            return PairingDecision.Reject("challenge_id mismatch")
        }
        if (challenge.protocolLabel != PROTOCOL_LABEL) {
            return PairingDecision.Reject("unsupported protocol_label")
        }
        if (challenge.state in CHALLENGE_TERMINAL) {
            return PairingDecision.Reject(
                "challenge already terminal: ${challenge.state}",
                details = mapOf("state" to challenge.state),
            )
        }
        if (challenge.state != "APPROVED" && challenge.state != "PENDING") {
            return PairingDecision.Reject(
                "challenge not consumable in state ${challenge.state}",
                details = mapOf("state" to challenge.state),
            )
        }
        // Local approval required before token issuance (SEC-PROFILE requiresLocalApproval).
        if (challenge.state != "APPROVED") {
            return PairingDecision.Reject(
                "challenge not approved by local UI",
                details = mapOf("state" to challenge.state),
            )
        }
        if (proof.nowEpochMs >= challenge.expiresAtEpochMs) {
            return PairingDecision.Reject(
                "challenge expired",
                details = mapOf("state" to "EXPIRED"),
            )
        }
        if (challenge.attemptsRemaining <= 0) {
            return PairingDecision.Reject(
                "attempt budget exhausted",
                details = mapOf("maxAttempts" to MAX_ATTEMPTS.toString()),
            )
        }
        if (!constantTimeEqualsHex(challenge.serverSpkiSha256, proof.observedSpkiSha256)) {
            return PairingDecision.Reject(
                "SPKI fingerprint mismatch (possible MITM)",
                details = mapOf("reason" to "spki_mismatch"),
            )
        }
        if (challenge.connectionEpoch != proof.observedConnectionEpoch) {
            return PairingDecision.Reject(
                "connection epoch mismatch — re-pair required",
                details = mapOf(
                    "challengeEpoch" to challenge.connectionEpoch.toString(),
                    "observedEpoch" to proof.observedConnectionEpoch.toString(),
                ),
            )
        }
        if (!proof.proofValid) {
            return PairingDecision.Reject(
                "pairing proof invalid",
                details = mapOf("reason" to "proof_invalid"),
            )
        }
        return PairingDecision.Accept(challenge.requestedScopes)
    }

    /**
     * Pure FSM step for PAIRING_CHALLENGE (no domain mutation).
     * Guards supplied by caller from durable facts.
     */
    fun step(
        from: String,
        event: String,
        guards: Map<String, Boolean> = mapOf(
            "identityBound" to true,
            "withinAttemptAndTimeLimit" to true,
            "approvalLocal" to true,
        ),
    ): TransitionOutcome =
        StateMachineDriver.transition(
            machine = StateMachines.PAIRING_CHALLENGE,
            from = from,
            event = event,
            guards = com.omnillm.core.state.GuardEvaluator.of(guards),
        )

    fun isExpired(challenge: ChallengeSnapshot, nowEpochMs: Long): Boolean =
        nowEpochMs >= challenge.expiresAtEpochMs

    fun expiresAt(nowEpochMs: Long, ttlSeconds: Int = TTL_SECONDS): Long =
        nowEpochMs + ttlSeconds * 1000L

    /**
     * Constant-time compare for hex digests (SEC-PROFILE algorithms.comparison).
     */
    fun constantTimeEqualsHex(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}

sealed class PairingDecision {
    data class Accept(val scopes: Set<String>) : PairingDecision()
    data class Reject(
        val reason: String,
        val details: Map<String, String> = emptyMap(),
    ) : PairingDecision()
}
