package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.CommandLedgerStates
import com.omnillm.core.ports.ledger.IdempotentCommandClaimRow

/**
 * Row models for request/command claim ledgers
 * (`inference_requests`, `request_attempts`, `request_terminals`, `idempotent_commands`
 * in omnillm-schema.sql).
 *
 * Claim key (request + command tables): `(principal_id, operation_kind, idempotency_key)`.
 * Same claim with different digest ⇒ IDEMPOTENCY_CONFLICT (catalog); claim-or-return
 * returns the durable original when digest matches (ADR-004/005).
 * CommandLedgerStates / IdempotentCommandClaimRow live in `:core:ports` (ARC-02).
 */

/** Request ledger states from specs/state-machines.yaml#REQUEST / schema inference_requests. */
object RequestLedgerStates {
    val ALL: Set<String> = setOf(
        "RECEIVED",
        "CLAIMED",
        "PLANNING",
        "QUEUED",
        "RESERVED",
        "COMMITTING",
        "PREPARED",
        "STARTING",
        "STREAMING",
        "TERMINATING",
        "RECONCILING",
        "COMPLETED",
        "FAILED",
        "CANCELLED",
        "ABORTED_UNCERTAIN",
    )

    /** Terminal states from specs/state-machines.yaml#REQUEST. */
    val TERMINAL: Set<String> = setOf(
        "COMPLETED",
        "FAILED",
        "CANCELLED",
        "ABORTED_UNCERTAIN",
    )
}

data class InferenceRequestClaimRow(
    val requestId: String,
    val principalId: String,
    val operationKind: String,
    val idempotencyKey: String,
    val canonicalRequestDigest: String,
    val revisionId: String? = null,
    val state: String,
    val resourceVersion: Long = 0,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Row for `request_attempts` (omnillm-schema.sql).
 * PK: (request_id, attempt_no); attempt_no >= 1.
 */
data class RequestAttemptRow(
    val requestId: String,
    val attemptNo: Int,
    val runtimeEpoch: Long,
    val workerInstanceId: String? = null,
    val state: String,
    val startedAt: String,
    val endedAt: String? = null,
) {
    init {
        require(attemptNo >= 1) { "attempt_no must be >= 1" }
        require(runtimeEpoch >= 0L) { "runtime_epoch must be >= 0" }
    }
}

/**
 * Row for `request_terminals` (omnillm-schema.sql).
 * Exactly one durable terminal per request_id (PK = request_id).
 */
data class RequestTerminalRow(
    val requestId: String,
    val terminalState: String,
    val outputDigest: String? = null,
    val errorCode: String? = null,
    val terminalSeq: Long,
    val completedAt: String,
) {
    init {
        require(terminalSeq >= 0L) { "terminal_seq must be >= 0" }
        require(terminalState in RequestLedgerStates.TERMINAL) {
            "unknown request terminal state: $terminalState"
        }
    }
}

/**
 * In-memory claim-or-return semantics for conformance placeholders.
 * Production path uses SQL UNIQUE claim keys + control-plane writer only.
 *
 * Prefer [InMemoryClaimLedgerStore] + `:runtime:request-registry` for new call sites.
 */
class ClaimOrReturnLedger {
    private val requestsById = linkedMapOf<String, InferenceRequestClaimRow>()
    private val requestsByClaim = linkedMapOf<String, InferenceRequestClaimRow>()
    private val commandsById = linkedMapOf<String, IdempotentCommandClaimRow>()
    private val commandsByClaim = linkedMapOf<String, IdempotentCommandClaimRow>()

    private fun claimKey(principalId: String, operationKind: String, idempotencyKey: String): String =
        "$principalId\u0000$operationKind\u0000$idempotencyKey"

    /**
     * Claim inference request row, or return existing identical claim.
     * @return Pair(row, createdNew)
     * @throws IdempotencyConflict when claim key exists with different digest or requestId
     */
    fun claimOrReturnRequest(row: InferenceRequestClaimRow): Pair<InferenceRequestClaimRow, Boolean> {
        require(row.state in RequestLedgerStates.ALL) { "unknown request state: ${row.state}" }
        val key = claimKey(row.principalId, row.operationKind, row.idempotencyKey)
        val byId = requestsById[row.requestId]
        val byClaim = requestsByClaim[key]
        if (byId != null) {
            if (byId.canonicalRequestDigest != row.canonicalRequestDigest ||
                byId.principalId != row.principalId ||
                byId.operationKind != row.operationKind ||
                byId.idempotencyKey != row.idempotencyKey
            ) {
                throw IdempotencyConflict("requestId collision with different claim payload")
            }
            return byId to false
        }
        if (byClaim != null) {
            if (byClaim.canonicalRequestDigest != row.canonicalRequestDigest ||
                byClaim.requestId != row.requestId
            ) {
                throw IdempotencyConflict("idempotency claim conflict for inference request")
            }
            return byClaim to false
        }
        requestsById[row.requestId] = row
        requestsByClaim[key] = row
        return row to true
    }

    fun claimOrReturnCommand(row: IdempotentCommandClaimRow): Pair<IdempotentCommandClaimRow, Boolean> {
        require(row.state in CommandLedgerStates.ALL) { "unknown command state: ${row.state}" }
        val key = claimKey(row.principalId, row.operationKind, row.idempotencyKey)
        val byId = commandsById[row.commandId]
        val byClaim = commandsByClaim[key]
        if (byId != null) {
            if (byId.canonicalInputDigest != row.canonicalInputDigest ||
                byId.principalId != row.principalId ||
                byId.operationKind != row.operationKind ||
                byId.idempotencyKey != row.idempotencyKey
            ) {
                throw IdempotencyConflict("commandId collision with different claim payload")
            }
            return byId to false
        }
        if (byClaim != null) {
            if (byClaim.canonicalInputDigest != row.canonicalInputDigest ||
                byClaim.commandId != row.commandId
            ) {
                throw IdempotencyConflict("idempotency claim conflict for command")
            }
            return byClaim to false
        }
        commandsById[row.commandId] = row
        commandsByClaim[key] = row
        return row to true
    }

    fun queryRequest(requestId: String): InferenceRequestClaimRow? = requestsById[requestId]

    fun queryCommand(commandId: String): IdempotentCommandClaimRow? = commandsById[commandId]
}

class IdempotencyConflict(message: String) : Exception(message)

