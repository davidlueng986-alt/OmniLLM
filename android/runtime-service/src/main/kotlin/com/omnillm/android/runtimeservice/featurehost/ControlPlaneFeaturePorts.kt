package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.llamacpp.LlamaCppModule
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.playground.ports.CancelPortResult
import com.omnillm.features.playground.ports.InferenceHandle
import com.omnillm.features.playground.ports.PlaygroundCapabilityPort
import com.omnillm.features.playground.ports.PlaygroundInferencePort
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.routing.ports.RoutingOrchestratorPort
import com.omnillm.features.server.api.CapabilityCellView
import com.omnillm.features.server.api.InferenceClaimSpec
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.ports.CapabilityQueryPort
import com.omnillm.features.server.ports.ServerInferencePort
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.ports.StructuredInferenceHandle
import com.omnillm.features.tools.ports.ToolCallingHandle
import com.omnillm.features.tools.ports.ToolsInferencePort
import com.omnillm.features.tools.ports.ToolsQueryHandle
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.orchestrator.CandidatePlanner
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.QueryView
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Feature ports that route through plane [Orchestrator] + engine binding
 * (not permanent FailClosedInferenceEngine).
 *
 * Fail-closed when: engine unbound, exploratory flag off, unknown capability,
 * or engineBuildId mismatch (no silent cross-revision fallback).
 */
object ControlPlaneFeaturePorts {

    fun playgroundInference(
        orchestrator: Orchestrator,
        binding: EngineExecuteBinding,
        modelManager: ModelManager,
        clockMs: () -> Long,
        runtimeEpoch: () -> Long,
        deviceFingerprint: () -> DeviceExecutionFingerprint =
            { DeviceExecutionFingerprint.parse("device-fp-control-plane") },
    ): PlaygroundInferencePort =
        OrchestratorPlaygroundInferencePort(
            orchestrator = orchestrator,
            binding = binding,
            modelManager = modelManager,
            clockMs = clockMs,
            runtimeEpoch = runtimeEpoch,
            deviceFingerprint = deviceFingerprint,
        )

    fun playgroundCapabilities(
        binding: EngineExecuteBinding,
        installations: List<com.omnillm.runtime.modelmanager.domain.InstallationSnapshot>,
    ): PlaygroundCapabilityPort =
        object : PlaygroundCapabilityPort {
            override fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState {
                val cand = binding.probeCandidate(modelRevisionId, installations)
                    ?: return CapabilityState.UNKNOWN
                return binding.resolveCapability(capability, cand)
            }

            override fun conditions(capability: CapabilityId, modelRevisionId: String): List<String> =
                binding.conditions(capability, modelRevisionId)
        }

    fun serverInference(
        orchestrator: Orchestrator,
        binding: EngineExecuteBinding,
        modelManager: ModelManager,
        clockMs: () -> Long,
        runtimeEpoch: () -> Long,
        deviceFingerprint: () -> DeviceExecutionFingerprint =
            { DeviceExecutionFingerprint.parse("device-fp-control-plane") },
    ): ServerInferencePort =
        OrchestratorServerInferencePort(
            orchestrator = orchestrator,
            binding = binding,
            modelManager = modelManager,
            clockMs = clockMs,
            runtimeEpoch = runtimeEpoch,
            deviceFingerprint = deviceFingerprint,
        )

