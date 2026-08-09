package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.data.persistence.ClaimLedgerPorts
import com.omnillm.data.persistence.InferenceRequestClaimRow
import com.omnillm.data.persistence.RequestAttemptRow
import com.omnillm.data.persistence.RequestLedgerStates
import com.omnillm.data.persistence.RequestTerminalRow
import com.omnillm.core.ports.ledger.SingleWriterPolicy

/**
 * Request Registry — durable inference claim ledger (CORE-ORCHESTRATOR §1, ADR-004/005).
 *
 * Before any high-cost work, claim with
 * `(principal, operationKind, idempotencyKey)` + canonical request hash.
 * Same key / different hash ⇒ [ClaimOutcome.Conflict] (`IDEMPOTENCY_CONFLICT`).
 * Identical re-claim returns the durable original ([ClaimOutcome.Existing]).
 *
 * Reply loss: caller must [queryRequest], never mint a new requestId and blind-replay.
 *
 * Persistence is injected via [ClaimLedgerPorts] (control-plane sole writer, ADR-010).
 */
class RequestRegistry(
    private val ports: ClaimLedgerPorts,
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {
    init {
        SingleWriterPolicy.assertWriterAllowed(ports.writerRole)
    }

    /**
     * Claim-or-return for an inference / embedding request.
     *
     * @param principal authenticated principal (never caller self-reported package)
     * @param operationKind catalog operation (e.g. CHAT, EMBEDDING)
     * @param idempotencyKey client-generated key scoped to principal + operationKind
     * @param canonicalHash SHA-256 of the canonical request payload
     * @param requestId client-generated request identity (UUID)
     * @param revisionId optional model revision binding at claim time
     */
    fun claim(
        principal: PrincipalId,
        operationKind: String,
        idempotencyKey: IdempotencyKey,
        canonicalHash: Sha256Digest,
        requestId: RequestId,
        revisionId: String? = null,
    ): ClaimOutcome<InferenceRequestClaimRow> {
        require(operationKind.isNotEmpty()) { "operationKind must be non-empty" }
        val now = clock()
        val digest = canonicalHash.hex
        return ports.tx.inTransaction {
            val byId = ports.requests.findByRequestId(requestId.value)
            val byClaim = ports.requests.findByClaimKey(
                principalId = principal.value,
                operationKind = operationKind,
                idempotencyKey = idempotencyKey.value,
            )

            if (byId != null) {
                return@inTransaction if (
                    byId.canonicalRequestDigest == digest &&
                    byId.principalId == principal.value &&
                    byId.operationKind == operationKind &&
                    byId.idempotencyKey == idempotencyKey.value
                ) {
                    ClaimOutcome.Existing(byId)
                } else {
                    conflict(
                        "requestId collision with different claim payload",
                        requestId = requestId.value,
                    )
                }
            }

            if (byClaim != null) {
                return@inTransaction if (
                    byClaim.canonicalRequestDigest == digest &&
                    byClaim.requestId == requestId.value
                ) {
                    ClaimOutcome.Existing(byClaim)
                } else {
                    conflict(
                        "idempotency claim conflict for inference request",
                        principalId = principal.value,
                        operationKind = operationKind,
                        idempotencyKey = idempotencyKey.value,
                    )
                }
            }

            val row = InferenceRequestClaimRow(
                requestId = requestId.value,
                principalId = principal.value,
                operationKind = operationKind,
                idempotencyKey = idempotencyKey.value,
                canonicalRequestDigest = digest,
                revisionId = revisionId,
                state = StateMachines.REQUEST.initial, // RECEIVED
                resourceVersion = 0,
                createdAt = now,
                updatedAt = now,
            )
            ports.requests.insert(row)
            ClaimOutcome.New(row)
        }
    }

    /** Query durable request by client-generated [requestId] (reply-loss path). */
    fun queryRequest(requestId: RequestId): InferenceRequestClaimRow? =
        ports.requests.findByRequestId(requestId.value)

    fun queryRequestTerminal(requestId: RequestId): RequestTerminalRow? =
        ports.terminals.findByRequestId(requestId.value)

    /**
     * Record the single durable terminal for a request (REQUEST FSM terminal set).
     * Updates `inference_requests.state` and inserts `request_terminals` atomically.
     * Second terminal write ⇒ STATE_CONFLICT (exactly one terminal invariant).
     */
    fun recordTerminal(
        requestId: RequestId,
        terminalState: String,
        terminalSeq: Long,
        outputDigest: Sha256Digest? = null,
        errorCode: String? = null,
    ): OmniResult<RequestTerminalRow> {
        if (terminalState !in RequestLedgerStates.TERMINAL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown request terminal state: $terminalState",
                    details = mapOf("terminalState" to terminalState),
                ),
            )
        }
        require(terminalSeq >= 0L) { "terminalSeq must be >= 0" }

        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.requests.findByRequestId(requestId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "request not found",
                        details = mapOf("requestId" to requestId.value),
                    ),
                )

            val priorTerminal = ports.terminals.findByRequestId(requestId.value)
            if (priorTerminal != null) {
                // Idempotent re-record of the same terminal is allowed; different payload conflicts.
                if (
                    priorTerminal.terminalState == terminalState &&
                    priorTerminal.terminalSeq == terminalSeq &&
                    priorTerminal.outputDigest == outputDigest?.hex &&
                    priorTerminal.errorCode == errorCode
                ) {
                    return@inTransaction OmniResult.ok(priorTerminal)
                }
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "request already has a durable terminal",
                        details = mapOf(
                            "requestId" to requestId.value,
                            "existingTerminal" to priorTerminal.terminalState,
                        ),
                    ),
                )
            }

            val terminal = RequestTerminalRow(
                requestId = requestId.value,
                terminalState = terminalState,
                outputDigest = outputDigest?.hex,
                errorCode = errorCode,
                terminalSeq = terminalSeq,
                completedAt = now,
            )
            val inserted = ports.terminals.insertIfAbsent(terminal)
            if (!inserted) {
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "request already has a durable terminal",
                        details = mapOf("requestId" to requestId.value),
                    ),
                )
            }
            ports.requests.updateState(
                requestId = requestId.value,
                state = terminalState,
                updatedAt = now,
                resourceVersion = existing.resourceVersion + 1,
            )
            OmniResult.ok(terminal)
        }
    }

    /**
     * Open a new attempt row for [requestId] (request_attempts).
     * @return the durable attempt row with assigned attempt_no
     */
    fun beginAttempt(
        requestId: RequestId,
        runtimeEpoch: Long,
        state: String = "STARTED",
        workerInstanceId: String? = null,
    ): OmniResult<RequestAttemptRow> {
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be >= 0" }
        require(state.isNotEmpty()) { "attempt state must be non-empty" }
        val now = clock()
        return ports.tx.inTransaction {
            ports.requests.findByRequestId(requestId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "request not found",
                        details = mapOf("requestId" to requestId.value),
                    ),
                )
            val attemptNo = ports.attempts.nextAttemptNo(requestId.value)
            val row = RequestAttemptRow(
                requestId = requestId.value,
                attemptNo = attemptNo,
                runtimeEpoch = runtimeEpoch,
                workerInstanceId = workerInstanceId,
                state = state,
                startedAt = now,
                endedAt = null,
            )
            ports.attempts.insert(row)
            OmniResult.ok(row)
        }
    }

    /** End an open attempt (update state + ended_at). */
    fun endAttempt(
        requestId: RequestId,
        attemptNo: Int,
        state: String,
    ): OmniResult<RequestAttemptRow> {
        require(attemptNo >= 1) { "attemptNo must be >= 1" }
        require(state.isNotEmpty()) { "attempt state must be non-empty" }
        val now = clock()
        return ports.tx.inTransaction {
            val ok = ports.attempts.endAttempt(
                requestId = requestId.value,
                attemptNo = attemptNo,
                state = state,
                endedAt = now,
            )
            if (!ok) {
                return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "attempt not found",
                        details = mapOf(
                            "requestId" to requestId.value,
                            "attemptNo" to attemptNo.toString(),
                        ),
                    ),
                )
            }
            val row = ports.attempts.listByRequestId(requestId.value)
                .first { it.attemptNo == attemptNo }
            OmniResult.ok(row)
        }
    }

    fun listAttempts(requestId: RequestId): List<RequestAttemptRow> =
        ports.attempts.listByRequestId(requestId.value)

    /**
     * Transition request non-terminal state (e.g. RECEIVED → CLAIMED).
     * Terminal states must go through [recordTerminal].
     */
    fun updateState(requestId: RequestId, state: String): OmniResult<InferenceRequestClaimRow> {
        if (state !in RequestLedgerStates.ALL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown request state: $state",
                    details = mapOf("state" to state),
                ),
            )
        }
        if (state in RequestLedgerStates.TERMINAL) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "use recordTerminal for terminal states",
                    details = mapOf("state" to state),
                ),
            )
        }
        val now = clock()
        return ports.tx.inTransaction {
            val existing = ports.requests.findByRequestId(requestId.value)
                ?: return@inTransaction OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "request not found",
                        details = mapOf("requestId" to requestId.value),
                    ),
                )
            if (existing.state in RequestLedgerStates.TERMINAL) {
                return@inTransaction OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "request is already terminal",
                        details = mapOf(
                            "requestId" to requestId.value,
                            "state" to existing.state,
                        ),
                    ),
                )
            }
            ports.requests.updateState(
                requestId = requestId.value,
                state = state,
                updatedAt = now,
                resourceVersion = existing.resourceVersion + 1,
            )
            OmniResult.ok(ports.requests.findByRequestId(requestId.value)!!)
        }
    }

    private fun conflict(
        message: String,
        requestId: String? = null,
        principalId: String? = null,
        operationKind: String? = null,
        idempotencyKey: String? = null,
    ): ClaimOutcome.Conflict {
        val details = buildMap {
            requestId?.let { put("requestId", it) }
            principalId?.let { put("principalId", it) }
            operationKind?.let { put("operationKind", it) }
            idempotencyKey?.let { put("idempotencyKey", it) }
        }
        return ClaimOutcome.Conflict(
            OmniError.IDEMPOTENCY_CONFLICT(message = message, details = details),
        )
    }
}
