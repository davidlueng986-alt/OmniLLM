package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.routing.ports.RoutingCapabilityPort
import com.omnillm.features.routing.ports.RoutingOrchestratorPort
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.domain.ToolCallingSpec
import com.omnillm.features.tools.ports.StructuredInferenceHandle
import com.omnillm.features.tools.ports.ToolCallingHandle
import com.omnillm.features.tools.ports.ToolsInferencePort
import com.omnillm.features.tools.ports.ToolsQueryHandle
import com.omnillm.features.tools.policy.StructuredModePolicy
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.QueryView
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Fail-closed routing orchestrator until Orchestrator is attached on the plane.
 * Never invents candidates or silent cross-revision fallbacks.
 */
object FailClosedRoutingOrchestrator : RoutingOrchestratorPort {
    private val unsupported: OmniError =
        OmniError.CAPABILITY_UNSUPPORTED(
            message = "routing orchestrator not attached on control plane",
            details = mapOf("reason" to "no_silent_fallback"),
        )

    override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> =
        OmniResult.err(unsupported)

    override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> =
        OmniResult.err(unsupported)

    override suspend fun cancel(requestId: RequestId): OmniResult<Unit> =
        OmniResult.err(unsupported)

    override suspend fun query(requestId: RequestId): QueryView? = null
}

/**
 * Platform capability cells for FEAT-ROUTING required set.
 *
 * CODE-05: never invents SUPPORTED. Catalog feature-required cells are
 * CONDITIONAL only when the plane Orchestrator is attached (operable but
 * unqualified). Unknown capabilities remain UNKNOWN (fail closed — INV-018).
 */
class HostRoutingCapabilityPort(
    private val orchestratorAttached: Boolean = false,
) : RoutingCapabilityPort {
    private val catalog: Set<CapabilityId> = setOf(
        CapabilityId.MULTI_MODEL_ROUTING,
        CapabilityId.FALLBACK_POLICY,
        CapabilityId.CAPABILITY_NEGOTIATION,
        CapabilityId.REQUEST_LIFECYCLE,
        CapabilityId.RESOURCE_ACCOUNTING,
    )

    override fun state(capability: CapabilityId): CapabilityState =
        when {
            capability !in catalog -> CapabilityState.UNKNOWN
            orchestratorAttached -> CapabilityState.CONDITIONAL
            else -> CapabilityState.UNKNOWN
        }
}

/**
 * Structured / tool inference fails closed until engine execute path is attached.
 * OmniLLM still never executes host tools (FEAT-TOOLS).
 */
object FailClosedToolsInferencePort : ToolsInferencePort {
    private val unsupported: OmniError =
        OmniError.CAPABILITY_UNSUPPORTED(
            message = "tools inference path not attached (engine execute pending)",
        )

    override suspend fun startStructured(
        principal: PrincipalId,
        spec: StructuredOutputSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
        maxRepairAttempts: Int,
    ): OmniResult<StructuredInferenceHandle> = OmniResult.err(unsupported)

    override suspend fun startToolCalling(
        principal: PrincipalId,
        spec: ToolCallingSpec,
        actualMode: StructuredMode,
        maxAttempts: Int,
    ): OmniResult<ToolCallingHandle> = OmniResult.err(unsupported)

    override suspend fun cancel(principal: PrincipalId, requestId: String): OmniResult<Unit> =
        OmniResult.err(unsupported)

    override suspend fun query(principal: PrincipalId, requestId: String): OmniResult<ToolsQueryHandle> =
        OmniResult.err(unsupported)
}

/**
 * Default tools capability map: UNKNOWN until model/engine evidence is bound
 * (do not invent SUPPORTED for unqualified engines).
 */
class HostToolsCapabilityPort : com.omnillm.features.tools.ports.ToolsCapabilityPort {
    override fun state(capability: CapabilityId, modelRevisionId: String): CapabilityState =
        CapabilityState.UNKNOWN

    override fun offeredStructuredMode(
        modelRevisionId: String,
        workloadEnvelope: String,
    ): StructuredModePolicy.EngineStructuredCell =
        StructuredModePolicy.EngineStructuredCell(
            offeredMode = StructuredMode.UNSUPPORTED,
            capabilityState = CapabilityState.UNKNOWN,
            workloadEnvelope = workloadEnvelope,
        )
}
