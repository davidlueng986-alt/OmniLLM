package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.omnillm.core.contracts.RequestId
import com.omnillm.features.dashboard.api.DashboardUiPhase
import com.omnillm.features.dashboard.viewmodel.DashboardViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.EvidencedMetricRow
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.LoadingBody
import com.omnillm.ui.components.MinTouchTarget
import com.omnillm.ui.components.OmniScreenScaffold
import com.omnillm.ui.components.PhaseBanner
import com.omnillm.ui.components.ScreenPhase
import com.omnillm.ui.components.ScrollableScreenColumn
import com.omnillm.ui.components.SecondaryActionButton
import com.omnillm.ui.components.SectionHeader
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode
import com.omnillm.ui.util.observeListenerAsState

private enum class DashboardTab {
    OVERVIEW,
    MODELS,
    REQUESTS,
    RESOURCES,
    MEASUREMENTS,
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel?,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = DashboardTab.entries

    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.state) { listener ->
            viewModel.addListener(listener)
            return@observeListenerAsState { viewModel.removeListener(listener) }
        }.value
    } else {
        DashboardViewModel.State()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refresh()
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state.phase == DashboardUiPhase.LOADING || state.loading -> ScreenPhase.LOADING
        state.phase == DashboardUiPhase.ERROR -> ScreenPhase.ERROR
        state.phase == DashboardUiPhase.DEGRADED -> ScreenPhase.DEGRADED
        state.phase == DashboardUiPhase.EMPTY || state.snapshot == null -> ScreenPhase.EMPTY
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.dashboard_title),
        modifier = modifier,
        onRefresh = { viewModel?.refresh() },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                        text = {
                            Text(
                                when (tab) {
                                    DashboardTab.OVERVIEW ->
                                        stringResource(R.string.dashboard_overview)
                                    DashboardTab.MODELS ->
                                        stringResource(R.string.dashboard_models_engines)
                                    DashboardTab.REQUESTS ->
                                        stringResource(R.string.dashboard_requests)
                                    DashboardTab.RESOURCES ->
                                        stringResource(R.string.dashboard_resources)
                                    DashboardTab.MEASUREMENTS ->
                                        stringResource(R.string.dashboard_measurements)
                                },
                            )
                        },
                    )
                }
            }
            when (phase) {
                ScreenPhase.LOADING -> LoadingBody()
                ScreenPhase.ERROR -> ErrorBody(
                    message = state.lastError?.message ?: stringResource(R.string.state_error),
                    errorCode = state.lastError?.code?.code,
                    onRetry = { viewModel?.refresh() },
                    onDiagnostics = onOpenDiagnostics,
                )
                ScreenPhase.EMPTY -> EmptyBody(
                    title = stringResource(R.string.dashboard_empty),
                    body = stringResource(R.string.dashboard_empty_body),
                )
                else -> {
                    if (phase == ScreenPhase.DEGRADED) {
                        PhaseBanner(ScreenPhase.DEGRADED)
                    }
                    val snap = state.snapshot
                    when (tabs[tabIndex]) {
                        DashboardTab.OVERVIEW -> OverviewPane(snap, density)
                        DashboardTab.MODELS -> ModelsPane(snap, density)
                        DashboardTab.REQUESTS -> RequestsPane(snap, viewModel)
                        DashboardTab.RESOURCES -> ResourcesPane(snap, density)
                        DashboardTab.MEASUREMENTS -> MeasurementsPane(snap, density)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewPane(
    snap: com.omnillm.features.dashboard.api.DashboardSnapshot?,
    density: DensityMode,
) {
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        item {
            InfoCard {
                if (snap == null) {
                    Text(stringResource(R.string.dashboard_empty))
                } else {
                    Text(
                        stringResource(
                            R.string.dashboard_health,
                            snap.health.overallLevel.name,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            R.string.dashboard_runtime,
                            snap.health.runtimeState,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    snap.health.degradedReasons.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                    }
                    if (density == DensityMode.EXPERT) {
                        Text(
                            "snapshot=${snap.snapshotVersion}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.dashboard_actions)) }
        items(snap?.actions.orEmpty()) { action ->
            InfoCard {
                Text(action.labelKey, style = MaterialTheme.typography.titleSmall)
                Text(action.reasonCode, style = MaterialTheme.typography.bodySmall)
                action.recommendedActionKeys.forEach {
                    Text("→ $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ModelsPane(
    snap: com.omnillm.features.dashboard.api.DashboardSnapshot?,
    density: DensityMode,
) {
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        items(snap?.health?.subjects.orEmpty()) { subject ->
            InfoCard {
                Text(
                    "${subject.kind}: ${subject.subjectId}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(subject.level.name, style = MaterialTheme.typography.bodyMedium)
                subject.affectedCapabilities.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                }
                if (density == DensityMode.EXPERT) {
                    subject.diagnosticSummary?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestsPane(
    snap: com.omnillm.features.dashboard.api.DashboardSnapshot?,
    viewModel: DashboardViewModel?,
) {
    val requests = snap?.requests.orEmpty()
    if (requests.isEmpty()) {
        EmptyBody(
            title = stringResource(R.string.dashboard_no_requests),
            body = "",
        )
        return
    }
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        items(requests, key = { it.requestId }) { req ->
            val cd = stringResource(
                R.string.dashboard_request_row_cd,
                req.requestId,
                req.phase,
            )
            InfoCard(modifier = Modifier.semantics { contentDescription = cd }) {
                Text(req.phase, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.label_request_id, req.requestId),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(req.labelKey, style = MaterialTheme.typography.bodySmall)
                if (req.cancelAllowed) {
                    SecondaryActionButton(
                        label = stringResource(R.string.dashboard_cancel_request),
                        onClick = {
                            viewModel?.cancelRequest(RequestId.parse(req.requestId))
                        },
                    )
                }
                if (req.replyLossRecoverable) {
                    SecondaryActionButton(
                        label = stringResource(R.string.dashboard_query_request),
                        onClick = {
                            viewModel?.queryRequest(RequestId.parse(req.requestId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourcesPane(
    snap: com.omnillm.features.dashboard.api.DashboardSnapshot?,
    density: DensityMode,
) {
    val resources = snap?.resources
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        item {
            InfoCard {
                if (resources == null) {
                    Text(stringResource(R.string.label_not_available))
                } else {
                    Text(
                        if (resources.conservationOk) {
                            stringResource(R.string.dashboard_conservation_ok)
                        } else {
                            stringResource(R.string.dashboard_conservation_fail)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
        items(resources?.dimensions.orEmpty()) { dim ->
            InfoCard {
                Text(dim.dimension, style = MaterialTheme.typography.titleSmall)
                EvidencedMetricRow(
                    name = "capacity",
                    displayValue = dim.capacity.displayValue,
                    unit = dim.capacity.unit,
                    evidenceLabel = dim.capacity.evidenceLabel,
                    allowsNumericDisplay = dim.capacity.allowsNumericDisplay,
                    source = dim.capacity.source,
                )
                EvidencedMetricRow(
                    name = "reserved",
                    displayValue = dim.reserved.displayValue,
                    unit = dim.reserved.unit,
                    evidenceLabel = dim.reserved.evidenceLabel,
                    allowsNumericDisplay = dim.reserved.allowsNumericDisplay,
                )
                EvidencedMetricRow(
                    name = "allocated",
                    displayValue = dim.allocated.displayValue,
                    unit = dim.allocated.unit,
                    evidenceLabel = dim.allocated.evidenceLabel,
                    allowsNumericDisplay = dim.allocated.allowsNumericDisplay,
                )
                EvidencedMetricRow(
                    name = "free",
                    displayValue = dim.free.displayValue,
                    unit = dim.free.unit,
                    evidenceLabel = dim.free.evidenceLabel,
                    allowsNumericDisplay = dim.free.allowsNumericDisplay,
                )
                if (density == DensityMode.EXPERT) {
                    dim.blockedReasonCode?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            InfoCard {
                Text(
                    stringResource(R.string.dashboard_operational_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snap?.performance?.let { perf ->
                    perf.ttftMs?.let {
                        EvidencedMetricRow(
                            name = "ttft",
                            displayValue = it.displayValue,
                            unit = it.unit,
                            evidenceLabel = it.evidenceLabel,
                            allowsNumericDisplay = it.allowsNumericDisplay,
                        )
                    }
                    perf.tokensPerSecond?.let {
                        EvidencedMetricRow(
                            name = "tokens/s",
                            displayValue = it.displayValue,
                            unit = it.unit,
                            evidenceLabel = it.evidenceLabel,
                            allowsNumericDisplay = it.allowsNumericDisplay,
                        )
                    }
                    perf.thermalStatus?.let {
                        EvidencedMetricRow(
                            name = "thermal",
                            displayValue = it.displayValue,
                            unit = it.unit,
                            evidenceLabel = it.evidenceLabel,
                            allowsNumericDisplay = it.allowsNumericDisplay,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementsPane(
    snap: com.omnillm.features.dashboard.api.DashboardSnapshot?,
    density: DensityMode,
) {
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        item {
            InfoCard {
                val last = snap?.lastMeasurementRun
                if (last == null) {
                    Text(
                        stringResource(R.string.dashboard_no_measurement_run),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        stringResource(R.string.dashboard_last_run_outcome, last.outcome),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "runSeq=${last.runSeq} profile=${last.profileId.take(12)}…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    last.ttftMs?.let {
                        EvidencedMetricRow(
                            name = "ttft",
                            displayValue = it.displayValue,
                            unit = it.unit,
                            evidenceLabel = it.evidenceLabel,
                            allowsNumericDisplay = it.allowsNumericDisplay,
                            source = it.source,
                        )
                    }
                    last.throughputTokensPerSec?.let {
                        EvidencedMetricRow(
                            name = "tokens/s",
                            displayValue = it.displayValue,
                            unit = it.unit,
                            evidenceLabel = it.evidenceLabel,
                            allowsNumericDisplay = it.allowsNumericDisplay,
                            source = it.source,
                        )
                    }
                    last.fixtureSource?.let {
                        Text(
                            stringResource(R.string.dashboard_fixture_metrics, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (density == DensityMode.EXPERT) {
                        last.methodVersion?.let {
                            Text("method=$it", style = MaterialTheme.typography.labelSmall)
                        }
                        last.deviationReasons.forEach {
                            Text("• $it", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        items(snap?.metrics.orEmpty()) { metric ->
            InfoCard {
                EvidencedMetricRow(
                    name = metric.name,
                    displayValue = metric.displayValue,
                    unit = metric.unit,
                    evidenceLabel = metric.evidenceLabel,
                    allowsNumericDisplay = metric.allowsNumericDisplay,
                    source = metric.source,
                    sampledAtLabel = if (density == DensityMode.EXPERT) {
                        metric.sampledAtEpochMs.toString()
                    } else {
                        null
                    },
                )
            }
        }
        items(snap?.traces.orEmpty()) { trace ->
            InfoCard {
                Text(
                    stringResource(R.string.dashboard_trace, trace.correlationId),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (density == DensityMode.EXPERT) {
                    trace.lastPhase?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "events=${trace.eventCount}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
