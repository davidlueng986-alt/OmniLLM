package com.omnillm.engines.mllm

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.SessionHandleId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.engines.api.CloseResult
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.EmbeddingInput
import com.omnillm.engines.api.EmbeddingPlan
import com.omnillm.engines.api.EngineSourceSessionRef
import com.omnillm.engines.api.EventSink
import com.omnillm.engines.api.InferenceInput
import com.omnillm.engines.api.InferencePlan
import com.omnillm.engines.api.LoadedModelPort
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.api.OperationHandle
import com.omnillm.engines.api.UnloadResult
import com.omnillm.engines.mllm.mapping.ErrorMapper
import com.omnillm.engines.mllm.mapping.EventNormalizer
import com.omnillm.engines.mllm.mapping.ParameterValidator
import com.omnillm.engines.mllm.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.mllm.server.ServerEmbedRequest
import com.omnillm.engines.mllm.server.ServerGenerateRequest
import com.omnillm.engines.mllm.server.ServerResult
import com.omnillm.engines.mllm.server.ServerSessionRequest
import com.omnillm.engines.mllm.server.ServerSessionToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-loaded-model port for mllm (CORE-ENGINE §2 LoadedModelPort).
 *
 * Server conversation/session IDs are opaque [SessionHandleId] values bound to
 * runtime/worker epoch (ENGINE-MLLM §5). Prefix TRUNCATE / FORK /
 * EXACT_CROSS_SESSION remain UNKNOWN until the server proves token/KV semantics.
 *
 * Embedding / multimodal / structured fail closed as CAPABILITY_UNKNOWN.
 */
