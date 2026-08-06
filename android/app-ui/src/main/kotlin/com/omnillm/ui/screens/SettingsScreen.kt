package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.features.admin.viewmodel.AdminSettingsViewModel
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.runtime.policy.SettingValue
import com.omnillm.ui.R
import com.omnillm.ui.components.DensityToggleChip
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.LoadingBody
import com.omnillm.ui.components.OmniScreenScaffold
import com.omnillm.ui.components.ScreenPhase
import com.omnillm.ui.components.ScrollableScreenColumn
import com.omnillm.ui.components.SecondaryActionButton
import com.omnillm.ui.components.SectionHeader
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode
import com.omnillm.ui.util.observeListenerAsState
import java.util.UUID

@Composable
fun SettingsScreen(
    viewModel: AdminSettingsViewModel?,
    densityMode: DensityMode,
    onToggleDensity: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenContentReport: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.state) { listener ->
            viewModel.addListener(listener)
            return@observeListenerAsState { viewModel.removeListener(listener) }
        }.value
    } else {
        AdminSettingsViewModel.State()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refresh()
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state.loading && state.screen == null -> ScreenPhase.LOADING
        state.error != null && state.screen == null -> ScreenPhase.ERROR
        state.screen == null -> ScreenPhase.EMPTY
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.settings_title),
        modifier = modifier,
        onRefresh = { viewModel?.refresh() },
    ) { padding ->
        when (phase) {
            ScreenPhase.LOADING -> LoadingBody(Modifier.padding(padding))
            ScreenPhase.ERROR -> {
                Column(Modifier.padding(padding)) {
                    ErrorBody(
                        message = state.error?.message ?: stringResource(R.string.state_error),
                        errorCode = state.error?.code?.code,
                        onRetry = { viewModel?.refresh() },
                    )
                }
            }
            ScreenPhase.EMPTY -> EmptyBody(
                title = stringResource(R.string.settings_empty),
                body = stringResource(R.string.runtime_disconnected_body),
                modifier = Modifier.padding(padding),
            )
            else -> {
                val screen = state.screen
                ScrollableScreenColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    item {
                        SectionHeader(stringResource(R.string.settings_density_section))
                    }
                    item {
                        InfoCard {
                            Text(
                                if (densityMode == DensityMode.STANDARD) {
                                    stringResource(R.string.density_standard_desc)
                                } else {
                                    stringResource(R.string.density_expert_desc)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            DensityToggleChip(
                                densityMode = densityMode,
                                onToggle = onToggleDensity,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    item {
                        SectionHeader(stringResource(R.string.settings_automation))
                    }
                    if (screen != null) {
                        item {
                            InfoCard {
                                Text(
                                    stringResource(
                                        R.string.settings_resource_version,
                                        screen.resourceVersion,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                state.lastCommand?.let { cmd ->
                                    Text(
                                        stringResource(
                                            R.string.settings_last_command,
                                            cmd.state,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        items(screen.fields, key = { it.key }) { field ->
                            val cd = stringResource(
                                R.string.settings_field_cd,
                                field.key,
                                field.displayValue,
                            )
                            val isExploratory = field.key == "runtime.exploratoryExecuteEnabled"
                            val isBoolField = field.valueType.equals("BOOLEAN", ignoreCase = true) ||
                                field.displayValue.equals("true", ignoreCase = true) ||
                                field.displayValue.equals("false", ignoreCase = true)
                            InfoCard(
                                modifier = Modifier.semantics { contentDescription = cd },
                            ) {
                                if (isExploratory || (isBoolField && field.key.startsWith("runtime."))) {
                                    val checked = field.displayValue.equals("true", ignoreCase = true)
                                    val toggleCd = if (isExploratory) {
                                        "toggle_runtime_exploratoryExecuteEnabled"
                                    } else {
                                        "toggle_${field.key}"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .semantics { contentDescription = toggleCd },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp),
                                        ) {
                                            Text(field.key, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                if (isExploratory) {
                                                    "Enable CONDITIONAL exploratory generate when native is attached (never invents SUPPORTED)."
                                                } else {
                                                    field.displayValue
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Switch(
                                            checked = checked,
                                            onCheckedChange = { next ->
                                                if (viewModel == null) return@Switch
                                                val rv = screen.resourceVersion
                                                val cmd = AdminCommandRequest(
                                                    commandId = UUID.randomUUID().toString(),
                                                    idempotencyKey = "settings-${field.key}-$next-$rv",
                                                    canonicalInputDigest = IdentityHashing.sha256Hex(
                                                        "patch|${field.key}|$next|$rv",
                                                    ),
                                                    expectedVersion = rv,
                                                )
                                                viewModel.applyPatch(
                                                    cmd,
                                                    mapOf(field.key to SettingValue.BoolValue(next)),
                                                )
                                            },
                                        )
                                    }
                                } else {
                                    Text(field.key, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        field.displayValue,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                if (density == DensityMode.EXPERT) {
                                    Text(
                                        field.valueType,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SectionHeader(stringResource(R.string.settings_advanced_engine))
                    }
                    item {
                        InfoCard {
                            Text(
                                stringResource(R.string.settings_advanced_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    item {
                        SectionHeader(stringResource(R.string.settings_diagnostics_export))
                    }
                    item {
                        InfoCard {
                            SecondaryActionButton(
                                label = stringResource(R.string.action_open_diagnostics),
                                onClick = onOpenDiagnostics,
                            )
                        }
                    }
                    item {
                        SectionHeader(stringResource(R.string.settings_privacy))
                    }
                    item {
                        InfoCard {
                            Text(
                                stringResource(R.string.content_report_not_telemetry),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.content_report_privacy_disclosure),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                stringResource(R.string.content_report_in_app_required),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            SecondaryActionButton(
                                label = stringResource(R.string.nav_content_report),
                                onClick = onOpenContentReport,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
