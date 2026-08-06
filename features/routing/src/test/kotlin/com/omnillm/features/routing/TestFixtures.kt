package com.omnillm.features.routing

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.routing.api.PlanRouteSpec
import com.omnillm.features.routing.api.RoutingCandidateSpec
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.routing.ports.AllSupportedRoutingCapabilities
import com.omnillm.features.routing.ports.RoutingCapabilityPort
import com.omnillm.features.routing.ports.RoutingFeaturePorts
import com.omnillm.features.routing.ports.RoutingOrchestratorPort
import com.omnillm.features.routing.usecase.RoutingService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.orchestrator.ClaimKind
import com.omnillm.runtime.orchestrator.EarliestStartEstimate
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlannedCandidate
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.QueryView
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.SubmitResult
import com.omnillm.runtime.orchestrator.CostClassLabels
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal fun digest(seed: Char = 'a'): Sha256Digest {
    require(seed in '0'..'9' || seed in 'a'..'f')
    return Sha256Digest.parse(seed.toString().repeat(64))
}

internal fun revision(seed: Char = '1'): ModelRevisionId {
    require(seed in '0'..'9' || seed in 'a'..'f')
    return ModelRevisionId.parse(seed.toString().repeat(64))
}

internal fun uuid(seed: Int = 0): String =
    "550e8400-e29b-41d4-a716-44665544${seed.toString().padStart(4, '0')}"

internal fun candidateSpec(
    id: String = "cand-primary",
    rev: ModelRevisionId = revision('1'),
    backend: String = "cpu",
    primary: Boolean = true,
    placement: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
    engine: String = "engine-build-1",
    installation: String = uuid(0),
    loadKeySeed: Char = 'c',
): RoutingCandidateSpec =
    RoutingCandidateSpec(
        candidateId = id,
        modelRevisionIdHex = rev.hex,
        installationId = installation,
        engineBuildId = engine,
        backend = backend,
        placementClass = placement,
        loadKeyDigestHex = digest(loadKeySeed).hex,
        isPrimary = primary,
        deviceExecutionFingerprint = "device-fp-test-1",
    )

internal fun preferenceView(
    policy: FallbackPolicy = FallbackPolicy.NONE,
    allowlist: List<ModelRevisionId> = emptyList(),
    backends: Set<String> = emptySet(),
    minPlacement: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
    preferred: String? = null,
): RoutingPreferenceView =
    RoutingPreferenceView(
        fallbackPolicy = policy,
        revisionAllowlistHex = allowlist.map { it.hex },
        allowedBackends = backends,
        minimumPlacementClass = minPlacement,
        preferredBackend = preferred,
    )

internal fun planSpec(
    rev: ModelRevisionId = revision('1'),
    preference: RoutingPreferenceView = preferenceView(),
    candidates: List<RoutingCandidateSpec> = listOf(candidateSpec(rev = rev)),
    sourceSession: Boolean = false,
    sourceRev: ModelRevisionId? = null,
    sourceLoadKey: String? = null,
    sourceEngine: String? = null,
    provenTransfer: Boolean = false,
): PlanRouteSpec =
    PlanRouteSpec(
        requestedRevisionIdHex = rev.hex,
        preference = preference,
        candidates = candidates,
        sourceSessionPresent = sourceSession,
        sourceRevisionIdHex = sourceRev?.hex,
        sourceLoadKeyDigestHex = sourceLoadKey,
        sourceEngineBuildId = sourceEngine,
        provenStateTransfer = provenTransfer,
    )

/**
 * Fake orchestrator: pure plan echoes viable candidates with a trivial envelope.
 */
