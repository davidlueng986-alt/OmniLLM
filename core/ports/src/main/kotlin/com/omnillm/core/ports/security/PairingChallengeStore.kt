package com.omnillm.core.ports.security

import com.omnillm.core.state.generated.StateMachines
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable pairing-challenge store (SEC-PROFILE lanPairing / PAIRING_CHALLENGE FSM).
 *
 * LAN secrets are stored only as AES-GCM ciphertext (never plaintext).
 * Production: SQLDelight / SQLite via control-plane sole writer (ADR-010)
 * (`:data:persistence` adapter). Tests: [InMemoryPairingChallengeStore].
 */
interface PairingChallengeStore {
    fun get(challengeId: String): PairingChallengeRecord?

    fun listAll(): List<PairingChallengeRecord>

    fun upsert(record: PairingChallengeRecord)

    fun delete(challengeId: String): Boolean
}

class InMemoryPairingChallengeStore : PairingChallengeStore {
    private val challenges =
        ConcurrentHashMap<String, PairingChallengeRecord>()

    override fun get(challengeId: String): PairingChallengeRecord? =
        challenges[challengeId]

    override fun listAll(): List<PairingChallengeRecord> =
        challenges.values.toList()

    override fun upsert(record: PairingChallengeRecord) {
        challenges[record.challengeId] = record
    }

    override fun delete(challengeId: String): Boolean = challenges.remove(challengeId) != null

    fun clear() {
        challenges.clear()
    }
}

enum class ChallengeKind {
    /** Observed Binder principal + local approval; no pairing secret. */
    AIDL_REGISTRATION,

    /** Channel-bound LAN HMAC pairing (OmniLLM-LAN-Pairing-1). */
    LAN_HMAC,
}

/**
 * Durable pairing-challenge record (port shape; SQLite adapter in
 * `:data:persistence`, service logic in `:runtime:policy`).
 */
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

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
