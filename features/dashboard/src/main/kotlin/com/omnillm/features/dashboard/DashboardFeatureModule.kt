package com.omnillm.features.dashboard

import com.omnillm.features.dashboard.api.DashboardApi
import com.omnillm.features.dashboard.ports.DashboardFeaturePorts
import com.omnillm.features.dashboard.ports.DashboardHealthPort
import com.omnillm.features.dashboard.ports.DashboardMetricsPort
import com.omnillm.features.dashboard.ports.DashboardTracePort
import com.omnillm.features.dashboard.ports.ObservabilityDashboardAdapter
import com.omnillm.features.dashboard.usecase.DashboardService
import com.omnillm.features.dashboard.viewmodel.DashboardViewModel
import com.omnillm.runtime.observability.ObservabilityFacade

/**
 * Feature pack entry for `:features:dashboard` (FEAT-DASHBOARD).
 *
 * Composes SERVICE_HEALTH / RESOURCE_ACCOUNTING / REQUEST_TRACE /
 * PERFORMANCE_MEASUREMENT / EVIDENCE_LABELING; does not redefine
 * Request, Session, or Trust semantics (FEATURE-SYSTEM).
 *
 * UI process talks only through Admin binder → these ports.
 * Never open domain DB or load native engines (INV-001).
 */
object DashboardFeatureModule {
    const val MODULE_PATH: String = ":features:dashboard"
    const val FEATURE_ID: String = "FEAT-DASHBOARD"

    fun createApi(ports: DashboardFeaturePorts): DashboardApi = DashboardService(ports)

    /**
     * Build feature API from an observability facade (+ optional resource/request ports).
     */
    fun createApi(
        facade: ObservabilityFacade,
        knownCorrelationIds: () -> List<String> = { emptyList() },
        resources: com.omnillm.features.dashboard.ports.DashboardResourcePort =
            com.omnillm.features.dashboard.ports.EmptyDashboardResourcePort,
        requests: com.omnillm.features.dashboard.ports.DashboardRequestPort =
            com.omnillm.features.dashboard.ports.EmptyDashboardRequestPort,
        capabilities: com.omnillm.features.dashboard.ports.DashboardCapabilityPort =
            com.omnillm.features.dashboard.ports.HonestDashboardCapabilityPort,
        measurements: com.omnillm.features.dashboard.ports.DashboardMeasurementPort =
            com.omnillm.features.dashboard.ports.EmptyDashboardMeasurementPort,
        clockWallMs: () -> Long = facade::nowEpochMs,
    ): DashboardApi {
        val adapter = ObservabilityDashboardAdapter(facade, knownCorrelationIds)
        val health: DashboardHealthPort = adapter
        val metrics: DashboardMetricsPort = adapter
        val traces: DashboardTracePort = adapter
        return createApi(
            DashboardFeaturePorts(
                health = health,
                metrics = metrics,
                traces = traces,
                resources = resources,
                requests = requests,
                capabilities = capabilities,
                measurements = measurements,
                clockWallMs = clockWallMs,
            ),
        )
    }

    fun createViewModel(api: DashboardApi): DashboardViewModel = DashboardViewModel(api)
}
