package com.omnillm.features.routing.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.SubmitResult

/**
 * Public Routing API for LOCAL_UI / Admin composition (FEAT-ROUTING).
 *
 * Composes MULTI_MODEL_ROUTING, FALLBACK_POLICY, CAPABILITY_NEGOTIATION,
 * REQUEST_LIFECYCLE, RESOURCE_ACCOUNTING without redefining Request / Session /
 * Trust (FEATURE-SYSTEM §1).
 *
 * Mutations go through Orchestrator ports only (ADR-010 / INV-001).
 * Client generates requestId / idempotencyKey; query on reply loss (ADR-004/005).
 */
interface RoutingApi {

    /** Current screen projection. */
    fun snapshot(): RoutingSnapshot

    /**
     * Capability negotiation for FEAT-ROUTING required set.
     * UNKNOWN / UNSUPPORTED fail closed (INV-018).
     */
    fun negotiate(principal: PrincipalId): OmniResult<RoutingCapabilityNegotiation>

    /**
     * Validate caller preference without planning (pure).
     * ALLOW_LIST without allowlist, NONE with allowlist, etc. fail closed.
     */
    fun validatePreference(
        principal: PrincipalId,
        preference: RoutingPreferenceView,
    ): OmniResult<RoutingPreferenceView>

    /**
     * Resolve alias or bare revision at accept (FEAT-ROUTING §1).
     * Result is fixed for subsequent plan/submit; never re-resolved mid-request.
     */
    fun resolveAlias(
        principal: PrincipalId,
        aliasOrRevisionHex: String,
    ): OmniResult<AliasResolveView>

    /**
     * Pure plan: policy filter + orchestrator candidate plan (ADR-002).
     * Does not claim request registry or execute inference.
     * [PlanRouteSpec.requestedRevisionIdHex] may be an alias; resolved once at accept.
     */
    suspend fun planRoute(
        principal: PrincipalId,
        spec: PlanRouteSpec,
    ): OmniResult<RoutingDecisionView>

    /**
     * Full submit: claim → plan → queue via Orchestrator.
     * Identity is client-generated (ADR-004/005).
     */
    suspend fun submitRoute(
        principal: PrincipalId,
        spec: PlanRouteSpec,
        identity: RouteSubmitIdentity,
    ): OmniResult<RoutingDecisionView>

    /**
     * Query durable request after reply loss — never blind replay.
     */
    suspend fun queryRoute(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<RoutingDecisionView>

    /**
     * Cancel in-flight request (fail closed if unknown).
     */
    suspend fun cancelRoute(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<Unit>

    /**
     * Expose last pure [PlanningResult] for diagnostics composition
     * (same decision inputs as runtime routing — FEAT-ROUTING §2).
     */
    fun lastPlanningResult(): PlanningResult?

    /** Last orchestrator submit result when present. */
    fun lastSubmitResult(): SubmitResult?
}
