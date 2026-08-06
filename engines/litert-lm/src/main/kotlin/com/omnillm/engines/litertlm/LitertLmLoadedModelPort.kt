package com.omnillm.engines.litertlm

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
import com.omnillm.engines.litertlm.mapping.ErrorMapper
import com.omnillm.engines.litertlm.mapping.EventNormalizer
import com.omnillm.engines.litertlm.mapping.ParameterValidator
import com.omnillm.engines.litertlm.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.litertlm.sdk.SdkConversationRequest
import com.omnillm.engines.litertlm.sdk.SdkConversationToken
import com.omnillm.engines.litertlm.sdk.SdkEmbedRequest
import com.omnillm.engines.litertlm.sdk.SdkGenerateRequest
import com.omnillm.engines.litertlm.sdk.SdkResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-loaded-model port for LiteRT-LM (CORE-ENGINE §2 LoadedModelPort).
 *
 * Conversation objects are represented only as opaque [SessionHandleId]
 * (ENGINE-LITERT §5). Prefix TRUNCATE / FORK / EXACT_CROSS_SESSION remain
 * UNKNOWN until qualification — proposing them without evidence fails closed.
 *
 * Embedding / multimodal / structured output map to CAPABILITY_UNKNOWN until
 * SDK version + model cell evidence exists (ENGINE-LITERT §10).
 */
