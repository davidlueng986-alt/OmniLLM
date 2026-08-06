package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.diagnostics.viewmodel.DiagnosticsUiState
import com.omnillm.features.diagnostics.viewmodel.DiagnosticsViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.EvidencedMetricRow
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.LoadingBody
import com.omnillm.ui.components.OmniScreenScaffold
import com.omnillm.ui.components.PhaseBanner
import com.omnillm.ui.components.PrimaryActionButton
import com.omnillm.ui.components.ScreenPhase
import com.omnillm.ui.components.ScrollableScreenColumn
import com.omnillm.ui.components.SecondaryActionButton
import com.omnillm.ui.components.SectionHeader
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode
import com.omnillm.ui.util.observeListenerAsState
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val scope = rememberCoroutineScope()

    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.uiState()) { listener ->
            viewModel.observe(listener)
        }.value
    } else {
        DiagnosticsUiState()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refresh()
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state.isLoading && state.snapshot == null -> ScreenPhase.LOADING
        state.isError && state.snapshot == null -> ScreenPhase.ERROR
        state.isEmpty -> ScreenPhase.EMPTY
        state.isDegraded -> ScreenPhase.DEGRADED
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.diagnostics_title),
        modifier = modifier,
        onRefresh = { scope.launch { viewModel?.refresh() } },
    ) { padding ->
        when (phase) {
            ScreenPhase.LOADING -> LoadingBody(Modifier.padding(padding))
            ScreenPhase.ERROR -> {
                Column(Modifier.padding(padding)) {
                    ErrorBody(
                        message = state.lastError?.message
                            ?: stringResource(R.string.state_error),
                        errorCode = state.lastError?.code?.code,
                        onRetry = { scope.launch { viewModel?.refresh() } },
                    )
                }
            }
            ScreenPhase.EMPTY -> EmptyBody(
                title = stringResource(R.string.diagnostics_empty),
                body = stringResource(R.string.diagnostics_empty_body),
                primaryActionLabel = stringResource(R.string.diagnostics_plan),
                onPrimaryAction = { viewModel?.planExport() },
                modifier = Modifier.padding(padding),
            )
            else -> {
                ScrollableScreenColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (phase == ScreenPhase.DEGRADED) {
                        item { PhaseBanner(ScreenPhase.DEGRADED) }
                    }
                    item {
                        InfoCard {
                            Text(
                                stringResource(R.string.diagnostics_phase, state.phaseLabelKey),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.diagnostics_share_irreversible),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            state.lastJob?.let { job ->
                                Text(
                                    stringResource(
                                        R.string.diagnostics_job,
                                        job.jobId,
                                        job.state,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (state.degradedReasons.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.diagnostics_degraded),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                state.degradedReasons.forEach {
                                    Text("• $it", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Column(Modifier.padding(top = 8.dp)) {
                                if ("plan-export" in state.actions) {
                                    PrimaryActionButton(
                                        label = stringResource(R.string.diagnostics_plan),
                                        onClick = { viewModel?.planExport() },
                                    )
                                }
                                if ("cancel" in state.actions) {
                                    SecondaryActionButton(
                                        label = stringResource(R.string.action_cancel),
                                        onClick = {
                                            // Client-generated cancel identity (ADR-004/005).
                                            val job = state.lastJob ?: return@SecondaryActionButton
                                            val commandId = java.util.UUID.randomUUID().toString()
                                            val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                                                "cancel|${job.jobId}|${job.resourceVersion}",
                                            )
                                            scope.launch {
                                                viewModel?.cancelExport(
                                                    com.omnillm.features.diagnostics.api.CancelExportSpec(
                                                        jobId = job.jobId,
                                                        command = com.omnillm.features.diagnostics.api.DiagnosticsCommandIdentity(
                                                            commandId = commandId,
                                                            idempotencyKey = "diag-cancel-$commandId",
                                                            canonicalInputDigest = digest,
                                                            expectedVersion = job.resourceVersion,
                                                        ),
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item { SectionHeader(stringResource(R.string.diagnostics_bundles)) }
                    items(state.bundles, key = { it.bundleId }) { bundle ->
                        InfoCard {
                            Text(
                                bundle.bundleId,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (density == DensityMode.EXPERT) {
                                Text(
                                    bundle.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 4,
                                )
                            }
                        }
                    }
                    item { SectionHeader(stringResource(R.string.diagnostics_metrics)) }
                    items(state.metrics) { metric ->
                        InfoCard {
                            EvidencedMetricRow(
                                name = metric.name,
                                displayValue = metric.value,
                                unit = metric.unit,
                                evidenceLabel = metric.evidenceLabel,
                                allowsNumericDisplay = metric.value != null &&
                                    metric.evidenceLabel != EvidenceLabel.UNKNOWN,
                                source = metric.source,
                            )
                        }
                    }
                }
            }
        }
    }
}
