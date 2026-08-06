package com.omnillm.engines.api.fake

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.SessionHandleId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.CloseResult
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.api.EmbeddingInput
import com.omnillm.engines.api.EmbeddingPlan
import com.omnillm.engines.api.EngineDescriptor
import com.omnillm.engines.api.EngineEvent
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.api.EngineSourceSessionRef
import com.omnillm.engines.api.EventSink
import com.omnillm.engines.api.InferenceInput
import com.omnillm.engines.api.InferencePlan
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.engines.api.LoadedModelHandle
import com.omnillm.engines.api.LoadedModelPort
import com.omnillm.engines.api.OmniEngine
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.api.OperationHandle
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.api.ProbeInput
import com.omnillm.engines.api.ProbePlan
import com.omnillm.engines.api.ProbeResult
import com.omnillm.engines.api.UnloadResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process fake engine for tests (no native code).
 *
 * Exercises **Plan → Reserve → Commit → Execute**:
 * - Plan methods are pure (no domain mutation)
 * - Commit is one-shot / idempotent / queryable by commitId
 * - Start streams metadata → delta → usage → terminal via [EventSink]
 *
 * Does **not** write OmniLLM DB or model store (ENGINE-STANDARD §3).
 */
class FakeEngine(
    override val engineBuildId: EngineBuildId = EngineBuildId.parse("fake-engine-build-1"),
    val engineId: String = "fake",
    val backend: String = "cpu",
    var placementClass: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
    /** Bytes charged on load peak/steady for governor tests. */
    var loadPeakBytes: Long = 4_096L,
    var loadSteadyBytes: Long = 2_048L,
    var inferencePeakBytes: Long = 1_024L,
    var inferenceSteadyBytes: Long = 512L,
    var streamDeltaCount: Int = 2,
    var failPlanLoad: Boolean = false,
    var failCommitLoad: Boolean = false,
    var failPlanInference: Boolean = false,
    var failCommitInference: Boolean = false,
    var failStart: Boolean = false,
) : OmniEngine {

    private val planSeq = AtomicInteger(0)
    private val loadedSeq = AtomicInteger(0)
    private val prepSeq = AtomicInteger(0)
    private val sessionSeq = AtomicLong(0)

    /** commitId → durable state label (COMMIT machine). */
    private val commits = ConcurrentHashMap<String, CommitRecord>()
    private val loaded = ConcurrentHashMap<String, LoadedModelHandle>()

    val planLoadCount get() = planSeq.get()
    val commitLoadCount: Int get() = commits.values.count { it.kind == CommitKind.LOAD }
    val startCount = AtomicInteger(0)

    override suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor> {
        return OmniResult.ok(
            EngineDescriptor(
                engineBuildId = engineBuildId,
                engineId = engineId,
                backends = listOf(backend),
                supportedFormats = listOf("fake-gguf"),
                placementClasses = listOf(placementClass),
                phaseCancellation = mapOf(
                    EnginePhases.PROBE to CancellationModes.COOPERATIVE,
                    EnginePhases.LOAD to CancellationModes.COOPERATIVE,
                    EnginePhases.PLAN to CancellationModes.COOPERATIVE,
                    EnginePhases.COMMIT to CancellationModes.COOPERATIVE,
                    EnginePhases.START to CancellationModes.INTERRUPTIBLE,
                    EnginePhases.GENERATE to CancellationModes.INTERRUPTIBLE,
                    EnginePhases.CLOSE to CancellationModes.COOPERATIVE,
                    EnginePhases.UNLOAD to CancellationModes.COOPERATIVE,
                    EnginePhases.CREATE_SESSION to CancellationModes.COOPERATIVE,
                ),
                notes = mapOf(
                    "deviceFingerprint" to device.deviceExecutionFingerprint.value,
                    "native" to "false",
                ),
            ),
        )
    }

    override suspend fun planProbe(input: ProbeInput): OmniResult<ProbePlan> {
        val n = planSeq.incrementAndGet()
        val dig = digest('a')
        return OmniResult.ok(
            ProbePlan(
                planId = PlanId.parse("fake-probe-plan-$n"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                backend = input.backend,
                resourceEnvelope = envelope(256, 512),
                phaseCapabilityDigest = dig,
                canonicalInputDigest = dig,
                expiryMonotonic = Long.MAX_VALUE / 4,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    override suspend fun probe(
        plan: ProbePlan,
        reservation: Reservation,
        op: OperationContext,
    ): OmniResult<ProbeResult> {
        // Fake accepts any live reservation; real adapters validate envelope fit.
        if (reservation.principalId.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "empty reservation principal"))
        }
        return OmniResult.ok(
            ProbeResult(
                planId = plan.planId,
                success = true,
                observedEnvelope = plan.resourceEnvelope,
                attributes = mapOf(
                    "backend" to plan.backend,
                    "operationId" to op.operationId,
                    "reservationId" to reservation.reservationId.value,
                ),
            ),
        )
    }

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        if (failPlanLoad) {
            return OmniResult.err(OmniError.INTERNAL(message = "fake planLoad failed"))
        }
        val n = planSeq.incrementAndGet()
        val dig = digest('b')
        return OmniResult.ok(
            LoadPlan(
                planId = PlanId.parse("fake-load-plan-$n"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadKey = input.loadKey,
                installationId = input.installationId,
                modelRevisionId = input.modelRevisionId,
                resourceEnvelope = envelope(loadSteadyBytes, loadPeakBytes),
                proposedPlacementClass = placementClass,
                phaseCapabilityDigest = dig,
                canonicalInputDigest = dig,
                expiryMonotonic = Long.MAX_VALUE / 4,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    override suspend fun commitLoad(
        plan: LoadPlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<LoadedModelHandle> {
        val existing = commits[commit.commitId.value]
        if (existing != null) {
            if (existing.payloadDigest != commitPayload(plan, commit)) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(message = "commitLoad payload mismatch"),
                )
            }
            @Suppress("UNCHECKED_CAST")
            return when (val r = existing.result) {
                is OmniResult.Ok<*> -> OmniResult.ok(r.value as LoadedModelHandle)
                is OmniResult.Err -> r
            }
        }

        if (failCommitLoad) {
            val err = OmniResult.err(OmniError.INTERNAL(message = "fake commitLoad failed"))
            commits[commit.commitId.value] = CommitRecord(
                kind = CommitKind.LOAD,
                state = "ABORTED",
                payloadDigest = commitPayload(plan, commit),
                result = err,
                loadedModelId = null,
            )
            return err
        }

        val n = loadedSeq.incrementAndGet()
        val handle = LoadedModelHandle(
            loadedModelId = LoadedModelId("fake-lm-$n"),
            installationId = plan.installationId,
            engineBuildId = plan.engineBuildId,
            loadKey = plan.loadKey,
            allocationHandleId = AllocationHandleId.parse("fake-alloc-$n"),
            placementClass = plan.proposedPlacementClass,
            runtimeEpoch = plan.runtimeEpoch,
        )
        val ok = OmniResult.ok(handle)
        commits[commit.commitId.value] = CommitRecord(
            kind = CommitKind.LOAD,
            state = "COMMITTED",
            payloadDigest = commitPayload(plan, commit),
            result = ok,
            loadedModelId = handle.loadedModelId,
        )
        loaded[handle.loadedModelId.value] = handle
        return ok
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> {
        val rec = commits[commitId.value]
            // Fail closed: missing journal must not invent INTENT_RECORDED (REL-RECOVERY).
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "commit journal entry not found",
                    details = mapOf("commitId" to commitId.value),
                ),
            )
        return OmniResult.ok(
            CommitQueryState(
                commitId = commitId,
                state = rec.state,
                loadedModelId = rec.loadedModelId,
            ),
        )
    }

    override fun bindLoadedModel(handle: LoadedModelHandle): OmniResult<LoadedModelPort> {
        val live = loaded[handle.loadedModelId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "loaded model not bound on fake engine",
                    details = mapOf("loadedModelId" to handle.loadedModelId.value),
                ),
            )
        if (live.engineBuildId != engineBuildId) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "engineBuildId mismatch"),
            )
        }
        return OmniResult.ok(FakeLoadedModelPort(this, live))
    }

    internal fun recordInferenceCommit(
        commit: CommitContext,
        plan: InferencePlan,
        prepared: PreparedOperation,
    ): OmniResult<PreparedOperation> {
        val existing = commits[commit.commitId.value]
        val dig = "inf|${plan.planId.value}|${commit.oneShotNonce}"
        if (existing != null) {
            if (existing.payloadDigest != dig) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(message = "commitInference payload mismatch"),
                )
            }
            @Suppress("UNCHECKED_CAST")
            return when (val r = existing.result) {
                is OmniResult.Ok<*> -> OmniResult.ok(r.value as PreparedOperation)
                is OmniResult.Err -> r
            }
        }
        if (failCommitInference) {
            val err = OmniResult.err(OmniError.STATE_CONFLICT(message = "fake commitInference failed"))
            commits[commit.commitId.value] = CommitRecord(
                kind = CommitKind.INFERENCE,
                state = "ABORTED",
                payloadDigest = dig,
                result = err,
                loadedModelId = plan.loadedModelId,
            )
            return err
        }
        val ok = OmniResult.ok(prepared)
        commits[commit.commitId.value] = CommitRecord(
            kind = CommitKind.INFERENCE,
            state = "COMMITTED",
            payloadDigest = dig,
            result = ok,
            loadedModelId = plan.loadedModelId,
        )
        return ok
    }

    internal fun recordEmbeddingCommit(
        commit: CommitContext,
        plan: EmbeddingPlan,
        prepared: PreparedOperation,
    ): OmniResult<PreparedOperation> {
        val dig = "emb|${plan.planId.value}|${commit.oneShotNonce}"
        val existing = commits[commit.commitId.value]
        if (existing != null) {
            if (existing.payloadDigest != dig) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(message = "commitEmbedding payload mismatch"),
                )
            }
            @Suppress("UNCHECKED_CAST")
            return when (val r = existing.result) {
                is OmniResult.Ok<*> -> OmniResult.ok(r.value as PreparedOperation)
                is OmniResult.Err -> r
            }
        }
        val ok = OmniResult.ok(prepared)
        commits[commit.commitId.value] = CommitRecord(
            kind = CommitKind.EMBEDDING,
            state = "COMMITTED",
            payloadDigest = dig,
            result = ok,
            loadedModelId = plan.loadedModelId,
        )
        return ok
    }

    internal fun nextPrepId(): String = "fake-prep-${prepSeq.incrementAndGet()}"

    internal fun nextSessionId(): SessionHandleId =
        SessionHandleId.parse("fake-session-${sessionSeq.incrementAndGet()}")

    internal fun removeLoaded(id: LoadedModelId) {
        loaded.remove(id.value)
    }

    private fun commitPayload(plan: LoadPlan, commit: CommitContext): String =
        "load|${plan.planId.value}|${commit.oneShotNonce}|${commit.privilegedLoadTicketId}"

    private fun envelope(steady: Long, peak: Long): ResourceEnvelope =
        ResourceEnvelope(
            steady = ResourceVector(cpuAnonBytes = steady, nativeThreads = 1L),
            peak = ResourceVector(cpuAnonBytes = peak, nativeThreads = 2L),
        )

    companion object {
        fun digest(seed: Char): Sha256Digest {
            require(seed in '0'..'9' || seed in 'a'..'f') { "seed must be hex digit" }
            return Sha256Digest.parse(seed.toString().repeat(64))
        }
    }

    private enum class CommitKind { LOAD, INFERENCE, EMBEDDING }

    private data class CommitRecord(
        val kind: CommitKind,
        val state: String,
        val payloadDigest: String,
        val result: OmniResult<*>,
        val loadedModelId: LoadedModelId?,
    )
}

