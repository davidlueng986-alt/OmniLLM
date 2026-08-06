package com.omnillm.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.ui.R
import com.omnillm.ui.components.DensityToggleChip
import com.omnillm.ui.components.MinTouchTarget
import com.omnillm.ui.screens.BenchmarkScreen
import com.omnillm.ui.screens.ContentReportScreen
import com.omnillm.ui.screens.DashboardScreen
import com.omnillm.ui.screens.DiagnosticsScreen
import com.omnillm.ui.screens.HomeScreen
import com.omnillm.ui.screens.ModelHubScreen
import com.omnillm.ui.screens.OnboardingScreen
import com.omnillm.ui.screens.PlaygroundScreen
import com.omnillm.ui.screens.RoutingScreen
import com.omnillm.ui.screens.ServerClientsInitialTab
import com.omnillm.ui.screens.ServerClientsScreen
import com.omnillm.ui.screens.SettingsScreen
import com.omnillm.ui.session.UiSession
import com.omnillm.ui.theme.OmniTheme
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Speed

/**
 * Root navigation host for UX-IA primary work areas.
 * Bottom bar: Home / ModelHub / Playground / Server / Dashboard.
 * Drawer: Settings / Diagnostics / Setup / Content report / LAN / Routing / Benchmark.
 * Expert density toggle in drawer (UX-ARCH §3).
 */
@Composable
fun OmniNavHost(
    session: UiSession,
    initialDestination: OmniDestination = OmniDestination.Home,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf(initialDestination) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun navigate(dest: OmniDestination) {
        current = dest
        scope.launch { drawerState.close() }
    }

    OmniTheme(densityMode = session.densityMode) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = modifier,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.semantics {
                        contentDescription = "more destinations"
                    },
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.nav_more),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                        DensityToggleChip(
                            densityMode = session.densityMode,
                            onToggle = { session.toggleDensity() },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        OmniDestination.moreDestinations.forEach { dest ->
                            NavigationDrawerItem(
                                label = { Text(destinationLabel(dest)) },
                                selected = current.route == dest.route ||
                                    current.route.startsWith(dest.route),
                                onClick = { navigate(dest) },
                                icon = {
                                    Icon(
                                        imageVector = destinationIcon(dest),
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier.heightIn(min = MinTouchTarget),
                            )
                        }
                    }
                }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                val showBottomBar = when (current) {
                    is OmniDestination.Settings,
                    is OmniDestination.Diagnostics,
                    is OmniDestination.Onboarding,
                    is OmniDestination.ContentReport,
                    is OmniDestination.ReportDetail,
                    is OmniDestination.JobDetail,
                    is OmniDestination.RequestDetail,
                    is OmniDestination.ModelDetail,
                    is OmniDestination.Benchmark,
                    is OmniDestination.Routing,
                    -> false
                    else -> true
                }
                Column(Modifier.weight(1f)) {
                    DestinationContent(
                        destination = current,
                        session = session,
                        onNavigate = ::navigate,
                    )
                }
                if (showBottomBar) {
                    PrimaryBottomBar(
                        current = current,
                        onSelect = ::navigate,
                        onOpenMore = { scope.launch { drawerState.open() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryBottomBar(
    current: OmniDestination,
    onSelect: (OmniDestination) -> Unit,
    onOpenMore: () -> Unit,
) {
    val barCd = stringResource(R.string.nav_cd_bottom_bar)
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = barCd },
    ) {
        OmniDestination.primaryRail.forEach { dest ->
            val selected = when (dest) {
                OmniDestination.Home -> current is OmniDestination.Home
                OmniDestination.ModelHub ->
                    current is OmniDestination.ModelHub ||
                        current is OmniDestination.ModelDetail
                OmniDestination.Playground -> current is OmniDestination.Playground
                OmniDestination.ServerClients -> current is OmniDestination.ServerClients
                OmniDestination.Dashboard ->
                    current is OmniDestination.Dashboard ||
                        current is OmniDestination.RequestDetail
                else -> false
            }
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(dest) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(dest),
                        contentDescription = destinationLabel(dest),
                    )
                },
                label = {
                    Text(
                        text = destinationLabel(dest),
                        maxLines = 1,
                    )
                },
                alwaysShowLabel = true,
            )
        }
        NavigationBarItem(
            selected = false,
            onClick = onOpenMore,
            icon = {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = stringResource(R.string.nav_cd_open_drawer),
                )
            },
            label = { Text(stringResource(R.string.nav_more)) },
            alwaysShowLabel = true,
        )
    }
}

