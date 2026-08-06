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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.features.contentreport.viewmodel.ContentReportUiState
import com.omnillm.features.contentreport.viewmodel.ContentReportViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.LoadingBody
import com.omnillm.ui.components.OmniScreenScaffold
import com.omnillm.ui.components.PhaseBanner
import com.omnillm.ui.components.ScreenPhase
import com.omnillm.ui.components.ScrollableScreenColumn
import com.omnillm.ui.components.SecondaryActionButton
import com.omnillm.ui.components.SectionHeader
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode
import com.omnillm.ui.util.observeListenerAsState
import kotlinx.coroutines.launch

@Composable
fun ContentReportScreen(
    viewModel: ContentReportViewModel?,
    initialReportId: String? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val scope = rememberCoroutineScope()

    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.uiState()) { listener ->
            viewModel.observe(listener)
        }.value
    } else {
        ContentReportUiState()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refresh()
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state.loading && state.reports.isEmpty() -> ScreenPhase.LOADING
        state.lastError != null && state.reports.isEmpty() -> ScreenPhase.ERROR
        state.reports.isEmpty() && state.activeReview == null -> ScreenPhase.EMPTY
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.content_report_title),
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
                title = stringResource(R.string.content_report_empty),
                body = stringResource(R.string.content_report_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> {
                ScrollableScreenColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    item {
                        InfoCard {
                            Text(
                                stringResource(R.string.content_report_not_telemetry),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.content_report_in_app_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                stringResource(R.string.content_report_privacy_disclosure),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                stringResource(R.string.content_report_not_resolved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                stringResource(
                                    R.string.content_report_network,
                                    if (state.networkAvailable) {
                                        stringResource(R.string.content_report_network_online)
                                    } else {
                                        stringResource(R.string.content_report_network_offline)
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                stringResource(
                                    R.string.content_report_endpoint,
                                    if (state.endpointConfigured) {
                                        stringResource(R.string.content_report_yes)
                                    } else {
                                        stringResource(R.string.content_report_no)
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(
                                    R.string.content_report_stream_kind,
                                    state.dataStreamKind,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (state.isTelemetryStream) {
                                // Product invariant: report stream must not be telemetry.
                                PhaseBanner(
                                    phase = ScreenPhase.BLOCKED,
                                    message = stringResource(R.string.content_report_not_telemetry),
                                )
                            }
                        }
                    }
                    state.activeReview?.let { review ->
                        item {
                            SectionHeader(stringResource(R.string.content_report_review))
                        }
                        item {
                            InfoCard {
                                Text(
                                    stringResource(
                                        R.string.content_report_state,
                                        review.state.name,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                review.payloadPreview.forEach { field ->
                                    Text(
                                        "${field.fieldName}: ${field.displayValue}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (density == DensityMode.EXPERT) {
                                    Text(
                                        review.canonicalPayloadDigest,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SectionHeader(stringResource(R.string.content_report_title))
                    }
                    val reports = if (initialReportId != null) {
                        state.reports.filter { it.reportId == initialReportId }
                            .ifEmpty { state.reports }
                    } else {
                        state.reports
                    }
                    items(reports, key = { it.reportId }) { report ->
                        val cd = stringResource(
                            R.string.content_report_row_cd,
                            report.reportId,
                            report.state.name,
                        )
                        InfoCard(
                            modifier = Modifier.semantics { contentDescription = cd },
                        ) {
                            Text(
                                stringResource(
                                    R.string.content_report_state,
                                    report.labelKey,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                report.state.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!state.networkAvailable &&
                                report.state.name == "QUEUED_OFFLINE"
                            ) {
                                Text(
                                    stringResource(R.string.content_report_queue_offline),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            report.error?.let {
                                Text(
                                    stringResource(R.string.label_error_code, it.code.code),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (density == DensityMode.EXPERT) {
                                Text(
                                    stringResource(
                                        R.string.label_canonical_id,
                                        report.reportId,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                report.receiptId?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            report.actions.forEach { action ->
                                when (action) {
                                    "review-payload" -> SecondaryActionButton(
                                        label = stringResource(R.string.content_report_review),
                                        onClick = { /* beginReview via VM */ },
                                    )
                                    "grant-consent" -> SecondaryActionButton(
                                        label = stringResource(
                                            R.string.content_report_grant_consent,
                                        ),
                                        onClick = { /* never pre-checked; explicit tap */ },
                                    )
                                    "submit" -> SecondaryActionButton(
                                        label = stringResource(R.string.content_report_submit),
                                        onClick = { },
                                    )
                                    "discard" -> SecondaryActionButton(
                                        label = stringResource(R.string.content_report_discard),
                                        onClick = { },
                                    )
                                    "retry" -> SecondaryActionButton(
                                        label = stringResource(R.string.content_report_retry),
                                        onClick = { },
                                    )
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