    fun serverCapabilities(
        binding: EngineExecuteBinding,
        modelManager: ModelManager,
        clockMs: () -> Long,
    ): CapabilityQueryPort =
        object : CapabilityQueryPort {
            override suspend fun listModels(principal: PrincipalId): OmniResult<List<ModelCapabilityView>> {
                val now = clockMs()
                val installations = modelManager.listInstallations()
                if (installations.isEmpty()) {
                    // Honest empty + exploratory note when engine bound.
                    val notes = buildList {
                        add("capabilities projected from EngineRegistry (UNKNOWN without evidence)")
                        if (binding.isEngineBound() && binding.isExploratoryExecuteEnabled()) {
                            add("runtime.exploratoryExecuteEnabled=true → TEXT_GENERATION CONDITIONAL")
                        } else if (binding.isEngineBound()) {
                            add("engine attached; enable runtime.exploratoryExecuteEnabled for experimental generate")
                        } else {
                            add("engine not attached; capabilities UNKNOWN")
                        }
                    }
                    return OmniResult.ok(emptyList())
                }
                return OmniResult.ok(
                    installations.map { inst ->
                        val rev = inst.modelRevisionId.hex
                        val textState = playgroundCapabilities(binding, installations)
                            .state(CapabilityId.TEXT_GENERATION, rev)
                        val conditions = playgroundCapabilities(binding, installations)
                            .conditions(CapabilityId.TEXT_GENERATION, rev)
                        ModelCapabilityView(
                            modelId = inst.installationId.value,
                            modelRevisionId = rev,
                            displayName = null,
                            engineBuildId = binding.attachment?.llamaCppEngine?.engineBuildId?.value,
                            backend = if (binding.isEngineBound()) "cpu" else null,
                            trustClass = EngineExecuteBinding.EXPLORATORY_PLACEMENT,
                            capabilities = listOf(
                                CapabilityCellView(
                                    capabilityId = CapabilityId.TEXT_GENERATION.id,
                                    state = textState,
                                    conditions = conditions,
                                    evidenceLabel = if (textState == CapabilityState.CONDITIONAL) {
                                        EvidenceLabel.REPORTED
                                    } else {
                                        EvidenceLabel.UNKNOWN
                                    },
                                    sampledAtEpochMs = now,
                                    source = "control-plane-engine-execute-binding",
                                ),
                            ),
                            degradationNotes = listOf(
                                when {
                                    textState == CapabilityState.CONDITIONAL ->
                                        "CONDITIONAL exploratory generate; cells UNQUALIFIED"
                                    binding.isEngineBound() ->
                                        "engine attached; exploratory flag off → UNKNOWN"
                                    else -> "engine not attached; fail closed"
                                },
                            ),
                        )
                    },
                )
            }
        }

    fun routingOrchestrator(
        orchestrator: Orchestrator,
        planner: CandidatePlanner,
    ): RoutingOrchestratorPort =
        object : RoutingOrchestratorPort {
            override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> =
                planner.plan(request)

            override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> =
                orchestrator.submit(request)

            override suspend fun cancel(requestId: RequestId): OmniResult<Unit> =
                orchestrator.cancel(requestId)

            override suspend fun query(requestId: RequestId): QueryView? =
                orchestrator.query(requestId)
        }

    fun toolsInference(
        orchestrator: Orchestrator,
        binding: EngineExecuteBinding,
        modelManager: ModelManager,
        clockMs: () -> Long,
        runtimeEpoch: () -> Long,
        deviceFingerprint: () -> DeviceExecutionFingerprint =
            { DeviceExecutionFingerprint.parse("device-fp-control-plane") },
    ): ToolsInferencePort =
        OrchestratorToolsInferencePort(
            orchestrator = orchestrator,
            binding = binding,
            modelManager = modelManager,
            clockMs = clockMs,
            runtimeEpoch = runtimeEpoch,
            deviceFingerprint = deviceFingerprint,
        )

    // ---- helpers ----

