package com.omnillm.runtime.job

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.domain.JobAggregate
import com.omnillm.core.state.domain.JobId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.runtime.policy.download.DownloadUrlPolicy

/**
 * Control-plane Job Manager (FEAT-ADMIN, ARCH-RUNTIME-LIFECYCLE).
 *
 * Responsibilities:
 * - claim-or-return create under (principal, kind, idempotencyKey)
 * - drive JOB FSM via [JobAggregate] / [StateMachineDriver]
 * - attempt numbering (unique attemptNo per retry)
 * - checkpoint validation for resume/recover
 * - pause reasons: network / foreground / input
 * - cancel from QUEUED, RUNNING, paused, or RECOVERING
 * - DOWNLOAD sourceUrl admitted via [DownloadUrlPolicy] (SEC-INPUT §3)
 *
 * Plan purity: this manager mutates only the job ledger (runtime single writer).
 * Workers receive narrow work descriptors; they never open the domain DB.
 */
class JobManager(
    private val store: JobStore = InMemoryJobStore(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val downloadUrlPolicy: DownloadUrlPolicy.Policy = DownloadUrlPolicy.Policy.DEFAULT,
) {
    fun query(jobId: JobId): OmniResult<JobRecord> {
        val row = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found: ${jobId.value}"))
        return OmniResult.ok(row)
    }

    fun listOwn(principalId: PrincipalId): List<JobRecord> =
        store.listByPrincipal(principalId.value)

    fun listActive(): List<JobRecord> = store.listActive()

    /**
     * REL-RECOVERY restart fence: RUNNING jobs whose worker is gone become
     * RECOVERING via PROCESS_LOST so operators/workers re-validate checkpoint
     * before resume (does not blind-replay EXECUTE).
     *
     * Call once after reopening a durable [JobStore] (e.g. control-plane attach).
     * Paused / QUEUED / RECOVERING jobs are left intact.
     *
     * @return job ids successfully transitioned to RECOVERING
     */
    fun reconcileAfterRestart(): List<JobId> {
        val recovered = mutableListOf<JobId>()
        for (job in store.listActive()) {
            if (job.state != "RUNNING") continue
            when (val result = markProcessLost(job.jobId)) {
                is OmniResult.Ok -> recovered.add(result.value.jobId)
                is OmniResult.Err -> Unit // leave for operator / next reconcile
            }
        }
        return recovered
    }

    /**
     * Create job or return existing identical claim (ADR-004/005).
     * Same claim key with different digest ⇒ [OmniError.IDEMPOTENCY_CONFLICT].
     */
    fun create(
        identity: JobIdentity,
        parameters: JobParameters,
    ): OmniResult<JobClaimResult> {
        val kindError = validateKindParameters(identity.kind, parameters)
        if (kindError != null) return OmniResult.err(kindError)

        val existing = store.findByClaim(
            identity.principalId.value,
            identity.kind,
            identity.idempotencyKey.value,
        )
        if (existing != null) {
            if (existing.identity.canonicalSpecDigest != identity.canonicalSpecDigest ||
                existing.identity.jobId.value != identity.jobId.value
            ) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(
                        message = "job claim conflict",
                        details = mapOf(
                            "principalId" to identity.principalId.value,
                            "kind" to identity.kind.name,
                        ),
                    ),
                )
            }
            return OmniResult.ok(JobClaimResult(existing, createdNew = false))
        }

        val byId = store.findById(identity.jobId)
        if (byId != null) {
            if (byId.identity.canonicalSpecDigest != identity.canonicalSpecDigest ||
                byId.identity.principalId.value != identity.principalId.value ||
                byId.identity.kind != identity.kind ||
                byId.identity.idempotencyKey.value != identity.idempotencyKey.value
            ) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(message = "jobId collision with different claim"),
                )
            }
            return OmniResult.ok(JobClaimResult(byId, createdNew = false))
        }

        val now = clock()
        val record = JobRecord(
            identity = identity,
            parameters = parameters,
            state = StateMachines.JOB.initial,
            resourceVersion = 0L,
            currentAttemptNo = null,
            attempts = emptyList(),
            checkpoint = null,
            progress = JobProgress(),
            pauseReason = null,
            error = null,
            events = listOf(
                JobEventRecord(
                    eventId = 1L,
                    attemptNo = null,
                    eventKind = "CREATED",
                    payload = mapOf("kind" to identity.kind.name),
                    occurredAtEpochMs = now,
                ),
            ),
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        store.putNew(record)
        return OmniResult.ok(JobClaimResult(record, createdNew = true))
    }

    /**
     * QUEUED → RUNNING with a new attempt (JOB-001, guard `attemptClaimed`).
     * Exactly one active attempt per job.
     */
    fun start(jobId: JobId): OmniResult<JobRecord> {
        val existing = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found: ${jobId.value}"))
        val hasOpenAttempt = existing.currentAttemptNo != null &&
            existing.attempts.any {
                it.attemptNo == existing.currentAttemptNo && it.endedAtEpochMs == null
            }
        if (hasOpenAttempt) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "one active attempt per job",
                    details = mapOf(
                        "currentAttemptNo" to existing.currentAttemptNo.toString(),
                    ),
                ),
            )
        }
        return transition(jobId, event = "START") { _, _ ->
            GuardEvaluator.of(
                "attemptClaimed" to true,
                "checkpointValid" to false,
            )
        }.map { afterStart ->
            val attemptNo = (afterStart.currentAttemptNo ?: 0) + 1
            val now = clock()
            val attempt = JobAttemptRecord(
                attemptNo = attemptNo,
                state = "RUNNING",
                startedAtEpochMs = now,
            )
            val updated = afterStart.copy(
                currentAttemptNo = attemptNo,
                attempts = afterStart.attempts + attempt,
                pauseReason = null,
                cancelRequested = false,
            )
            persistEvent(updated, "START", mapOf("attemptNo" to attemptNo.toString()))
        }
    }

    /**
     * Pause RUNNING for input / network / foreground (JOB-003/004/005).
     */
    fun pause(jobId: JobId, reason: JobPauseReason): OmniResult<JobRecord> =
        transition(jobId, event = reason.waitEvent()) { record, _ ->
            GuardEvaluator.ALWAYS_TRUE
        }.map { after ->
            persistEvent(
                after.copy(pauseReason = reason),
                reason.waitEvent(),
                mapOf("pauseReason" to reason.name),
            )
        }

    /**
     * Resume from a paused state when the blocking condition clears
     * (JOB-009/010/011, guard `checkpointValid`).
     */
    fun resume(
        jobId: JobId,
        reason: JobPauseReason,
        checkpoint: JobCheckpoint? = null,
    ): OmniResult<JobRecord> {
        val current = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
        if (current.pauseReason != null && current.pauseReason != reason) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "pause reason mismatch",
                    details = mapOf(
                        "expected" to (current.pauseReason?.name ?: ""),
                        "requested" to reason.name,
                    ),
                ),
            )
        }
        val effectiveCheckpoint = checkpoint ?: current.checkpoint
        return transition(jobId, event = reason.resumeEvent()) { record, _ ->
            GuardEvaluator.of(
                "checkpointValid" to isCheckpointValid(record, effectiveCheckpoint),
                "attemptClaimed" to true,
            )
        }.map { after ->
            val withCp = if (effectiveCheckpoint != null) {
                after.copy(checkpoint = effectiveCheckpoint, pauseReason = null)
            } else {
                after.copy(pauseReason = null)
            }
            persistEvent(withCp, reason.resumeEvent(), emptyMap())
        }
    }

    /**
     * Persist a durable checkpoint while RUNNING or paused.
     * Checkpoint must bind to current attempt (JOB invariant).
     */
    fun saveCheckpoint(jobId: JobId, checkpoint: JobCheckpoint): OmniResult<JobRecord> {
        val record = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
        if (record.isTerminal) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "cannot checkpoint terminal job"),
            )
        }
        val currentAttempt = record.currentAttemptNo
            ?: return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "no active attempt for checkpoint"),
            )
        if (checkpoint.attemptNo != currentAttempt) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "checkpoint attemptNo must match current attempt",
                    details = mapOf(
                        "currentAttemptNo" to currentAttempt.toString(),
                        "checkpointAttemptNo" to checkpoint.attemptNo.toString(),
                    ),
                ),
            )
        }
        val updated = record.copy(
            checkpoint = checkpoint,
            resourceVersion = record.resourceVersion + 1,
            updatedAtEpochMs = clock(),
        )
        return OmniResult.ok(persistEvent(updated, "CHECKPOINT", mapOf("payloadHash" to checkpoint.payloadHash)))
    }

    /**
     * Update progress counters (does not change FSM state).
     */
    fun updateProgress(jobId: JobId, progress: JobProgress): OmniResult<JobRecord> {
        val record = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
        if (record.isTerminal) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "terminal job"))
        }
        val updated = record.copy(
            progress = progress,
            resourceVersion = record.resourceVersion + 1,
            updatedAtEpochMs = clock(),
        )
        store.replace(updated)
        return OmniResult.ok(updated)
    }

    /**
     * Worker/process loss: RUNNING → RECOVERING (JOB-006).
     */
    fun markProcessLost(jobId: JobId): OmniResult<JobRecord> =
        transition(jobId, event = "PROCESS_LOST") { _, _ ->
            GuardEvaluator.ALWAYS_TRUE
        }.map { after ->
            persistEvent(after, "PROCESS_LOST", emptyMap())
        }

    /**
     * RECOVERING → RUNNING when checkpoint is valid (JOB-007),
     * or RECOVERING → FAILED when invalid (JOB-008).
     */
    fun completeRecovery(
        jobId: JobId,
        checkpointValid: Boolean,
        checkpoint: JobCheckpoint? = null,
    ): OmniResult<JobRecord> {
        val event = if (checkpointValid) "CHECKPOINT_READY" else "CHECKPOINT_INVALID"
        return transition(jobId, event = event) { record, _ ->
            val cp = checkpoint ?: record.checkpoint
            GuardEvaluator.of(
                "checkpointValid" to (checkpointValid && isCheckpointValid(record, cp)),
                "attemptClaimed" to true,
            )
        }.map { after ->
            val withCp = if (checkpoint != null && checkpointValid) {
                after.copy(checkpoint = checkpoint, pauseReason = null)
            } else {
                after.copy(pauseReason = null)
            }
            val closed = if (!checkpointValid) closeOpenAttempt(withCp, "FAILED") else withCp
            persistEvent(closed, event, mapOf("checkpointValid" to checkpointValid.toString()))
        }
    }

    fun succeed(jobId: JobId): OmniResult<JobRecord> =
        transition(jobId, event = "SUCCESS") { _, _ ->
            GuardEvaluator.ALWAYS_TRUE
        }.map { after ->
            persistEvent(closeOpenAttempt(after, "SUCCEEDED"), "SUCCESS", emptyMap())
        }

    fun fail(jobId: JobId, error: OmniError): OmniResult<JobRecord> =
        transition(jobId, event = "FAILURE") { _, _ ->
            GuardEvaluator.ALWAYS_TRUE
        }.map { after ->
            persistEvent(
                closeOpenAttempt(after.copy(error = error), "FAILED"),
                "FAILURE",
                mapOf("errorCode" to error.code.code),
            )
        }

    /**
     * Cancel from any non-terminal state that has a CANCEL edge
     * (JOB-002/014/015/017/019/021). Paused jobs cancel without permanent stickiness.
     *
     * When the phase cannot interrupt immediately, set [requestOnly] to mark
     * cancel-requested / safe-stopping without forcing the edge yet.
     */
    fun cancel(jobId: JobId, requestOnly: Boolean = false): OmniResult<JobRecord> {
        val record = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
        if (record.isTerminal) {
            if (record.state == "CANCELLED") return OmniResult.ok(record)
            return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "job already terminal: ${record.state}"),
            )
        }
        if (requestOnly && record.state == "RUNNING") {
            val marked = record.copy(
                cancelRequested = true,
                resourceVersion = record.resourceVersion + 1,
                updatedAtEpochMs = clock(),
            )
            return OmniResult.ok(persistEvent(marked, "CANCEL_REQUESTED", emptyMap()))
        }
        return transition(jobId, event = "CANCEL") { _, _ ->
            GuardEvaluator.ALWAYS_TRUE
        }.map { after ->
            persistEvent(
                closeOpenAttempt(
                    after.copy(
                        cancelRequested = true,
                        error = OmniError.CANCELLED(message = "job cancelled"),
                        pauseReason = null,
                    ),
                    "CANCELLED",
                ),
                "CANCEL",
                emptyMap(),
            )
        }
    }

    /**
     * Fail a paused job due to policy/deadline or permanently unavailable input
     * (JOB-016/018/020). Prevents permanent stuckness (FEAT-ADMIN acceptance §3).
     */
    fun failPaused(jobId: JobId, error: OmniError): OmniResult<JobRecord> {
        val record = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
        val reason = record.pauseReason
            ?: JobPauseReason.fromState(record.state)
            ?: return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "job is not paused"),
            )
        val event = reason.permanentFailEvent()
            ?: return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "no permanent fail edge for pause"),
            )
        return transition(jobId, event = event) { _, _ ->
            GuardEvaluator.ALWAYS_TRUE
        }.map { after ->
            persistEvent(
                closeOpenAttempt(after.copy(error = error, pauseReason = null), "FAILED"),
                event,
                mapOf("errorCode" to error.code.code),
            )
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun transition(
        jobId: JobId,
        event: String,
        guardsFor: (JobRecord, GuardEvaluator) -> GuardEvaluator,
    ): OmniResult<JobRecord> {
        val record = store.findById(jobId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found: ${jobId.value}"))
        if (record.isTerminal) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "terminal job rejects event $event",
                    details = mapOf("state" to record.state),
                ),
            )
        }
        val aggregate = JobAggregate(
            jobId = jobId,
            ownerKey = OwnerKey(record.identity.principalId.value),
            state = record.state,
        )
        val guards = guardsFor(record, GuardEvaluator.ALWAYS_TRUE)
        return when (val result = aggregate.apply(event, guards)) {
            is AggregateTransitionResult.Success -> {
                val now = clock()
                val next = record.copy(
                    state = result.aggregate.state,
                    resourceVersion = record.resourceVersion + 1,
                    updatedAtEpochMs = now,
                    pauseReason = JobPauseReason.fromState(result.aggregate.state),
                )
                store.replace(next)
                OmniResult.ok(next)
            }
            is AggregateTransitionResult.Rejected -> {
                OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "illegal job transition: ${result.rejection.reason}",
                        details = mapOf(
                            "from" to record.state,
                            "event" to event,
                            "machine" to "JOB",
                        ),
                    ),
                )
            }
        }
    }

    private fun persistEvent(
        record: JobRecord,
        eventKind: String,
        payload: Map<String, String>,
    ): JobRecord {
        val nextId = (record.events.maxOfOrNull { it.eventId } ?: 0L) + 1L
        val event = JobEventRecord(
            eventId = nextId,
            attemptNo = record.currentAttemptNo,
            eventKind = eventKind,
            payload = payload,
            occurredAtEpochMs = clock(),
        )
        val updated = record.copy(
            events = record.events + event,
            updatedAtEpochMs = clock(),
        )
        store.replace(updated)
        return updated
    }

    private fun closeOpenAttempt(record: JobRecord, attemptState: String): JobRecord {
        val openNo = record.currentAttemptNo ?: return record
        val now = clock()
        val attempts = record.attempts.map { a ->
            if (a.attemptNo == openNo && a.endedAtEpochMs == null) {
                a.copy(state = attemptState, endedAtEpochMs = now)
            } else {
                a
            }
        }
        return record.copy(attempts = attempts)
    }

    /**
     * JOB guard `checkpointValid`: checkpoint belongs to job, attempt and
     * canonical payload hash; non-null when resume requires durable cursor.
     *
     * Resume without any prior checkpoint is allowed only when the job never
     * materialised progress (fresh attempt restart from phase start).
     */
    private fun isCheckpointValid(record: JobRecord, checkpoint: JobCheckpoint?): Boolean {
        if (checkpoint == null) {
            // Fresh jobs with no durable progress may resume without checkpoint.
            return record.progress.networkBytes == 0L &&
                record.progress.materializedBytes == 0L &&
                record.progress.verifiedBytes == 0L
        }
        val currentAttempt = record.currentAttemptNo ?: return false
        if (checkpoint.attemptNo != currentAttempt) return false
        if (!checkpoint.payloadHash.matches(HEX64)) return false
        if (checkpoint.resumeCursor.isBlank()) return false
        return true
    }

    private fun validateKindParameters(
        kind: JobKind,
        parameters: JobParameters,
    ): OmniError? {
        val ok = when (kind) {
            JobKind.DOWNLOAD -> parameters is JobParameters.Download
            JobKind.IMPORT -> parameters is JobParameters.Import
            JobKind.BENCHMARK -> parameters is JobParameters.Benchmark
            JobKind.DELETE -> parameters is JobParameters.Delete
            JobKind.DIAGNOSTIC_EXPORT -> parameters is JobParameters.DiagnosticExport
            JobKind.CONTENT_REPORT -> parameters is JobParameters.ContentReport
        }
        if (!ok) {
            return OmniError.INVALID_REQUEST(
                message = "parameters type does not match job kind",
                details = mapOf(
                    "kind" to kind.name,
                    "parameters" to parameters::class.simpleName.orEmpty(),
                ),
            )
        }
        // SEC-INPUT §3 / SEC-SUPPLY §5: fail closed on non-HTTPS / userinfo / file URLs.
        if (kind == JobKind.DOWNLOAD && parameters is JobParameters.Download) {
            when (val url = DownloadUrlPolicy.admitUrl(parameters.sourceUrl, downloadUrlPolicy)) {
                is DownloadUrlPolicy.Outcome.Rejected -> return url.error
                is DownloadUrlPolicy.Outcome.Accepted -> Unit
            }
        }
        return null
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun ownerKey(principalId: PrincipalId): OwnerKey = OwnerKey(principalId.value)

        fun jobIdentity(
            jobId: String,
            principalId: String,
            kind: JobKind,
            idempotencyKey: String,
            canonicalSpecDigest: String,
        ): JobIdentity = JobIdentity(
            jobId = JobId(jobId),
            principalId = PrincipalId.parse(principalId),
            kind = kind,
            idempotencyKey = IdempotencyKey.parse(idempotencyKey),
            canonicalSpecDigest = canonicalSpecDigest.lowercase(),
        )
    }
}