@Composable
private fun DestinationContent(
    destination: OmniDestination,
    session: UiSession,
    onNavigate: (OmniDestination) -> Unit,
) {
    when (destination) {
        OmniDestination.Home -> HomeScreen(
            viewModel = session.adminHomeVm,
            adminBound = session.adminBound,
            onOpenModelHub = { onNavigate(OmniDestination.ModelHub) },
            onOpenPlayground = { onNavigate(OmniDestination.Playground) },
            onOpenSetup = { onNavigate(OmniDestination.Onboarding) },
            onOpenDiagnostics = { onNavigate(OmniDestination.Diagnostics) },
            onOpenJob = { id -> onNavigate(OmniDestination.JobDetail(id)) },
        )
        OmniDestination.ModelHub -> ModelHubScreen(
            viewModel = session.modelHubVm,
            localImporter = session.adminConnection.admin?.let {
                com.omnillm.ui.admin.ModelHubLocalImporter(it)
            },
            onOpenSetup = { onNavigate(OmniDestination.Onboarding) },
        )
        is OmniDestination.ModelDetail -> ModelHubScreen(
            viewModel = session.modelHubVm,
            localImporter = session.adminConnection.admin?.let {
                com.omnillm.ui.admin.ModelHubLocalImporter(it)
            },
            initialInstallationId = destination.installationId,
            onOpenSetup = { onNavigate(OmniDestination.Onboarding) },
        )
        OmniDestination.Playground -> PlaygroundScreen(
            viewModel = session.playgroundVm,
            onOpenModelHub = { onNavigate(OmniDestination.ModelHub) },
            onOpenContentReport = { onNavigate(OmniDestination.ContentReport) },
        )
        OmniDestination.ServerClients -> ServerClientsScreen(
            serverVm = session.serverVm,
            lanVm = session.lanVm,
            initialTab = ServerClientsInitialTab.LOCAL,
        )
        OmniDestination.Lan -> ServerClientsScreen(
            serverVm = session.serverVm,
            lanVm = session.lanVm,
            initialTab = ServerClientsInitialTab.LAN,
        )
        OmniDestination.Dashboard -> DashboardScreen(
            viewModel = session.dashboardVm,
            onOpenDiagnostics = { onNavigate(OmniDestination.Diagnostics) },
        )
        OmniDestination.Settings -> SettingsScreen(
            viewModel = session.adminSettingsVm,
            densityMode = session.densityMode,
            onToggleDensity = { session.toggleDensity() },
            onOpenDiagnostics = { onNavigate(OmniDestination.Diagnostics) },
            onOpenContentReport = { onNavigate(OmniDestination.ContentReport) },
        )
        OmniDestination.Diagnostics -> DiagnosticsScreen(
            viewModel = session.diagnosticsVm,
        )
        OmniDestination.Onboarding -> OnboardingScreen(
            viewModel = session.autoSetupVm,
            onOpenPlayground = { onNavigate(OmniDestination.Playground) },
            onOpenDashboard = { onNavigate(OmniDestination.Dashboard) },
            onOpenModelHub = { onNavigate(OmniDestination.ModelHub) },
            onSetupCompleted = { session.markOnboardingComplete(true) },
        )
        OmniDestination.ContentReport -> ContentReportScreen(
            viewModel = session.contentReportVm,
        )
        is OmniDestination.ReportDetail -> ContentReportScreen(
            viewModel = session.contentReportVm,
            initialReportId = destination.reportId,
        )
        is OmniDestination.JobDetail -> {
            // Jobs surface via Home / ModelHub; deep link lands on Home with opaque id in chrome.
            HomeScreen(
                viewModel = session.adminHomeVm,
                adminBound = session.adminBound,
                onOpenModelHub = { onNavigate(OmniDestination.ModelHub) },
                onOpenPlayground = { onNavigate(OmniDestination.Playground) },
                onOpenSetup = { onNavigate(OmniDestination.Onboarding) },
                onOpenDiagnostics = { onNavigate(OmniDestination.Diagnostics) },
                onOpenJob = { onNavigate(OmniDestination.JobDetail(it)) },
            )
        }
        is OmniDestination.RequestDetail -> DashboardScreen(
            viewModel = session.dashboardVm,
            onOpenDiagnostics = { onNavigate(OmniDestination.Diagnostics) },
        )
        OmniDestination.Routing -> RoutingScreen(
            viewModel = session.routingVm,
        )
        OmniDestination.Benchmark -> BenchmarkScreen(
            viewModel = session.benchmarkVm,
            onOpenModelHub = { onNavigate(OmniDestination.ModelHub) },
            onOpenDashboard = { onNavigate(OmniDestination.Dashboard) },
        )
    }
}

