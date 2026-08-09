package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.data.persistence.CommitLedgerPorts
import com.omnillm.data.persistence.CommitLedgerStates
import com.omnillm.data.persistence.CommitRecordRow
import com.omnillm.data.persistence.CommitResourceBindingRow
import com.omnillm.data.persistence.PreparedOperationLedgerStates
import com.omnillm.data.persistence.PreparedOperationRow
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import java.security.MessageDigest

/**
 * Durable COMMIT / PreparedOperation recovery ledger (REL-RECOVERY, RR-001..RR-007).
 *
 * Hard rules:
 * - [recordIntent] **before** any worker receives commitLoad/commitInference
 * - Same [CommitId] with changed binding ⇒ IDEMPOTENCY_CONFLICT (RR-007)
 * - Reply loss / restart: [queryCommit] / [listOpenCommits] only — never blind re-EXECUTE
 * - Unprovable outcomes → UNCERTAIN_QUARANTINED (no blind mutation)
 *
 * Persistence via [CommitLedgerPorts] (control-plane sole writer, ADR-010).
 */
class CommitLedger(
    private val ports: CommitLedgerPorts,
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {
    init {
        SingleWriterPolicy.assertWriterAllowed(ports.writerRole)
    }

    /**
     * Persist INTENT_RECORDED before worker side effects.
     * Idempotent when the same Commit binding is re-claimed; conflicts on RR-007 divergence.
     */
    fun recordIntent(
        commit: Commit,
        targetPolicyDigest: Sha256Digest = Sha256Digest.parse("0".repeat(64)),
    ): ClaimOutcome<CommitRecordRow> {
        val now = clock()
        val nonceDigest = sha256Hex(commit.oneShotNonce)
        val digest = commit.canonicalInputDigest.hex
        return ports.tx.inTransaction {
            val byId = ports.commits.findByCommitId(commit.commitId.value)
            val byNonce = ports.commits.findByNonceDigest(nonceDigest)

            val candidate = CommitRecordRow(
                commitId = commit.commitId.value,
                requestId = commit.requestId.value,
                principalId = commit.principalId.value,
                planId = commit.planId.value,
                reservationId = commit.reservationId.value,
                revisionLeaseId = commit.revisionLeaseId.value,
                issuerBootId = commit.issuerBootId,
                runtimeEpoch = commit.runtimeEpoch,
                revocationEpoch = commit.revocationEpoch,
                sourceSessionEpoch = commit.sourceSessionEpoch,
                targetPolicyDigest = targetPolicyDigest.hex,
                engineBuildId = commit.engineBuildId.value,
                canonicalInputDigest = digest,
                commitNonceDigest = nonceDigest,
                state = StateMachines.COMMIT.initial, // INTENT_RECORDED
                createdAt = now,
                updatedAt = now,
            )

            if (byId != null) {
                return@inTransaction if (byId.bindingKey() == candidate.bindingKey()) {
                    ClaimOutcome.Existing(byId)
                } else {
                    conflict(
                        "commitId binding conflict (RR-007)",
                        commitId = commit.commitId.value,
                    )
                }
            }

            if (byNonce != null) {
                return@inTransaction if (byNonce.bindingKey() == candidate.bindingKey()) {
                    ClaimOutcome.Existing(byNonce)
                } else {
                    conflict(
                        "commit nonce already claimed under different binding",
                        commitId = commit.commitId.value,
                    )
                }
            }

            ports.commits.insert(candidate)
            ClaimOutcome.New(candidate)
        }
    }

    fun queryCommit(commitId: CommitId): CommitRecordRow? =
        ports.commits.findByCommitId(commitId.value)

    /** Open (non-terminal) commits for runtime-restart recovery (RR-003). */
    fun listOpenCommits(): List<CommitRecordRow> = ports.commits.listOpen()

    /**
     * Advance to EXECUTING after durable intent and before/while worker runs.
     * Illegal when already terminal.
     */
    fun markExecuting(commitId: CommitId): OmniResult<CommitRecordRow> =
        transition(commitId, "EXECUTING")

    /** Fence after worker/reply loss (RR-001/002) → RECONCILING. */
    fun markReconciling(commitId: CommitId): OmniResult<CommitRecordRow> =
        transition(commitId, "RECONCILING")

    /**
     * Terminal or result states: COMMITTED | ABORTED | UNCERTAIN_QUARANTINED | RESULT_RECORDED.
     * Identical re-publish of the same terminal is idempotent.
     */
    fun recordOutcome(
        commitId: CommitId,
        state: String,
        resultJson: String? = null,
        errorCode: String? = null,
        reconciliationDisposition: String? = null,
    ): OmniResult<CommitRecordRow> {
        if (state !in CommitLedgerStates.ALL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown commit state: $state",
                    details = mapOf("state" to state),
                ),
            )
        }
        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.commits.findByCommitId(commitId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "commit not found",
                        details = mapOf("commitId" to commitId.value),
                    ),
                )

            if (existing.state in CommitLedgerStates.TERMINAL) {
                if (
                    existing.state == state &&
                    existing.resultJson == resultJson &&
                    existing.errorCode == errorCode &&
                    existing.reconciliationDisposition == reconciliationDisposition
                ) {
                    return@inTransaction OmniResult.ok(existing)
                }
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "commit already terminal",
                        details = mapOf(
                            "commitId" to commitId.value,
                            "state" to existing.state,
                        ),
                    ),
                )
            }

            ports.commits.updateState(
                commitId = commitId.value,
                state = state,
                resultJson = resultJson,
                errorCode = errorCode,
                reconciliationDisposition = reconciliationDisposition,
                updatedAt = now,
            )
            OmniResult.ok(ports.commits.findByCommitId(commitId.value)!!)
        }
    }

    /**
     * Persist prepared operation **before** worker start (RR-005 one-shot).
     * Second start claim from STARTING is STATE_CONFLICT at FSM layer; ledger
     * rejects duplicate start claim when already past PREPARED.
     */
    fun recordPrepared(prepared: PreparedOperation): ClaimOutcome<PreparedOperationRow> {
        val now = clock()
        return ports.tx.inTransaction {
            val byId = ports.prepared.findByPreparedOperationId(
                prepared.preparedOperationId.value,
            )
            val byCommit = ports.prepared.findByCommitId(prepared.commitId.value)
            val byOp = ports.prepared.findByOperationId(prepared.operationId)

            val row = PreparedOperationRow(
                preparedOperationId = prepared.preparedOperationId.value,
                operationId = prepared.operationId,
                requestId = prepared.requestId.value,
                commitId = prepared.commitId.value,
                principalId = prepared.principalId.value,
                reservationId = prepared.reservationId.value,
                revisionLeaseId = prepared.revisionLeaseId.value,
                issuerBootId = prepared.issuerBootId,
                runtimeEpoch = prepared.runtimeEpoch,
                revocationEpoch = prepared.revocationEpoch,
                sourceSessionEpoch = prepared.sourceSessionEpoch,
                targetSessionId = prepared.targetSessionId?.value,
                canonicalInputDigest = prepared.canonicalInputDigest.hex,
                state = StateMachines.OPERATION.initial, // PREPARED
                createdAt = now,
                updatedAt = now,
            )

            if (byId != null) {
                return@inTransaction if (
                    byId.commitId == row.commitId &&
                    byId.operationId == row.operationId &&
                    byId.canonicalInputDigest == row.canonicalInputDigest
                ) {
                    ClaimOutcome.Existing(byId)
                } else {
                    conflict(
                        "preparedOperationId collision with different payload",
                        commitId = prepared.commitId.value,
                    )
                }
            }
            if (byCommit != null || byOp != null) {
                val existing = byCommit ?: byOp!!
                return@inTransaction if (
                    existing.preparedOperationId == row.preparedOperationId &&
                    existing.canonicalInputDigest == row.canonicalInputDigest
                ) {
                    ClaimOutcome.Existing(existing)
                } else {
                    conflict(
                        "prepared operation already bound to commit/operation",
                        commitId = prepared.commitId.value,
                    )
                }
            }

            ports.prepared.insert(row)
            ClaimOutcome.New(row)
        }
    }

    /**
     * One-shot start claim (RR-005). Second claim from non-PREPARED ⇒ STATE_CONFLICT.
     */
    fun claimStart(preparedOperationId: String): OmniResult<PreparedOperationRow> {
        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.prepared.findByPreparedOperationId(preparedOperationId)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "prepared operation not found",
                        details = mapOf("preparedOperationId" to preparedOperationId),
                    ),
                )
            if (existing.state != "PREPARED") {
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "start already claimed or operation not PREPARED",
                        details = mapOf(
                            "preparedOperationId" to preparedOperationId,
                            "state" to existing.state,
                        ),
                    ),
                )
            }
            ports.prepared.updateState(
                preparedOperationId = preparedOperationId,
                state = "STARTING",
                startClaimedAt = now,
                resultJson = existing.resultJson,
                errorCode = existing.errorCode,
                reconciliationDisposition = existing.reconciliationDisposition,
                resourceVersion = existing.resourceVersion + 1,
                updatedAt = now,
            )
            OmniResult.ok(ports.prepared.findByPreparedOperationId(preparedOperationId)!!)
        }
    }

    fun upsertResourceBinding(row: CommitResourceBindingRow) {
        ports.tx.inTransaction { ports.bindings.upsert(row) }
    }

    fun listBindings(commitId: CommitId): List<CommitResourceBindingRow> =
        ports.bindings.listByCommitId(commitId.value)

    private fun transition(commitId: CommitId, state: String): OmniResult<CommitRecordRow> {
        if (state !in CommitLedgerStates.ALL || state in CommitLedgerStates.TERMINAL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "use recordOutcome for terminal commit states",
                    details = mapOf("state" to state),
                ),
            )
        }
        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.commits.findByCommitId(commitId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "commit not found",
                        details = mapOf("commitId" to commitId.value),
                    ),
                )
            if (existing.state in CommitLedgerStates.TERMINAL) {
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "commit is already terminal",
                        details = mapOf(
                            "commitId" to commitId.value,
                            "state" to existing.state,
                        ),
                    ),
                )
            }
            if (existing.state == state) {
                return@inTransaction OmniResult.ok(existing)
            }
            ports.commits.updateState(
                commitId = commitId.value,
                state = state,
                resultJson = existing.resultJson,
                errorCode = existing.errorCode,
                reconciliationDisposition = existing.reconciliationDisposition,
                updatedAt = now,
            )
            OmniResult.ok(ports.commits.findByCommitId(commitId.value)!!)
        }
    }

    private fun conflict(message: String, commitId: String?): ClaimOutcome.Conflict {
        val details = buildMap {
            commitId?.let { put("commitId", it) }
        }
        return ClaimOutcome.Conflict(
            OmniError.IDEMPOTENCY_CONFLICT(message = message, details = details),
        )
    }

    companion object {
        fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
