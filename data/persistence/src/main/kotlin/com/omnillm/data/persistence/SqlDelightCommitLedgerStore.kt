package com.omnillm.data.persistence

/**
 * SQLDelight-backed [CommitLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Tables: `commit_records`, `prepared_operations`, `commit_resource_bindings`.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 */
class SqlDelightCommitLedgerStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : CommitLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val commits: CommitRecordDao = object : CommitRecordDao {
        override fun findByCommitId(commitId: String): CommitRecordRow? =
            database.commitRecordsQueries
                .selectByCommitId(commitId)
                .executeAsOneOrNull()
                ?.toCommitRow()

        override fun findByNonceDigest(commitNonceDigest: String): CommitRecordRow? =
            database.commitRecordsQueries
                .selectByNonceDigest(commitNonceDigest)
                .executeAsOneOrNull()
                ?.toCommitRow()

        override fun insert(row: CommitRecordRow) {
            require(row.state in CommitLedgerStates.ALL)
            database.commitRecordsQueries.insertCommit(
                commit_id = row.commitId,
                request_id = row.requestId,
                principal_id = row.principalId,
                plan_id = row.planId,
                reservation_id = row.reservationId,
                revision_lease_id = row.revisionLeaseId,
                issuer_boot_id = row.issuerBootId,
                runtime_epoch = row.runtimeEpoch,
                revocation_epoch = row.revocationEpoch,
                source_session_epoch = row.sourceSessionEpoch,
                target_policy_digest = row.targetPolicyDigest,
                engine_build_id = row.engineBuildId,
                canonical_input_digest = row.canonicalInputDigest,
                commit_nonce_digest = row.commitNonceDigest,
                state = row.state,
                result_json = row.resultJson,
                error_code = row.errorCode,
                reconciliation_disposition = row.reconciliationDisposition,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
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
            if (findByCommitId(commitId) == null) return false
            database.commitRecordsQueries.updateState(
                state = state,
                result_json = resultJson,
                error_code = errorCode,
                reconciliation_disposition = reconciliationDisposition,
                updated_at = updatedAt,
                commit_id = commitId,
            )
            return true
        }

        override fun listOpen(): List<CommitRecordRow> =
            database.commitRecordsQueries
                .listOpen()
                .executeAsList()
                .map { it.toCommitRow() }
    }

