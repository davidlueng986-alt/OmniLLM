package com.omnillm.ui.session

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.omnillm.features.admin.viewmodel.AdminHomeViewModel
import com.omnillm.features.admin.viewmodel.AdminJobsViewModel
import com.omnillm.features.admin.viewmodel.AdminSettingsViewModel
import com.omnillm.features.autosetup.viewmodel.AutoSetupViewModel
import com.omnillm.features.benchmark.viewmodel.BenchmarkViewModel
import com.omnillm.features.contentreport.viewmodel.ContentReportViewModel
import com.omnillm.features.dashboard.viewmodel.DashboardViewModel
import com.omnillm.features.diagnostics.viewmodel.DiagnosticsViewModel
import com.omnillm.features.lan.viewmodel.LanAccessViewModel
import com.omnillm.features.modelhub.viewmodel.ModelHubViewModel
import com.omnillm.features.playground.viewmodel.PlaygroundViewModel
import com.omnillm.features.routing.viewmodel.RoutingViewModel
import com.omnillm.features.server.viewmodel.DeveloperServerViewModel
import com.omnillm.ui.admin.AdminLiveFeatureFactory
import com.omnillm.ui.admin.AdminRuntimeConnection
import com.omnillm.ui.admin.BinderAdminFeatureFactory
import com.omnillm.ui.theme.DensityMode

/**
 * UI-process session: Admin binder connection + feature ViewModels.
 *
 * INV-001: never opens domain DB writers or loads native engines.
 * Feature VMs are pure projections fed by Admin binder when connected.
 *
 * When unbound, screens render disconnected / empty / loading states with
 * the same chrome (UX-ARCH §4).
 */
class UiSession(
    appContext: Context,
) {
    val adminConnection: AdminRuntimeConnection = AdminRuntimeConnection(appContext.applicationContext)

    var densityMode: DensityMode by mutableStateOf(DensityMode.STANDARD)
        private set

    var adminBound: Boolean by mutableStateOf(false)
        private set

    var onboardingComplete: Boolean by mutableStateOf(false)
        private set

    // Feature VMs — null until Admin binder connects.
    var adminHomeVm: AdminHomeViewModel? by mutableStateOf(null)
        private set
    var adminJobsVm: AdminJobsViewModel? by mutableStateOf(null)
        private set
    var adminSettingsVm: AdminSettingsViewModel? by mutableStateOf(null)
        private set
    var modelHubVm: ModelHubViewModel? by mutableStateOf(null)
        private set
    var playgroundVm: PlaygroundViewModel? by mutableStateOf(null)
        private set
    var serverVm: DeveloperServerViewModel? by mutableStateOf(null)
        private set
    var lanVm: LanAccessViewModel? by mutableStateOf(null)
        private set
    var dashboardVm: DashboardViewModel? by mutableStateOf(null)
        private set
    var diagnosticsVm: DiagnosticsViewModel? by mutableStateOf(null)
        private set
    var autoSetupVm: AutoSetupViewModel? by mutableStateOf(null)
        private set
    var contentReportVm: ContentReportViewModel? by mutableStateOf(null)
        private set
    var routingVm: RoutingViewModel? by mutableStateOf(null)
        private set
    var benchmarkVm: BenchmarkViewModel? by mutableStateOf(null)
        private set

    private val adminListener = AdminRuntimeConnection.Listener { admin ->
        adminBound = admin != null
        if (admin != null) {
            // Wire live Admin feature VMs from non-exported IOmniAdmin (INV-001).
            attachFeatureViewModels(
                adminHome = BinderAdminFeatureFactory.createHomeViewModel(admin),
                adminJobs = BinderAdminFeatureFactory.createJobsViewModel(admin),
                adminSettings = BinderAdminFeatureFactory.createSettingsViewModel(admin),
                modelHub = AdminLiveFeatureFactory.createModelHubViewModel(admin),
                autoSetup = AdminLiveFeatureFactory.createAutoSetupViewModel(admin),
                playground = AdminLiveFeatureFactory.createPlaygroundViewModel(admin),
                dashboard = AdminLiveFeatureFactory.createDashboardViewModel(admin),
                benchmark = AdminLiveFeatureFactory.createBenchmarkViewModel(admin),
                server = AdminLiveFeatureFactory.createServerViewModel(admin),
                lan = AdminLiveFeatureFactory.createLanViewModel(admin),
            )
        }
    }

    fun start() {
        adminConnection.addListener(adminListener)
        adminConnection.connect()
    }

    fun stop() {
        adminConnection.removeListener(adminListener)
        adminConnection.disconnect()
        adminBound = false
    }

    fun toggleDensity() {
        densityMode = when (densityMode) {
            DensityMode.STANDARD -> DensityMode.EXPERT
            DensityMode.EXPERT -> DensityMode.STANDARD
        }
    }

    fun markOnboardingComplete(complete: Boolean) {
        onboardingComplete = complete
    }

    /**
     * Inject feature ViewModels once facades are available from the runtime host.
     * UI never constructs control-plane writers here (ADR-010 / INV-001).
     */
    fun attachFeatureViewModels(
        adminHome: AdminHomeViewModel? = null,
        adminJobs: AdminJobsViewModel? = null,
        adminSettings: AdminSettingsViewModel? = null,
        modelHub: ModelHubViewModel? = null,
        playground: PlaygroundViewModel? = null,
        server: DeveloperServerViewModel? = null,
        lan: LanAccessViewModel? = null,
        dashboard: DashboardViewModel? = null,
        diagnostics: DiagnosticsViewModel? = null,
        autoSetup: AutoSetupViewModel? = null,
        contentReport: ContentReportViewModel? = null,
        routing: RoutingViewModel? = null,
        benchmark: BenchmarkViewModel? = null,
    ) {
        adminHome?.let { adminHomeVm = it }
        adminJobs?.let { adminJobsVm = it }
        adminSettings?.let { adminSettingsVm = it }
        modelHub?.let { modelHubVm = it }
        playground?.let { playgroundVm = it }
        server?.let { serverVm = it }
        lan?.let { lanVm = it }
        dashboard?.let { dashboardVm = it }
        diagnostics?.let { diagnosticsVm = it }
        autoSetup?.let { autoSetupVm = it }
        contentReport?.let { contentReportVm = it }
        routing?.let { routingVm = it }
        benchmark?.let { benchmarkVm = it }
    }
}
