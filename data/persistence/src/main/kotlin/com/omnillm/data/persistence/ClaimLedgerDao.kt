package com.omnillm.data.persistence

/**
 * Control-plane DAO surfaces for request/command claim ledgers (DATA-OWNERSHIP).
 *
 * Implementations must only be opened by the runtime control plane writer
 * (ADR-010 / [SingleWriterPolicy]). Transport adapters and UI must not hold writers.
 *
 * Authority tables:
 * - `inference_requests`, `request_attempts`, `request_terminals`
 * - `idempotent_commands`
 */

/** Atomic claim/insert boundary for request + command ledgers. */
interface ClaimLedgerTransaction {
    fun <T> inTransaction(block: () -> T): T
}

interface InferenceRequestDao {
    fun findByRequestId(requestId: String): InferenceRequestClaimRow?

    fun findByClaimKey(
        principalId: String,
        operationKind: String,
        idempotencyKey: String,
    ): InferenceRequestClaimRow?

    /** Insert a newly claimed request. Caller enforces claim uniqueness. */
    fun insert(row: InferenceRequestClaimRow)

    fun updateState(
        requestId: String,
        state: String,
        updatedAt: String,
        resourceVersion: Long,
    ): Boolean

    /**
     * All requests still in a non-terminal REQUEST state (restart fence input,
     * COR-19 / REL-RECOVERY). Terminal states are untouched by recovery.
     */
    fun listNonTerminal(): List<InferenceRequestClaimRow>
}

interface RequestAttemptDao {
    fun insert(row: RequestAttemptRow)

    fun listByRequestId(requestId: String): List<RequestAttemptRow>

    /** Next attempt_no for [requestId] (1 when none exist). */
    fun nextAttemptNo(requestId: String): Int

    fun endAttempt(
        requestId: String,
        attemptNo: Int,
        state: String,
        endedAt: String,
    ): Boolean
}

interface RequestTerminalDao {
    fun findByRequestId(requestId: String): RequestTerminalRow?

    /**
     * Insert the single durable terminal for a request.
     * @return false when a terminal already exists (exactly-one invariant).
     */
    fun insertIfAbsent(row: RequestTerminalRow): Boolean
}

interface IdempotentCommandDao {
    fun findByCommandId(commandId: String): IdempotentCommandClaimRow?

    fun findByClaimKey(
        principalId: String,
        operationKind: String,
        idempotencyKey: String,
    ): IdempotentCommandClaimRow?

    fun insert(row: IdempotentCommandClaimRow)

    fun updateResult(
        commandId: String,
        state: String,
        resultJson: String?,
        errorCode: String?,
        affectedResourceId: String?,
        reconciliationDisposition: String?,
        resourceVersion: Long,
        updatedAt: String,
    ): Boolean
}

/**
 * Bundled claim-ledger ports injected into `:runtime:request-registry`.
 * Marker [ControlPlaneWriter] documents single-writer ownership.
 */
interface ClaimLedgerPorts : ControlPlaneWriter {
    val requests: InferenceRequestDao
    val attempts: RequestAttemptDao
    val terminals: RequestTerminalDao
    val commands: IdempotentCommandDao
    val tx: ClaimLedgerTransaction
}