@Composable
private fun destinationLabel(dest: OmniDestination): String = when {
    dest is OmniDestination.Home || dest is OmniDestination.JobDetail ->
        stringResource(R.string.nav_home)
    dest is OmniDestination.ModelHub || dest is OmniDestination.ModelDetail ->
        stringResource(R.string.nav_modelhub)
    dest is OmniDestination.Playground -> stringResource(R.string.nav_playground)
    dest is OmniDestination.ServerClients -> stringResource(R.string.nav_server_clients)
    dest is OmniDestination.Lan -> stringResource(R.string.nav_lan)
    dest is OmniDestination.Dashboard || dest is OmniDestination.RequestDetail ->
        stringResource(R.string.nav_dashboard)
    dest is OmniDestination.Settings -> stringResource(R.string.settings_title)
    dest is OmniDestination.Diagnostics -> stringResource(R.string.nav_diagnostics)
    dest is OmniDestination.Onboarding -> stringResource(R.string.nav_onboarding)
    dest is OmniDestination.ContentReport || dest is OmniDestination.ReportDetail ->
        stringResource(R.string.nav_content_report)
    dest is OmniDestination.Routing -> stringResource(R.string.nav_routing)
    dest is OmniDestination.Benchmark -> stringResource(R.string.nav_benchmark)
    else -> dest.route
}

private fun destinationIcon(dest: OmniDestination): ImageVector = when {
    dest is OmniDestination.Home || dest is OmniDestination.JobDetail -> Icons.Filled.Home
    dest is OmniDestination.ModelHub || dest is OmniDestination.ModelDetail -> Icons.Filled.Hub
    dest is OmniDestination.Playground -> Icons.Filled.PlayArrow
    dest is OmniDestination.ServerClients -> Icons.Filled.Dns
    dest is OmniDestination.Lan -> Icons.Filled.Wifi
    dest is OmniDestination.Dashboard || dest is OmniDestination.RequestDetail -> Icons.Filled.Dashboard
    dest is OmniDestination.Settings -> Icons.Filled.Settings
    dest is OmniDestination.Diagnostics -> Icons.Filled.BugReport
    dest is OmniDestination.Onboarding -> Icons.Filled.RocketLaunch
    dest is OmniDestination.ContentReport || dest is OmniDestination.ReportDetail -> Icons.Filled.Report
    dest is OmniDestination.Routing -> Icons.Filled.AltRoute
    dest is OmniDestination.Benchmark -> Icons.Filled.Speed
    else -> Icons.Filled.Home
}
