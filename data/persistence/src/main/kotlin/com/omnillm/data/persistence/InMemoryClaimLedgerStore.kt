package com.omnillm.data.persistence

/**
 * Thread-safe in-memory implementation of claim ledger DAOs.
 *
 * **Test-only.** Production [com.omnillm.android.runtimeservice] wires
 * [SqlDelightClaimLedgerStore] via [ControlPlaneDatabase]. Do not use this
 * store from live RuntimeControlPlane.
 *
 * Semantics match schema uniqueness:
 * - UNIQUE (principal_id, operation_kind, idempotency_key) on requests/commands
 * - PK request_id / command_id
 * - one terminal per request_id
 */
class InMemoryClaimLedgerStore(
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : ClaimLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    private val lock = Any()

    private val requestsById = linkedMapOf<String, InferenceRequestClaimRow>()
    private val requestsByClaim = linkedMapOf<String, InferenceRequestClaimRow>()
    private val attemptsByRequest = linkedMapOf<String, MutableList<RequestAttemptRow>>()
    private val terminalsById = linkedMapOf<String, RequestTerminalRow>()
    private val commandsById = linkedMapOf<String, IdempotentCommandClaimRow>()
    private val commandsByClaim = linkedMapOf<String, IdempotentCommandClaimRow>()

    override val requests: InferenceRequestDao = object : InferenceRequestDao {
        override fun findByRequestId(requestId: String): InferenceRequestClaimRow? =
            synchronized(lock) { requestsById[requestId] }

        override fun findByClaimKey(
            principalId: String,
            operationKind: String,
            idempotencyKey: String,
        ): InferenceRequestClaimRow? =
            synchronized(lock) { requestsByClaim[claimKey(principalId, operationKind, idempotencyKey)] }

        override fun insert(row: InferenceRequestClaimRow) {
            require(row.state in RequestLedgerStates.ALL) { "unknown request state: ${row.state}" }
            synchronized(lock) {
                check(row.requestId !in requestsById) {
                    "duplicate request_id: ${row.requestId}"
                }
                val key = claimKey(row.principalId, row.operationKind, row.idempotencyKey)
                check(key !in requestsByClaim) {
                    "duplicate claim key for request"
                }
                requestsById[row.requestId] = row
                requestsByClaim[key] = row
            }
        }

        override fun updateState(
            requestId: String,
            state: String,
            updatedAt: String,
            resourceVersion: Long,
        ): Boolean {
            require(state in RequestLedgerStates.ALL) { "unknown request state: $state" }
            synchronized(lock) {
                val existing = requestsById[requestId] ?: return false
                val updated = existing.copy(
                    state = state,
                    updatedAt = updatedAt,
                    resourceVersion = resourceVersion,
                )
                requestsById[requestId] = updated
                requestsByClaim[
                    claimKey(existing.principalId, existing.operationKind, existing.idempotencyKey),
                ] = updated
                return true
            }
        }

        override fun listNonTerminal(): List<InferenceRequestClaimRow> =
            synchronized(lock) {
                requestsById.values
                    .filter { it.state !in RequestLedgerStates.TERMINAL }
                    .toList()
            }
    }

    override val attempts: RequestAttemptDao = object : RequestAttemptDao {
        override fun insert(row: RequestAttemptRow) {
            synchronized(lock) {
                check(row.requestId in requestsById) {
                    "request_attempts FK: unknown request_id ${row.requestId}"
                }
                val list = attemptsByRequest.getOrPut(row.requestId) { mutableListOf() }
                check(list.none { it.attemptNo == row.attemptNo }) {
                    "duplicate attempt (${row.requestId}, ${row.attemptNo})"
                }
                list.add(row)
                list.sortBy { it.attemptNo }
            }
        }

        override fun listByRequestId(requestId: String): List<RequestAttemptRow> =
            synchronized(lock) { attemptsByRequest[requestId]?.toList().orEmpty() }

        override fun nextAttemptNo(requestId: String): Int =
            synchronized(lock) {
                (attemptsByRequest[requestId]?.maxOfOrNull { it.attemptNo } ?: 0) + 1
            }

        override fun endAttempt(
            requestId: String,
            attemptNo: Int,
            state: String,
            endedAt: String,
        ): Boolean {
            synchronized(lock) {
                val list = attemptsByRequest[requestId] ?: return false
                val idx = list.indexOfFirst { it.attemptNo == attemptNo }
                if (idx < 0) return false
                list[idx] = list[idx].copy(state = state, endedAt = endedAt)
                return true
            }
        }
    }

    override val terminals: RequestTerminalDao = object : RequestTerminalDao {
        override fun findByRequestId(requestId: String): RequestTerminalRow? =
            synchronized(lock) { terminalsById[requestId] }

        override fun insertIfAbsent(row: RequestTerminalRow): Boolean {
            synchronized(lock) {
                check(row.requestId in requestsById) {
                    "request_terminals FK: unknown request_id ${row.requestId}"
                }
                if (row.requestId in terminalsById) return false
                terminalsById[row.requestId] = row
                return true
            }
        }
    }

    override val commands: IdempotentCommandDao = object : IdempotentCommandDao {
        override fun findByCommandId(commandId: String): IdempotentCommandClaimRow? =
            synchronized(lock) { commandsById[commandId] }

        override fun findByClaimKey(
            principalId: String,
            operationKind: String,
            idempotencyKey: String,
        ): IdempotentCommandClaimRow? =
            synchronized(lock) { commandsByClaim[claimKey(principalId, operationKind, idempotencyKey)] }

        override fun insert(row: IdempotentCommandClaimRow) {
            require(row.state in CommandLedgerStates.ALL) { "unknown command state: ${row.state}" }
            synchronized(lock) {
                check(row.commandId !in commandsById) {
                    "duplicate command_id: ${row.commandId}"
                }
                val key = claimKey(row.principalId, row.operationKind, row.idempotencyKey)
                check(key !in commandsByClaim) {
                    "duplicate claim key for command"
                }
                commandsById[row.commandId] = row
                commandsByClaim[key] = row
            }
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
            synchronized(lock) {
                val existing = commandsById[commandId] ?: return false
                val updated = existing.copy(
                    state = state,
                    resultJson = resultJson,
                    errorCode = errorCode,
                    affectedResourceId = affectedResourceId,
                    reconciliationDisposition = reconciliationDisposition,
                    resourceVersion = resourceVersion,
                    updatedAt = updatedAt,
                )
                commandsById[commandId] = updated
                commandsByClaim[
                    claimKey(existing.principalId, existing.operationKind, existing.idempotencyKey),
                ] = updated
                return true
            }
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T = synchronized(lock) { block() }
    }

    private fun claimKey(principalId: String, operationKind: String, idempotencyKey: String): String =
        "$principalId\u0000$operationKind\u0000$idempotencyKey"
}
