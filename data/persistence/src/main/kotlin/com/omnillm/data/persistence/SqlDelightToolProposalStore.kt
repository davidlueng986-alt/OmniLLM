package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.ToolResultClaimDao
import com.omnillm.core.ports.ledger.ToolProposalRow
import com.omnillm.core.ports.ledger.ToolResultClaimRow
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.ToolProposalLedgerPorts
import com.omnillm.core.ports.ledger.ToolProposalLedgerStates
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.ToolProposalRecordDao

/**
 * SQLDelight-backed [ToolProposalLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Tables: `tool_proposals`, `tool_result_claims`.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 */
class SqlDelightToolProposalStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : ToolProposalLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val proposals: ToolProposalRecordDao = object : ToolProposalRecordDao {
        override fun findByProposalId(proposalId: String): ToolProposalRow? =
            database.toolProposalsQueries
                .selectByProposalId(proposalId)
                .executeAsOneOrNull()
                ?.toProposalRow()

        override fun listByRequestId(requestId: String): List<ToolProposalRow> =
            database.toolProposalsQueries
                .listByRequestId(requestId)
                .executeAsList()
                .map { it.toProposalRow() }

        override fun upsert(row: ToolProposalRow) {
            require(row.state in ToolProposalLedgerStates.ALL) {
                "unknown tool proposal state: ${row.state}"
            }
            database.toolProposalsQueries.upsertProposal(
                proposal_id = row.proposalId,
                request_id = row.requestId,
                tool_id = row.toolId,
                schema_digest = row.schemaDigest,
                arguments_json = row.argumentsJson,
                attempt = row.attempt,
                state = row.state,
                created_at_epoch_ms = row.createdAtEpochMs,
            )
        }

        override fun updateState(proposalId: String, state: String): Boolean {
            require(state in ToolProposalLedgerStates.ALL) {
                "unknown tool proposal state: $state"
            }
            if (findByProposalId(proposalId) == null) return false
            database.toolProposalsQueries.updateState(state, proposalId)
            return true
        }
    }

    override val claims: ToolResultClaimDao = object : ToolResultClaimDao {
        override fun find(proposalId: String, idempotencyKey: String): ToolResultClaimRow? =
            database.toolResultClaimsQueries
                .selectByProposalAndKey(proposalId, idempotencyKey)
                .executeAsOneOrNull()
                ?.toClaimRow()

        override fun upsert(row: ToolResultClaimRow) {
            database.toolResultClaimsQueries.upsertClaim(
                proposal_id = row.proposalId,
                idempotency_key = row.idempotencyKey,
                request_id = row.requestId,
                attempt = row.attempt,
                result_payload_digest = row.resultPayloadDigest,
                is_error = if (row.isError) 1L else 0L,
                submitted_at_epoch_ms = row.submittedAtEpochMs,
            )
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun com.omnillm.data.persistence.Tool_proposals.toProposalRow(): ToolProposalRow =
    ToolProposalRow(
        proposalId = proposal_id,
        requestId = request_id,
        toolId = tool_id,
        schemaDigest = schema_digest,
        argumentsJson = arguments_json,
        attempt = attempt,
        state = state,
        createdAtEpochMs = created_at_epoch_ms,
    )

private fun com.omnillm.data.persistence.Tool_result_claims.toClaimRow(): ToolResultClaimRow =
    ToolResultClaimRow(
        proposalId = proposal_id,
        idempotencyKey = idempotency_key,
        requestId = request_id,
        attempt = attempt,
        resultPayloadDigest = result_payload_digest,
        isError = is_error != 0L,
        submittedAtEpochMs = submitted_at_epoch_ms,
    )

