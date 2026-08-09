package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.CommandLedgerStates
import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.core.ports.ledger.IdempotentCommandClaimRow

/**
 * SQLDelight-backed [ClaimLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Tables: `inference_requests`, `request_attempts`, `request_terminals`, `idempotent_commands`.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 */
class SqlDelightClaimLedgerStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : ClaimLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val requests: InferenceRequestDao = object : InferenceRequestDao {
        override fun findByRequestId(requestId: String): InferenceRequestClaimRow? =
            database.inferenceRequestsQueries
                .selectByRequestId(requestId)
                .executeAsOneOrNull()
                ?.toClaimRow()

        override fun findByClaimKey(
            principalId: String,
            operationKind: String,
            idempotencyKey: String,
        ): InferenceRequestClaimRow? =
            database.inferenceRequestsQueries
                .selectByClaimKey(principalId, operationKind, idempotencyKey)
                .executeAsOneOrNull()
                ?.toClaimRow()

        override fun insert(row: InferenceRequestClaimRow) {
            require(row.state in RequestLedgerStates.ALL) { "unknown request state: ${row.state}" }
            database.inferenceRequestsQueries.insertRequest(
                request_id = row.requestId,
                principal_id = row.principalId,
                operation_kind = row.operationKind,
                idempotency_key = row.idempotencyKey,
                canonical_request_digest = row.canonicalRequestDigest,
                revision_id = row.revisionId,
                state = row.state,
                resource_version = row.resourceVersion,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
        }

        override fun updateState(
            requestId: String,
            state: String,
            updatedAt: String,
            resourceVersion: Long,
        ): Boolean {
            require(state in RequestLedgerStates.ALL) { "unknown request state: $state" }
            if (findByRequestId(requestId) == null) return false
            database.inferenceRequestsQueries.updateState(
                state = state,
                updated_at = updatedAt,
                resource_version = resourceVersion,
                request_id = requestId,
            )
            return true
        }

        override fun listNonTerminal(): List<InferenceRequestClaimRow> =
            database.inferenceRequestsQueries
                .listNonTerminal()
                .executeAsList()
                .map { it.toClaimRow() }
    }

    override val attempts: RequestAttemptDao = object : RequestAttemptDao {
        override fun insert(row: RequestAttemptRow) {
            database.requestAttemptsQueries.insertAttempt(
                request_id = row.requestId,
                attempt_no = row.attemptNo.toLong(),
                runtime_epoch = row.runtimeEpoch,
                worker_instance_id = row.workerInstanceId,
                state = row.state,
                started_at = row.startedAt,
                ended_at = row.endedAt,
            )
        }

        override fun listByRequestId(requestId: String): List<RequestAttemptRow> =
            database.requestAttemptsQueries
                .selectByRequestId(requestId)
                .executeAsList()
                .map { it.toAttemptRow() }

        override fun nextAttemptNo(requestId: String): Int {
            val max = database.requestAttemptsQueries
                .selectMaxAttemptNo(requestId)
                .executeAsOne()
            return (max + 1L).toInt()
        }

        override fun endAttempt(
            requestId: String,
            attemptNo: Int,
            state: String,
            endedAt: String,
        ): Boolean {
            val existing = listByRequestId(requestId).any { it.attemptNo == attemptNo }
            if (!existing) return false
            database.requestAttemptsQueries.endAttempt(
                state = state,
                ended_at = endedAt,
                request_id = requestId,
                attempt_no = attemptNo.toLong(),
            )
            return true
        }
    }

    override val terminals: RequestTerminalDao = object : RequestTerminalDao {
        override fun findByRequestId(requestId: String): RequestTerminalRow? =
            database.requestTerminalsQueries
                .selectByRequestId(requestId)
                .executeAsOneOrNull()
                ?.toTerminalRow()

        override fun insertIfAbsent(row: RequestTerminalRow): Boolean {
            if (findByRequestId(row.requestId) != null) return false
            return try {
                database.requestTerminalsQueries.insertTerminal(
                    request_id = row.requestId,
                    terminal_state = row.terminalState,
                    output_digest = row.outputDigest,
                    error_code = row.errorCode,
                    terminal_seq = row.terminalSeq,
                    completed_at = row.completedAt,
                )
                true
            } catch (_: Exception) {
                // Concurrent unique PK (exactly-one invariant).
                false
            }
        }
    }

