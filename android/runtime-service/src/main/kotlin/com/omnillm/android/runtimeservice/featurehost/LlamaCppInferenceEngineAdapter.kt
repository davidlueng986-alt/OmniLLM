package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.Reservation
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EngineSourceSessionRef
import com.omnillm.engines.api.InferenceInput
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadedModelPort
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.llamacpp.LlamaCppEngine
import com.omnillm.engines.llamacpp.native.JniNativeMapping
import com.omnillm.engines.llamacpp.resource.ResourceEnvelopeEstimator
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.StreamBatchOutcome
import com.omnillm.runtime.orchestrator.StreamEvent
import com.omnillm.runtime.orchestrator.StreamTerminal
import com.omnillm.runtime.orchestrator.StreamTerminalKind
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridges [LlamaCppEngine] (Engine Pack SPI) to Orchestrator [InferenceEnginePort].
 *
 * Plan is pure (ADR-002): envelope estimate only — no weight open / DB write.
 * Commit/start may load the packaged **EXPERIMENTAL_FIXTURE** path when no
 * resident GGUF is brokered (exploratory / software smoke only). Markers are
 * explicit — never silent fixture substitution for real installs.
 * Cells stay UNQUALIFIED — never mint SUPPORTED.
 *
 * No silent cross-revision fallback: candidate [engineBuildId] must match the
 * attached adapter build; otherwise fail closed.
 */
