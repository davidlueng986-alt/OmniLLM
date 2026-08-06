package com.omnillm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.features.admin.model.AdminHomeUi
import com.omnillm.features.admin.model.JobListItemUi
import com.omnillm.features.admin.viewmodel.AdminHomeViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.LoadingBody
import com.omnillm.ui.components.MinTouchTarget
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

@Composable
fun HomeScreen(
    viewModel: AdminHomeViewModel?,
    adminBound: Boolean,
    onOpenModelHub: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenJob: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    var localState by remember { mutableStateOf(AdminHomeViewModel.State(loading = true)) }

    if (viewModel != null) {
        val observed = observeListenerAsState(viewModel.state) { listener ->
            viewModel.addListener(listener)
            return@observeListenerAsState { viewModel.removeListener(listener) }
        }
        localState = observed.value
        LaunchedEffect(viewModel) {
            viewModel.refresh()
        }
    } else {
        localState = AdminHomeViewModel.State(
            loading = !adminBound,
            home = null,
            error = null,
        )
    }

    val phase = when {
        localState.loading -> ScreenPhase.LOADING
        localState.error != null -> ScreenPhase.ERROR
        !adminBound && viewModel == null -> ScreenPhase.EMPTY
        localState.home == null -> ScreenPhase.EMPTY
        localState.home?.runtimeState == "DEGRADED" -> ScreenPhase.DEGRADED
        localState.home?.runtimeState == "RECOVERING" -> ScreenPhase.RECOVERING
        localState.home?.runtimeState == "FAULTED" -> ScreenPhase.ERROR
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.home_title),
        modifier = modifier,
        onRefresh = { viewModel?.refresh() },
    ) { padding ->
        when (phase) {
            ScreenPhase.LOADING -> LoadingBody(Modifier.padding(padding))
            ScreenPhase.ERROR -> {
                Column(Modifier.padding(padding)) {
                    PhaseBanner(ScreenPhase.ERROR)
                    ErrorBody(
                        message = localState.error?.message
                            ?: stringResource(R.string.state_error),
                        errorCode = localState.error?.code?.code,
                        onRetry = { viewModel?.refresh() },
                        onDiagnostics = onOpenDiagnostics,
                    )
                }
            }
            ScreenPhase.EMPTY -> EmptyBody(
                title = stringResource(R.string.home_title),
                body = if (!adminBound) {
                    stringResource(R.string.runtime_disconnected_body)
                } else {
                    stringResource(R.string.home_empty_body)
                },
                primaryActionLabel = stringResource(R.string.action_start_setup),
                onPrimaryAction = onOpenSetup,
                modifier = Modifier.padding(padding),
            )
            else -> {
                val home = localState.home
                ScrollableScreenColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    if (phase == ScreenPhase.DEGRADED || phase == ScreenPhase.RECOVERING) {
                        item { PhaseBanner(phase) }
                    }
                    item {
                        SectionHeader(stringResource(R.string.home_quick_start))
                    }
                    item {
                        InfoCard {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                PrimaryActionButton(
                                    label = stringResource(R.string.action_start_setup),
                                    onClick = onOpenSetup,
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryActionButton(
                                    label = stringResource(R.string.action_open_modelhub),
                                    onClick = onOpenModelHub,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            SecondaryActionButton(
                                label = stringResource(R.string.action_open_playground),
                                onClick = onOpenPlayground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                    if (home != null) {
                        item {
                            SectionHeader(stringResource(R.string.home_active_model))
                        }
                        item {
                            HomeStatusCard(home = home, density = density)
                        }
                        if (home.models.isEmpty()) {
                            item {
                                InfoCard {
                                    Text(
                                        text = stringResource(R.string.home_no_models),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = stringResource(R.string.home_no_models_cta),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PrimaryActionButton(
                                        label = stringResource(R.string.action_open_modelhub),
                                        onClick = onOpenModelHub,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        } else {
                            items(home.models, key = { it.modelRevisionId }) { model ->
                                val name = model.displayName
                                    ?: model.modelRevisionId.take(12) + "…"
                                val cd = stringResource(
                                    R.string.home_model_row_cd,
                                    name,
                                )
                                InfoCard(
                                    modifier = Modifier.semantics { contentDescription = cd },
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (density == DensityMode.EXPERT) {
                                        Text(
                                            text = stringResource(
                                                R.string.label_canonical_id,
                                                model.modelRevisionId.take(12) + "…",
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            SectionHeader(stringResource(R.string.home_blocking_issues))
                        }
                        item {
                            JobsSection(
                                jobs = home.jobs,
                                onOpenJob = onOpenJob,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeStatusCard(home: AdminHomeUi, density: DensityMode) {
    InfoCard {
        Text(
            text = stringResource(R.string.home_runtime_state, home.runtimeState),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.home_lan_state, home.lanState),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.home_active_jobs, home.activeJobCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        home.resourcePressureLabel?.let {
            Text(
                text = stringResource(R.string.home_resource_pressure, it),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (density == DensityMode.EXPERT) {
            Text(
                text = stringResource(R.string.home_snapshot_version, home.snapshotVersion),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JobsSection(
    jobs: List<JobListItemUi>,
    onOpenJob: (String) -> Unit,
) {
    if (jobs.isEmpty()) {
        InfoCard {
            Text(
                text = stringResource(R.string.home_no_jobs),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        Column {
            jobs.forEach { job ->
                val cd = stringResource(R.string.home_job_row_cd, job.jobId, job.state)
                InfoCard(
                    modifier = Modifier
                        .semantics { contentDescription = cd }
                        .clickable { onOpenJob(job.jobId) }
                        .heightIn(min = MinTouchTarget),
                ) {
                    Text(
                        text = job.kind,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = job.state,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    job.progressRatio?.let { ratio ->
                        Text(
                            text = "${(ratio * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