class LitertLmLoadedModelPort(
    private val engine: LitertLmEngine,
    private val bound: LitertLmEngine.BoundLoadedModel,
) : LoadedModelPort {

    override val loadedModelId: LoadedModelId = bound.handle.loadedModelId
    override val engineBuildId: EngineBuildId = bound.handle.engineBuildId

    private val sessions = ConcurrentHashMap<String, SessionRecord>()
    private val startedOps = ConcurrentHashMap<String, OperationHandle>()
    /** preparedOperationId → generate parameters captured at commit (Plan is pure). */
    private val preparedParams = ConcurrentHashMap<String, PreparedGenerateParams>()
    /** operationId → cooperative cancel flag for in-flight start/generate. */
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val prepSeq = AtomicInteger(0)
    private val sessionSeq = AtomicInteger(0)
    private val unloaded = AtomicBoolean(false)
    private val planMaxTokens = ConcurrentHashMap<String, Int>()

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

        // Explicit request for unqualified prefix ops fails closed (ENGINE-LITERT §10).
        input.attributes["prefixMode"]?.let { mode ->
            if (mode in setOf("TRUNCATE", "FORK", "EXACT_CROSS_SESSION")) {
                return OmniResult.err(
                    ErrorMapper.capabilityUnknown(
                        capability = "prefix.$mode",
                        reason = "prefix $mode not qualified for this LiteRT-LM build (ENGINE-LITERT §10)",
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

        val envelope = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                scratchBytes = 1L * 1024L * 1024L,
            ),
        )

        // Same-SDK conversation continuation only — not cross-session reuse.
        val prefix = if (sourceEpoch != null) {
            PrefixDecision.EXACT_SAME_SESSION
        } else {
            PrefixDecision.NONE
        }

        val planId = engine.nextPlanId("litert-inf-plan")
        // Capture maxTokens for commit→start (plan itself stays pure / no domain mutation).
        planMaxTokens[planId.value] = maxTokens

        return OmniResult.ok(
            InferencePlan(
                planId = planId,
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = LitertLmEngine.digestOf(
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

        val conversationToken = when (
            val created = engine.sdk.createConversation(
                bound.engineToken,
                SdkConversationRequest(),
            )
        ) {
            is SdkResult.Ok -> created.value
            is SdkResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(created.error))
                engine.recordCommit(
                    commit = commit,
                    kind = LitertLmEngine.CommitKind.INFERENCE,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
        }

        val sessionId = SessionHandleId.parse(
            "litert-session-${sessionSeq.incrementAndGet()}",
        )
        val epoch = plan.sourceSessionEpoch ?: 0L
        sessions[sessionId.value] = SessionRecord(
            epoch = epoch,
            conversationToken = conversationToken,
        )

        val preparedId = PreparedOperationId.parse(
            "litert-prep-${prepSeq.incrementAndGet()}",
        )
        val maxTokens = planMaxTokens.remove(plan.planId.value) ?: 16
        preparedParams[preparedId.value] = PreparedGenerateParams(maxTokens = maxTokens)

        val prepared = PreparedOperation(
            preparedOperationId = preparedId,
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
            kind = LitertLmEngine.CommitKind.INFERENCE,
            payloadDigest = payload,
            loadedModelId = loadedModelId,
            result = ok,
            state = "COMMITTED",
        )
        return ok
    }

    /**
     * Best-effort cooperative cancel for an in-flight [start] / generate.
     * SDK output stop ≠ native execution stopped (ENGINE-LITERT §6); UNKNOWN
     * measured cancel modes still require killable worker placement.
     */
    fun requestCancel(operationId: String): OmniResult<Unit> {
        cancelFlags[operationId]?.set(true)
        return when (val r = engine.sdk.requestCancel(operationId)) {
            is SdkResult.Ok -> OmniResult.ok(Unit)
            is SdkResult.Err -> OmniResult.err(ErrorMapper.toOmniError(r.error))
        }
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
        // Cooperative path: control plane calls requestCancel(operationId) or
        // SdkBackend.requestCancel(operationToken). cancelHandle is opaque ledger id.
        cancelFlags[op.operationId] = cancelFlag

        val params = preparedParams[prepared.preparedOperationId.value]
        val maxTokens = params?.maxTokens ?: 16

        val genReq = SdkGenerateRequest(
            operationToken = op.operationId,
            promptDigestHex = prepared.canonicalInputDigest.hex,
            maxTokens = maxTokens,
        )
        val genResult = engine.sdk.generate(
            conversation = session.conversationToken,
            request = genReq,
            cancelFlag = { cancelFlag.get() },
            onEvent = { sdkEvt ->
                normalizer.normalize(sdkEvt)?.let { events.add(it) }
            },
        )

        return try {
            when (genResult) {
                is SdkResult.Ok -> {
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
                    OmniResult.ok(completed)
                }
                is SdkResult.Err -> {
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
                        OmniResult.ok(done)
                    } else {
                        startedOps.remove(op.operationId)
                        OmniResult.err(omni)
                    }
                }
            }
        } finally {
            cancelFlags.remove(op.operationId)
            preparedParams.remove(prepared.preparedOperationId.value)
        }
    }

    override suspend fun planEmbedding(input: EmbeddingInput): OmniResult<EmbeddingPlan> {
        if (unloaded.get()) {
            return OmniResult.err(OmniError.STATE_CONFLICT(message = "model unloaded"))
        }
        ParameterValidator.validateAttributes(input.attributes)?.let {
            return OmniResult.err(it)
        }

        // Pure plan still returns envelope; commit fails closed until qualified.
        val envelope = ResourceEnvelopeEstimator.estimateEmbedding(
            ResourceEnvelopeEstimator.EstimateInput(scratchBytes = 512L * 1024L),
        )
        return OmniResult.ok(
            EmbeddingPlan(
                planId = engine.nextPlanId("litert-emb-plan"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadedModelId = loadedModelId,
                resourceEnvelope = envelope,
                phaseCapabilityDigest = LitertLmEngine.digestOf(
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

        // Fail closed until embedding cell is qualified (ENGINE-LITERT §10).
        when (
            val emb = engine.sdk.embed(
                bound.engineToken,
                SdkEmbedRequest(
                    operationToken = commit.commitId.value,
                    inputDigestHex = plan.canonicalInputDigest.hex,
                ),
            )
        ) {
            is SdkResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(emb.error))
                engine.recordCommit(
                    commit = commit,
                    kind = LitertLmEngine.CommitKind.EMBEDDING,
                    payloadDigest = payload,
                    loadedModelId = loadedModelId,
                    result = err,
                    state = "ABORTED",
                )
                return err
            }
            is SdkResult.Ok -> {
                val prepared = PreparedOperation(
                    preparedOperationId = PreparedOperationId.parse(
                        "litert-emb-prep-${prepSeq.incrementAndGet()}",
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
                    kind = LitertLmEngine.CommitKind.EMBEDDING,
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
            engine.sdk.closeConversation(rec.conversationToken)
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
            sessions.remove(sid)?.let { engine.sdk.closeConversation(it.conversationToken) }
        }
        engine.sdk.unloadEngine(bound.engineToken)
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
        val conversationToken: SdkConversationToken,
    )

    private data class PreparedGenerateParams(
        val maxTokens: Int,
    )
}
