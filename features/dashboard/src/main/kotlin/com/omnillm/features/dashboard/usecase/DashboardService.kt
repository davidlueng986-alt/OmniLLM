package com.omnillm.features.dashboard.usecase

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.dashboard.api.CancelRequestResult
import com.omnillm.features.dashboard.api.CapabilityCellUi
import com.omnillm.features.dashboard.api.CapabilityNegotiationResult
import com.omnillm.features.dashboard.api.DashboardApi
import com.omnillm.features.dashboard.api.DashboardSnapshot
import com.omnillm.features.dashboard.api.TraceSummaryUi
import com.omnillm.features.dashboard.ports.DashboardFeaturePorts
import com.omnillm.features.dashboard.projection.DashboardProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.atomic.AtomicLong

/**
 * FEAT-DASHBOARD use-case facade.
 *
 * - Composes observability / resource / request / capability ports only
 * - Never writes DB or loads native engines (INV-001, ADR-010)
 * - Client-generated requestId for cancel / query (ADR-004/005)
 * - Capability negotiation fail closed (INV-018)
 */
class DashboardService(
    private val ports: DashboardFeaturePorts,
) : DashboardApi {

    private val snapshotSeq = AtomicLong(0L)

    /**
     * Capabilities this feature requires for full home snapshot
     * (`specs/feature-capability-map.yaml` FEAT-DASHBOARD).
     */
    val requiredCapabilities: List<CapabilityId> = listOf(
        CapabilityId.SERVICE_HEALTH,
        CapabilityId.ENGINE_HEALTH,
        CapabilityId.MODEL_HEALTH,
        CapabilityId.REQUEST_TRACE,
        CapabilityId.JOB_PROGRESS,
        CapabilityId.RESOURCE_ACCOUNTING,
        CapabilityId.PERFORMANCE_MEASUREMENT,
        CapabilityId.EVIDENCE_LABELING,
        CapabilityId.LOCAL_UI_INTERFACE,
    )

    override fun getSnapshot(principal: PrincipalId): OmniResult<DashboardSnapshot> {
        requireLocalUi(principal)
        val negotiation = negotiate(requiredCapabilities)
        if (!negotiation.allSupported) {
            return OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "dashboard capabilities not supported: ${negotiation.blockingIds}",
                    details = mapOf(
                        "blocking" to negotiation.blockingIds.joinToString(","),
                        "unsupported" to negotiation.unsupportedIds.joinToString(","),
                    ),
                ),
            )
        }
        return OmniResult.ok(buildSnapshot())
    }

    override fun getTrace(
        principal: PrincipalId,
        correlationId: String,
    ): OmniResult<TraceSummaryUi> {
        requireLocalUi(principal)
        when (val st = ports.capabilities.state(CapabilityId.REQUEST_TRACE)) {
            CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
            CapabilityState.UNSUPPORTED ->
                return OmniResult.err(
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = "REQUEST_TRACE unsupported",
                        details = mapOf(
                            "capability" to CapabilityId.REQUEST_TRACE.id,
                            "state" to st.name,
                        ),
                    ),
                )
            CapabilityState.UNKNOWN, CapabilityState.TEMPORARILY_UNAVAILABLE ->
                return OmniResult.err(
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = "REQUEST_TRACE not available (fail closed): ${st.name}",
                        details = mapOf(
                            "capability" to CapabilityId.REQUEST_TRACE.id,
                            "state" to st.name,
                        ),
                    ),
                )
        }
        val trace = ports.traces.redactedTrace(correlationId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "trace not found",
                    details = mapOf("correlationId" to correlationId),
                ),
            )
        return OmniResult.ok(DashboardProjection.projectTrace(trace))
    }

    /**
     * Cancel an in-flight request using **client-generated** [requestId]
     * (ADR-004/005). Prefer [queryRequest] after cancel for durable terminal.
     */
    override fun cancelRequest(
        principal: PrincipalId,
        requestId: RequestId,
    ): OmniResult<CancelRequestResult> {
        requireLocalUi(principal)
        when (val st = ports.capabilities.state(CapabilityId.CANCELLATION)) {
            CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
            else ->
                return OmniResult.err(
                    OmniError.CAPABILITY_UNSUPPORTED(
                        message = "CANCELLATION not available: ${st.name}",
                        details = mapOf(
                            "capability" to CapabilityId.CANCELLATION.id,
                            "state" to st.name,
                        ),
                    ),
                )
        }
        val prior = when (val q = ports.requests.query(requestId)) {
            is OmniResult.Ok -> q.value.phase
            is OmniResult.Err -> null
        }
        return when (val cancelled = ports.requests.cancel(requestId)) {
            is OmniResult.Ok -> OmniResult.ok(
                CancelRequestResult(
                    requestId = cancelled.value.requestId,
                    priorPhase = prior,
                    phase = cancelled.value.phase,
                    terminal = DashboardProjection.isTerminal(cancelled.value.phase),
                ),
            )
            is OmniResult.Err -> OmniResult.err(cancelled.error)
        }
    }

    /**
     * Reply-loss safe query by client-generated [requestId] (ADR-004/005).
     */
    override fun queryRequest(
        principal: PrincipalId,
        requestId: RequestId,
    ): OmniResult<CancelRequestResult> {
        requireLocalUi(principal)
        return when (val q = ports.requests.query(requestId)) {
            is OmniResult.Ok -> OmniResult.ok(
                CancelRequestResult(
                    requestId = q.value.requestId,
                    priorPhase = null,
                    phase = q.value.phase,
                    terminal = DashboardProjection.isTerminal(q.value.phase),
                ),
            )
            is OmniResult.Err -> OmniResult.err(q.error)
        }
    }

    /**
     * CAPABILITY_NEGOTIATION for an arbitrary required set.
     * UNSUPPORTED / UNKNOWN / TEMPORARILY_UNAVAILABLE are blocking (INV-018).
     * CONDITIONAL is non-blocking but reported with conditions.
     */
    override fun negotiate(required: List<CapabilityId>): CapabilityNegotiationResult {
        val cells = required.map { cap ->
            val state = ports.capabilities.state(cap)
            CapabilityCellUi(
                capabilityId = cap.id,
                state = state,
                conditions = if (state == CapabilityState.CONDITIONAL) {
                    listOf("conditional-runtime")
                } else {
                    emptyList()
                },
            )
        }
        val allSupported = cells.all {
            it.state == CapabilityState.SUPPORTED || it.state == CapabilityState.CONDITIONAL
        }
        return CapabilityNegotiationResult(allSupported = allSupported, cells = cells)
    }

    private fun buildSnapshot(): DashboardSnapshot {
        val now = ports.clockWallMs()
        val health = ports.health.serviceHealth()
        // Local admin sees detail; privacy still redacts forbidden dimensions
        // inside MetricRegistry / Redactor.
        val metrics = ports.metrics.metricDetail()
        val ledger = ports.resources.ledgerSnapshot()
        val evidence = ports.resources.ledgerEvidence()
        val requests = ports.requests.listActiveRequests()
        val traces = ports.traces.listCorrelationIds().mapNotNull { id ->
            ports.traces.redactedTrace(id)
        }
        return DashboardProjection.projectSnapshot(
            snapshotVersion = snapshotSeq.incrementAndGet(),
            health = health,
            metricSnapshot = metrics,
            ledger = ledger,
            ledgerEvidence = evidence,
            requests = requests,
            traces = traces,
            nowEpochMs = now,
            lastMeasurement = ports.measurements.lastSealedRun(),
        )
    }

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal == LocalUiPrincipal.ID) {
            "dashboard home is LOCAL_UI only (INV-001 / access-control)"
        }
    }
}
