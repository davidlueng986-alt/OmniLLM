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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.features.lan.viewmodel.LanAccessUiState
import com.omnillm.features.lan.viewmodel.LanAccessViewModel
import com.omnillm.features.server.viewmodel.DeveloperServerUiState
import com.omnillm.features.server.viewmodel.DeveloperServerViewModel
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
import kotlinx.coroutines.launch

private enum class ServerTab { LOCAL, LAN, CLIENTS, TOKENS }

/** Deep-link entry for Server & Clients / LAN destinations (UX-IA). */
enum class ServerClientsInitialTab {
    LOCAL,
    LAN,
    CLIENTS,
    TOKENS,
}

@Composable
fun ServerClientsScreen(
    serverVm: DeveloperServerViewModel?,
    lanVm: LanAccessViewModel?,
    modifier: Modifier = Modifier,
    initialTab: ServerClientsInitialTab = ServerClientsInitialTab.LOCAL,
) {
    val density = LocalDensityMode.current
    val scope = rememberCoroutineScope()
    val tabs = ServerTab.entries
    var tabIndex by remember {
        mutableIntStateOf(
            when (initialTab) {
                ServerClientsInitialTab.LOCAL -> 0
                ServerClientsInitialTab.LAN -> 1
                ServerClientsInitialTab.CLIENTS -> 2
                ServerClientsInitialTab.TOKENS -> 3
            }.coerceIn(0, tabs.lastIndex),
        )
    }

    val serverState = if (serverVm != null) {
        observeListenerAsState(serverVm.uiState()) { listener ->
            serverVm.observe(listener)
        }.value
    } else {
        null
    }

    val lanState = if (lanVm != null) {
        observeListenerAsState(lanVm.uiState()) { listener ->
            lanVm.observe(listener)
        }.value
    } else {
        null
    }

    LaunchedEffect(serverVm, lanVm) {
        serverVm?.onRefresh()
        lanVm?.onRefresh()
    }

    val phase = when {
        serverVm == null && lanVm == null -> ScreenPhase.EMPTY
        serverState?.isLoading == true || lanState?.busy == true -> ScreenPhase.LOADING
        serverState?.isError == true -> ScreenPhase.ERROR
        serverState?.isDegraded == true -> ScreenPhase.DEGRADED
        serverState?.isEmpty == true -> ScreenPhase.EMPTY
        serverState == null -> ScreenPhase.EMPTY
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.server_title),
        modifier = modifier,
        onRefresh = {
            scope.launch {
                serverVm?.onRefresh()
                lanVm?.onRefresh()
            }
        },
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
                                    ServerTab.LOCAL ->
                                        stringResource(R.string.server_local_endpoint)
                                    ServerTab.LAN -> stringResource(R.string.server_lan)
                                    ServerTab.CLIENTS ->
                                        stringResource(R.string.server_aidl_clients)
                                    ServerTab.TOKENS -> stringResource(R.string.server_tokens)
                                },
                            )
                        },
                    )
                }
            }
            when {
                phase == ScreenPhase.LOADING && serverState == null -> LoadingBody()
                phase == ScreenPhase.ERROR -> ErrorBody(
                    message = serverState?.error?.message
                        ?: stringResource(R.string.state_error),
                    errorCode = serverState?.error?.code?.code,
                    onRetry = {
                        scope.launch { serverVm?.onRefresh() }
                    },
                )
                phase == ScreenPhase.EMPTY && serverState == null -> EmptyBody(
                    title = stringResource(R.string.server_empty),
                    body = stringResource(R.string.server_empty_body),
                )
                else -> {
                    if (phase == ScreenPhase.DEGRADED) {
                        PhaseBanner(ScreenPhase.DEGRADED)
                    }
                    when (tabs[tabIndex]) {
                        ServerTab.LOCAL -> LocalEndpointPane(serverState, density) {
                            scope.launch { serverVm?.onEnsureLoopback() }
                        }
                        ServerTab.LAN -> LanPane(
                            state = lanState,
                            density = density,
                            lanVm = lanVm,
                            scope = scope,
                        )
                        ServerTab.CLIENTS -> ClientsPane(serverState)
                        ServerTab.TOKENS -> TokensPane(serverState, density) {
                            serverVm?.onAcknowledgeTokenReceipt()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalEndpointPane(
    state: DeveloperServerUiState?,
    density: DensityMode,
    onEnsureLoopback: () -> Unit,
) {
    val snap = state?.snapshot
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        item { SectionHeader(stringResource(R.string.server_local_endpoint)) }
        item {
            InfoCard {
                val loopback = snap?.loopback
                if (loopback == null || !loopback.running) {
                    Text(
                        stringResource(R.string.server_loopback_not_running),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PrimaryActionButton(
                        label = stringResource(R.string.server_ensure_loopback),
                        onClick = onEnsureLoopback,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.server_loopback_url, loopback.baseUrl),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.home_runtime_state, loopback.runtimeState),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (loopback.degradedReasons.isNotEmpty()) {
                        Text(
                            stringResource(R.string.server_degraded_reasons),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        loopback.degradedReasons.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (density == DensityMode.EXPERT) {
                        Text(
                            "rv=${loopback.resourceVersion}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        item { SectionHeader(stringResource(R.string.server_sdk_samples)) }
        items(state?.samples.orEmpty()) { sample ->
            InfoCard {
                Text(sample.titleKey, style = MaterialTheme.typography.titleSmall)
                Text(sample.transport.name, style = MaterialTheme.typography.labelMedium)
                if (density == DensityMode.EXPERT) {
                    Text(sample.bodyTemplate, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LanPane(
    state: LanAccessUiState?,
    density: DensityMode,
    lanVm: LanAccessViewModel?,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        item {
            InfoCard {
                Text(
                    stringResource(R.string.server_lan_default_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val enabled = state?.lanEnabled == true
                Text(
                    if (enabled) {
                        stringResource(R.string.server_lan_enabled)
                    } else {
                        stringResource(R.string.server_lan_disabled)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                state?.serviceState?.let {
                    Text(
                        stringResource(R.string.home_runtime_state, it),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state?.serverSpkiSha256?.let {
                    Text(
                        stringResource(R.string.server_lan_fingerprint, it.take(16) + "…"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state?.connectionEpoch?.let {
                    Text(
                        stringResource(R.string.server_lan_epoch, it),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    stringResource(R.string.server_lan_clients, state?.clientCount ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                )
                state?.error?.let { err ->
                    Text(
                        err.message ?: stringResource(R.string.state_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (density == DensityMode.EXPERT) {
                    state?.serverLocator?.let {
                        Text(
                            stringResource(R.string.server_lan_locator, it),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (lanVm != null) {
                    if (!enabled) {
                        PrimaryActionButton(
                            label = stringResource(R.string.server_lan_enable),
                            onClick = {
                                scope.launch {
                                    lanVm.onEnable(
                                        com.omnillm.features.lan.api.EnableLanSpec(
                                            command = com.omnillm.features.lan.api.LanCommandIdentity(
                                                commandId = java.util.UUID.randomUUID().toString(),
                                                idempotencyKey = "lan-enable-${System.currentTimeMillis()}",
                                            ),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        SecondaryActionButton(
                            label = stringResource(R.string.server_lan_disable),
                            onClick = {
                                scope.launch {
                                    lanVm.onDisable(
                                        com.omnillm.features.lan.api.DisableLanSpec(
                                            command = com.omnillm.features.lan.api.LanCommandIdentity(
                                                commandId = java.util.UUID.randomUUID().toString(),
                                                idempotencyKey = "lan-disable-${System.currentTimeMillis()}",
                                            ),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        PrimaryActionButton(
                            label = stringResource(R.string.server_lan_pair),
                            onClick = {
                                scope.launch {
                                    val chId = java.util.UUID.randomUUID().toString()
                                    lanVm.onCreateChallenge(
                                        com.omnillm.features.lan.api.CreatePairingChallengeSpec(
                                            command = com.omnillm.features.lan.api.LanCommandIdentity(
                                                commandId = java.util.UUID.randomUUID().toString(),
                                                idempotencyKey = "lan-pair-${System.currentTimeMillis()}",
                                            ),
                                            challengeId = chId,
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
        if (state?.hasPendingChallenge == true && state.qrPayload != null) {
            item {
                SectionHeader(stringResource(R.string.server_lan_qr_section))
            }
            item {
                InfoCard {
                    Text(
                        stringResource(R.string.server_lan_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.qrPayload!!,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (lanVm != null &&
                        state.pendingChallengeId != null &&
                        state.pendingChallengeState == "PENDING"
                    ) {
                        SecondaryActionButton(
                            label = stringResource(R.string.server_lan_approve_pair),
                            onClick = {
                                scope.launch {
                                    lanVm.onApproveChallenge(
                                        com.omnillm.features.lan.api.ApprovePairingChallengeSpec(
                                            command = com.omnillm.features.lan.api.LanCommandIdentity(
                                                commandId = java.util.UUID.randomUUID().toString(),
                                                idempotencyKey = "lan-approve-${System.currentTimeMillis()}",
                                            ),
                                            challengeId = state.pendingChallengeId!!,
                                            approvedScopes = state.pendingChallengeScopes.ifEmpty {
                                                com.omnillm.features.lan.domain.LanScopePolicy.DEFAULT_INFER_SCOPES
                                            },
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
        if (state?.hasPendingToken == true) {
            item {
                InfoCard {
                    Text(
                        stringResource(R.string.server_token_once),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    PrimaryActionButton(
                        label = stringResource(R.string.server_token_ack),
                        onClick = { lanVm?.onAcknowledgeTokenReceipt() },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        if (!state?.clients.isNullOrEmpty()) {
            item { SectionHeader(stringResource(R.string.server_lan_clients_section)) }
            items(state!!.clients, key = { it.clientId }) { client ->
                InfoCard {
                    Text(client.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(client.state, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        client.scopes.joinToString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (client.state == "ACTIVE" && lanVm != null) {
                        SecondaryActionButton(
                            label = stringResource(R.string.server_revoke_client),
                            onClick = {
                                scope.launch {
                                    lanVm.onRevokeClient(
                                        com.omnillm.features.lan.api.RevokeLanClientSpec(
                                            command = com.omnillm.features.lan.api.LanCommandIdentity(
                                                commandId = java.util.UUID.randomUUID().toString(),
                                                idempotencyKey = "lan-revoke-${System.currentTimeMillis()}",
                                            ),
                                            clientId = client.clientId,
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
    }
}

@Composable
private fun ClientsPane(state: DeveloperServerUiState?) {
    val clients = state?.snapshot?.clients.orEmpty()
    if (clients.isEmpty()) {
        EmptyBody(
            title = stringResource(R.string.server_empty),
            body = stringResource(R.string.server_empty_body),
        )
        return
    }
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        items(clients, key = { it.clientId }) { client ->
            val cd = stringResource(
                R.string.server_client_row_cd,
                client.displayName,
                client.state,
            )
            InfoCard(modifier = Modifier.semantics { contentDescription = cd }) {
                Text(client.displayName, style = MaterialTheme.typography.titleSmall)
                Text(client.state, style = MaterialTheme.typography.bodyMedium)
                Text(
                    client.scopes.joinToString(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TokensPane(
    state: DeveloperServerUiState?,
    density: DensityMode,
    onAckReceipt: () -> Unit,
) {
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        state?.snapshot?.pendingTokenReceipt?.let { receipt ->
            item {
                InfoCard {
                    Text(
                        stringResource(R.string.server_token_once),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        receipt.tokenPlaintext,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    PrimaryActionButton(
                        label = stringResource(R.string.server_token_ack),
                        onClick = onAckReceipt,
                    )
                }
            }
        }
        val tokens = state?.snapshot?.tokens.orEmpty()
        items(tokens, key = { it.tokenId }) { token ->
            val cd = stringResource(R.string.server_token_row_cd, token.tokenId, token.state)
            InfoCard(modifier = Modifier.semantics { contentDescription = cd }) {
                Text(token.state, style = MaterialTheme.typography.titleSmall)
                Text(token.scopes.joinToString(), style = MaterialTheme.typography.bodySmall)
                if (density == DensityMode.EXPERT) {
                    Text(
                        stringResource(R.string.label_canonical_id, token.tokenId),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
