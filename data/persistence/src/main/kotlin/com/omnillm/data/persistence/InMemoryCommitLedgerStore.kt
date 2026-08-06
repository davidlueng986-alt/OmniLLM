package com.omnillm.data.persistence

/**
 * Thread-safe in-memory implementation of commit recovery ledgers.
 *
 * **Test-only.** Production wires [SqlDelightCommitLedgerStore] via
 * [ControlPlaneDatabase]. Do not use this store from live RuntimeControlPlane.
 *
 * Semantics match schema uniqueness:
 * - PK commit_id / prepared_operation_id
 * - UNIQUE plan_id, commit_nonce_digest on commit_records
 * - UNIQUE operation_id, commit_id on prepared_operations
 *
 * **Not process-crash durable.**
 */
class InMemoryCommitLedgerStore(
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : CommitLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    private val lock = Any()

    private val commitsById = linkedMapOf<String, CommitRecordRow>()
    private val commitsByNonce = linkedMapOf<String, CommitRecordRow>()
    private val commitsByPlan = linkedMapOf<String, CommitRecordRow>()
    private val preparedById = linkedMapOf<String, PreparedOperationRow>()
    private val preparedByCommit = linkedMapOf<String, PreparedOperationRow>()
    private val preparedByOp = linkedMapOf<String, PreparedOperationRow>()
    private val bindingsByKey = linkedMapOf<String, CommitResourceBindingRow>()

    override val commits: CommitRecordDao = object : CommitRecordDao {
        override fun findByCommitId(commitId: String): CommitRecordRow? =
            synchronized(lock) { commitsById[commitId] }

        override fun findByNonceDigest(commitNonceDigest: String): CommitRecordRow? =
            synchronized(lock) { commitsByNonce[commitNonceDigest] }

        override fun insert(row: CommitRecordRow) {
            require(row.state in CommitLedgerStates.ALL)
            synchronized(lock) {
                check(row.commitId !in commitsById) { "duplicate commit_id: ${row.commitId}" }
                check(row.commitNonceDigest !in commitsByNonce) {
                    "duplicate commit_nonce_digest"
                }
                check(row.planId !in commitsByPlan) { "duplicate plan_id: ${row.planId}" }
                commitsById[row.commitId] = row
                commitsByNonce[row.commitNonceDigest] = row
                commitsByPlan[row.planId] = row
            }
        }

        override fun updateState(
            commitId: String,
            state: String,
            resultJson: String?,
            errorCode: String?,
            reconciliationDisposition: String?,
            updatedAt: String,
        ): Boolean {
            require(state in CommitLedgerStates.ALL)
            synchronized(lock) {
                val existing = commitsById[commitId] ?: return false
                val updated = existing.copy(
                    state = state,
                    resultJson = resultJson,
                    errorCode = errorCode,
                    reconciliationDisposition = reconciliationDisposition,
                    updatedAt = updatedAt,
                )
                commitsById[commitId] = updated
                commitsByNonce[existing.commitNonceDigest] = updated
                commitsByPlan[existing.planId] = updated
                return true
            }
        }

        override fun listOpen(): List<CommitRecordRow> =
            synchronized(lock) {
                commitsById.values.filter { it.state in CommitLedgerStates.OPEN }
            }
    }

    override val prepared: PreparedOperationDao = object : PreparedOperationDao {
        override fun findByPreparedOperationId(preparedOperationId: String): PreparedOperationRow? =
            synchronized(lock) { preparedById[preparedOperationId] }

        override fun findByCommitId(commitId: String): PreparedOperationRow? =
            synchronized(lock) { preparedByCommit[commitId] }

        override fun findByOperationId(operationId: String): PreparedOperationRow? =
            synchronized(lock) { preparedByOp[operationId] }

        override fun insert(row: PreparedOperationRow) {
            require(row.state in PreparedOperationLedgerStates.ALL)
            synchronized(lock) {
                check(row.preparedOperationId !in preparedById) {
                    "duplicate prepared_operation_id"
                }
                check(row.commitId !in preparedByCommit) { "duplicate prepared commit_id" }
                check(row.operationId !in preparedByOp) { "duplicate operation_id" }
                preparedById[row.preparedOperationId] = row
                preparedByCommit[row.commitId] = row
                preparedByOp[row.operationId] = row
            }
        }

        override fun updateState(
            preparedOperationId: String,
            state: String,
            startClaimedAt: String?,
            resultJson: String?,
            errorCode: String?,
            reconciliationDisposition: String?,
            resourceVersion: Long,
            updatedAt: String,
        ): Boolean {
            require(state in PreparedOperationLedgerStates.ALL)
            synchronized(lock) {
                val existing = preparedById[preparedOperationId] ?: return false
                val updated = existing.copy(
                    state = state,
                    startClaimedAt = startClaimedAt,
                    resultJson = resultJson,
                    errorCode = errorCode,
                    reconciliationDisposition = reconciliationDisposition,
                    resourceVersion = resourceVersion,
                    updatedAt = updatedAt,
                )
                preparedById[preparedOperationId] = updated
                preparedByCommit[existing.commitId] = updated
                preparedByOp[existing.operationId] = updated
                return true
            }
        }
    }

    override val bindings: CommitResourceBindingDao = object : CommitResourceBindingDao {
        override fun listByCommitId(commitId: String): List<CommitResourceBindingRow> =
            synchronized(lock) {
                bindingsByKey.values.filter { it.commitId == commitId }
            }

        override fun upsert(row: CommitResourceBindingRow) {
            synchronized(lock) {
                bindingsByKey[bindingKey(row.commitId, row.allocationId, row.bindingRole)] = row
            }
        }

        override fun updateDisposition(
            commitId: String,
            allocationId: String,
            bindingRole: String,
            disposition: String,
            updatedAt: String,
        ): Boolean {
            require(disposition in CommitResourceBindingDispositions.ALL)
            synchronized(lock) {
                val key = bindingKey(commitId, allocationId, bindingRole)
                val existing = bindingsByKey[key] ?: return false
                bindingsByKey[key] = existing.copy(disposition = disposition, updatedAt = updatedAt)
                return true
            }
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T = synchronized(lock) { block() }
    }

    private fun bindingKey(commitId: String, allocationId: String, role: String): String =
        "$commitId\u0000$allocationId\u0000$role"
}