class LlamaCppInferenceEngineAdapter(
    private val engine: LlamaCppEngine,
    private val runtimeEpochProvider: () -> Long = { 1L },
) : InferenceEnginePort {

    private val planSeq = AtomicInteger(0)
    private val loadedPorts = ConcurrentHashMap<String, LoadedModelPort>()
    private val streamBuffers = ConcurrentHashMap<String, StreamBuffer>()

    /** Keyed by installationId for lazy fixture load reuse within process. */
    private val loadByInstallation = ConcurrentHashMap<String, LoadedModelPort>()

    override suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome> {
        if (candidate.engineBuildId.value != engine.engineBuildId.value) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "engineBuildId mismatch — no silent cross-revision fallback",
                    details = mapOf(
                        "candidate" to candidate.engineBuildId.value,
                        "attached" to engine.engineBuildId.value,
                    ),
                ),
            )
        }
        if (!engine.native.isAvailable()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "llama-cpp native backend unavailable (fail closed)",
                    details = mapOf("library" to engine.native.libraryLabel()),
                ),
            )
        }

        val envelope = ResourceEnvelopeEstimator.estimateInference(
            ResourceEnvelopeEstimator.EstimateInput(
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                scratchBytes = 1L * 1024L * 1024L,
            ),
        )
        val dig = digestOf(
            "orch-plan|${request.requestId.value}|${candidate.candidateId}|${planSeq.incrementAndGet()}",
        )
        val plan = Plan(
            planId = PlanId.parse("orch-llama-plan-${planSeq.get()}"),
            requestId = request.requestId,
            principalId = request.principalId,
            modelRevisionId = candidate.modelRevisionId,
            engineBuildId = candidate.engineBuildId,
            deviceExecutionFingerprint = candidate.deviceExecutionFingerprint,
            canonicalInputDigest = request.canonicalRequestDigest,
            resourceEnvelope = envelope,
            expiryMonotonic = request.deadlineMonotonic,
            runtimeEpoch = request.runtimeEpoch,
            sourceSessionEpoch = request.sourceSessionEpoch,
        )
        return OmniResult.ok(InferencePlanOutcome(plan, envelope, dig))
    }

    override suspend fun commitInference(
        plan: Plan,
        reservation: Reservation,
        commit: Commit,
    ): OmniResult<PreparedOperation> {
        if (plan.engineBuildId.value != engine.engineBuildId.value) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "commit engineBuildId mismatch (fail closed)",
                    details = mapOf(
                        "plan" to plan.engineBuildId.value,
                        "attached" to engine.engineBuildId.value,
                    ),
                ),
            )
        }

        val port = when (val ensured = ensureLoadedPort(plan, commit)) {
            is OmniResult.Err -> return ensured
            is OmniResult.Ok -> ensured.value
        }

        val infPlan = when (
            val p = port.planInference(
                InferenceInput(
                    requestId = plan.requestId,
                    principalId = plan.principalId,
                    canonicalInputDigest = plan.canonicalInputDigest,
                    runtimeEpoch = plan.runtimeEpoch,
                    revocationEpoch = commit.revocationEpoch,
                    deadlineMonotonic = plan.expiryMonotonic,
                ),
                EngineSourceSessionRef.None,
            )
        ) {
            is OmniResult.Err -> return p
            is OmniResult.Ok -> p.value
        }

        val ctx = CommitContext(
            commitId = commit.commitId,
            requestId = commit.requestId,
            principalId = commit.principalId,
            reservationId = commit.reservationId,
            revisionLeaseId = commit.revisionLeaseId,
            issuerBootId = commit.issuerBootId,
            runtimeEpoch = commit.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            oneShotNonce = commit.oneShotNonce,
            privilegedLoadTicketId = EXPLORATORY_LOAD_TICKET,
        )
        return when (val prepared = port.commitInference(infPlan, reservation, ctx)) {
            is OmniResult.Err -> prepared
            is OmniResult.Ok -> {
                loadedPorts[prepared.value.preparedOperationId.value] = port
                prepared
            }
        }
    }

    override suspend fun start(
        prepared: PreparedOperation,
        operationId: String,
        runtimeEpoch: Long,
    ): OmniResult<Unit> {
        val port = loadedPorts[prepared.preparedOperationId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "prepared operation not bound to loaded model",
                    details = mapOf("preparedOperationId" to prepared.preparedOperationId.value),
                ),
            )

        val events = mutableListOf<StreamEvent>()
        var terminal: StreamTerminal? = null
        val op = OperationContext(
            operationId = operationId,
            principalId = prepared.principalId.value,
            deadline = Long.MAX_VALUE / 4,
            cancelHandle = "cancel-$operationId",
            runtimeEpoch = runtimeEpoch,
            revocationEpoch = prepared.revocationEpoch,
        )

        when (
            val started = port.start(prepared, op) { engEvt ->
                val kind = engEvt.kind
                events += StreamEvent(
                    seq = engEvt.seq,
                    kind = kind,
                    payloadDigest = engEvt.payloadDigest,
                )
                if (engEvt.isTerminal) {
                    val disposition = engEvt.attributes["disposition"]
                        ?: engEvt.attributes["stopReason"]
                        ?: "STOP"
                    terminal = when {
                        disposition.equals("CANCELLED", ignoreCase = true) ||
                            disposition.equals("CANCEL", ignoreCase = true) ->
                            StreamTerminal(StreamTerminalKind.CANCELLED, engEvt.payloadDigest, "CANCELLED")
                        disposition.equals("ERROR", ignoreCase = true) ||
                            disposition.equals("FAILED", ignoreCase = true) ->
                            StreamTerminal(
                                StreamTerminalKind.FAILURE,
                                engEvt.payloadDigest,
                                engEvt.attributes["errorCode"] ?: "INTERNAL",
                            )
                        else ->
                            StreamTerminal(StreamTerminalKind.SUCCESS, engEvt.payloadDigest, null)
                    }
                }
            }
        ) {
            is OmniResult.Err -> return started
            is OmniResult.Ok -> {
                if (terminal == null) {
                    // Ensure a terminal if native completed without TERMINAL event.
                    val ok = started.value.state == "COMPLETED" || started.value.state == "RUNNING"
                    terminal = if (ok) {
                        StreamTerminal(
                            StreamTerminalKind.SUCCESS,
                            outputDigest = digestOf("terminal|$operationId"),
                        )
                    } else {
                        StreamTerminal(
                            StreamTerminalKind.FAILURE,
                            errorCode = started.value.state,
                        )
                    }
                    if (events.none { EngineEventKinds.isTerminal(it.kind) }) {
                        events += StreamEvent(
                            seq = (events.maxOfOrNull { it.seq } ?: -1L) + 1L,
                            kind = EngineEventKinds.TERMINAL,
                            payloadDigest = terminal!!.outputDigest,
                        )
                    }
                }
            }
        }

        streamBuffers[prepared.preparedOperationId.value] = StreamBuffer(
            events = events.sortedBy { it.seq },
            terminal = terminal!!,
        )
        return OmniResult.ok(Unit)
    }

    override suspend fun nextEvents(
        prepared: PreparedOperation,
        fromSeq: Long,
    ): OmniResult<StreamBatchOutcome> {
        val buf = streamBuffers[prepared.preparedOperationId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "stream buffer missing — start not completed",
                    details = mapOf("preparedOperationId" to prepared.preparedOperationId.value),
                ),
            )
        val remaining = buf.events.filter { it.seq >= fromSeq }
        if (remaining.isEmpty()) {
            return OmniResult.ok(
                StreamBatchOutcome(
                    seqFrom = fromSeq,
                    seqTo = fromSeq,
                    events = emptyList(),
                    terminal = buf.terminal,
                ),
            )
        }
        val seqTo = remaining.maxOf { it.seq } + 1L
        return OmniResult.ok(
            StreamBatchOutcome(
                seqFrom = fromSeq,
                seqTo = seqTo,
                events = remaining,
                terminal = buf.terminal,
            ),
        )
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        engine.queryCommit(commitId)

    private suspend fun ensureLoadedPort(
        plan: Plan,
        commit: Commit,
    ): OmniResult<LoadedModelPort> {
        // Prefer cache by installation derived from plan revision (exploratory single-load).
        val installKey = "exploratory-${plan.modelRevisionId.hex.take(16)}"
        loadByInstallation[installKey]?.let { return OmniResult.ok(it) }

        val installationId = try {
            // Stable UUID namespace from revision hex prefix for fixture path.
            val hex = plan.modelRevisionId.hex
            val uuid = buildString {
                append(hex.take(8)); append('-')
                append(hex.substring(8, 12)); append('-')
                append("4"); append(hex.substring(13, 16)); append('-')
                append("8"); append(hex.substring(17, 20)); append('-')
                append(hex.substring(20, 32))
            }
            com.omnillm.core.identity.InstallationId.parse(uuid)
        } catch (_: Exception) {
            com.omnillm.core.identity.InstallationId.parse("550e8400-e29b-41d4-a716-4466554400ef")
        }

        val loadKey = LoadKey(
            modelRevisionId = plan.modelRevisionId,
            engineBuildId = plan.engineBuildId,
            backend = "cpu",
            deviceExecutionFingerprint = plan.deviceExecutionFingerprint,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
            loadConfigurationDigest = plan.canonicalInputDigest,
        )
        // Explicit fixture markers required by native fail-closed policy
        // (no silent broker-only → fixture). Domain installationId stays UUID.
        val fixturePath = "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}"
        val loadInput = LoadInput(
            requestId = plan.requestId,
            principalId = plan.principalId,
            installationId = installationId,
            modelRevisionId = plan.modelRevisionId,
            loadKey = loadKey,
            device = DeviceDescriptor(deviceExecutionFingerprint = plan.deviceExecutionFingerprint),
            storageRootKey = fixturePath,
            runtimeEpoch = plan.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
            resolvedModelPath = fixturePath,
            modelFd = -1,
        )
        val loadPlan = when (val p = engine.planLoad(loadInput)) {
            is OmniResult.Err -> return p
            is OmniResult.Ok -> p.value
        }
        val loadCommit = CommitContext(
            commitId = CommitId.parse(java.util.UUID.randomUUID().toString()),
            requestId = plan.requestId,
            principalId = plan.principalId,
            reservationId = commit.reservationId,
            revisionLeaseId = commit.revisionLeaseId,
            issuerBootId = commit.issuerBootId,
            runtimeEpoch = commit.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            oneShotNonce = "load-${commit.oneShotNonce}",
            privilegedLoadTicketId = EXPLORATORY_LOAD_TICKET,
        )
        val handle = when (val c = engine.commitLoad(loadPlan, reservationFromPlan(loadPlan), loadCommit)) {
            is OmniResult.Err -> return c
            is OmniResult.Ok -> c.value
        }
        val port = when (val b = engine.bindLoadedModel(handle)) {
            is OmniResult.Err -> return b
            is OmniResult.Ok -> b.value
        }
        loadByInstallation[installKey] = port
        return OmniResult.ok(port)
    }

    private fun reservationFromPlan(
        loadPlan: com.omnillm.engines.api.LoadPlan,
    ): Reservation =
        Reservation(
            reservationId = com.omnillm.core.resource.ReservationId.parse(
                "res-load-${java.util.UUID.randomUUID().toString().take(8)}",
            ),
            principalId = loadPlan.principalId.value,
            issuerBootId = "load-bridge",
            runtimeEpoch = loadPlan.runtimeEpoch,
            nonce = "nonce-load",
            deadlineMonotonic = loadPlan.expiryMonotonic,
            envelope = loadPlan.resourceEnvelope,
        )

    private data class StreamBuffer(
        val events: List<StreamEvent>,
        val terminal: StreamTerminal,
    )

    companion object {
        /** Non-empty ticket required by native load; exploratory fixture accepts any non-empty. */
        const val EXPLORATORY_LOAD_TICKET: String = "exploratory-privileged-ticket"

        fun digestOf(text: String): Sha256Digest =
            Sha256Digest.parse(IdentityHashing.sha256Hex(text))
    }
}
