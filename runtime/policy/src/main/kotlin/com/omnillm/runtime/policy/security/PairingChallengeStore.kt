package com.omnillm.runtime.policy.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Durable pairing-challenge store (SEC-PROFILE lanPairing / PAIRING_CHALLENGE FSM).
 *
 * LAN secrets are stored only as AES-GCM ciphertext (never plaintext).
 * Production: SQLDelight / SQLite via control-plane sole writer (ADR-010).
 */
interface PairingChallengeStore {
    fun get(challengeId: String): PairingChallengeService.PairingChallengeRecord?

    fun listAll(): List<PairingChallengeService.PairingChallengeRecord>

    fun upsert(record: PairingChallengeService.PairingChallengeRecord)

    fun delete(challengeId: String): Boolean
}

class InMemoryPairingChallengeStore : PairingChallengeStore {
    private val challenges =
        ConcurrentHashMap<String, PairingChallengeService.PairingChallengeRecord>()

    override fun get(challengeId: String): PairingChallengeService.PairingChallengeRecord? =
        challenges[challengeId]

    override fun listAll(): List<PairingChallengeService.PairingChallengeRecord> =
        challenges.values.toList()

    override fun upsert(record: PairingChallengeService.PairingChallengeRecord) {
        challenges[record.challengeId] = record
    }

    override fun delete(challengeId: String): Boolean = challenges.remove(challengeId) != null

    fun clear() {
        challenges.clear()
    }
}
