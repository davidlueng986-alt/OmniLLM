package com.omnillm.data.persistence

/**
 * Control-plane REVISION_LEASE durable rows (CORE-MODEL §9 / DATA-OWNERSHIP).
 *
 * Authority base: `specs/database/omnillm-schema.sql#revision_leases`.
 */

/** REVISION_LEASE FSM states from specs/state-machines.yaml#REVISION_LEASE. */
object RevisionLeaseLedgerStates {
    val ALL: Set<String> = setOf("ACTIVE", "DRAINING", "RELEASED")
    val TERMINAL: Set<String> = setOf("RELEASED")
    val BLOCKS_DELETE: Set<String> = setOf("ACTIVE", "DRAINING")
}

data class RevisionLeaseRecordRow(
    val leaseId: String,
    val revisionId: String,
    val requestId: String,
    val principalId: String,
    val runtimeEpoch: Long,
    val state: String,
    val installationId: String? = null,
    val referenceCount: Int = 1,
    val expiresAt: String? = null,
    val expiresAtMonotonic: Long? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(leaseId.isNotEmpty()) { "leaseId must be non-empty" }
        require(revisionId.isNotEmpty()) { "revisionId must be non-empty" }
        require(requestId.isNotEmpty()) { "requestId must be non-empty" }
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(state in RevisionLeaseLedgerStates.ALL) { "unknown lease state: $state" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(referenceCount >= 0) { "referenceCount must be non-negative" }
    }
}