    /**
     * Probe candidate for capability-state computation (ARC-06).
     *
     * Routes the probe through **real** installation resolution: when a real
     * installation exists for [modelRevisionId], the candidate carries the real
     * installation identity + a real loadKeyDigest + the caller's real device
     * fingerprint. When no real installation exists the probe FAILS CLOSED and
     * returns null (capability UNKNOWN).
     *
     * The returned candidate is **ephemeral** — it is only used for capability
     * state/condition projections and must never be submitted to the
     * claim/commit ledgers as a real identity (no fabricated InstallationId /
     * loadKeyDigest / device fingerprints in production paths).
     */
    internal fun EngineExecuteBinding.probeCandidate(
        modelRevisionId: String,
        installations: List<com.omnillm.runtime.modelmanager.domain.InstallationSnapshot>,
        deviceFingerprint: () -> DeviceExecutionFingerprint =
            { DeviceExecutionFingerprint.parse("device-fp-probe") },
    ): RoutingCandidate? {
        val revision = try {
            ModelRevisionId.parse(modelRevisionId.lowercase())
        } catch (_: Exception) {
            return null
        }
        val realInstallation = installations.firstOrNull {
            it.modelRevisionId.hex == revision.hex
        } ?: return null
        val build = attachment?.llamaCppEngine?.engineBuildId
            ?: attachment?.llamaCppRegistration?.engineBuildId
            ?: return null
        return RoutingCandidate(
            candidateId = "probe-primary",
            modelRevisionId = revision,
            installationId = com.omnillm.core.identity.InstallationId.parse(
                realInstallation.installationId.value,
            ),
            engineBuildId = build,
            backend = "cpu",
            placementClass = EngineExecuteBinding.EXPLORATORY_PLACEMENT,
            loadKeyDigest = Sha256Digest.parse(
                com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                    "loadkey|${revision.hex}|${build.value}|cpu",
                ),
            ),
            isPrimary = true,
            deviceExecutionFingerprint = deviceFingerprint(),
        )
    }

    internal fun buildCandidate(
        binding: EngineExecuteBinding,
        revision: ModelRevisionId,
        installationId: InstallationId,
        device: DeviceExecutionFingerprint,
    ): OmniResult<RoutingCandidate> {
        val eng = binding.attachment?.llamaCppEngine
        val buildId = eng?.engineBuildId
            ?: binding.attachment?.llamaCppRegistration?.engineBuildId
            ?: return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "engine not attached — cannot build routing candidate",
                    details = mapOf("engineId" to LlamaCppModule.ENGINE_ID),
                ),
            )
        if (!binding.isEngineBound()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "inference engine not bound (native missing or fail-closed)",
                ),
            )
        }
        return OmniResult.ok(
            RoutingCandidate(
                candidateId = "primary-${LlamaCppModule.ENGINE_ID}",
                modelRevisionId = revision,
                installationId = installationId,
                engineBuildId = buildId,
                backend = "cpu",
                placementClass = EngineExecuteBinding.EXPLORATORY_PLACEMENT,
                loadKeyDigest = Sha256Digest.parse(
                    com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                        "loadkey|${revision.hex}|${buildId.value}|cpu",
                    ),
                ),
                isPrimary = true,
                deviceExecutionFingerprint = device,
            ),
        )
    }

    internal fun exploratoryRouting(): RoutingPreference =
        RoutingPreference(
            fallbackPolicy = FallbackPolicy.NONE,
            minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
        )

    /**
     * Resolve the REAL installation for [revision] (ARC-06).
     *
     * Returns null when no real installation exists — callers must fail closed
     * (never fabricate a synthetic installation identity for durable paths).
     * Synthetic identities must not reach the claim/commit ledgers.
     */
    internal suspend fun resolveInstallationOrNull(
        modelManager: ModelManager,
        revision: ModelRevisionId,
    ): com.omnillm.core.identity.InstallationId? =
        modelManager.listInstallations().firstOrNull {
            it.modelRevisionId.hex == revision.hex
        }?.installationId?.let { com.omnillm.core.identity.InstallationId.parse(it.value) }
}

// ---------------------------------------------------------------------------
// Playground
// ---------------------------------------------------------------------------

