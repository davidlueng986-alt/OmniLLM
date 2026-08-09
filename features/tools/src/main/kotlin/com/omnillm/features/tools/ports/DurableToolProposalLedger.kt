package com.omnillm.features.tools.ports

import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.ToolProposalLedgerPorts
import com.omnillm.core.ports.ledger.ToolProposalRow
import com.omnillm.core.ports.ledger.ToolResultClaimRow
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.policy.StoredToolResultClaim

/**
 * SQLite-backed [ToolProposalLedgerPort] for production control plane (ADR-010).
 *
 * Maps domain proposals/claims ↔ [ToolProposalLedgerPorts] rows. Opens only through
 * runtime [com.omnillm.data.persistence.ControlPlaneDatabase] — UI never holds this
 * writer (INV-001).
 *
 * Arguments are retained for host claim recovery; traces must redact (FEAT-TOOLS §6).
 */
class DurableToolProposalLedger(
    private val ledger: ToolProposalLedgerPorts,
) : ToolProposalLedgerPort {

    init {
        SingleWriterPolicy.assertWriterAllowed(ledger.writerRole)
    }

    override fun putProposal(proposal: ToolProposal) {
        ledger.tx.inTransaction {
            ledger.proposals.upsert(proposal.toRow())
        }
    }

    override fun getProposal(proposalId: String): ToolProposal? =
        ledger.proposals.findByProposalId(proposalId)?.toDomain()

    override fun listProposals(requestId: String): List<ToolProposal> =
        ledger.proposals.listByRequestId(requestId).map { it.toDomain() }

    override fun updateProposalState(proposalId: String, state: ToolProposalState): ToolProposal? {
        return ledger.tx.inTransaction {
            val cur = ledger.proposals.findByProposalId(proposalId) ?: return@inTransaction null
            ledger.proposals.updateState(proposalId, state.name)
            cur.copy(state = state.name).toDomain()
        }
    }

    override fun putResultClaim(claim: StoredToolResultClaim) {
        ledger.tx.inTransaction {
            ledger.claims.upsert(
                ToolResultClaimRow(
                    proposalId = claim.proposalId,
                    idempotencyKey = claim.idempotencyKey,
                    requestId = claim.requestId,
                    attempt = claim.attempt.toLong(),
                    resultPayloadDigest = claim.resultPayloadDigest,
                    isError = claim.isError,
                    submittedAtEpochMs = claim.submittedAtEpochMs,
                ),
            )
        }
    }

    override fun getResultClaim(proposalId: String, idempotencyKey: String): StoredToolResultClaim? =
        ledger.claims.find(proposalId, idempotencyKey)?.toDomain()
}

private fun ToolProposal.toRow(): ToolProposalRow =
    ToolProposalRow(
        proposalId = proposalId,
        requestId = requestId,
        toolId = toolId,
        schemaDigest = schemaDigest,
        argumentsJson = argumentsJson,
        attempt = attempt.toLong(),
        state = state.name,
        createdAtEpochMs = createdAtEpochMs,
    )

private fun ToolProposalRow.toDomain(): ToolProposal =
    ToolProposal(
        proposalId = proposalId,
        requestId = requestId,
        toolId = toolId,
        schemaDigest = schemaDigest,
        argumentsJson = argumentsJson,
        attempt = attempt.toInt().coerceAtLeast(1),
        state = ToolProposalState.fromWire(state) ?: ToolProposalState.PROPOSED,
        createdAtEpochMs = createdAtEpochMs,
    )

private fun ToolResultClaimRow.toDomain(): StoredToolResultClaim =
    StoredToolResultClaim(
        requestId = requestId,
        proposalId = proposalId,
        attempt = attempt.toInt().coerceAtLeast(1),
        idempotencyKey = idempotencyKey,
        resultPayloadDigest = resultPayloadDigest,
        isError = isError,
        submittedAtEpochMs = submittedAtEpochMs,
    )