/**
 * Fake [LoadedModelPort] bound to a [FakeEngine] load handle.
 * Plan is pure; commit/start mutate only in-memory fake state.
 */
class FakeLoadedModelPort(
    private val engine: FakeEngine,
    private val handle: LoadedModelHandle,
) : LoadedModelPort {

    override val loadedModelId: LoadedModelId = handle.loadedModelId
    override val engineBuildId: EngineBuildId = handle.engineBuildId

    private val sessions = ConcurrentHashMap<String, Long>()
    private var unloaded = false
    private val startedOps = ConcurrentHashMap<String, OperationHandle>()

    override suspend fun planInference(
        input: InferenceInput,
        source: EngineSourceSessionRef,
    ): OmniResult<InferencePlan> {
        if (unloaded) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        if (engine.failPlanInference) {
            return OmniResult.err(OmniError.INTERNAL(message = "fake planInference failed"))
        }
        val sourceEpoch = when (source) {
            is EngineSourceSessionRef.None -> null
            is EngineSourceSessionRef.Existing -> {
                val live = sessions[source.sessionHandleId.value]
                    ?: return OmniResult.err(
                        OmniError.NOT_FOUND(message = "source session not found"),
                    )
                if (live != source.sessionEpoch) {
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(message = "source session epoch mismatch"),
                    )
                }
                source.sessionEpoch
            }
        }
        val dig = FakeEngine.digest('c')
        return OmniResult.ok(
            InferencePlan(
                planId = PlanId.parse("fake-inf-plan-${input.requestId.value.take(8)}"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = ResourceEnvelope(
                    steady = ResourceVector(
                        cpuAnonBytes = engine.inferenceSteadyBytes,
                        nativeThreads = 1L,
                    ),
                    peak = ResourceVector(
                        cpuAnonBytes = engine.inferencePeakBytes,
                        nativeThreads = 2L,
                    ),
                ),
                phaseCapabilityDigest = dig,
                canonicalInputDigest = input.canonicalInputDigest,
                proposedPrefixDecision = if (sourceEpoch != null) {
                    PrefixDecision.EXACT_SAME_SESSION
                } else {
                    PrefixDecision.NONE
                },
                sourceSessionEpoch = sourceEpoch,
                expiryMonotonic = input.deadlineMonotonic,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    override suspend fun commitInference(
        plan: InferencePlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<PreparedOperation> {
        if (unloaded) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        val session = engine.nextSessionId()
        sessions[session.value] = plan.sourceSessionEpoch ?: 0L
        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse(engine.nextPrepId()),
            operationId = "op-${commit.commitId.value}",
            requestId = commit.requestId,
            commitId = commit.commitId,
            principalId = commit.principalId,
            reservationId = reservation.reservationId,
            revisionLeaseId = commit.revisionLeaseId,
            issuerBootId = commit.issuerBootId,
            runtimeEpoch = commit.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            sourceSessionEpoch = plan.sourceSessionEpoch,
            targetSessionId = session,
            canonicalInputDigest = plan.canonicalInputDigest,
            allocationHandleId = handle.allocationHandleId,
        )
        return engine.recordInferenceCommit(commit, plan, prepared)
    }

    override suspend fun start(
        prepared: PreparedOperation,
        op: OperationContext,
        sink: EventSink,
    ): OmniResult<OperationHandle> {
        if (unloaded) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        // Idempotent start claim by operationId.
        startedOps[op.operationId]?.let { return OmniResult.ok(it) }

        if (engine.failStart) {
            return OmniResult.err(OmniError.WORKER_DIED(message = "fake start failed"))
        }
        if (prepared.operationId != op.operationId &&
            prepared.operationId.isNotEmpty() &&
            op.operationId != prepared.operationId
        ) {
            // Prefer caller-supplied op.operationId when they differ after claim.
        }

        engine.startCount.incrementAndGet()
        val handleOut = OperationHandle(
            operationId = op.operationId,
            preparedOperationId = prepared.preparedOperationId.value,
            requestId = prepared.requestId,
            state = "RUNNING",
            runtimeEpoch = op.runtimeEpoch,
        )
        startedOps[op.operationId] = handleOut

        var seq = 0L
        sink.onEvent(
            EngineEvent(
                seq = seq++,
                kind = EngineEventKinds.METADATA,
                requestId = prepared.requestId,
                runtimeEpoch = op.runtimeEpoch,
                attributes = mapOf("engineBuildId" to engineBuildId.value),
            ),
        )
        repeat(engine.streamDeltaCount) { i ->
            sink.onEvent(
                EngineEvent(
                    seq = seq++,
                    kind = EngineEventKinds.DELTA,
                    requestId = prepared.requestId,
                    runtimeEpoch = op.runtimeEpoch,
                    payloadDigest = FakeEngine.digest('d'),
                    attributes = mapOf("index" to i.toString()),
                ),
            )
        }
        sink.onEvent(
            EngineEvent(
                seq = seq++,
                kind = EngineEventKinds.USAGE,
                requestId = prepared.requestId,
                runtimeEpoch = op.runtimeEpoch,
                attributes = mapOf("promptTokens" to "1", "completionTokens" to engine.streamDeltaCount.toString()),
            ),
        )
        sink.onEvent(
            EngineEvent(
                seq = seq,
                kind = EngineEventKinds.TERMINAL,
                requestId = prepared.requestId,
                runtimeEpoch = op.runtimeEpoch,
                payloadDigest = FakeEngine.digest('f'),
                attributes = mapOf("disposition" to "COMPLETED"),
            ),
        )
        return OmniResult.ok(handleOut.copy(state = "COMPLETED"))
    }

    override suspend fun planEmbedding(input: EmbeddingInput): OmniResult<EmbeddingPlan> {
        if (unloaded) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        val dig = FakeEngine.digest('e')
        return OmniResult.ok(
            EmbeddingPlan(
                planId = PlanId.parse("fake-emb-plan-${input.requestId.value.take(8)}"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = ResourceEnvelope(
                    steady = ResourceVector(cpuAnonBytes = 256L),
                    peak = ResourceVector(cpuAnonBytes = 512L),
                ),
                phaseCapabilityDigest = dig,
                canonicalInputDigest = input.canonicalInputDigest,
                expiryMonotonic = input.deadlineMonotonic,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    override suspend fun commitEmbedding(
        plan: EmbeddingPlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<PreparedOperation> {
        if (unloaded) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse(engine.nextPrepId()),
            operationId = "op-emb-${commit.commitId.value}",
            requestId = commit.requestId,
            commitId = commit.commitId,
            principalId = commit.principalId,
            reservationId = reservation.reservationId,
            revisionLeaseId = commit.revisionLeaseId,
            issuerBootId = commit.issuerBootId,
            runtimeEpoch = commit.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            sourceSessionEpoch = null,
            targetSessionId = null,
            canonicalInputDigest = plan.canonicalInputDigest,
            allocationHandleId = handle.allocationHandleId,
        )
        return engine.recordEmbeddingCommit(commit, plan, prepared)
    }

    override suspend fun closeSession(
        handle: SessionHandleId,
        op: OperationContext,
    ): OmniResult<CloseResult> {
        val removed = sessions.remove(handle.value) != null
        return OmniResult.ok(
            CloseResult(
                sessionHandleId = handle,
                closed = removed,
                attributes = mapOf("operationId" to op.operationId),
            ),
        )
    }

    override suspend fun unload(op: OperationContext): OmniResult<UnloadResult> {
        unloaded = true
        sessions.clear()
        engine.removeLoaded(loadedModelId)
        return OmniResult.ok(
            UnloadResult(
                loadedModelId = loadedModelId,
                unloaded = true,
                attributes = mapOf("operationId" to op.operationId),
            ),
        )
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        engine.queryCommit(commitId)
}