    override val prepared: PreparedOperationDao = object : PreparedOperationDao {
        override fun findByPreparedOperationId(preparedOperationId: String): PreparedOperationRow? =
            database.preparedOperationsQueries
                .selectById(preparedOperationId)
                .executeAsOneOrNull()
                ?.toPreparedRow()

        override fun findByCommitId(commitId: String): PreparedOperationRow? =
            database.preparedOperationsQueries
                .selectByCommitId(commitId)
                .executeAsOneOrNull()
                ?.toPreparedRow()

        override fun findByOperationId(operationId: String): PreparedOperationRow? =
            database.preparedOperationsQueries
                .selectByOperationId(operationId)
                .executeAsOneOrNull()
                ?.toPreparedRow()

        override fun insert(row: PreparedOperationRow) {
            require(row.state in PreparedOperationLedgerStates.ALL)
            database.preparedOperationsQueries.insertPrepared(
                prepared_operation_id = row.preparedOperationId,
                operation_id = row.operationId,
                request_id = row.requestId,
                commit_id = row.commitId,
                principal_id = row.principalId,
                reservation_id = row.reservationId,
                revision_lease_id = row.revisionLeaseId,
                issuer_boot_id = row.issuerBootId,
                runtime_epoch = row.runtimeEpoch,
                revocation_epoch = row.revocationEpoch,
                source_session_id = row.sourceSessionId,
                source_session_epoch = row.sourceSessionEpoch,
                target_session_id = row.targetSessionId,
                canonical_input_digest = row.canonicalInputDigest,
                state = row.state,
                start_claimed_at = row.startClaimedAt,
                result_json = row.resultJson,
                error_code = row.errorCode,
                reconciliation_disposition = row.reconciliationDisposition,
                resource_version = row.resourceVersion,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
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
            if (findByPreparedOperationId(preparedOperationId) == null) return false
            database.preparedOperationsQueries.updateState(
                state = state,
                start_claimed_at = startClaimedAt,
                result_json = resultJson,
                error_code = errorCode,
                reconciliation_disposition = reconciliationDisposition,
                resource_version = resourceVersion,
                updated_at = updatedAt,
                prepared_operation_id = preparedOperationId,
            )
            return true
        }
    }

    override val bindings: CommitResourceBindingDao = object : CommitResourceBindingDao {
        override fun listByCommitId(commitId: String): List<CommitResourceBindingRow> =
            database.commitResourceBindingsQueries
                .listByCommitId(commitId)
                .executeAsList()
                .map { it.toBindingRow() }

        override fun upsert(row: CommitResourceBindingRow) {
            database.commitResourceBindingsQueries.upsertBinding(
                commit_id = row.commitId,
                allocation_id = row.allocationId,
                binding_role = row.bindingRole,
                resource_vector_digest = row.resourceVectorDigest,
                disposition = row.disposition,
                updated_at = row.updatedAt,
            )
        }

        override fun updateDisposition(
            commitId: String,
            allocationId: String,
            bindingRole: String,
            disposition: String,
            updatedAt: String,
        ): Boolean {
            require(disposition in CommitResourceBindingDispositions.ALL)
            val existing = listByCommitId(commitId).any {
                it.allocationId == allocationId && it.bindingRole == bindingRole
            }
            if (!existing) return false
            database.commitResourceBindingsQueries.updateDisposition(
                disposition = disposition,
                updated_at = updatedAt,
                commit_id = commitId,
                allocation_id = allocationId,
                binding_role = bindingRole,
            )
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Commit_records.toCommitRow(): CommitRecordRow =
    CommitRecordRow(
        commitId = commit_id,
        requestId = request_id,
        principalId = principal_id,
        planId = plan_id,
        reservationId = reservation_id,
        revisionLeaseId = revision_lease_id,
        issuerBootId = issuer_boot_id,
        runtimeEpoch = runtime_epoch,
        revocationEpoch = revocation_epoch,
        sourceSessionEpoch = source_session_epoch,
        targetPolicyDigest = target_policy_digest,
        engineBuildId = engine_build_id,
        canonicalInputDigest = canonical_input_digest,
        commitNonceDigest = commit_nonce_digest,
        state = state,
        resultJson = result_json,
        errorCode = error_code,
        reconciliationDisposition = reconciliation_disposition,
        createdAt = created_at,
        updatedAt = updated_at,
    )

private fun Prepared_operations.toPreparedRow(): PreparedOperationRow =
    PreparedOperationRow(
        preparedOperationId = prepared_operation_id,
        operationId = operation_id,
        requestId = request_id,
        commitId = commit_id,
        principalId = principal_id,
        reservationId = reservation_id,
        revisionLeaseId = revision_lease_id,
        issuerBootId = issuer_boot_id,
        runtimeEpoch = runtime_epoch,
        revocationEpoch = revocation_epoch,
        sourceSessionId = source_session_id,
        sourceSessionEpoch = source_session_epoch,
        targetSessionId = target_session_id,
        canonicalInputDigest = canonical_input_digest,
        state = state,
        startClaimedAt = start_claimed_at,
        resultJson = result_json,
        errorCode = error_code,
        reconciliationDisposition = reconciliation_disposition,
        resourceVersion = resource_version,
        createdAt = created_at,
        updatedAt = updated_at,
    )

private fun Commit_resource_bindings.toBindingRow(): CommitResourceBindingRow =
    CommitResourceBindingRow(
        commitId = commit_id,
        allocationId = allocation_id,
        bindingRole = binding_role,
        resourceVectorDigest = resource_vector_digest,
        disposition = disposition,
        updatedAt = updated_at,
    )