internal class FakeRoutingOrchestrator(
    var failPlan: Boolean = false,
    var rejectCandidateIds: Set<String> = emptySet(),
) : RoutingOrchestratorPort {
    val planCount = AtomicInteger(0)
    val submitCount = AtomicInteger(0)
    var lastRequest: OrchestrationRequest? = null
    private val rows = linkedMapOf<String, QueryView>()

    override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> {
        planCount.incrementAndGet()
        lastRequest = request
        if (failPlan) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.ADMISSION_REJECTED(
                    message = "plan failed",
                ),
            )
        }
        val viable = request.candidates
            .filter { it.candidateId !in rejectCandidateIds }
            .map { c ->
                PlannedCandidate(
                    candidate = c,
                    resourceEnvelope = ResourceEnvelope(
                        steady = ResourceVector(cpuAnonBytes = 1_024L, nativeThreads = 1L),
                        peak = ResourceVector(cpuAnonBytes = 2_048L, nativeThreads = 2L),
                    ),
                    planInputDigest = digest('e'),
                    costClass = CostClassLabels.GENERATION,
                    costUnits = 4L,
                )
            }
        val rejections = request.candidates
            .filter { it.candidateId in rejectCandidateIds }
            .map {
                com.omnillm.runtime.orchestrator.CandidateRejection(
                    candidateId = it.candidateId,
                    code = com.omnillm.runtime.orchestrator.CandidateRejectionCodes.HEALTH,
                    message = "engine unhealthy",
                )
            }
        if (viable.isEmpty()) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.ADMISSION_REJECTED(
                    message = "no viable",
                    details = mapOf("rejectionCount" to rejections.size.toString()),
                ),
            )
        }
        return OmniResult.ok(PlanningResult(viable = viable, rejections = rejections))
    }

    override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> {
        submitCount.incrementAndGet()
        lastRequest = request
        return when (val p = plan(request)) {
            is OmniResult.Err -> p
            is OmniResult.Ok -> {
                val head = p.value.viable.first().candidate
                val view = QueryView(
                    requestId = request.requestId,
                    state = "QUEUED",
                    terminalState = null,
                    errorCode = null,
                    actualRouting = null,
                    rejectionTrail = p.value.rejections,
                )
                rows[request.requestId.value] = view
                OmniResult.ok(
                    SubmitResult(
                        requestId = request.requestId,
                        claim = ClaimKind.NEW,
                        state = "QUEUED",
                        earliestStart = EarliestStartEstimate(
                            enqueueSeq = 1L,
                            estimatedStartMonotonic = 100L,
                            policyVersion = "orchestrator-drr-v1",
                            confidence = "ESTIMATED",
                            requestId = request.requestId,
                        ),
                        planning = p.value,
                        actualRouting = null,
                    ),
                )
            }
        }
    }

    override suspend fun cancel(requestId: RequestId): OmniResult<Unit> {
        val row = rows[requestId.value]
            ?: return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.NOT_FOUND(message = "missing"),
            )
        rows[requestId.value] = row.copy(state = "CANCEL_REQUESTED")
        return OmniResult.ok(Unit)
    }

    override suspend fun query(requestId: RequestId): QueryView? = rows[requestId.value]
}

internal class SelectiveRoutingCapabilities(
    private val unsupported: Set<CapabilityId> = emptySet(),
    private val unknown: Set<CapabilityId> = emptySet(),
) : RoutingCapabilityPort {
    override fun state(capability: CapabilityId): CapabilityState =
        when {
            capability in unknown -> CapabilityState.UNKNOWN
            capability in unsupported -> CapabilityState.UNSUPPORTED
            else -> CapabilityState.SUPPORTED
        }
}

internal fun routingService(
    orch: RoutingOrchestratorPort = FakeRoutingOrchestrator(),
    caps: RoutingCapabilityPort = AllSupportedRoutingCapabilities,
): RoutingService =
    RoutingService(
        RoutingFeaturePorts(
            orchestrator = orch,
            capabilities = caps,
        ),
    )

internal val localUi: PrincipalId = LocalUiPrincipal.ID

/** Convert candidate specs to orchestrator candidates (for pure policy unit tests). */
internal fun toRoutingCandidate(spec: RoutingCandidateSpec): RoutingCandidate =
    RoutingCandidate(
        candidateId = spec.candidateId,
        modelRevisionId = ModelRevisionId.parse(spec.modelRevisionIdHex),
        installationId = InstallationId.parse(spec.installationId),
        engineBuildId = EngineBuildId.parse(spec.engineBuildId),
        backend = spec.backend,
        placementClass = spec.placementClass,
        loadKeyDigest = Sha256Digest.parse(spec.loadKeyDigestHex),
        isPrimary = spec.isPrimary,
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse(spec.deviceExecutionFingerprint),
    )
