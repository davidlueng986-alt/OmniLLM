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
 * Plan is pure (ADR-002): envelope estimate only ??no weight open / DB write.
 * Commit/start loads the **real installed GGUF** when [modelSourceResolver]
 * resolves a READY installation (privileged load re-verify, INV-010). When no
 * real model is installed, it falls back to the packaged **EXPERIMENTAL_FIXTURE**
 * path (exploratory / software smoke only) ??markers are explicit, never a
 * silent fixture substitution for real installs.
 * Cells stay UNQUALIFIED ??never mint SUPPORTED.
 *
 * No silent cross-revision fallback: candidate [engineBuildId] must match the
 * attached adapter build; otherwise fail closed.
 */
class LlamaCppInferenceEngineAdapter(
    private val engine: LlamaCppEngine,
    private val runtimeEpochProvider: () -> Long = { 1L },
    /**
     * Resolves the real installed model bytes for a plan. Default resolves
     * nothing ??explicit EXPERIMENTAL_FIXTURE path (host tests / exploratory).
     * Production wiring supplies [RuntimeGgufModelSourceResolver].
     */
    private val modelSourceResolver: ModelSourceResolver = NO_MODEL_SOURCE,
    /**
     * When true (DEV mode), an unresolved real model falls back to the explicit
     * fixture path so feature journeys still work end-to-end. Compliance mode
     * should keep this false (fail closed, INV-018).
     */
    private val fallbackToFixtureOnUnresolved: Boolean = true,
) : InferenceEnginePort {

    private val planSeq = AtomicInteger(0)
    private val loadedPorts = ConcurrentHashMap<String, LoadedModelPort>()
    private val streamBuffers = ConcurrentHashMap<String, StreamBuffer>()

    /**
     * preparedOperationId ??shared-load lease. Registered at commit and removed
     * in [start] (try/finally), so this map is bounded by in-flight operations
     * instead of growing one entry per chat (COR-16).
     */
    private val operationLeases = ConcurrentHashMap<String, PortLease>()

    /**
     * installationId ??refcounted shared port (COR-16). Every prepared
     * operation takes one reference at commit and releases it when its [start]
     * terminates; the native model is unloaded only when the last user finishes.
     * Concurrent operations on the same revision therefore share the port
     * safely and can never race on a port being torn down.
     */
    private val loadByInstallation = ConcurrentHashMap<String, PortLease>()

    override suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome> {
        if (candidate.engineBuildId.value != engine.engineBuildId.value) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "engineBuildId mismatch ??no silent cross-revision fallback",
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

        val lease = when (val ensured = ensureLoadedLease(plan, commit)) {
            is OmniResult.Err -> return ensured
            is OmniResult.Ok -> ensured.value
        }
        val port = lease.port

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
                releaseLease(lease)
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
                releaseLease(lease)
                prepared
            }
            is OmniResult.Ok -> {
                val prepId = prepared.value.preparedOperationId.value
                loadedPorts[prepId] = port
                operationLeases[prepId] = lease
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
        } finally {
            // COR-16: terminate the prepared-op bindings here (success or
            // failure) so loadedPorts / operationLeases stay bounded by
            // in-flight operations; removal is idempotent. The lease release
            // may unload the shared native model when this was the last user.
            loadedPorts.remove(prepared.preparedOperationId.value)
            operationLeases.remove(prepared.preparedOperationId.value)?.let { releaseLease(it) }
        }
    }

    override suspend fun nextEvents(
        prepared: PreparedOperation,
        fromSeq: Long,
    ): OmniResult<StreamBatchOutcome> {
        val buf = streamBuffers[prepared.preparedOperationId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "stream buffer missing ??start not completed",
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
        // The orchestrator stops polling once a terminal is delivered, so the
        // buffer is dead after this call ??drop it to keep streamBuffers
        // bounded by completed chats (COR-16).
        streamBuffers.remove(prepared.preparedOperationId.value)
        return outcome
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
        engine.queryCommit(commitId)

    private suspend fun ensureLoadedLease(
        plan: Plan,
        commit: Commit,
    ): OmniResult<PortLease> {
        // Prefer shared cache by installation derived from plan revision.
        val installKey = "exploratory-${plan.modelRevisionId.hex.take(16)}"
        acquireExistingLease(installKey)?.let { return OmniResult.ok(it) }

        val port = try {
            buildPort(plan, commit)
        } catch (e: PortBuildFailure) {
            return OmniResult.err(e.error)
        }
        val candidate = PortLease(installKey = installKey, port = port)
        val winner = requireNotNull(
            loadByInstallation.compute(installKey) { _, existing ->
                if (existing != null) {
                    existing.also { it.users.incrementAndGet() }
                } else {
                    candidate.also { it.users.incrementAndGet() }
                }
            },
        ) { "install compute must always install or reuse a lease" }
        if (winner !== candidate) {
            // Lost a concurrent install race: the candidate port's native model
            // is orphaned (nobody holds a lease on it) ??unload best-effort.
            unloadPort(candidate.port)
            return OmniResult.ok(winner)
        }
        return OmniResult.ok(candidate)
    }

    private suspend fun buildPort(plan: Plan, commit: Commit): LoadedModelPort {
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

        // Real installed GGUF (privileged load path, INV-010) when the resolver
        // finds a READY installation; otherwise explicit fixture markers.
        val resolved = modelSourceResolver.resolve(plan)
        val realSource = when (resolved) {
            is OmniResult.Ok -> resolved.value
            is OmniResult.Err -> {
                if (!fallbackToFixtureOnUnresolved) {
                    throw PortBuildFailure(resolved.error)
                }
                null
            }
        }
        val (storageRootKey, resolvedPath, modelFd) = realSource?.let {
            Triple(it.storageRootKey, it.resolvedModelPath, it.modelFd)
        } ?: Triple(
            "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}",
            "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}",
            -1,
        )
        // Explicit fixture markers required by native fail-closed policy
        // (no silent broker-only ??fixture). Domain installationId stays UUID.
        val loadInput = LoadInput(
            requestId = plan.requestId,
            principalId = plan.principalId,
            installationId = installationId,
            modelRevisionId = plan.modelRevisionId,
            loadKey = loadKey,
            device = DeviceDescriptor(deviceExecutionFingerprint = plan.deviceExecutionFingerprint),
            storageRootKey = storageRootKey,
            runtimeEpoch = plan.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            templateEpoch = 1L,
            tokenizerEpoch = 1L,
            resolvedModelPath = resolvedPath,
            modelFd = modelFd,
        )
        val loadPlan = when (val p = engine.planLoad(loadInput)) {
            is OmniResult.Err -> throw PortBuildFailure(p.error)
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
            is OmniResult.Err -> throw PortBuildFailure(c.error)
            is OmniResult.Ok -> c.value
        }
        return when (val b = engine.bindLoadedModel(handle)) {
            is OmniResult.Err -> throw PortBuildFailure(b.error)
            is OmniResult.Ok -> b.value
        }
    }

    /**
     * Refcounted shared-load lease (COR-16). [users] counts prepared operations
     * currently holding this port; the shared slot is detached and the native
     * model unloaded when the last user releases.
     */
    private class PortLease(
        val installKey: String,
        val port: LoadedModelPort,
    ) {
        val users = AtomicInteger(0)
    }

    /** Fail-closed carrier for [buildPort] errors (unwrapped in [ensureLoadedLease]). */
    private class PortBuildFailure(val error: OmniError) : RuntimeException()

    /**
     * Atomically increments [PortLease.users] on a live shared slot. Returns
     * null when no slot exists (caller builds a fresh port).
     */
    private fun acquireExistingLease(installKey: String): PortLease? =
        loadByInstallation.compute(installKey) { _, existing ->
            existing?.also { it.users.incrementAndGet() }
        }

    /**
     * Releases one operation's reference on [lease]. Atomic with the slot
     * removal: the last user detaches the slot and unloads the native model,
     * and a concurrent acquire either wins the increment (keeping the slot) or
     * sees the removed slot (building a fresh port) — it can never obtain a
     * port that is being torn down. Each operation releases exactly once (the
     * [operationLeases] entry is removed in [start]'s finally), so [users] can
     * never underflow.
     */
    private suspend fun releaseLease(lease: PortLease) {
        val removed = loadByInstallation.compute(lease.installKey) { _, existing ->
            if (existing !== lease) {
                existing
            } else if (lease.users.decrementAndGet() == 0) {
                null
            } else {
                existing
            }
        }
        if (removed == null) {
            unloadPort(lease.port)
        }
    }

    private suspend fun unloadPort(port: LoadedModelPort) {
        port.unload(
            OperationContext(
                operationId = "unload-idle-shared-port",
                principalId = "runtime-adapter",
                deadline = Long.MAX_VALUE / 4,
                cancelHandle = "cancel-unload-idle-shared-port",
                runtimeEpoch = 1L,
                revocationEpoch = 0L,
            ),
        )
    }

    /** Test seams (COR-16): bookkeeping must stay bounded across chats. */
    internal fun activeLoadedPortsCount(): Int = loadedPorts.size
    internal fun activeOperationLeaseCount(): Int = operationLeases.size
    internal fun sharedLeaseCount(): Int = loadByInstallation.size
    internal fun sharedLeaseUsers(): Int = loadByInstallation.values.sumOf { it.users.get() }

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

    /**
     * Resolves the real installed model bytes for a plan's revision.
     * Implementations run inside the `:runtime` process (trusted, INV-001/ADR-010);
     * resolved paths/FDs never cross process boundaries to clients.
     */
    fun interface ModelSourceResolver {
        /**
         * @return Ok(source) with real path/FD, or Err when no READY installation
         * exists for [plan.modelRevisionId] (caller decides fixture fallback).
         */
        suspend fun resolve(plan: Plan): OmniResult<ResolvedModelSource>
    }

    /** Real model bytes location for privileged load (path and/or fd). */
    data class ResolvedModelSource(
        val storageRootKey: String,
        val resolvedModelPath: String? = null,
        val modelFd: Int = -1,
    ) {
        init {
            require(storageRootKey.isNotEmpty()) { "storageRootKey must be non-empty" }
            require(resolvedModelPath != null || modelFd >= 0) {
                "resolved source must carry a path or an fd"
            }
        }
    }

    companion object {
        /** Non-empty ticket required by native load; exploratory fixture accepts any non-empty. */
        const val EXPLORATORY_LOAD_TICKET: String = "exploratory-privileged-ticket"

        /** Default resolver: never resolves a real model (fixture-only path). */
        val NO_MODEL_SOURCE: ModelSourceResolver = ModelSourceResolver { _ ->
            OmniResult.err(
                OmniError.NOT_FOUND(message = "no model source resolver configured"),
            )
        }

        fun digestOf(text: String): Sha256Digest =
            Sha256Digest.parse(IdentityHashing.sha256Hex(text))
    }
}

