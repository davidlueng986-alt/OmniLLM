package com.omnillm.engines.mlcllm

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
import com.omnillm.engines.mlcllm.mapping.ErrorMapper
import com.omnillm.engines.mlcllm.mapping.EventNormalizer
import com.omnillm.engines.mlcllm.mapping.ParameterValidator
import com.omnillm.engines.mlcllm.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.mlcllm.runtime.NativeEmbedRequest
import com.omnillm.engines.mlcllm.runtime.NativeGenerateRequest
import com.omnillm.engines.mlcllm.runtime.NativeResult
import com.omnillm.engines.mlcllm.runtime.NativeSessionRequest
import com.omnillm.engines.mlcllm.runtime.NativeSessionToken
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-loaded-model port for MLC-LLM (CORE-ENGINE §2 LoadedModelPort).
 *
 * Maps chat/session + stream generation onto opaque [NativeSessionToken] handles.
 * Prefix TRUNCATE / FORK / EXACT_CROSS_SESSION remain UNKNOWN until the runtime
 * proves snapshot/fork/truncate semantics (ENGINE-MLC §4 / §10).
 * Embedding / multimodal / structured fail closed as CAPABILITY_UNKNOWN.
 */
class MlcLlmLoadedModelPort(
    private val engine: MlcLlmEngine,
    private val bound: MlcLlmEngine.BoundLoadedModel,
) : LoadedModelPort {

    override val loadedModelId: LoadedModelId = bound.handle.loadedModelId
    override val engineBuildId: EngineBuildId = bound.handle.engineBuildId

    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val startedOps = ConcurrentHashMap<String, OperationHandle>()
    private val planParams = ConcurrentHashMap<String, PlanParams>()
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

        // Explicit request for unqualified prefix ops fails closed (ENGINE-MLC §10).
        input.attributes["prefixMode"]?.let { mode ->
            if (mode in setOf("TRUNCATE", "FORK", "EXACT_CROSS_SESSION")) {
                return OmniResult.err(
                    ErrorMapper.capabilityUnknown(
                        capability = "prefix.$mode",
                        reason = "prefix $mode not qualified for this MLC-LLM build",
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
        val temperature = when (
            val p = ParameterValidator.parseOptionalFloat(input.attributes, "temperature")
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }
        val topP = when (
            val p = ParameterValidator.parseOptionalFloat(input.attributes, "topP")
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }
        val topK = when (
            val p = parseOptionalInt(input.attributes, "topK")
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }
        // Real runtime needs the prompt body, not only a digest (ENGINE-MLC §4).
        val promptUtf8 = input.attributes["promptUtf8"]
        val stopSequences = input.attributes["stopSequences"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        planParams[input.requestId.value] = PlanParams(
            maxTokens = maxTokens,
            temperature = temperature,
            topP = topP,
            topK = topK,
            promptUtf8 = promptUtf8,
            stopSequences = stopSequences,
        )

        val envelope = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                scratchBytes = 1L * 1024L * 1024L,
            ),
        )

        val prefix = if (sourceEpoch != null) {
            PrefixDecision.EXACT_SAME_SESSION
        } else {
            PrefixDecision.NONE
        }

        return OmniResult.ok(
            InferencePlan(
                planId = engine.nextPlanId("mlc-inf-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = MlcLlmEngine.digestOf(
                    "inf-phase|${input.requestId.value}|${loadedModelId.value}|$maxTokens",
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

        val sessionToken = when (
            val created = engine.runtime.createSession(
                bound.modelToken,
                NativeSessionRequest(),
            )
        ) {
            is NativeResult.Ok -> created.value
            is NativeResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(created.error))
                engine.recordCommit(
                    commit = commit,
                    kind = MlcLlmEngine.CommitKind.INFERENCE,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
        }

        val sessionId = SessionHandleId.parse(
            "mlc-session-${sessionSeq.incrementAndGet()}",
        )
        val epoch = plan.sourceSessionEpoch ?: 0L
        val params = planParams.remove(plan.requestId.value)
        sessions[sessionId.value] = SessionRecord(
            epoch = epoch,
            nativeToken = sessionToken,
            params = params,
        )

        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse(
                "mlc-prep-${prepSeq.incrementAndGet()}",
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
            kind = MlcLlmEngine.CommitKind.INFERENCE,
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

        val genReq = NativeGenerateRequest(
            operationToken = op.operationId,
            promptDigestHex = prepared.canonicalInputDigest.hex,
            maxTokens = session.params?.maxTokens ?: 16,
            temperature = session.params?.temperature,
            topP = session.params?.topP,
            topK = session.params?.topK,
            stopSequenceCount = session.params?.stopSequences?.size ?: 0,
            promptUtf8 = session.params?.promptUtf8,
            stopSequences = session.params?.stopSequences,
        )
        val genResult = engine.runtime.generate(
            session = session.nativeToken,
            request = genReq,
            cancelFlag = { cancelFlag.get() },
            onEvent = { nativeEvt ->
                normalizer.normalize(nativeEvt)?.let { events.add(it) }
            },
        )

        when (genResult) {
            is NativeResult.Ok -> {
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
            is NativeResult.Err -> {
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

        // Plan may describe envelope; commit always fail-closed until qualified.
        val envelope = ResourceEnvelopeEstimator.estimateEmbedding(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 512L * 1024L),
        )
        return OmniResult.ok(
            EmbeddingPlan(
                planId = engine.nextPlanId("mlc-emb-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = MlcLlmEngine.digestOf(
                    "emb-phase|${input.requestId.value}",
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

        // Always fail closed in scaffold (ENGINE-MLC §10 embedding per-cell).
        when (
            val emb = engine.runtime.embed(
                bound.modelToken,
                NativeEmbedRequest(
                    operationToken = commit.commitId.value,
                    inputDigestHex = plan.canonicalInputDigest.hex,
                ),
            )
        ) {
            is NativeResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(emb.error))
                engine.recordCommit(
                    commit = commit,
                    kind = MlcLlmEngine.CommitKind.EMBEDDING,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
            is NativeResult.Ok -> {
                // Should not occur with default stub; still no trust elevation.
                val prepared = PreparedOperation(
                    preparedOperationId = PreparedOperationId.parse(
                        "mlc-emb-prep-${prepSeq.incrementAndGet()}",
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
                    kind = MlcLlmEngine.CommitKind.EMBEDDING,
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
            engine.runtime.closeSession(rec.nativeToken)
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
            sessions.remove(sid)?.let { engine.runtime.closeSession(it.nativeToken) }
        }
        planParams.clear()
        engine.runtime.unloadModel(bound.modelToken)
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

    /** Sampling + prompt parameters captured at plan time (one-shot per request). */
    private data class PlanParams(
        val maxTokens: Int,
        val temperature: Float?,
        val topP: Float?,
        val topK: Int?,
        val promptUtf8: String?,
        val stopSequences: List<String>?,
    )

    private fun parseOptionalInt(
        attributes: Map<String, String>,
        key: String,
    ): OmniResult<Int?> {
        val raw = attributes[key] ?: return OmniResult.ok(null)
        val v = raw.toIntOrNull()
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "invalid integer for $key",
                    details = mapOf("parameter" to key),
                ),
            )
        if (v <= 0) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "$key must be positive",
                    details = mapOf("parameter" to key),
                ),
            )
        }
        return OmniResult.ok(v)
    }

    private data class SessionRecord(
        val epoch: Long,
        val nativeToken: NativeSessionToken,
        val params: PlanParams?,
    )
}
