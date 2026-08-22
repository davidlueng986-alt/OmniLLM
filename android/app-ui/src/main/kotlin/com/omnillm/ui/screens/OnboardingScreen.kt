package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.features.autosetup.domain.UserSetupPreferences
import com.omnillm.features.autosetup.viewmodel.AutoSetupUiState
import com.omnillm.features.autosetup.viewmodel.AutoSetupViewModel
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
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: AutoSetupViewModel?,
    onOpenPlayground: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenModelHub: () -> Unit,
    onSetupCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val scope = rememberCoroutineScope()

    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.uiState()) { listener ->
            viewModel.observe(listener)
        }.value
    } else {
        null
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state?.busy == true && state.phase == "IDLE" -> ScreenPhase.LOADING
        state?.phase == "FAILED" -> ScreenPhase.ERROR
        state?.phase == "CANCELLED" -> ScreenPhase.EMPTY
        state?.busy == true -> ScreenPhase.LOADING
        state == null -> ScreenPhase.EMPTY
        state.phase == "IDLE" -> ScreenPhase.EMPTY
        state.phase == "COMPLETED" -> ScreenPhase.CONTENT
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.onboarding_title),
        modifier = modifier,
        onRefresh = null,
    ) { padding ->
        when {
            viewModel == null || state == null -> EmptyBody(
                title = stringResource(R.string.onboarding_empty),
                body = stringResource(R.string.onboarding_empty_body),
                modifier = Modifier.padding(padding),
            )
            phase == ScreenPhase.ERROR -> {
                Column(Modifier.padding(padding)) {
                    PhaseBanner(ScreenPhase.ERROR)
                    ErrorBody(
                        message = state.errorMessage
                            ?: stringResource(R.string.onboarding_failed),
                        errorCode = state.errorCode,
                        onRetry = { viewModel.onReset() },
                    )
                    SecondaryActionButton(
                        label = stringResource(R.string.onboarding_reset),
                        onClick = { viewModel.onReset() },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            phase == ScreenPhase.LOADING && state.phase == "IDLE" ->
                LoadingBody(Modifier.padding(padding))
            else -> {
                ScrollableScreenColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    item {
                        InfoCard {
                            Text(
                                stringResource(R.string.onboarding_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(
                                    R.string.onboarding_phase,
                                    state.phaseLabelKey,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            state.progressRatio?.let { ratio ->
                                Text(
                                    stringResource(R.string.onboarding_progress),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                LinearProgressIndicator(
                                    progress = { ratio.toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                )
                            }
                            if (state.busy) {
                                Text(
                                    state.busyLabel ?: stringResource(R.string.state_loading),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    state.humanDeviceSummary?.let { summary ->
                        item {
                            SectionHeader(stringResource(R.string.onboarding_device_summary))
                        }
                        item {
                            InfoCard {
                                Text(summary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    state.topCandidateName?.let { name ->
                        item {
                            InfoCard {
                                Text(
                                    stringResource(R.string.onboarding_top_candidate, name),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (state.topReasonCodes.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.onboarding_reasons),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    state.topReasonCodes.forEach {
                                        Text("• $it", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                if (density == DensityMode.EXPERT) {
                                    state.topCandidateId?.let {
                                        Text(
                                            stringResource(R.string.label_canonical_id, it),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    state.configurationSummary?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    if (state.minimalAdjustments.isNotEmpty()) {
                        item {
                            SectionHeader(stringResource(R.string.onboarding_adjustments))
                        }
                        items(state.minimalAdjustments) { adj ->
                            InfoCard {
                                Text(adj, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    item {
                        InfoCard {
                            SetupActions(
                                state = state,
                                onDiscover = {
                                    scope.launch { viewModel.onDiscoverDevice() }
                                },
                                onRecommend = {
                                    scope.launch {
                                        viewModel.onRecommend(
                                            UserSetupPreferences(
                                                targetOperation = CapabilityId.TEXT_GENERATION,
                                            ),
                                        )
                                    }
                                },
                                onReset = { viewModel.onReset() },
                                onCancel = {
                                    scope.launch { viewModel.onCancel() }
                                },
                                onOpenPlayground = {
                                    onSetupCompleted()
                                    onOpenPlayground()
                                },
                                onOpenDashboard = {
                                    onSetupCompleted()
                                    onOpenDashboard()
                                },
                                onOpenModelHub = onOpenModelHub,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupActions(
    state: AutoSetupUiState,
    onDiscover: () -> Unit,
    onRecommend: () -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onOpenPlayground: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenModelHub: () -> Unit,
) {
    state.actions.forEach { action ->
        when (action) {
            "discover-device" -> PrimaryActionButton(
                label = stringResource(R.string.onboarding_discover),
                onClick = onDiscover,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "recommend", "recommend-again", "set-preferences" -> PrimaryActionButton(
                label = stringResource(R.string.onboarding_recommend),
                onClick = onRecommend,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "open-playground" -> PrimaryActionButton(
                label = stringResource(R.string.action_open_playground),
                onClick = onOpenPlayground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "submit-first-inference" -> PrimaryActionButton(
                label = stringResource(R.string.onboarding_first_inference),
                onClick = onOpenPlayground,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "open-dashboard" -> SecondaryActionButton(
                label = stringResource(R.string.action_open_dashboard),
                onClick = onOpenDashboard,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "reset" -> SecondaryActionButton(
                label = stringResource(R.string.onboarding_reset),
                onClick = onReset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "start-download", "start-import", "select-another" -> SecondaryActionButton(
                label = stringResource(R.string.action_open_modelhub),
                onClick = onOpenModelHub,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            "cancel" -> SecondaryActionButton(
                label = stringResource(R.string.action_cancel),
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            else -> {
                // wait / view-reason etc. — informational only
            }
        }
    }
}
