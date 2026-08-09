package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.ControlPlaneWriter
import com.omnillm.core.ports.ledger.SingleWriterPolicy

/**
 * Control-plane DAO surfaces for commit recovery ledgers (REL-RECOVERY / DATA-OWNERSHIP).
 *
 * Implementations must only be opened by the runtime control plane writer
 * (ADR-010 / [SingleWriterPolicy]). Engine workers and UI must not hold writers.
 *
 * Authority tables:
 * - `commit_records`
 * - `prepared_operations`
 * - `commit_resource_bindings`
 */

interface CommitRecordDao {
    fun findByCommitId(commitId: String): CommitRecordRow?

    fun findByNonceDigest(commitNonceDigest: String): CommitRecordRow?

    fun insert(row: CommitRecordRow)

    fun updateState(
        commitId: String,
        state: String,
        resultJson: String?,
        errorCode: String?,
        reconciliationDisposition: String?,
        updatedAt: String,
    ): Boolean

    /** Non-terminal commits for runtime restart reconcile. */
    fun listOpen(): List<CommitRecordRow>
}

interface PreparedOperationDao {
    fun findByPreparedOperationId(preparedOperationId: String): PreparedOperationRow?

    fun findByCommitId(commitId: String): PreparedOperationRow?

    fun findByOperationId(operationId: String): PreparedOperationRow?

    fun insert(row: PreparedOperationRow)

    fun updateState(
        preparedOperationId: String,
        state: String,
        startClaimedAt: String?,
        resultJson: String?,
        errorCode: String?,
        reconciliationDisposition: String?,
        resourceVersion: Long,
        updatedAt: String,
    ): Boolean
}

interface CommitResourceBindingDao {
    fun listByCommitId(commitId: String): List<CommitResourceBindingRow>

    fun upsert(row: CommitResourceBindingRow)

    fun updateDisposition(
        commitId: String,
        allocationId: String,
        bindingRole: String,
        disposition: String,
        updatedAt: String,
    ): Boolean
}

/**
 * Bundled commit-ledger ports injected into control-plane recovery paths.
 * Marker [ControlPlaneWriter] documents single-writer ownership.
 */
interface CommitLedgerPorts : ControlPlaneWriter {
    val commits: CommitRecordDao
    val prepared: PreparedOperationDao
    val bindings: CommitResourceBindingDao
    val tx: ClaimLedgerTransaction
}

