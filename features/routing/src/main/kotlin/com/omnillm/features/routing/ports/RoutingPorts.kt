package com.omnillm.features.routing.ports

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.QueryView
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Runtime ports for FEAT-ROUTING.
 *
 * Implementations live in the control plane (ADR-010). The feature module
 * never opens DB or loads native engines — it only composes these ports.
 */
data class RoutingFeaturePorts(
    val orchestrator: RoutingOrchestratorPort,
    val capabilities: RoutingCapabilityPort,
    /** Alias → revision table; fixed at accept (FEAT-ROUTING §1). */
    val aliases: AliasTablePort = EmptyAliasTablePort,
    val clockMs: () -> Long = { System.currentTimeMillis() },
)

/**
 * Alias table for request-accept fixation (FEAT-ROUTING §1).
 * Alias is resolved once; subsequent planning uses the fixed revision only.
 */
fun interface AliasTablePort {
    fun snapshot(): Map<String, com.omnillm.core.canonical.generated.ModelRevisionId>
}

object EmptyAliasTablePort : AliasTablePort {
    override fun snapshot(): Map<String, com.omnillm.core.canonical.generated.ModelRevisionId> = emptyMap()
}

/** Mutable in-memory alias table for tests / local admin expert controls. */
class InMemoryAliasTablePort(
    initial: Map<String, com.omnillm.core.canonical.generated.ModelRevisionId> = emptyMap(),
) : AliasTablePort {
    private val lock = Any()
    private val table = LinkedHashMap(initial)

    fun put(alias: String, revision: com.omnillm.core.canonical.generated.ModelRevisionId) =
        synchronized(lock) {
            require(alias.isNotBlank()) { "alias must be non-blank" }
            table[alias.trim()] = revision
        }

    fun remove(alias: String) = synchronized(lock) { table.remove(alias.trim()) }

    override fun snapshot(): Map<String, com.omnillm.core.canonical.generated.ModelRevisionId> =
        synchronized(lock) { table.toMap() }
}

/**
 * Orchestrator surface: pure plan + submit + query + cancel.
 * Plan has no domain mutation (ADR-002); submit claims request ledger.
 */
interface RoutingOrchestratorPort {
    /** Pure candidate plan (resource envelope); no execute. */
    suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult>

    /** Claim → plan → queue path. */
    suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult>

    /** Cancel in-flight request (fail closed if unknown). */
    suspend fun cancel(requestId: RequestId): OmniResult<Unit>

    /** Query durable request state / actual routing (reply-loss path). */
    suspend fun query(requestId: RequestId): QueryView?
}

/**
 * Capability cell lookup for negotiation (CAPABILITY_NEGOTIATION).
 * Unknown capability ⇒ [CapabilityState.UNKNOWN] ⇒ fail closed.
 */
fun interface RoutingCapabilityPort {
    fun state(capability: CapabilityId): CapabilityState
}

/** Default: all known FEAT-ROUTING capabilities supported (tests / happy path). */
object AllSupportedRoutingCapabilities : RoutingCapabilityPort {
    override fun state(capability: CapabilityId): CapabilityState = CapabilityState.SUPPORTED
}

/**
 * In-memory orchestrator stub that only fails closed — useful for pure-policy
 * unit tests that never hit plan/submit.
 */
object UnimplementedRoutingOrchestrator : RoutingOrchestratorPort {
    override suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> =
        error("orchestrator plan not wired")

    override suspend fun submit(request: OrchestrationRequest): OmniResult<SubmitResult> =
        error("orchestrator submit not wired")

    override suspend fun cancel(requestId: RequestId): OmniResult<Unit> =
        error("orchestrator cancel not wired")

    override suspend fun query(requestId: RequestId): QueryView? = null
}

/** Principal gate for LOCAL_UI-only surfaces. */
fun requireLocalUiPrincipal(principal: PrincipalId) {
    require(principal.value == com.omnillm.interfaces.admin.LocalUiPrincipal.ID.value) {
        "routing feature requires LOCAL_UI principal (got ${principal.value})"
    }
}