    override val commands: IdempotentCommandDao = object : IdempotentCommandDao {
        override fun findByCommandId(commandId: String): IdempotentCommandClaimRow? =
            database.idempotentCommandsQueries
                .selectByCommandId(commandId)
                .executeAsOneOrNull()
                ?.toCommandRow()

        override fun findByClaimKey(
            principalId: String,
            operationKind: String,
            idempotencyKey: String,
        ): IdempotentCommandClaimRow? =
            database.idempotentCommandsQueries
                .selectByClaimKey(principalId, operationKind, idempotencyKey)
                .executeAsOneOrNull()
                ?.toCommandRow()

        override fun insert(row: IdempotentCommandClaimRow) {
            require(row.state in CommandLedgerStates.ALL) { "unknown command state: ${row.state}" }
            database.idempotentCommandsQueries.insertCommand(
                command_id = row.commandId,
                principal_id = row.principalId,
                operation_kind = row.operationKind,
                idempotency_key = row.idempotencyKey,
                expected_version = row.expectedVersion,
                canonical_input_digest = row.canonicalInputDigest,
                state = row.state,
                affected_resource_id = row.affectedResourceId,
                result_json = row.resultJson,
                error_code = row.errorCode,
                reconciliation_disposition = row.reconciliationDisposition,
                resource_version = row.resourceVersion,
                expires_at = row.expiresAt,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
        }

        override fun updateResult(
            commandId: String,
            state: String,
            resultJson: String?,
            errorCode: String?,
            affectedResourceId: String?,
            reconciliationDisposition: String?,
            resourceVersion: Long,
            updatedAt: String,
        ): Boolean {
            require(state in CommandLedgerStates.ALL) { "unknown command state: $state" }
            if (findByCommandId(commandId) == null) return false
            database.idempotentCommandsQueries.updateResult(
                state = state,
                result_json = resultJson,
                error_code = errorCode,
                affected_resource_id = affectedResourceId,
                reconciliation_disposition = reconciliationDisposition,
                resource_version = resourceVersion,
                updated_at = updatedAt,
                command_id = commandId,
            )
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Inference_requests.toClaimRow(): InferenceRequestClaimRow =
    InferenceRequestClaimRow(
        requestId = request_id,
        principalId = principal_id,
        operationKind = operation_kind,
        idempotencyKey = idempotency_key,
        canonicalRequestDigest = canonical_request_digest,
        revisionId = revision_id,
        state = state,
        resourceVersion = resource_version,
        createdAt = created_at,
        updatedAt = updated_at,
    )

private fun Request_attempts.toAttemptRow(): RequestAttemptRow =
    RequestAttemptRow(
        requestId = request_id,
        attemptNo = attempt_no.toInt(),
        runtimeEpoch = runtime_epoch,
        workerInstanceId = worker_instance_id,
        state = state,
        startedAt = started_at,
        endedAt = ended_at,
    )

private fun Request_terminals.toTerminalRow(): RequestTerminalRow =
    RequestTerminalRow(
        requestId = request_id,
        terminalState = terminal_state,
        outputDigest = output_digest,
        errorCode = error_code,
        terminalSeq = terminal_seq,
        completedAt = completed_at,
    )

private fun Idempotent_commands.toCommandRow(): IdempotentCommandClaimRow =
    IdempotentCommandClaimRow(
        commandId = command_id,
        principalId = principal_id,
        operationKind = operation_kind,
        idempotencyKey = idempotency_key,
        expectedVersion = expected_version,
        canonicalInputDigest = canonical_input_digest,
        state = state,
        affectedResourceId = affected_resource_id,
        resultJson = result_json,
        errorCode = error_code,
        reconciliationDisposition = reconciliation_disposition,
        resourceVersion = resource_version,
        expiresAt = expires_at,
        createdAt = created_at,
        updatedAt = updated_at,
    )

