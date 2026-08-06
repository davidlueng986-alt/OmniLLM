package com.omnillm.data.persistence

/**
 * Durable tool proposal / result claim rows (FEAT-TOOLS / DATA-OWNERSHIP).
 *
 * Control-plane sole writer (ADR-010). UI never holds these DAOs (INV-001).
 * Arguments live in [ToolProposalRow.argumentsJson] for host recovery only —
 * observability traces must redact (FEAT-TOOLS §6).
 */

object ToolProposalLedgerStates {
    val ALL: Set<String> = setOf(
        "PROPOSED",
        "HOST_CLAIMED",
        "RESULT_COMMITTED",
        "CONFLICT",
        "UNCERTAIN",
        "CANCELLED",
    )
}

data class ToolProposalRow(
    val proposalId: String,
    val requestId: String,
    val toolId: String,
    val schemaDigest: String,
    val argumentsJson: String,
    val attempt: Long,
    val state: String,
    val createdAtEpochMs: Long,
)

data class ToolResultClaimRow(
    val proposalId: String,
    val idempotencyKey: String,
    val requestId: String,
    val attempt: Long,
    val resultPayloadDigest: String,
    val isError: Boolean,
    val submittedAtEpochMs: Long,
)

interface ToolProposalRecordDao {
    fun findByProposalId(proposalId: String): ToolProposalRow?

    fun listByRequestId(requestId: String): List<ToolProposalRow>

    fun upsert(row: ToolProposalRow)

    fun updateState(proposalId: String, state: String): Boolean
}

interface ToolResultClaimDao {
    fun find(proposalId: String, idempotencyKey: String): ToolResultClaimRow?

    fun upsert(row: ToolResultClaimRow)
}

/**
 * Bundled tool proposal ledger ports for control-plane sole writer (ADR-010).
 */
interface ToolProposalLedgerPorts : ControlPlaneWriter {
    val proposals: ToolProposalRecordDao
    val claims: ToolResultClaimDao
    val tx: ClaimLedgerTransaction
}
