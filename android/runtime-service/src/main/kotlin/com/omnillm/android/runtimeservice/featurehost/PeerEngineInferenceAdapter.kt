package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
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
import com.omnillm.engines.api.OmniEngine
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.StreamBatchOutcome
import com.omnillm.runtime.orchestrator.StreamEvent
import com.omnillm.runtime.orchestrator.StreamTerminal
import com.omnillm.runtime.orchestrator.StreamTerminalKind
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges a peer [OmniEngine] (LiteRT-LM / ONNX-Runtime-GenAI) to the
 * Orchestrator [InferenceEnginePort] (C-07 control-plane attach).
 *
 * Honesty rules (same as the llama adapter):
 * - Plan is pure (ADR-002) and gated on **attachability** ([planningAvailable]:
 *   official SDK/API present). Commit/start rely on the engine's own fail-closed
 *   backend checks — missing natives ⇒ NOT_AVAILABLE, never claimed success.
 * - No silent cross-revision fallback: candidate/plan [EngineBuildId] must match.
 * - Cells stay UNQUALIFIED — this adapter never mints SUPPORTED.
 * - No shared-load refcounting (unlike llama): one commit = one load; the port
 *   is unloaded when [start] terminates (single-user per commit for now — real
 *   load sharing is part of the device-wave wiring, Stage 5).
 */
class PeerEngineInferenceAdapter(
    val engineId: String,
    private val engine: OmniEngine,
    val engineBuildId: EngineBuildId = engine.engineBuildId,
    /**
     * Plan-time gate: attachability of the official API/SDK (e.g. AAR on the
     * classpath). This is NOT native availability and never qualification —
     * the engine's backend fails closed at commit/start when natives are
     * missing (host JVM: NOT_AVAILABLE; device: real load).
     */
    private val planningAvailable: () -> Boolean = { true },
    /** Pure resource envelope estimate for this engine's model family. */
    private val envelopeProvider: () -> ResourceEnvelope,
) : InferenceEnginePort {

    private val planSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val loadedPorts = ConcurrentHashMap<String, LoadedModelPort>()
    private val streamBuffers = ConcurrentHashMap<String, StreamBuffer>()

    override suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome> {
        if (candidate.engineBuildId.value != engineBuildId.value) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "engineBuildId mismatch — no silent cross-engine fallback",
                    details = mapOf(
                        "candidate" to candidate.engineBuildId.value,
                        "attached" to engineBuildId.value,
                        "engineId" to engineId,
                    ),
                ),
            )
        }
        if (!planningAvailable()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "$engineId official SDK/API not present on this runtime " +
                        "(engine attached but not executable — fail closed)",
                    details = mapOf("engineId" to engineId),
                ),
            )
        }
        val envelope = envelopeProvider()
        val dig = digestOf(
            "peer-plan|${request.requestId.value}|${candidate.candidateId}|${planSeq.incrementAndGet()}",
        )
        val plan = Plan(
            planId = PlanId.parse("peer-$engineId-plan-${planSeq.get()}"),
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
        if (plan.engineBuildId.value != engineBuildId.value) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "commit engineBuildId mismatch (fail closed)",
                    details = mapOf(
                        "plan" to plan.engineBuildId.value,
                        "attached" to engineBuildId.value,
                        "engineId" to engineId,
                    ),
                ),
            )
        }

        val port = when (val loaded = loadPort(plan, commit)) {
            is OmniResult.Err -> return loaded
            is OmniResult.Ok -> loaded.value
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
            is OmniResult.Err -> {
                unloadPort(port)
                return p
            }
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
            is OmniResult.Err -> {
                unloadPort(port)
                prepared
            }
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

        try {
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
                    events += StreamEvent(
                        seq = engEvt.seq,
                        kind = engEvt.kind,
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
        } finally {
            // Single-user per commit: unload when this operation terminates
            // (success or failure). Idempotent removal keeps maps bounded.
            loadedPorts.remove(prepared.preparedOperationId.value)?.let { unloadPort(it) }
        }
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
        val outcome = if (remaining.isEmpty()) {
            OmniResult.ok(
                StreamBatchOutcome(
                    seqFrom = fromSeq,
                    seqTo = fromSeq,
                    events = emptyList(),
                    terminal = buf.terminal,
                ),
            )
        } else {
            val seqTo = remaining.maxOf { it.seq } + 1L
            OmniResult.ok(
                StreamBatchOutcome(
                    seqFrom = fromSeq,
                    seqTo = seqTo,
                    events = remaining,
                    terminal = buf.terminal,
                ),
            )
        }
        streamBuffers.remove(prepared.preparedOperationId.value)
        return outcome
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        engine.queryCommit(commitId)

    private suspend fun loadPort(plan: Plan, commit: Commit): OmniResult<LoadedModelPort> {
        val installationId = try {
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
        val loadInput = LoadInput(
            requestId = plan.requestId,
            principalId = plan.principalId,
            installationId = installationId,
            modelRevisionId = plan.modelRevisionId,
            loadKey = loadKey,
            device = DeviceDescriptor(deviceExecutionFingerprint = plan.deviceExecutionFingerprint),
            storageRootKey = "broker:${installationId.value}",
            runtimeEpoch = plan.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
            resolvedModelPath = null,
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
        val handle = when (
            val c = engine.commitLoad(
                loadPlan,
                reservationFromPlan(loadPlan),
                loadCommit,
            )
        ) {
            is OmniResult.Err -> return c
            is OmniResult.Ok -> c.value
        }
        return when (val b = engine.bindLoadedModel(handle)) {
            is OmniResult.Err -> b
            is OmniResult.Ok -> OmniResult.ok(b.value)
        }
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

    private suspend fun unloadPort(port: LoadedModelPort) {
        port.unload(
            OperationContext(
                operationId = "unload-peer-port",
                principalId = "runtime-adapter",
                deadline = Long.MAX_VALUE / 4,
                cancelHandle = "cancel-unload-peer-port",
                runtimeEpoch = 1L,
                revocationEpoch = 0L,
            ),
        )
    }

    private data class StreamBuffer(
        val events: List<StreamEvent>,
        val terminal: StreamTerminal,
    )

    companion object {
        /** Non-empty ticket required by privileged load; exploratory accepts any non-empty. */
        const val EXPLORATORY_LOAD_TICKET: String = "exploratory-privileged-ticket"

        fun digestOf(text: String): Sha256Digest =
            Sha256Digest.parse(IdentityHashing.sha256Hex(text))
    }
}