private class OrchestratorPlaygroundInferencePort(
    private val orchestrator: Orchestrator,
    private val binding: EngineExecuteBinding,
    private val modelManager: ModelManager,
    private val clockMs: () -> Long,
    private val runtimeEpoch: () -> Long,
    private val deviceFingerprint: () -> DeviceExecutionFingerprint,
) : PlaygroundInferencePort {

    override suspend fun startChat(
        principal: PrincipalId,
        spec: ChatRequestSpec,
    ): OmniResult<InferenceHandle> {
        if (!binding.isEngineBound()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "playground engine not attached (native missing or unbound)",
                ),
            )
        }
        if (!binding.isExploratoryExecuteEnabled() &&
            !binding.buildMode.allowExecuteWithoutQualification()
        ) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "experimental generate disabled; enable runtime.exploratoryExecuteEnabled " +
                        "(LOCAL_ADMIN).",
                    details = mapOf("setting" to EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE),
                ),
            )
        }
        val revision = try {
            ModelRevisionId.parse(spec.modelRevisionId.lowercase())
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid modelRevisionId"))
        }
        val reqId = try {
            RequestId.parse(spec.identity.requestId)
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid requestId"))
        }
        val digest = try {
            Sha256Digest.parse(spec.identity.canonicalInputDigest)
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid canonicalInputDigest"))
        }
        val installation = ControlPlaneFeaturePorts.resolveInstallationOrNull(modelManager, revision)
            ?: return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "no installed model for requested revision (fail closed, ARC-06)",
                    details = mapOf("modelRevisionId" to revision.hex),
                ),
            )
        val candidate = when (
            val c = ControlPlaneFeaturePorts.buildCandidate(
                binding, revision, installation, deviceFingerprint(),
            )
        ) {
            is OmniResult.Err -> return OmniResult.err(c.error)
            is OmniResult.Ok -> c.value
        }
        val request = OrchestrationRequest(
            requestId = reqId,
            principalId = principal,
            idempotencyKey = IdempotencyKey.parse(spec.identity.idempotencyKey),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate),
            routing = ControlPlaneFeaturePorts.exploratoryRouting(),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = runtimeEpoch(),
            revocationEpoch = 0L,
            deadlineMonotonic = Long.MAX_VALUE / 8,
        )
        return when (val submitted = orchestrator.submit(request)) {
            is OmniResult.Err -> OmniResult.err(submitted.error)
            is OmniResult.Ok -> {
                val terminal = orchestrator.pumpOnce()
                val view = orchestrator.query(reqId)
                val state = when {
                    terminal is OmniResult.Ok -> terminal.value.state
                    view != null -> view.terminalState ?: view.state
                    else -> submitted.value.state
                }
                val err = when {
                    terminal is OmniResult.Err -> terminal.error
                    terminal is OmniResult.Ok && terminal.value.errorCode != null ->
                        OmniError.INTERNAL(message = terminal.value.errorCode!!)
                    else -> null
                }
                OmniResult.ok(
                    InferenceHandle(
                        requestId = reqId.value,
                        operationKind = "CHAT",
                        state = state,
                        actualModelRevisionId = terminal?.let {
                            (it as? OmniResult.Ok)?.value?.actualRouting?.modelRevisionId?.hex
                        } ?: submitted.value.actualRouting?.modelRevisionId?.hex,
                        engineBuildId = terminal?.let {
                            (it as? OmniResult.Ok)?.value?.actualRouting?.engineBuildId?.value
                        } ?: candidate.engineBuildId.value,
                        backend = "cpu",
                        // C-02: surface aggregated engine token-delta text as the
                        // visible assistant text (null when the engine produced
                        // no visible tokens).
                        assistantText = if (err == null) {
                            binding.boundLlamaAdapter?.deltaText(reqId.value)
                        } else {
                            null
                        },
                        error = err,
                        degraded = true,
                        degradedReasons = listOf(
                            "CONDITIONAL exploratory execute",
                            "engine cells UNQUALIFIED",
                        ),
                    ),
                )
            }
        }
    }

    override suspend fun startEmbedding(
        principal: PrincipalId,
        spec: EmbeddingRequestSpec,
    ): OmniResult<InferenceHandle> =
        OmniResult.err(
            OmniError.CAPABILITY_UNKNOWN(
                message = "embedding remains UNKNOWN/unqualified for llama-cpp exploratory path",
                details = mapOf("capability" to CapabilityId.EMBEDDING.id),
            ),
        )

    /** In-process cancel phase ladder for progressive UI (FEAT-PLAYGROUND §2). */
    private val cancelPhaseByRequest =
        java.util.concurrent.ConcurrentHashMap<String, CancelPhase>()

    override suspend fun cancel(
        principal: PrincipalId,
        spec: CancelInferenceSpec,
    ): OmniResult<CancelPortResult> {
        val rid = try {
            RequestId.parse(spec.requestId)
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid requestId"))
        }
        // Progressive cancel phases: REQUESTED → ACKNOWLEDGED → EXECUTION_STOPPED → TERMINAL
        // First cancel issues orchestrator cancel; subsequent cancel/query advances disclosure.
        val prior = cancelPhaseByRequest[spec.requestId]
        if (prior == CancelPhase.TERMINAL) {
            return OmniResult.ok(
                CancelPortResult(spec.requestId, CancelPhase.TERMINAL, "CANCELLED"),
            )
        }
        // COR-12: phases advance ONLY on an actually-applied orchestrator cancel.
        // When the orchestrator rejects cancel (STATE_CONFLICT during STREAMING —
        // no in-stream cancel channel), report the conflict honestly and never
        // climb the ladder toward a fabricated CANCELLED.
        when (val r = orchestrator.cancel(rid)) {
            is OmniResult.Err -> {
                if (r.error.code == com.omnillm.core.errors.generated.OmniErrorCode.NOT_FOUND) {
                    return OmniResult.err(r.error)
                }
                if (r.error.code == com.omnillm.core.errors.generated.OmniErrorCode.STATE_CONFLICT) {
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "cancel not applied in current state: " +
                                (r.error.message ?: r.error.code.code),
                            details = mapOf(
                                "requestId" to spec.requestId,
                                "cause" to r.error.code.code,
                                "phase" to (prior?.name ?: "none"),
                            ),
                        ),
                    )
                }
                // Other errors (e.g. already terminal with different disposition):
                // surface honestly without advancing the phase ladder.
                return OmniResult.err(r.error)
            }
            is OmniResult.Ok -> Unit
        }
        val next = when (prior) {
            null -> CancelPhase.REQUESTED
            CancelPhase.REQUESTED -> CancelPhase.ACKNOWLEDGED
            CancelPhase.ACKNOWLEDGED -> CancelPhase.EXECUTION_STOPPED
            CancelPhase.EXECUTION_STOPPED, CancelPhase.TERMINAL -> CancelPhase.TERMINAL
        }
        cancelPhaseByRequest[spec.requestId] = next
        val requestState = when (next) {
            CancelPhase.REQUESTED, CancelPhase.ACKNOWLEDGED -> "CANCEL_REQUESTED"
            CancelPhase.EXECUTION_STOPPED -> "CANCELLING"
            CancelPhase.TERMINAL -> "CANCELLED"
        }
        return OmniResult.ok(CancelPortResult(spec.requestId, next, requestState))
    }

    override suspend fun query(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<InferenceHandle> {
        val rid = try {
            RequestId.parse(requestId)
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid requestId"))
        }
        val view = orchestrator.query(rid)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "request not found"))
        val cancelPhase = cancelPhaseByRequest[requestId]
        val state = when {
            cancelPhase == CancelPhase.TERMINAL -> "CANCELLED"
            view.terminalState != null -> view.terminalState!!
            else -> view.state
        }
        return OmniResult.ok(
            InferenceHandle(
                requestId = requestId,
                operationKind = "INFERENCE",
                state = state,
                cancelPhase = cancelPhase,
                actualModelRevisionId = view.actualRouting?.modelRevisionId?.hex,
                engineBuildId = view.actualRouting?.engineBuildId?.value,
                backend = view.actualRouting?.backend,
                // C-02: visible assistant text from aggregated engine token
                // deltas (polled by the SSE stream projection).
                assistantText = binding.boundLlamaAdapter?.deltaText(requestId),
                // D23: surface the durable error code so mid-stream failures
                // terminate honestly (never a fabricated finish_reason "stop").
                error = view.errorCode?.let { OmniError.INTERNAL(message = it) },
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Server smoke
// ---------------------------------------------------------------------------

private class OrchestratorServerInferencePort(
    private val orchestrator: Orchestrator,
    private val binding: EngineExecuteBinding,
    private val modelManager: ModelManager,
    private val clockMs: () -> Long,
    private val runtimeEpoch: () -> Long,
    private val deviceFingerprint: () -> DeviceExecutionFingerprint,
) : ServerInferencePort {

    override suspend fun submitSmoke(
        principal: PrincipalId,
        claim: InferenceClaimSpec,
    ): OmniResult<SmokeTestResult> {
        val now = clockMs()
        if (!binding.isEngineBound()) {
            return OmniResult.ok(
                SmokeTestResult(
                    step = "PLAN",
                    success = false,
                    requestId = claim.requestId,
                    requestState = null,
                    error = OmniError.CAPABILITY_UNSUPPORTED(
                        message = "smoke engine not attached (fail closed)",
                    ),
                    completedAtEpochMs = now,
                ),
            )
        }
        if (!binding.isExploratoryExecuteEnabled() &&
            !binding.buildMode.allowExecuteWithoutQualification()
        ) {
            return OmniResult.ok(
                SmokeTestResult(
                    step = "PLAN",
                    success = false,
                    requestId = claim.requestId,
                    requestState = null,
                    error = OmniError.CAPABILITY_UNSUPPORTED(
                        message = "experimental generate disabled; set runtime.exploratoryExecuteEnabled=true",
                        details = mapOf("setting" to EngineExecuteBinding.SETTING_EXPLORATORY_EXECUTE),
                    ),
                    completedAtEpochMs = now,
                ),
            )
        }
        val reqId = try {
            RequestId.parse(claim.requestId)
        } catch (_: Exception) {
            return OmniResult.ok(
                SmokeTestResult("PLAN", false, claim.requestId, null,
                    OmniError.INVALID_REQUEST(message = "invalid requestId"), now),
            )
        }
        val revision = try {
            ModelRevisionId.parse(claim.modelRevisionIdHex.lowercase())
        } catch (_: Exception) {
            return OmniResult.ok(
                SmokeTestResult("PLAN", false, claim.requestId, null,
                    OmniError.INVALID_REQUEST(message = "invalid modelRevisionIdHex"), now),
            )
        }
        val digest = try {
            Sha256Digest.parse(claim.canonicalRequestDigestHex.lowercase())
        } catch (_: Exception) {
            return OmniResult.ok(
                SmokeTestResult("PLAN", false, claim.requestId, null,
                    OmniError.INVALID_REQUEST(message = "invalid digest"), now),
            )
        }
        val caps = claim.requiredCapabilities.mapNotNull {
            try {
                CapabilityId.requireFromId(it)
            } catch (_: Exception) {
                null
            }
        }.toSet()
        if (caps.isEmpty()) {
            return OmniResult.ok(
                SmokeTestResult("PLAN", false, claim.requestId, null,
                    OmniError.INVALID_REQUEST(message = "no valid requiredCapabilities"), now),
            )
        }
        val installation = ControlPlaneFeaturePorts.resolveInstallationOrNull(modelManager, revision)
            ?: return OmniResult.ok(
                SmokeTestResult("PLAN", false, claim.requestId, null,
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = "no installed model for requested revision (fail closed, ARC-06)",
                        details = mapOf("modelRevisionId" to revision.hex),
                    ), now),
            )
        val candidate = when (
            val c = ControlPlaneFeaturePorts.buildCandidate(
                binding, revision, installation, deviceFingerprint(),
            )
        ) {
            is OmniResult.Err ->
                return OmniResult.ok(
                    SmokeTestResult("PLAN", false, claim.requestId, null, c.error, now),
                )
            is OmniResult.Ok -> c.value
        }
        val request = OrchestrationRequest(
            requestId = reqId,
            principalId = principal,
            idempotencyKey = IdempotencyKey.parse(claim.idempotencyKey),
            operationKind = claim.operationKind,
            canonicalRequestDigest = digest,
            requiredCapabilities = caps,
            requestedRevisionId = revision,
            candidates = listOf(candidate),
            routing = ControlPlaneFeaturePorts.exploratoryRouting(),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = claim.runtimeEpoch.coerceAtLeast(runtimeEpoch()),
            revocationEpoch = claim.revocationEpoch,
            deadlineMonotonic = claim.deadlineMonotonic.coerceAtLeast(Long.MAX_VALUE / 8),
        )
        return when (val submitted = orchestrator.submit(request)) {
            is OmniResult.Err -> OmniResult.ok(
                SmokeTestResult("SUBMIT", false, claim.requestId, null, submitted.error, now),
            )
            is OmniResult.Ok -> {
                val terminal = orchestrator.pumpOnce()
                when (terminal) {
                    is OmniResult.Ok -> OmniResult.ok(
                        SmokeTestResult(
                            step = "EXECUTE",
                            success = terminal.value.errorCode == null &&
                                terminal.value.state in setOf("COMPLETED", "SUCCEEDED", "TERMINAL_SUCCESS"),
                            requestId = claim.requestId,
                            requestState = terminal.value.state,
                            error = terminal.value.errorCode?.let {
                                OmniError.INTERNAL(message = it)
                            },
                            completedAtEpochMs = clockMs(),
                            actualModelRevisionId = terminal.value.actualRouting.modelRevisionId.hex,
                            actualEngineBuildId = terminal.value.actualRouting.engineBuildId.value,
                            actualBackend = terminal.value.actualRouting.backend,
                        ),
                    )
                    is OmniResult.Err -> OmniResult.ok(
                        SmokeTestResult(
                            step = "EXECUTE",
                            success = false,
                            requestId = claim.requestId,
                            requestState = submitted.value.state,
                            error = terminal.error,
                            completedAtEpochMs = clockMs(),
                        ),
                    )
                    null -> OmniResult.ok(
                        SmokeTestResult(
                            step = "QUEUED",
                            success = true,
                            requestId = claim.requestId,
                            requestState = submitted.value.state,
                            error = null,
                            completedAtEpochMs = clockMs(),
                            actualModelRevisionId = submitted.value.actualRouting?.modelRevisionId?.hex,
                            actualEngineBuildId = submitted.value.actualRouting?.engineBuildId?.value,
                            actualBackend = submitted.value.actualRouting?.backend,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun cancel(
        principal: PrincipalId,
        requestId: RequestId,
        command: ServerCommandIdentity,
    ): OmniResult<SmokeTestResult> =
        when (val r = orchestrator.cancel(requestId)) {
            is OmniResult.Ok -> OmniResult.ok(
                SmokeTestResult("CANCEL", true, requestId.value, "CANCELLED", null, clockMs()),
            )
            is OmniResult.Err -> OmniResult.ok(
                SmokeTestResult("CANCEL", false, requestId.value, null, r.error, clockMs()),
            )
        }

    override suspend fun query(
        principal: PrincipalId,
        requestId: RequestId,
    ): OmniResult<SmokeTestResult> {
        val view = orchestrator.query(requestId)
        return OmniResult.ok(
            SmokeTestResult(
                step = "QUERY",
                success = view != null,
                requestId = requestId.value,
                requestState = view?.terminalState ?: view?.state,
                error = if (view == null) OmniError.NOT_FOUND(message = "request not found") else null,
                completedAtEpochMs = clockMs(),
                actualModelRevisionId = view?.actualRouting?.modelRevisionId?.hex,
                actualEngineBuildId = view?.actualRouting?.engineBuildId?.value,
                actualBackend = view?.actualRouting?.backend,
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// Tools (structured remains UNKNOWN; cancel/query via orchestrator)
// ---------------------------------------------------------------------------

private class OrchestratorToolsInferencePort(
    private val orchestrator: Orchestrator,
    private val binding: EngineExecuteBinding,
    private val modelManager: ModelManager,
    private val clockMs: () -> Long,
    private val runtimeEpoch: () -> Long,
    private val deviceFingerprint: () -> DeviceExecutionFingerprint,
) : ToolsInferencePort {

    override suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredOutputSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
        maxRepairAttempts: Int,
    ): OmniResult<StructuredInferenceHandle> {
        if (!binding.buildMode.allowExecuteWithoutQualification()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNKNOWN(
                    message = "STRUCTURED_OUTPUT unknown/unqualified (compliance mode)",
                    details = mapOf(
                        "capability" to CapabilityId.STRUCTURED_OUTPUT.id,
                        "engineBound" to binding.isEngineBound().toString(),
                    ),
                ),
            )
        }
        // Development ship mode: allow path to orchestrator once engine bound (wire fully later).
        if (!binding.isEngineBound()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "structured: engine not bound"),
            )
        }
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "STRUCTURED_OUTPUT path open in DEV mode but engine structured adapter not implemented yet",
                details = mapOf("capability" to CapabilityId.STRUCTURED_OUTPUT.id, "todo" to "E1/I3"),
            ),
        )
    }

    override suspend fun startToolCalling(
        principal: PrincipalId,
        spec: ToolCallingSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
    ): OmniResult<ToolCallingHandle> {
        if (!binding.buildMode.allowExecuteWithoutQualification()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNKNOWN(
                    message = "TOOL_CALLING unknown/unqualified (compliance mode)",
                    details = mapOf("capability" to CapabilityId.TOOL_CALLING.id),
                ),
            )
        }
        if (!binding.isEngineBound()) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "tools: engine not bound"),
            )
        }
        return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "TOOL_CALLING path open in DEV mode but tool loop not implemented yet",
                details = mapOf("capability" to CapabilityId.TOOL_CALLING.id, "todo" to "I3/F9"),
            ),
        )
    }

    override suspend fun cancel(principal: PrincipalId, requestId: String): OmniResult<Unit> {
        val rid = try {
            RequestId.parse(requestId)
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid requestId"))
        }
        return orchestrator.cancel(rid)
    }

    override suspend fun query(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<ToolsQueryHandle> {
        val rid = try {
            RequestId.parse(requestId)
        } catch (_: Exception) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "invalid requestId"))
        }
        val view = orchestrator.query(rid)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "request not found"))
        return OmniResult.ok(
            ToolsQueryHandle(
                requestId = requestId,
                state = view.terminalState ?: view.state,
                actualModelRevisionId = view.actualRouting?.modelRevisionId?.hex,
                isTerminal = view.terminalState != null,
                error = view.errorCode?.let { OmniError.INTERNAL(message = it) },
            ),
        )
    }
}
