package com.omnillm.engines.llamacpp

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
import com.omnillm.engines.llamacpp.mapping.ErrorMapper
import com.omnillm.engines.llamacpp.mapping.EventNormalizer
import com.omnillm.engines.llamacpp.mapping.ParameterValidator
import com.omnillm.engines.llamacpp.native.NativeEmbedRequest
import com.omnillm.engines.llamacpp.native.NativeGenerateRequest
import com.omnillm.engines.llamacpp.native.NativeResult
import com.omnillm.engines.llamacpp.native.NativeSessionRequest
import com.omnillm.engines.llamacpp.native.NativeSessionToken
import com.omnillm.engines.llamacpp.resource.ResourceEnvelopeEstimator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-loaded-model port for llama.cpp (CORE-ENGINE §2 LoadedModelPort).
 *
 * Prefix decisions TRUNCATE / FORK / EXACT_CROSS_SESSION remain UNKNOWN until
 * qualification — proposing them without evidence fails closed via
 * [PrefixDecision.NONE] / CAPABILITY_UNKNOWN when requested via attributes.
 */
class LlamaCppLoadedModelPort(
    private val engine: LlamaCppEngine,
    private val bound: LlamaCppEngine.BoundLoadedModel,
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

        // Explicit request for unqualified prefix ops fails closed.
        input.attributes["prefixMode"]?.let { mode ->
            if (mode in setOf("TRUNCATE", "FORK", "EXACT_CROSS_SESSION")) {
                return OmniResult.err(
                    ErrorMapper.capabilityUnknown(
                        capability = "prefix.$mode",
                        reason = "prefix $mode not qualified for this llama.cpp build",
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

        val nCtx = when (
            val p = ParameterValidator.parsePositiveInt(input.attributes, "nCtx", 2048)
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }
        val nThreads = when (
            val p = ParameterValidator.parsePositiveInt(
                input.attributes,
                "nThreads",
                ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
            )
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }

        val envelope = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(
                contextLength = nCtx,
                nThreads = nThreads,
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
                planId = engine.nextPlanId("llama-inf-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = LlamaCppEngine.digestOf(
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

        val sessionToken = when (
            val created = engine.native.createSession(
                bound.modelToken,
                NativeSessionRequest(nCtx = 2048),
            )
        ) {
            is NativeResult.Ok -> created.value
            is NativeResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(created.error))
                engine.recordCommit(
                    commit = commit,
                    kind = LlamaCppEngine.CommitKind.INFERENCE,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
        }

        val sessionId = SessionHandleId.parse(
            "llama-session-${sessionSeq.incrementAndGet()}",
        )
        val epoch = plan.sourceSessionEpoch ?: 0L
        sessions[sessionId.value] = SessionRecord(
            epoch = epoch,
            nativeToken = sessionToken,
        )

        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse(
                "llama-prep-${prepSeq.incrementAndGet()}",
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
            kind = LlamaCppEngine.CommitKind.INFERENCE,
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

        // Collect native events synchronously, then flush to suspend [EventSink].
        // JNI backend should replace this with a channel/flow bridge.
        val normalizer = EventNormalizer(
            requestId = prepared.requestId,
            runtimeEpoch = op.runtimeEpoch,
        )
        val events = mutableListOf<com.omnillm.engines.api.EngineEvent>()
        val cancelFlag = AtomicBoolean(false)

        val genReq = NativeGenerateRequest(
            operationToken = op.operationId,
            promptDigestHex = prepared.canonicalInputDigest.hex,
            maxTokens = 16,
        )
        val genResult = engine.native.generate(
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
                // Stream may already have a terminal event after partial decode.
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

        // Embedding pooling unsupported-by-default: plan still returns envelope;
        // commit path fails closed until pooling is qualified.
        val envelope = ResourceEnvelopeEstimator.estimateEmbedding(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 512L * 1024L),
        )
        return OmniResult.ok(
            EmbeddingPlan(
                planId = engine.nextPlanId("llama-emb-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = LlamaCppEngine.digestOf(
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

        // Fail closed until pooling qualified (ENGINE-LLAMACPP §10).
        when (
            val emb = engine.native.embed(
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
                    kind = LlamaCppEngine.CommitKind.EMBEDDING,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
            is NativeResult.Ok -> {
                val prepared = PreparedOperation(
                    preparedOperationId = PreparedOperationId.parse(
                        "llama-emb-prep-${prepSeq.incrementAndGet()}",
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
                    kind = LlamaCppEngine.CommitKind.EMBEDDING,
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
            engine.native.closeSession(rec.nativeToken)
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
            sessions.remove(sid)?.let { engine.native.closeSession(it.nativeToken) }
        }
        engine.native.unloadModel(bound.modelToken)
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
        val nativeToken: NativeSessionToken,
    )
}
