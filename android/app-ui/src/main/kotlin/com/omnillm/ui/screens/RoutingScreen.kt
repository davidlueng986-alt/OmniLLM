package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.routing.viewmodel.RoutingViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.OmniScreenScaffold
import com.omnillm.ui.components.ScrollableScreenColumn
import com.omnillm.ui.components.SectionHeader
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode
import com.omnillm.ui.util.observeListenerAsState

/**
 * Expert routing controls (FEAT-ROUTING): FallbackPolicy NONE / SAME_REVISION_ONLY / ALLOW_LIST.
 * No silent cross-revision — ALLOW_LIST requires explicit allowlist.
 * INV-001: projections only; never touches Orchestrator/DB/engines from UI process.
 */
@Composable
fun RoutingScreen(
    viewModel: RoutingViewModel?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.state) { listener ->
            viewModel.addListener(listener)
            return@observeListenerAsState { viewModel.removeListener(listener) }
        }.value
    } else {
        RoutingViewModel.State()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refreshSnapshot()
        viewModel?.negotiate()
    }

    OmniScreenScaffold(
        title = stringResource(R.string.routing_title),
        modifier = modifier,
        onRefresh = { viewModel?.refreshSnapshot() },
    ) { padding ->
        if (viewModel == null) {
            EmptyBody(
                title = stringResource(R.string.routing_empty),
                body = stringResource(R.string.runtime_disconnected_body),
                modifier = Modifier.padding(padding),
            )
            return@OmniScreenScaffold
        }
        ScrollableScreenColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item { SectionHeader(stringResource(R.string.routing_fallback_section)) }
            item {
                InfoCard {
                    Text(
                        stringResource(R.string.routing_fallback_help),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FallbackPolicy.entries.forEach { policy ->
                        val selected = state.snapshot?.preference?.fallbackPolicy == policy ||
                            (state.snapshot?.preference == null && policy == FallbackPolicy.NONE)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val allowlist = if (policy == FallbackPolicy.ALLOW_LIST) {
                                    state.snapshot?.preference?.revisionAllowlistHex
                                        ?: emptyList()
                                } else {
                                    emptyList()
                                }
                                // ALLOW_LIST without allowlist fails closed — expert must set hex later.
                                if (policy == FallbackPolicy.ALLOW_LIST && allowlist.isEmpty()) {
                                    // Keep prior preference if allowlist empty; show policy intent only.
                                    return@FilterChip
                                }
                                viewModel.validatePreference(
                                    RoutingPreferenceView(
                                        fallbackPolicy = policy,
                                        revisionAllowlistHex = allowlist,
                                        allowedBackends = state.snapshot?.preference?.allowedBackends
                                            ?: emptySet(),
                                        minimumPlacementClass = state.snapshot?.preference
                                            ?.minimumPlacementClass
                                            ?: "PRIVILEGED_TRUSTED",
                                        preferredBackend = state.snapshot?.preference?.preferredBackend,
                                    ),
                                )
                            },
                            label = { Text(policy.name) },
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.routing_no_silent_cross_revision),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            if (density == DensityMode.EXPERT) {
                item { SectionHeader(stringResource(R.string.routing_aliases_section)) }
                item {
                    InfoCard {
                        val aliases = state.snapshot?.aliasEntries.orEmpty()
                        if (aliases.isEmpty()) {
                            Text(
                                stringResource(R.string.routing_aliases_empty),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            aliases.forEach { (alias, rev) ->
                                Text(
                                    "$alias → ${rev.take(12)}…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.routing_last_decision)) }
                item {
                    InfoCard {
                        val d = state.lastDecision
                        if (d == null) {
                            Text(
                                stringResource(R.string.label_not_available),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                "requested=${d.requestedRevisionIdHex.take(12)}…",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            d.actualRouting?.let {
                                Text(
                                    "actual=${it.modelRevisionIdHex.take(12)}… " +
                                        "backend=${it.backend} fallback=${it.usedFallback}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                "viable=${d.viableCount} rejections=${d.rejections.size}",
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
                            err.message ?: err.code.code,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
