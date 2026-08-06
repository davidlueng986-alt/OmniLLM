package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.features.benchmark.api.BenchmarkCommandIdentity
import com.omnillm.features.benchmark.api.CancelBenchmarkSpec
import com.omnillm.features.benchmark.api.PlanBenchmarkSpec
import com.omnillm.features.benchmark.api.StartBenchmarkSpec
import com.omnillm.features.benchmark.domain.BenchmarkScenarioTemplate
import com.omnillm.features.benchmark.domain.BenchmarkUiPhases
import com.omnillm.features.benchmark.domain.MeasurementProfileFactory
import com.omnillm.features.benchmark.viewmodel.BenchmarkUiState
import com.omnillm.features.benchmark.viewmodel.BenchmarkViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
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
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * FEAT-BENCHMARK research workspace (UX-IA Dashboard / measurements sibling).
 *
 * INV-001: talks only through [BenchmarkViewModel] → Admin binder projections.
 * Never invents QUALIFIED/SUPPORTED engine evidence (ENG-Q).
 */
@Composable
fun BenchmarkScreen(
    viewModel: BenchmarkViewModel?,
    onOpenModelHub: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val scope = rememberCoroutineScope()

    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.uiState()) { listener ->
            viewModel.observe(listener)
        }.value
    } else {
        BenchmarkUiState()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refresh()
    }

    val phase = when {
        // Unbound: shell empty chrome only.
        viewModel == null -> ScreenPhase.EMPTY
        state.loading && state.plan == null && state.runs.isEmpty() &&
            state.activeJob == null -> ScreenPhase.LOADING
        state.uiPhase == BenchmarkUiPhases.ERROR &&
            state.plan == null &&
            state.runs.isEmpty() &&
            state.activeJob == null -> ScreenPhase.ERROR
        state.uiPhase == BenchmarkUiPhases.DEGRADED -> ScreenPhase.DEGRADED
        state.uiPhase == BenchmarkUiPhases.LOADING -> ScreenPhase.LOADING
        // Bound Admin VM with no plan yet: still CONTENT so user can Plan (UX-ARCH empty is
        // for no-operable surface; plan CTA is the operable surface).
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.benchmark_title),
        modifier = modifier,
        onRefresh = { scope.launch { viewModel?.refresh() } },
    ) { padding ->
        when (phase) {
            ScreenPhase.LOADING -> LoadingBody(Modifier.padding(padding))
            ScreenPhase.ERROR -> {
                Column(Modifier.padding(padding)) {
                    PhaseBanner(ScreenPhase.ERROR)
                    ErrorBody(
                        message = state.lastError?.message
                            ?: stringResource(R.string.state_error),
                        errorCode = state.lastError?.code?.code,
                        onRetry = { scope.launch { viewModel?.refresh() } },
                    )
                }
            }
            ScreenPhase.EMPTY -> EmptyBody(
                title = stringResource(R.string.benchmark_empty_title),
                body = stringResource(R.string.benchmark_empty_body),
                primaryActionLabel = stringResource(R.string.action_open_modelhub),
                onPrimaryAction = onOpenModelHub,
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
                    if (state.uiPhase == BenchmarkUiPhases.EMPTY &&
                        state.plan == null &&
                        state.runs.isEmpty()
                    ) {
                        item {
                            PhaseBanner(
                                phase = ScreenPhase.EMPTY,
                                message = stringResource(R.string.benchmark_empty_body),
                            )
                        }
                    }
                    item {
                        InfoCard {
                            Text(
                                text = stringResource(R.string.benchmark_honest_cells),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.benchmark_no_supported_claim),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = stringResource(
                                    R.string.benchmark_phase,
                                    state.uiPhase,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            if (density == DensityMode.EXPERT) {
                                Text(
                                    text = stringResource(R.string.benchmark_expert_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    state.degradedReasons.takeIf { it.isNotEmpty() }?.let { reasons ->
                        item {
                            SectionHeader(stringResource(R.string.state_degraded))
                        }
                        item {
                            InfoCard {
                                reasons.forEach { r ->
                                    Text("• $r", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    item {
                        SectionHeader(stringResource(R.string.benchmark_plan_section))
                    }
                    item {
                        InfoCard {
                            Text(
                                text = stringResource(R.string.benchmark_plan_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PrimaryActionButton(
                                label = stringResource(R.string.benchmark_plan_quick_smoke),
                                onClick = {
                                    if (viewModel == null) return@PrimaryActionButton
                                    // Placeholder revision until ModelHub selection is bound;
                                    // plan remains pure (ADR-002) and does not invent SUPPORTED.
                                    val rev = "0".repeat(64)
                                    val host = MeasurementProfileFactory.HostDimensions(
                                        engineBuildId = "ui-admin-projection",
                                        backend = "cpu",
                                        modelRevisionId = rev,
                                        deviceExecutionFingerprint =
                                            "android-ui-${android.os.Build.FINGERPRINT.take(32)
                                                .replace(Regex("[^a-zA-Z0-9._-]"), "_")}",
                                        pageSizeBytes = 16_384,
                                    )
                                    val profile = MeasurementProfileFactory.quickSmoke(host)
                                    viewModel.plan(
                                        PlanBenchmarkSpec(
                                            profile = profile,
                                            templateId = BenchmarkScenarioTemplate.QUICK_SMOKE.templateId,
                                            iterations = profile.sampleCount,
                                            fallbackPolicy = FallbackPolicy.NONE.name,
                                        ),
                                    )
                                },
                                enabled = viewModel != null,
                                contentDescription = stringResource(
                                    R.string.benchmark_plan_quick_smoke_cd,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                            )
                            SecondaryActionButton(
                                label = stringResource(R.string.action_open_dashboard),
                                onClick = onOpenDashboard,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                    state.plan?.let { plan ->
                        item {
                            SectionHeader(stringResource(R.string.benchmark_preview_section))
                        }
                        item {
                            val planCd = stringResource(
                                R.string.benchmark_plan_cd,
                                plan.profileId.take(12),
                            )
                            InfoCard(
                                modifier = Modifier.semantics { contentDescription = planCd },
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.benchmark_profile_id,
                                        plan.profileId.take(16) + "…",
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.benchmark_fallback_policy,
                                        plan.fallbackPolicy,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.benchmark_samples,
                                        plan.estimatedSampleCount,
                                        plan.estimatedWarmupCount,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (density == DensityMode.EXPERT) {
                                    plan.dimensions.take(12).forEach { dim ->
                                        Text(
                                            text = "${dim.key}: ${dim.value}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                PrimaryActionButton(
                                    label = stringResource(R.string.benchmark_start),
                                    onClick = {
                                        if (viewModel == null) return@PrimaryActionButton
                                        scope.launch {
                                            val jobId = UUID.randomUUID().toString()
                                            val runId = UUID.randomUUID().toString()
                                            val idem = "bench-start-$jobId"
                                            val digest = IdentityHashing.sha256Hex(
                                                "$jobId|$runId|${plan.profileId}",
                                            )
                                            viewModel.start(
                                                StartBenchmarkSpec(
                                                    jobId = jobId,
                                                    runId = runId,
                                                    profile = plan.profile,
                                                    iterations = plan.iterations,
                                                    fallbackPolicy = plan.fallbackPolicy,
                                                    command = BenchmarkCommandIdentity(
                                                        commandId = UUID.randomUUID().toString(),
                                                        idempotencyKey = idem,
                                                        canonicalInputDigest = digest,
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                    enabled = viewModel != null &&
                                        state.activeJob?.state !in setOf("QUEUED", "RUNNING"),
                                    contentDescription = stringResource(R.string.benchmark_start_cd),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    state.activeJob?.let { job ->
                        item {
                            SectionHeader(stringResource(R.string.benchmark_active_job))
                        }
                        item {
                            val jobCd = stringResource(
                                R.string.benchmark_job_cd,
                                job.jobId,
                                job.state,
                            )
                            InfoCard(
                                modifier = Modifier.semantics { contentDescription = jobCd },
                            ) {
                                Text(
                                    text = stringResource(R.string.label_job_id, job.jobId),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = job.state,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                job.runId?.let {
                                    Text(
                                        text = stringResource(R.string.benchmark_run_id, it.take(16) + "…"),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                if (job.state in setOf("QUEUED", "RUNNING", "PAUSED")) {
                                    SecondaryActionButton(
                                        label = stringResource(R.string.action_cancel),
                                        onClick = {
                                            scope.launch {
                                                val cmdId = UUID.randomUUID().toString()
                                                val digest = IdentityHashing.sha256Hex(
                                                    "cancel|${job.jobId}|$cmdId",
                                                )
                                                viewModel?.cancel(
                                                    CancelBenchmarkSpec(
                                                        jobId = job.jobId,
                                                        command = BenchmarkCommandIdentity(
                                                            commandId = cmdId,
                                                            idempotencyKey = "bench-cancel-${job.jobId}",
                                                            canonicalInputDigest = digest,
                                                        ),
                                                    ),
                                                )
                                            }
                                        },
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (state.runs.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.benchmark_runs_section))
                        }
                        items(state.runs, key = { it.runId }) { run ->
                            val runCd = stringResource(
                                R.string.benchmark_run_cd,
                                run.runId,
                                run.outcome,
                            )
                            InfoCard(
                                modifier = Modifier.semantics { contentDescription = runCd },
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.benchmark_run_outcome,
                                        run.outcome,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.benchmark_run_seq,
                                        run.runSeq,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (density == DensityMode.EXPERT) {
                                    Text(
                                        text = stringResource(
                                            R.string.label_canonical_id,
                                            run.runId.take(16) + "…",
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.benchmark_profile_id,
                                            run.profileId.take(16) + "…",
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    state.lastError?.let { err ->
                        item {
                            InfoCard {
                                Text(
                                    text = stringResource(
                                        R.string.label_error_code,
                                        err.code.code,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                err.message?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
