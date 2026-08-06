package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.runtime.governor.ResourceGovernor
import com.omnillm.runtime.requestregistry.ClaimOutcome
import com.omnillm.runtime.requestregistry.CommitLedger
import com.omnillm.runtime.requestregistry.RequestRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrator control-plane: claim → plan → queue → reserve → commit →
 * prepare → start → stream → terminal / reconcile
 * (CORE-ORCHESTRATOR, ARCH-SEQUENCE-FLOWS, FEAT-ROUTING, ADR-002).
 *
 * Hard rules:
 * - Plan has **no** domain mutation (ADR-002)
 * - Single writer: registry/governor mutation only on this path (ADR-010)
 * - Fallback only per caller allowlist; [ActualRouting] always reported
 * - Reply loss: query, never blind replay (ADR-004/005)
 * - Commit INTENT_RECORDED via [commitLedger] **before** worker side effects
 *   (REL-RECOVERY / runtime-recovery-fixtures)
 */
class Orchestrator(
    private val registry: RequestRegistry,
    private val governor: ResourceGovernor,
    private val planner: CandidatePlanner,
    private val scheduler: DeficitRoundRobinScheduler,
    private val engine: InferenceEnginePort,
    private val issuerBootId: String,
    private val runtimeEpoch: Long,
    private val clockMonotonic: () -> Long = { System.nanoTime() },
    private val idSource: () -> String = { UUID.randomUUID().toString() },
    /**
     * Durable commit recovery ledger. When null, INTENT is not persisted
     * (scaffold only — production control plane must inject a real ledger).
     */
    private val commitLedger: CommitLedger? = null,
) {
    init {
        require(issuerBootId.isNotEmpty()) { "issuerBootId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }

    private val lifecycle = RequestLifecycle(registry)
    private val active = linkedMapOf<String, ActiveRequest>()
    private val lock = Any()
    private val terminalSeq = AtomicLong(0L)

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Claim-or-return then plan candidates and enqueue (RECEIVED → … → QUEUED).
     * Existing identical claims are returned without re-planning (idempotent).
     */
    suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> {
        if (request.cancelRequested) {
            return failBeforeClaim(request, "cancel already requested")
        }
        if (clockMonotonic() > request.deadlineMonotonic) {
            return OmniResult.err(
                OmniError.DEADLINE_EXCEEDED(
                    message = "request deadline already passed",
                    details = mapOf("requestId" to request.requestId.value),
                ),
            )
        }

        val claim = registry.claim(
            principal = request.principalId,
            operationKind = request.operationKind,
            idempotencyKey = request.idempotencyKey,
            canonicalHash = request.canonicalRequestDigest,
            requestId = request.requestId,
            revisionId = request.requestedRevisionId.hex,
        )

        when (claim) {
            is ClaimOutcome.Conflict ->
                return OmniResult.err(claim.error)

            is ClaimOutcome.Existing -> {
                // Reply-loss / duplicate submit: return durable state; do not re-execute.
                val row = claim.value
                return OmniResult.ok(
                    SubmitResult(
                        requestId = request.requestId,
                        claim = ClaimKind.EXISTING,
                        state = row.state,
                        earliestStart = scheduler.estimateStart(request.requestId),
                        planning = null,
                        actualRouting = activeSnapshot(request.requestId)?.actualRouting,
                    ),
                )
            }

            is ClaimOutcome.New -> {
                lifecycle.bindFromClaim(
                    requestId = request.requestId,
                    principalId = request.principalId.value,
                    state = claim.value.state,
                )
            }
        }

        // RECEIVED → CLAIMED
        applyOrFail(request.requestId, "CLAIM_SUCCEEDED")?.let { return it }

        // CLAIMED → PLANNING
        applyOrFail(request.requestId, "BEGIN_PLANNING")?.let { return it }

        val planning = when (val p = planner.plan(request)) {
            is OmniResult.Err -> {
                // PLANNING → FAILED
                lifecycle.terminal(
                    requestId = request.requestId,
                    event = "PLAN_FAILED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = p.error.code.code,
                )
                return OmniResult.err(p.error)
            }
            is OmniResult.Ok -> p.value
        }

        // PLANNING → QUEUED (first viable candidate is the head for scheduling)
        applyOrFail(request.requestId, "PLAN_READY")?.let { return it }

        val head = planning.viable.first()
        val work = scheduler.enqueue(
            requestId = request.requestId,
            principalId = request.principalId,
            planned = head,
            request = request,
        )

        synchronized(lock) {
            active[request.requestId.value] = ActiveRequest(
                request = request,
                planning = planning,
                scheduled = work,
                selected = head,
                actualRouting = null,
                reservation = null,
                plan = null,
                commit = null,
                prepared = null,
                state = "QUEUED",
            )
        }

        return OmniResult.ok(
            SubmitResult(
                requestId = request.requestId,
                claim = ClaimKind.NEW,
                state = "QUEUED",
                earliestStart = scheduler.estimateStart(request.requestId),
                planning = planning,
                actualRouting = null,
            ),
        )
    }

    /**
     * Drive one scheduled work through reserve → commit → prepare → start → stream → terminal.
     * Returns null when the queue is empty.
     *
     * Governor is the authoritative admission gate (ADR-003). Soft pre-filtering is intentionally
     * not applied here so permanent capacity shortfalls surface as `ADMISSION_REJECTED` rather
     * than leaving work stuck without a terminal.
     */
    suspend fun pumpOnce(): OmniResult<TerminalResult>? {
        val work = scheduler.selectNext() ?: return null
        return executeWork(work)
    }

    /** Pump until empty or [maxSteps] exhausted. */
    suspend fun pumpAll(maxSteps: Int = 64): List<OmniResult<TerminalResult>> {
        require(maxSteps > 0) { "maxSteps must be positive" }
        val results = mutableListOf<OmniResult<TerminalResult>>()
        repeat(maxSteps) {
            val r = pumpOnce() ?: return results
            results += r
        }
        return results
    }

    fun query(requestId: RequestId): QueryView? {
        val row = registry.queryRequest(requestId) ?: return null
        val term = registry.queryRequestTerminal(requestId)
        val act = activeSnapshot(requestId)
        return QueryView(
            requestId = requestId,
            state = row.state,
            terminalState = term?.terminalState,
            errorCode = term?.errorCode,
            actualRouting = act?.actualRouting,
            rejectionTrail = act?.planning?.rejections.orEmpty(),
        )
    }

    fun cancel(requestId: RequestId): OmniResult<Unit> {
        scheduler.remove(requestId)
        val state = lifecycle.currentState(requestId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "request not found", details = mapOf("requestId" to requestId.value)),
            )
        return when (state) {
            "RECEIVED", "CLAIMED", "PLANNING", "QUEUED" -> {
                lifecycle.terminal(
                    requestId = requestId,
                    event = "CLIENT_CANCEL",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = OmniError.CANCELLED().code.code,
                ).map { }
            }
            "RESERVED" -> {
                releaseReservationIfAny(requestId)
                lifecycle.terminal(
                    requestId = requestId,
                    event = "CLIENT_CANCEL",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = OmniError.CANCELLED().code.code,
                ).map { }
            }
            "PREPARED" -> {
                releaseReservationIfAny(requestId)
                lifecycle.terminal(
                    requestId = requestId,
                    event = "CLIENT_CANCEL",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = OmniError.CANCELLED().code.code,
                ).map { }
            }
            else ->
                OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "cancel not applied in state $state (use stream cancel path)",
                        details = mapOf("state" to state),
                    ),
                )
        }
    }

    // -----------------------------------------------------------------------
    // Pipeline
    // -----------------------------------------------------------------------

    private suspend fun executeWork(work: ScheduledWork): OmniResult<TerminalResult> {
        val request = work.request
        val requestId = work.requestId

        // Try viable candidates in plan order until one reserves (atomic admission).
        val planning = activeSnapshot(requestId)?.planning
            ?: return failQueued(
                requestId,
                OmniError.INTERNAL(message = "missing planning snapshot"),
            )

        var lastError: OmniError? = null
        var chosen: PlannedCandidate? = null
        var reservation: Reservation? = null
        var plan: Plan? = null

        for (candidate in planning.viable) {
            // Re-plan pure envelope for this candidate (ADR-002) to obtain Plan object.
            val planOut = when (val p = engine.planInference(request, candidate.candidate)) {
                is OmniResult.Err -> {
                    lastError = p.error
                    continue
                }
                is OmniResult.Ok -> p.value
            }

            val reserve = governor.reserve(
                vector = planOut.resourceEnvelope.peak,
                owner = request.principalId.value,
                deadlineMonotonic = request.deadlineMonotonic,
                envelope = planOut.resourceEnvelope,
                nowMonotonic = clockMonotonic(),
            )
            when (reserve) {
                is OmniResult.Err -> {
                    lastError = reserve.error
                    continue
                }
                is OmniResult.Ok -> {
                    chosen = candidate.copy(
                        resourceEnvelope = planOut.resourceEnvelope,
                        planInputDigest = planOut.planInputDigest,
                    )
                    reservation = reserve.value
                    plan = planOut.plan
                    break
                }
            }
        }

        if (chosen == null || reservation == null || plan == null) {
            val err = lastError ?: OmniError.ADMISSION_REJECTED(message = "no candidate could reserve")
            // QUEUED → FAILED (catalog ADMISSION_REJECTED).
            lifecycle.terminal(
                requestId = requestId,
                event = "ADMISSION_REJECTED",
                terminalSeq = terminalSeq.incrementAndGet(),
                errorCode = err.code.code,
            )
            return OmniResult.err(err)
        }

        // QUEUED → RESERVED via ADMISSION_GRANTED
        val guards = GuardEvaluator.of("leaseValid" to true)
        when (val admitted = lifecycle.apply(requestId, "ADMISSION_GRANTED", guards)) {
            is OmniResult.Err -> {
                governor.releaseReservation(reservation.reservationId)
                return admitted.map { error("unreachable") }
            }
            is OmniResult.Ok -> Unit
        }

        val actual = ActualRouting(
            modelRevisionId = chosen.candidate.modelRevisionId,
            engineBuildId = chosen.candidate.engineBuildId,
            backend = chosen.candidate.backend,
            placementClass = chosen.candidate.placementClass,
            installationId = chosen.candidate.installationId,
            candidateId = chosen.candidate.candidateId,
            usedFallback = !chosen.candidate.isPrimary ||
                chosen.candidate.modelRevisionId.hex != request.requestedRevisionId.hex,
            fallbackPolicy = request.routing.fallbackPolicy,
            rejectionTrail = planning.rejections,
        )

        updateActive(requestId) {
            it.copy(
                selected = chosen,
                reservation = reservation,
                plan = plan,
                actualRouting = actual,
                state = "RESERVED",
            )
        }

        // RESERVED → COMMITTING
        when (val begin = lifecycle.apply(requestId, "BEGIN_COMMIT", guards)) {
            is OmniResult.Err -> {
                governor.releaseReservation(reservation.reservationId)
                return failWithRelease(requestId, reservation, begin.error)
            }
            is OmniResult.Ok -> Unit
        }

        val commit = Commit(
            commitId = CommitId.parse(idSource()),
            planId = plan.planId,
            requestId = requestId,
            principalId = request.principalId,
            reservationId = reservation.reservationId,
            revisionLeaseId = RevisionLeaseId.parse(idSource()),
            issuerBootId = issuerBootId,
            runtimeEpoch = runtimeEpoch,
            revocationEpoch = request.revocationEpoch,
            sourceSessionEpoch = request.sourceSessionEpoch,
            engineBuildId = chosen.candidate.engineBuildId,
            canonicalInputDigest = plan.canonicalInputDigest,
            oneShotNonce = idSource(),
        )

        updateActive(requestId) { it.copy(commit = commit, state = "COMMITTING") }

        // REL-RECOVERY: INTENT_RECORDED durable before worker receives commit.
        when (val intent = commitLedger?.recordIntent(commit)) {
            is ClaimOutcome.Conflict -> {
                governor.releaseReservation(reservation.reservationId)
                lifecycle.terminal(
                    requestId = requestId,
                    event = "COMMIT_REJECTED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = intent.error.code.code,
                )
                return OmniResult.err(intent.error)
            }
            is ClaimOutcome.Existing, is ClaimOutcome.New, null -> Unit
        }
        commitLedger?.markExecuting(commit.commitId)

        val prepared = when (val c = engine.commitInference(plan, reservation, commit)) {
            is OmniResult.Err -> {
                // COMMIT_REJECTED path — durable abort when ledger present
                commitLedger?.recordOutcome(
                    commitId = commit.commitId,
                    state = "ABORTED",
                    errorCode = c.error.code.code,
                    reconciliationDisposition = "ROLLED_BACK",
                )
                governor.releaseReservation(reservation.reservationId)
                lifecycle.terminal(
                    requestId = requestId,
                    event = "COMMIT_REJECTED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = c.error.code.code,
                )
                return OmniResult.err(c.error)
            }
            is OmniResult.Ok -> c.value
        }

        // Persist prepared operation before start (RR-005 one-shot surface).
        when (val prepClaim = commitLedger?.recordPrepared(prepared)) {
            is ClaimOutcome.Conflict -> {
                commitLedger?.recordOutcome(
                    commitId = commit.commitId,
                    state = "UNCERTAIN_QUARANTINED",
                    errorCode = prepClaim.error.code.code,
                    reconciliationDisposition = "UNPROVABLE",
                )
                return reconcile(requestId, commit, reservation, prepClaim.error)
            }
            is ClaimOutcome.Existing, is ClaimOutcome.New, null -> Unit
        }
        commitLedger?.recordOutcome(
            commitId = commit.commitId,
            state = "RESULT_RECORDED",
            resultJson = """{"preparedOperationId":"${prepared.preparedOperationId.value}"}""",
        )

        // COMMITTING → PREPARED
        val commitGuards = GuardEvaluator.of(
            "commitKnown" to true,
            "leaseValid" to true,
        )
        when (val conf = lifecycle.apply(requestId, "COMMIT_CONFIRMED", commitGuards)) {
            is OmniResult.Err -> {
                // Uncertain — reconcile
                commitLedger?.markReconciling(commit.commitId)
                return reconcile(requestId, commit, reservation, conf.error)
            }
            is OmniResult.Ok -> Unit
        }

        // Convert reservation → allocation (resident charge).
        val allocationHandleId = when (
            val converted = governor.convert(
                reservationId = reservation.reservationId,
                convertedAllocation = chosen.resourceEnvelope.steady,
                nowMonotonic = clockMonotonic(),
            )
        ) {
            is OmniResult.Err -> {
                lifecycle.terminal(
                    requestId = requestId,
                    event = "START_REJECTED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = converted.error.code.code,
                )
                governor.releaseReservation(reservation.reservationId)
                return OmniResult.err(converted.error)
            }
            is OmniResult.Ok -> converted.value.allocationHandleId
        }

        val preparedBound = prepared.copy(allocationHandleId = allocationHandleId)
        updateActive(requestId) {
            it.copy(prepared = preparedBound, state = "PREPARED")
        }

        // PREPARED → STARTING
        val startGuards = GuardEvaluator.of("streamMayStart" to true)
        when (val startAcc = lifecycle.apply(requestId, "START_ACCEPTED", startGuards)) {
            is OmniResult.Err -> {
                // INV-005: terminal (or pre-start failure) releases only temporary
                // reservation remainder; resident AllocationHandle stays charged.
                return failPrepared(requestId, startAcc.error)
            }
            is OmniResult.Ok -> Unit
        }

        // RR-005: one-shot start claim durable before worker start side effect.
        when (val startClaim = commitLedger?.claimStart(preparedBound.preparedOperationId.value)) {
            is OmniResult.Err -> {
                lifecycle.terminal(
                    requestId = requestId,
                    event = "START_FAILED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = startClaim.error.code.code,
                )
                releaseReservationIfAny(requestId)
                return OmniResult.err(startClaim.error)
            }
            is OmniResult.Ok, null -> Unit
        }

        when (
            val started = engine.start(
                prepared = preparedBound,
                operationId = preparedBound.operationId,
                runtimeEpoch = runtimeEpoch,
            )
        ) {
            is OmniResult.Err -> {
                lifecycle.terminal(
                    requestId = requestId,
                    event = "START_FAILED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = started.error.code.code,
                )
                // INV-005: temporary residual only (idempotent after convert).
                releaseReservationIfAny(requestId)
                return OmniResult.err(started.error)
            }
            is OmniResult.Ok -> Unit
        }

        // STARTING → STREAMING on first event
        var fromSeq = 0L
        var first = true
        var terminal: StreamTerminal? = null
        var lastOutput: Sha256Digest? = null

        while (terminal == null) {
            when (val batch = engine.nextEvents(preparedBound, fromSeq)) {
                is OmniResult.Err -> {
                    lifecycle.apply(requestId, "ENGINE_TERMINAL")
                    lifecycle.terminal(
                        requestId = requestId,
                        event = "TERMINAL_FAILURE",
                        terminalSeq = terminalSeq.incrementAndGet(),
                        errorCode = batch.error.code.code,
                    )
                    // INV-005: request terminal releases temporary reservation only.
                    releaseReservationIfAny(requestId)
                    return OmniResult.err(batch.error)
                }
                is OmniResult.Ok -> {
                    if (first) {
                        applyOrFail(requestId, "FIRST_EVENT_COMMITTED")
                        first = false
                        updateActive(requestId) { it.copy(state = "STREAMING") }
                    }
                    fromSeq = batch.value.seqTo
                    terminal = batch.value.terminal
                    lastOutput = batch.value.terminal?.outputDigest ?: lastOutput
                    if (batch.value.events.isEmpty() && terminal == null) {
                        // Engine returned empty non-terminal — fail closed.
                        lifecycle.apply(requestId, "ENGINE_TERMINAL")
                        lifecycle.terminal(
                            requestId = requestId,
                            event = "TERMINAL_FAILURE",
                            terminalSeq = terminalSeq.incrementAndGet(),
                            errorCode = OmniError.INTERNAL().code.code,
                        )
                        releaseReservationIfAny(requestId)
                        return OmniResult.err(
                            OmniError.INTERNAL(message = "empty non-terminal stream batch"),
                        )
                    }
                }
            }
        }

        // STREAMING → TERMINATING
        applyOrFail(requestId, "ENGINE_TERMINAL")

        val termEvent = when (terminal.kind) {
            StreamTerminalKind.SUCCESS -> "TERMINAL_SUCCESS"
            StreamTerminalKind.FAILURE -> "TERMINAL_FAILURE"
            StreamTerminalKind.CANCELLED -> "TERMINAL_CANCELLED"
        }
        val termError = when (terminal.kind) {
            StreamTerminalKind.SUCCESS -> null
            StreamTerminalKind.FAILURE -> terminal.errorCode ?: OmniError.INTERNAL().code.code
            StreamTerminalKind.CANCELLED -> OmniError.CANCELLED().code.code
        }

        val terminalAgg = lifecycle.terminal(
            requestId = requestId,
            event = termEvent,
            terminalSeq = terminalSeq.incrementAndGet(),
            outputDigest = terminal.outputDigest ?: lastOutput,
            errorCode = termError,
        )

        val finalState = when (terminalAgg) {
            is OmniResult.Ok -> terminalAgg.value.state
            is OmniResult.Err -> "FAILED"
        }

        updateActive(requestId) { it.copy(state = finalState) }

        // INV-005: request terminal releases temporary reservation remainder only.
        // After convert, reservation is COMMITTED → release is idempotent no-op for
        // residual; AllocationHandle (steady/resident) remains charged until reclaim.
        releaseReservationIfAny(requestId)

        // Control plane always returns TerminalResult; state carries success/failure.
        return OmniResult.ok(
            TerminalResult(
                requestId = requestId,
                state = finalState,
                actualRouting = actual,
                preparedOperationId = preparedBound.preparedOperationId,
                outputDigest = terminal.outputDigest ?: lastOutput,
                errorCode = termError,
            ),
        )
    }

    private suspend fun reconcile(
        requestId: RequestId,
        commit: Commit,
        reservation: Reservation,
        cause: OmniError,
    ): OmniResult<TerminalResult> {
        lifecycle.apply(requestId, "COMMIT_RESULT_UNKNOWN")
        commitLedger?.markReconciling(commit.commitId)

        // Prefer durable control-plane ledger over engine journal (engine may die).
        val durable = commitLedger?.queryCommit(commit.commitId)
        if (durable != null && durable.state in setOf("COMMITTED", "RESULT_RECORDED")) {
            val guards = GuardEvaluator.of(
                "commitKnown" to true,
                "cancelPending" to false,
            )
            lifecycle.apply(requestId, "COMMIT_FOUND_PREPARED", guards)
            return OmniResult.err(
                OmniError.ABORTED_UNCERTAIN(
                    message = "commit found prepared during reconcile; caller must query",
                    details = mapOf(
                        "commitId" to commit.commitId.value,
                        "durableState" to durable.state,
                        "cause" to (cause.message ?: cause.code.code),
                    ),
                ),
            )
        }

        return when (val q = engine.queryCommit(commit.commitId)) {
            is OmniResult.Ok -> {
                val state = q.value.state
                when {
                    state == "COMMITTED" || state == "PREPARED" || state == "RESULT_RECORDED" -> {
                        val guards = GuardEvaluator.of(
                            "commitKnown" to true,
                            "cancelPending" to false,
                        )
                        lifecycle.apply(requestId, "COMMIT_FOUND_PREPARED", guards)
                        commitLedger?.recordOutcome(
                            commitId = commit.commitId,
                            state = "COMMITTED",
                            reconciliationDisposition = "RESULT_FOUND",
                        )
                        OmniResult.err(
                            OmniError.ABORTED_UNCERTAIN(
                                message = "commit found prepared during reconcile; caller must query",
                                details = mapOf(
                                    "commitId" to commit.commitId.value,
                                    "cause" to (cause.message ?: cause.code.code),
                                ),
                            ),
                        )
                    }
                    state == "ABORTED" || state == "INTENT_RECORDED" -> {
                        governor.releaseReservation(reservation.reservationId)
                        val guards = GuardEvaluator.of(
                            "commitKnown" to true,
                            "cancelPending" to false,
                        )
                        lifecycle.terminal(
                            requestId = requestId,
                            event = "COMMIT_FOUND_ABORTED",
                            terminalSeq = terminalSeq.incrementAndGet(),
                            errorCode = cause.code.code,
                            guards = guards,
                        )
                        commitLedger?.recordOutcome(
                            commitId = commit.commitId,
                            state = "ABORTED",
                            errorCode = cause.code.code,
                            reconciliationDisposition = "ROLLED_BACK",
                        )
                        OmniResult.err(cause)
                    }
                    else -> {
                        // Unknown / unprovable engine state — quarantine (RR-002).
                        governor.releaseReservation(reservation.reservationId)
                        lifecycle.terminal(
                            requestId = requestId,
                            event = "RECONCILIATION_EXHAUSTED",
                            terminalSeq = terminalSeq.incrementAndGet(),
                            errorCode = OmniError.ABORTED_UNCERTAIN().code.code,
                        )
                        commitLedger?.recordOutcome(
                            commitId = commit.commitId,
                            state = "UNCERTAIN_QUARANTINED",
                            reconciliationDisposition = "UNPROVABLE",
                        )
                        OmniResult.err(
                            OmniError.ABORTED_UNCERTAIN(
                                message = "reconciliation unprovable",
                                details = mapOf(
                                    "commitId" to commit.commitId.value,
                                    "engineState" to state,
                                ),
                            ),
                        )
                    }
                }
            }
            is OmniResult.Err -> {
                // Engine journal missing/not found — fail closed, no blind replay.
                governor.releaseReservation(reservation.reservationId)
                lifecycle.terminal(
                    requestId = requestId,
                    event = "RECONCILIATION_EXHAUSTED",
                    terminalSeq = terminalSeq.incrementAndGet(),
                    errorCode = OmniError.ABORTED_UNCERTAIN().code.code,
                )
                commitLedger?.recordOutcome(
                    commitId = commit.commitId,
                    state = "UNCERTAIN_QUARANTINED",
                    reconciliationDisposition = "UNPROVABLE",
                )
                OmniResult.err(
                    OmniError.ABORTED_UNCERTAIN(
                        message = "reconciliation exhausted; query durable commit ledger",
                        details = mapOf("commitId" to commit.commitId.value),
                    ),
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun applyOrFail(requestId: RequestId, event: String): OmniResult.Err? {
        return when (val r = lifecycle.apply(requestId, event)) {
            is OmniResult.Err -> r
            is OmniResult.Ok -> null
        }
    }

    private fun failBeforeClaim(
        request: OrchestrationRequest,
        message: String,
    ): OmniResult<SubmitResult> =
        OmniResult.err(
            OmniError.CANCELLED(
                message = message,
                details = mapOf("requestId" to request.requestId.value),
            ),
        )

    private fun failQueued(requestId: RequestId, error: OmniError): OmniResult<TerminalResult> {
        lifecycle.terminal(
            requestId = requestId,
            event = "ADMISSION_REJECTED",
            terminalSeq = terminalSeq.incrementAndGet(),
            errorCode = error.code.code,
        )
        return OmniResult.err(error)
    }

    private fun failWithRelease(
        requestId: RequestId,
        reservation: Reservation,
        error: OmniError,
    ): OmniResult<TerminalResult> {
        governor.releaseReservation(reservation.reservationId)
        return OmniResult.err(error)
    }

    private fun failPrepared(requestId: RequestId, error: OmniError): OmniResult<TerminalResult> {
        lifecycle.terminal(
            requestId = requestId,
            event = "START_REJECTED",
            terminalSeq = terminalSeq.incrementAndGet(),
            errorCode = error.code.code,
        )
        // INV-005: temporary reservation residual only (never AllocationHandle).
        releaseReservationIfAny(requestId)
        return OmniResult.err(error)
    }

    /**
     * INV-005: release temporary [Reservation] residual for this request.
     * Does **not** credit resident [com.omnillm.core.resource.AllocationHandle]
     * capacity — that requires governor drain + observed release barrier.
     */
    private fun releaseReservationIfAny(requestId: RequestId) {
        val res = activeSnapshot(requestId)?.reservation ?: return
        governor.releaseReservation(res.reservationId)
    }

    private fun activeSnapshot(requestId: RequestId): ActiveRequest? =
        synchronized(lock) { active[requestId.value] }

    private fun updateActive(requestId: RequestId, transform: (ActiveRequest) -> ActiveRequest) {
        synchronized(lock) {
            val cur = active[requestId.value] ?: return
            active[requestId.value] = transform(cur)
        }
    }

    private data class ActiveRequest(
        val request: OrchestrationRequest,
        val planning: PlanningResult,
        val scheduled: ScheduledWork,
        val selected: PlannedCandidate,
        val actualRouting: ActualRouting?,
        val reservation: Reservation?,
        val plan: Plan?,
        val commit: Commit?,
        val prepared: PreparedOperation?,
        val state: String,
    )
}

enum class ClaimKind {
    NEW,
    EXISTING,
}

data class SubmitResult(
    val requestId: RequestId,
    val claim: ClaimKind,
    val state: String,
    val earliestStart: EarliestStartEstimate?,
    val planning: PlanningResult?,
    val actualRouting: ActualRouting?,
)

data class TerminalResult(
    val requestId: RequestId,
    val state: String,
    val actualRouting: ActualRouting,
    val preparedOperationId: PreparedOperationId?,
    val outputDigest: Sha256Digest?,
    val errorCode: String?,
)

data class QueryView(
    val requestId: RequestId,
    val state: String,
    val terminalState: String?,
    val errorCode: String?,
    val actualRouting: ActualRouting?,
    val rejectionTrail: List<CandidateRejection>,
)

/**
 * Factory helpers for tests and control-plane wiring.
 */
object OrchestratorFactory {
    fun create(
        registry: RequestRegistry,
        governor: ResourceGovernor,
        engine: InferenceEnginePort,
        capabilities: CapabilityLookup,
        health: HealthLookup = HealthLookup {
            HealthSnapshot(revocationEpoch = 0L)
        },
        sessionCheck: SessionCompatibilityCheck = SessionCompatibilityCheck { _, _ -> null },
        policyVersion: String = "orchestrator-drr-v1",
        issuerBootId: String = "boot-test",
        runtimeEpoch: Long = 1L,
        clockMonotonic: () -> Long = { System.nanoTime() },
        idSource: () -> String = { UUID.randomUUID().toString() },
        commitLedger: CommitLedger? = null,
    ): Orchestrator {
        val planner = CandidatePlanner(
            capabilities = capabilities,
            health = health,
            sessionCheck = sessionCheck,
            engine = engine,
        )
        val scheduler = DeficitRoundRobinScheduler(
            policyVersion = policyVersion,
            clockMonotonic = clockMonotonic,
        )
        return Orchestrator(
            registry = registry,
            governor = governor,
            planner = planner,
            scheduler = scheduler,
            engine = engine,
            issuerBootId = issuerBootId,
            runtimeEpoch = runtimeEpoch,
            clockMonotonic = clockMonotonic,
            idSource = idSource,
            commitLedger = commitLedger,
        )
    }
}
