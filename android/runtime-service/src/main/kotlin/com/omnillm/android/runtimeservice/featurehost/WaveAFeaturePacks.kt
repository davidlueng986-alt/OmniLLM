package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.features.admin.usecase.AdminFeatureApi
import com.omnillm.features.autosetup.api.AutoSetupApi
import com.omnillm.features.dashboard.api.DashboardApi
import com.omnillm.features.modelhub.api.ModelHubApi
import com.omnillm.features.playground.api.PlaygroundApi
import com.omnillm.features.server.api.DeveloperServerApi
import com.omnillm.runtime.governor.ResourceGovernor
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.orchestrator.CandidatePlanner
import com.omnillm.runtime.orchestrator.Orchestrator

/**
 * Wave-A Feature Pack APIs hosted on the runtime control plane (ADR-010).
 * Packs: admin, auto-setup, modelhub, playground, server, dashboard.
 * All fields non-null by construction for RuntimeControlPlane smoke coverage.
 *
 * [engineExecute] is the switchable Orchestrator inference port binding updated
 * by [com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane.ensureEnginePacksAttached].
 */
data class WaveAFeaturePacks(
    val admin: AdminFeatureApi,
    val autoSetup: AutoSetupApi,
    val modelHub: ModelHubApi,
    val playground: PlaygroundApi,
    val server: DeveloperServerApi,
    val dashboard: DashboardApi,
    val modelManager: ModelManager,
    val orchestrator: Orchestrator,
    val resourceGovernor: ResourceGovernor,
    val engineExecute: EngineExecuteBinding = EngineExecuteBinding(),
    val candidatePlanner: CandidatePlanner? = null,
    /** Late-bound ToolsApi for playground STRUCTURED_TOOLS (set by FeaturePackHost). */
    val toolsApiHolder: ToolsApiHolder = ToolsApiHolder(),
    /** Late-bound BenchmarkApi for dashboard MEASUREMENTS (set by FeaturePackHost). */
    val benchmarkApiHolder: BenchmarkApiHolder = BenchmarkApiHolder(),
) {
    fun assertAllServicesNonNull() {
        requireNotNull(admin)
        requireNotNull(autoSetup)
        requireNotNull(modelHub)
        requireNotNull(playground)
        requireNotNull(server)
        requireNotNull(dashboard)
        requireNotNull(modelManager)
        requireNotNull(orchestrator)
        requireNotNull(resourceGovernor)
        requireNotNull(engineExecute)
    }
}
