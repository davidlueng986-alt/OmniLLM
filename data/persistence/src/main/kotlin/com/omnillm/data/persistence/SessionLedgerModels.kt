package com.omnillm.data.persistence

/**
 * Control-plane Session durable row models (CORE-SESSION / DATA-OWNERSHIP / INV-007).
 *
 * Authority base: `specs/database/omnillm-schema.sql#sessions`.
 * Extended with owner + LoadKey dimensions + epochs + recovery disposition so
 * POISONED / ORPHANED fencing survives runtime restart.
 *
 * **Durable:** descriptor fields, owner, state, disposition, epochs, allocation id.
 * **Not durable:** native KV bytes, native pointers, free-pool membership,
 * live operation counts (process-local; reset on reload). Do not claim KV
 * survival unless an engine snapshot protocol is present.
 */

/** SESSION FSM states from specs/state-machines.yaml#SESSION. */
object SessionLedgerStates {
    val ALL: Set<String> = setOf(
        "NEW",
        "ACTIVE",
        "DRAINING",
        "POISONED",
        "ORPHANED",
        "CLOSING",
        "CLOSED",
    )

    val TERMINAL: Set<String> = setOf("CLOSED")

    /** States that must never re-enter free pool (INV-007 / CORE-SESSION §5). */
    val NEVER_REPOOL: Set<String> = setOf("POISONED", "ORPHANED", "DRAINING", "CLOSING", "CLOSED")
}

/**
 * Recovery disposition persisted with the control-plane Session record.
 *
 * Distinct from engine-native snapshot availability: [CONTROL_PLANE_ONLY] means
 * the descriptor/state is durable but native KV is **not** proven after restart.
 */
object SessionRecoveryDispositions {
    const val CONTROL_PLANE_ONLY: String = "CONTROL_PLANE_ONLY"
    const val POISONED: String = "POISONED"
    const val ORPHANED: String = "ORPHANED"
    const val CLOSING: String = "CLOSING"
    const val CLOSED: String = "CLOSED"

    val ALL: Set<String> = setOf(
        CONTROL_PLANE_ONLY,
        POISONED,
        ORPHANED,
        CLOSING,
        CLOSED,
    )

    /** Map SESSION FSM state → durable disposition (fail closed for poison/orphan). */
    fun fromAggregateState(state: String): String =
        when (state) {
            "POISONED" -> POISONED
            "ORPHANED" -> ORPHANED
            "CLOSING" -> CLOSING
            "CLOSED" -> CLOSED
            // NEW / ACTIVE / DRAINING: record only — native KV not assumed after restart.
            else -> CONTROL_PLANE_ONLY
        }
}

/**
 * Durable control-plane Session row.
 *
 * [loadedModelId] is a control-plane projection id (may be synthetic until
 * model-manager attaches); it does **not** imply a live native LoadedModel.
 */
data class SessionRecordRow(
    val sessionId: String,
    val sessionEpoch: Long,
    val ownerKey: String,
    val principalId: String,
    val loadedModelId: String,
    val modelRevisionId: String,
    val engineBuildId: String,
    val backend: String,
    val deviceExecutionFingerprint: String,
    val templateEpoch: Long,
    val tokenizerEpoch: Long,
    val loadConfigurationDigest: String,
    val tokenizerDigest: String,
    val contextConfig: String,
    val committedTokenFingerprint: String? = null,
    val state: String,
    val allocationId: String,
    val revocationEpoch: Long = 0L,
    val runtimeEpoch: Long = 0L,
    val recoveryDisposition: String,
    val healthy: Boolean = true,
    val pinned: Boolean = false,
    val deliveredSeq: Long = 0L,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(sessionId.isNotEmpty()) { "sessionId must be non-empty" }
        require(sessionEpoch >= 0L) { "sessionEpoch must be non-negative" }
        require(ownerKey.isNotEmpty()) { "ownerKey must be non-empty" }
        require(state in SessionLedgerStates.ALL) { "unknown session state: $state" }
        require(recoveryDisposition in SessionRecoveryDispositions.ALL) {
            "unknown recovery disposition: $recoveryDisposition"
        }
        require(templateEpoch >= 0L) { "templateEpoch must be non-negative" }
        require(tokenizerEpoch >= 0L) { "tokenizerEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(deliveredSeq >= 0L) { "deliveredSeq must be non-negative" }
    }
}
