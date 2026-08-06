package com.omnillm.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.ui.R
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode

/** Minimum touch target (UX-A11Y-I18N §3). */
val MinTouchTarget = 48.dp

enum class ScreenPhase {
    LOADING,
    EMPTY,
    CONTENT,
    ERROR,
    DEGRADED,
    RECOVERING,
    PARTIAL,
    BLOCKED,
    STALE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmniScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(MinTouchTarget)
                                .semantics {
                                    contentDescription = /* resolved below via string */ "back"
                                },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(MinTouchTarget),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                    actions()
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun PhaseBanner(
    phase: ScreenPhase,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    if (phase == ScreenPhase.CONTENT) return
    val (icon, defaultMsg) = when (phase) {
        ScreenPhase.LOADING -> Icons.Filled.HourglassEmpty to stringResource(R.string.state_loading)
        ScreenPhase.EMPTY -> Icons.Filled.Info to stringResource(R.string.state_empty)
        ScreenPhase.ERROR -> Icons.Filled.ErrorOutline to stringResource(R.string.state_error)
        ScreenPhase.DEGRADED -> Icons.Filled.WarningAmber to stringResource(R.string.state_degraded)
        ScreenPhase.RECOVERING -> Icons.Filled.HourglassEmpty to stringResource(R.string.state_recovering)
        ScreenPhase.PARTIAL -> Icons.Filled.Info to stringResource(R.string.state_partial)
        ScreenPhase.BLOCKED -> Icons.Filled.WarningAmber to stringResource(R.string.state_blocked)
        ScreenPhase.STALE -> Icons.Filled.Info to stringResource(R.string.state_stale)
        ScreenPhase.CONTENT -> Icons.Filled.Info to ""
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (phase) {
                ScreenPhase.ERROR, ScreenPhase.BLOCKED ->
                    MaterialTheme.colorScheme.errorContainer
                ScreenPhase.DEGRADED, ScreenPhase.STALE, ScreenPhase.RECOVERING ->
                    MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                text = message ?: defaultMsg,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun LoadingBody(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.state_loading),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
fun EmptyBody(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (primaryActionLabel != null && onPrimaryAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) {
                Text(primaryActionLabel)
            }
        }
    }
}

@Composable
fun ErrorBody(
    message: String,
    errorCode: String? = null,
    stage: String? = null,
    affected: String? = null,
    recovered: Boolean? = null,
    onRetry: (() -> Unit)? = null,
    onDiagnostics: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(text = message, style = MaterialTheme.typography.titleMedium)
        errorCode?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.label_error_code, it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        stage?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.error_phase, it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        affected?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.error_affected, it),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        recovered?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (it) {
                    stringResource(R.string.error_auto_recovered)
                } else {
                    stringResource(R.string.error_not_recovered)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onRetry != null) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            if (onDiagnostics != null) {
                OutlinedButton(
                    onClick = onDiagnostics,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                ) {
                    Text(stringResource(R.string.action_open_diagnostics))
                }
            }
        }
    }
}

@Composable
fun EvidenceChip(
    label: EvidenceLabel,
    modifier: Modifier = Modifier,
) {
    val text = when (label) {
        EvidenceLabel.MEASURED -> stringResource(R.string.evidence_measured)
        EvidenceLabel.ESTIMATED -> stringResource(R.string.evidence_estimated)
        EvidenceLabel.REPORTED -> stringResource(R.string.evidence_reported)
        EvidenceLabel.LAST_SAMPLED -> stringResource(R.string.evidence_last_sampled)
        EvidenceLabel.UNKNOWN -> stringResource(R.string.evidence_unknown)
    }
    val cd = stringResource(R.string.evidence_cd, text)
    SuggestionChip(
        onClick = {},
        enabled = false,
        label = { Text(text) },
        modifier = modifier.semantics { contentDescription = cd },
    )
}

/**
 * Metric display: UNKNOWN never renders as 0 (UX-STATE §6 / FEAT-DASHBOARD §3).
 */
@Composable
fun EvidencedMetricRow(
    name: String,
    displayValue: Double?,
    unit: String,
    evidenceLabel: EvidenceLabel,
    allowsNumericDisplay: Boolean,
    source: String? = null,
    sampledAtLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val valueText = if (!allowsNumericDisplay || displayValue == null ||
        evidenceLabel == EvidenceLabel.UNKNOWN
    ) {
        stringResource(R.string.metric_unknown_value)
    } else {
        stringResource(R.string.metric_value_with_unit, formatMetric(displayValue), unit)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EvidenceChip(label = evidenceLabel)
            if (density == DensityMode.EXPERT) {
                source?.let {
                    Text(
                        text = stringResource(R.string.metric_source, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sampledAtLabel?.let {
                    Text(
                        text = stringResource(R.string.label_sampled_at, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatMetric(value: Double): String {
    return if (value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format("%.2f", value)
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun PrimaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(label)
    }
}

@Composable
fun SecondaryActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MinTouchTarget),
    ) {
        Text(label)
    }
}

@Composable
fun DensityToggleChip(
    densityMode: DensityMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (densityMode) {
        DensityMode.STANDARD -> stringResource(R.string.density_standard)
        DensityMode.EXPERT -> stringResource(R.string.density_expert)
    }
    val cd = stringResource(R.string.density_toggle_cd, label)
    FilterChip(
        selected = densityMode == DensityMode.EXPERT,
        onClick = onToggle,
        label = { Text(label) },
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .semantics { contentDescription = cd },
    )
}

@Composable
fun TrustDimensionRow(
    sourceLabel: String?,
    compatibilityLabel: String?,
    placementLabel: String?,
    licenseLabel: String?,
    modifier: Modifier = Modifier,
) {
    // Four separate dimensions — never a single green "safe" badge (UX-SAFETY-COPY).
    Column(modifier = modifier.fillMaxWidth()) {
        sourceLabel?.let {
            LabeledValue(stringResource(R.string.trust_dim_source), it)
        }
        compatibilityLabel?.let {
            LabeledValue(stringResource(R.string.trust_dim_compatibility), it)
        }
        placementLabel?.let {
            LabeledValue(stringResource(R.string.trust_dim_placement), it)
        }
        licenseLabel?.let {
            LabeledValue(stringResource(R.string.trust_dim_license), it)
        }
    }
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
        )
    }
}

@Composable
fun PlacementRiskCopy(
    placementClass: String?,
    modifier: Modifier = Modifier,
) {
    val text = when (placementClass?.uppercase()) {
        "ISOLATED_CPU", "ISOLATED", "PARSER_ISOLATED" ->
            stringResource(R.string.placement_isolated_cpu)
        "SAME_UID", "SAME_UID_WORKER", "WORKER" ->
            stringResource(R.string.placement_same_uid)
        "COMPANION", "DIFFERENT_PACKAGE", "ACCELERATED_SANDBOX" ->
            stringResource(R.string.placement_companion)
        else -> null
    } ?: return
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun ScrollableScreenColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        content = content,
    )
}

@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content,
            )
        },
    )
}