class MllmLoadedModelPort(
    private val engine: MllmEngine,
    private val bound: MllmEngine.BoundLoadedModel,
) : LoadedModelPort {

    override val loadedModelId: LoadedModelId = bound.handle.loadedModelId
    override val engineBuildId: EngineBuildId = bound.handle.engineBuildId

    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val startedOps = ConcurrentHashMap<String, OperationHandle>()
    private val prepSeq = AtomicInteger(0)
    private val sessionSeq = AtomicInteger(0)
    private val unloaded = AtomicBoolean(false)

    override suspend fun planInference(
        input: InferenceInput,
        source: EngineSourceSessionRef,
    ): OmniResult<InferencePlan> {
        if (unloaded.get()) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }

        ParameterValidator.validateAttributes(input.attributes)?.let {
            return OmniResult.err(it)
        }

        // Explicit request for unqualified prefix ops fails closed (ENGINE-MLLM §5/§9).
        input.attributes["prefixMode"]?.let { mode ->
            if (mode in setOf("TRUNCATE", "FORK", "EXACT_CROSS_SESSION")) {
                return OmniResult.err(
                    ErrorMapper.capabilityUnknown(
                        capability = "prefix.$mode",
                        reason = "prefix $mode not qualified for this mllm build " +
                            "(ENGINE-MLLM §5 — needs exact token/KV proof)",
                    ),
                )
            }
        }

        val sourceEpoch = when (source) {
            is EngineSourceSessionRef.None -> null
            is EngineSourceSessionRef.Existing -> {
                val live = sessions[source.sessionHandleId.value]
                    ?: return OmniResult.err(
                        OmniError.NOT_FOUND(message = "source session not found"),
                    )
                if (live.epoch != source.sessionEpoch) {
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(message = "source session epoch mismatch"),
                    )
                }
                source.sessionEpoch
            }
        }

        val maxTokens = when (
            val p = ParameterValidator.parsePositiveInt(input.attributes, "maxTokens", 16)
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }
        @Suppress("UNUSED_VARIABLE")
        val _mt = maxTokens

        val envelope = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                scratchBytes = 1L * 1024L * 1024L,
            ),
        )

        // Without server KV proof, never claim EXACT_SAME_SESSION as reusable prefix
        // across unknown server semantics; same-session epoch is still allowed.
        val prefix = if (sourceEpoch != null) {
            PrefixDecision.EXACT_SAME_SESSION
        } else {
            PrefixDecision.NONE
        }

        return OmniResult.ok(
            InferencePlan(
                planId = engine.nextPlanId("mllm-inf-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = MllmEngine.digestOf(
                    "inf-phase|${input.requestId.value}|${loadedModelId.value}",
                ),
                canonicalInputDigest = input.canonicalInputDigest,
                proposedPrefixDecision = prefix,
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
        if (unloaded.get()) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }

        val payload = "inf|${plan.planId.value}|${commit.oneShotNonce}"
        engine.findCommit(commit.commitId)?.let { existing ->
            if (existing.payloadDigest != payload) {
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

        if (!engine.allowUnprovenExecution || !engine.lock.isComplete()) {
            val err = OmniResult.err(
                ErrorMapper.capabilityUnknown(
                    capability = "COMMIT_INFERENCE",
                    reason = "mllm inference commit unproven until lock + cell evidence",
                ),
            )
            engine.recordCommit(
                commit = commit,
                kind = MllmEngine.CommitKind.INFERENCE,
                payloadDigest = payload,
                loadedModelId = loadedModelId,
                result = err,
                state = "ABORTED",
            )
            return err
        }

        val sessionToken = when (
            val created = engine.server.createSession(
                bound.modelToken,
                ServerSessionRequest(
                    ownerKey = commit.principalId.value,
                    runtimeEpoch = commit.runtimeEpoch,
                ),
            )
        ) {
            is ServerResult.Ok -> created.value
            is ServerResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(created.error))
                engine.recordCommit(
                    commit = commit,
                    kind = MllmEngine.CommitKind.INFERENCE,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
        }

        val sessionId = SessionHandleId.parse(
            "mllm-session-${sessionSeq.incrementAndGet()}",
        )
        val epoch = plan.sourceSessionEpoch ?: 0L
        sessions[sessionId.value] = SessionRecord(
            epoch = epoch,
            serverToken = sessionToken,
        )

        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse(
                "mllm-prep-${prepSeq.incrementAndGet()}",
            ),
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
            targetSessionId = sessionId,
            canonicalInputDigest = plan.canonicalInputDigest,
            allocationHandleId = bound.handle.allocationHandleId,
        )
        val ok = OmniResult.ok(prepared)
        engine.recordCommit(
            commit = commit,
            kind = MllmEngine.CommitKind.INFERENCE,
            payloadDigest = payload,
            loadedModelId = loadedModelId,
            result = ok,
            state = "COMMITTED",
        )
        return ok
    }

    override suspend fun start(
        prepared: PreparedOperation,
        op: OperationContext,
        sink: EventSink,
    ): OmniResult<OperationHandle> {
        if (unloaded.get()) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }

        startedOps[op.operationId]?.let { return OmniResult.ok(it) }

        if (!engine.allowUnprovenExecution || !engine.lock.isComplete()) {
            return OmniResult.err(
                ErrorMapper.capabilityUnknown(
                    capability = "START",
                    reason = "mllm stream start unproven until lock + cell evidence",
                ),
            )
        }

        val sessionId = prepared.targetSessionId
            ?: return OmniResult.err(
                OmniError.STATE_CONFLICT(message = "prepared operation missing target session"),
            )
        val session = sessions[sessionId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "session not found for start"),
            )

        val handleOut = OperationHandle(
            operationId = op.operationId,
            preparedOperationId = prepared.preparedOperationId.value,
            requestId = prepared.requestId,
            state = "RUNNING",
            runtimeEpoch = op.runtimeEpoch,
        )
        startedOps[op.operationId] = handleOut

        val normalizer = EventNormalizer(
            requestId = prepared.requestId,
            runtimeEpoch = op.runtimeEpoch,
        )
        val events = mutableListOf<com.omnillm.engines.api.EngineEvent>()
        val cancelFlag = AtomicBoolean(false)

        val genReq = ServerGenerateRequest(
            operationToken = op.operationId,
            canonicalInputDigest = prepared.canonicalInputDigest.hex,
            maxTokens = 16,
            // Real backend requires the resolved prompt content (the upstream
            // server cannot synthesize from a digest). The control plane must
            // resolve canonicalInputDigest → content (Stage-5 work item); the
            // backend fails closed with INVALID_ARGUMENT until then.
            promptUtf8 = null,
        )
        val genResult = engine.server.generate(
            session = session.serverToken,
            request = genReq,
            cancelFlag = { cancelFlag.get() },
            onEvent = { serverEvt ->
                normalizer.normalize(serverEvt)?.let { events.add(it) }
            },
        )

        when (genResult) {
            is ServerResult.Ok -> {
                if (!normalizer.hasTerminal) {
                    normalizer.ensureTerminal(
                        disposition = genResult.value.stopReason,
                        extra = mapOf(
                            "promptTokens" to genResult.value.promptTokens.toString(),
                            "completionTokens" to genResult.value.completionTokens.toString(),
                        ),
                    )?.let { events.add(it) }
                }
                for (e in events) sink.onEvent(e)
                val completed = handleOut.copy(state = "COMPLETED")
                startedOps[op.operationId] = completed
                return OmniResult.ok(completed)
            }
            is ServerResult.Err -> {
                val omni = ErrorMapper.toOmniError(genResult.error)
                if (!normalizer.hasTerminal) {
                    normalizer.ensureTerminal(
                        disposition = omni.code.name,
                        extra = mapOf("message" to (omni.message ?: "")),
                    )?.let { events.add(it) }
                }
                for (e in events) sink.onEvent(e)
                if (events.any { it.isTerminal }) {
                    val terminalState = when (omni) {
                        is OmniError.CANCELLED -> "CANCELLED"
                        else -> "FAILED"
                    }
                    val done = handleOut.copy(state = terminalState)
                    startedOps[op.operationId] = done
                    return OmniResult.ok(done)
                }
                startedOps.remove(op.operationId)
                return OmniResult.err(omni)
            }
        }
    }

    override suspend fun planEmbedding(input: EmbeddingInput): OmniResult<EmbeddingPlan> {
        if (unloaded.get()) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        ParameterValidator.validateAttributes(input.attributes)?.let {
            return OmniResult.err(it)
        }

        // Pure plan for envelope visibility; execute path remains UNKNOWN.
        val envelope = ResourceEnvelopeEstimator.estimateEmbedding(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 512L * 1024L),
        )
        return OmniResult.ok(
            EmbeddingPlan(
                planId = engine.nextPlanId("mllm-emb-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = MllmEngine.digestOf(
                    "emb-phase|${input.requestId.value}|${loadedModelId.value}",
                ),
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
        if (unloaded.get()) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }

        val payload = "emb|${plan.planId.value}|${commit.oneShotNonce}"
        engine.findCommit(commit.commitId)?.let { existing ->
            if (existing.payloadDigest != payload) {
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

        // Always fail closed in scaffold (ENGINE-MLLM §9 embedding per-cell).
        when (
            val emb = engine.server.embed(
                bound.modelToken,
                ServerEmbedRequest(
                    operationToken = commit.commitId.value,
                    canonicalInputDigest = plan.canonicalInputDigest.hex,
                ),
            )
        ) {
            is ServerResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(emb.error))
                engine.recordCommit(
                    commit = commit,
                    kind = MllmEngine.CommitKind.EMBEDDING,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
            is ServerResult.Ok -> {
                // Should not occur with default stub; still no trust elevation.
                val prepared = PreparedOperation(
                    preparedOperationId = PreparedOperationId.parse(
                        "mllm-emb-prep-${prepSeq.incrementAndGet()}",
                    ),
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
                    allocationHandleId = bound.handle.allocationHandleId,
                )
                val ok = OmniResult.ok(prepared)
                engine.recordCommit(
                    commit = commit,
                    kind = MllmEngine.CommitKind.EMBEDDING,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = ok,
                    state = "COMMITTED",
                )
                return ok
            }
        }
    }

    override suspend fun closeSession(
        handle: SessionHandleId,
        op: OperationContext,
    ): OmniResult<CloseResult> {
        val rec = sessions.remove(handle.value)
        if (rec != null) {
            engine.server.closeSession(rec.serverToken)
        }
        return OmniResult.ok(
            CloseResult(
                sessionHandleId = handle,
                closed = rec != null,
                attributes = mapOf("operationId" to op.operationId),
            ),
        )
    }

    override suspend fun unload(op: OperationContext): OmniResult<UnloadResult> {
        unloaded.set(true)
        sessions.keys.toList().forEach { sid ->
            sessions.remove(sid)?.let { engine.server.closeSession(it.serverToken) }
        }
        engine.server.unloadModel(bound.modelToken)
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

    private data class SessionRecord(
        val epoch: Long,
        val serverToken: ServerSessionToken,
    )
}
