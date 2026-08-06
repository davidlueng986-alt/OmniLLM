package com.omnillm.features.dashboard.api

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Public Dashboard API for LOCAL_UI / Admin composition (FEAT-DASHBOARD).
 *
 * Composes SERVICE_HEALTH, ENGINE_HEALTH, MODEL_HEALTH, REQUEST_TRACE,
 * JOB_PROGRESS, RESOURCE_ACCOUNTING, PERFORMANCE_MEASUREMENT,
 * EVIDENCE_LABELING, LOCAL_UI_INTERFACE (feature-capability-map.yaml).
 *
 * UI process talks only through Admin binder → these ports.
 * Never open domain DB or load native engines (INV-001).
 *
 * Client generates requestId for cancel/query (ADR-004/005).
 */
interface DashboardApi {

    /** Full home snapshot (health / resources / performance / requests / traces / actions). */
    fun getSnapshot(principal: PrincipalId = LocalUiPrincipal.ID): OmniResult<DashboardSnapshot>

    /** Redacted REQUEST_TRACE summary by correlation id. */
    fun getTrace(
        principal: PrincipalId = LocalUiPrincipal.ID,
        correlationId: String,
    ): OmniResult<TraceSummaryUi>

    /**
     * Cancel in-flight request. [requestId] is **client-generated**.
     * Query after cancel for durable terminal (ADR-004/005).
     */
    fun cancelRequest(
        principal: PrincipalId = LocalUiPrincipal.ID,
        requestId: RequestId,
    ): OmniResult<CancelRequestResult>

    /** Reply-loss safe query by client-generated [requestId]. */
    fun queryRequest(
        principal: PrincipalId = LocalUiPrincipal.ID,
        requestId: RequestId,
    ): OmniResult<CancelRequestResult>

    /**
     * CAPABILITY_NEGOTIATION for a required set.
     * UNSUPPORTED / UNKNOWN / TEMPORARILY_UNAVAILABLE block (INV-018).
     */
    fun negotiate(required: List<CapabilityId>): CapabilityNegotiationResult
}
