package com.omnillm.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.modelhub.api.ModelCard
import com.omnillm.features.modelhub.api.ModelHubCommandIdentity
import com.omnillm.features.modelhub.api.StartDownloadSpec
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import com.omnillm.features.modelhub.viewmodel.ModelHubUiState
import com.omnillm.features.modelhub.viewmodel.ModelHubViewModel
import com.omnillm.ui.R
import com.omnillm.ui.admin.ModelHubLocalImporter
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.InfoCard
import com.omnillm.ui.components.LoadingBody
import com.omnillm.ui.components.MinTouchTarget
import com.omnillm.ui.components.OmniScreenScaffold
import com.omnillm.ui.components.PhaseBanner
import com.omnillm.ui.components.PlacementRiskCopy
import com.omnillm.ui.components.ScreenPhase
import com.omnillm.ui.components.ScrollableScreenColumn
import com.omnillm.ui.components.SecondaryActionButton
import com.omnillm.ui.components.SectionHeader
import com.omnillm.ui.components.TrustDimensionRow
import com.omnillm.ui.theme.DensityMode
import com.omnillm.ui.theme.LocalDensityMode
import com.omnillm.ui.util.collectAsComposeState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ModelHubTab { SUGGESTED, INSTALLED, DOWNLOADS, DETAIL }

@Composable
fun ModelHubScreen(
    viewModel: ModelHubViewModel?,
    localImporter: ModelHubLocalImporter? = null,
    initialInstallationId: String? = null,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = ModelHubTab.entries
    var actionBusy by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    val state: ModelHubUiState = if (viewModel != null) {
        viewModel.state.collectAsComposeState()
    } else {
        ModelHubUiState(loading = false)
    }

    val openDocument = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null || localImporter == null || viewModel == null) {
            actionMessage = "Import cancelled"
            return@rememberLauncherForActivityResult
        }
        actionBusy = true
        actionMessage = "Hashing and importing…"
        scope.launch {
            val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported GGUF"
            val hashed = withContext(Dispatchers.IO) {
                localImporter.hashUri(context, uri, displayName)
            }
            when (hashed) {
                is OmniResult.Err -> {
                    actionMessage = hashed.error.message ?: "hash failed"
                    actionBusy = false
                }
                is OmniResult.Ok -> {
                    val result = withContext(Dispatchers.IO) {
                        localImporter.importHashed(context, hashed.value)
                    }
                    when (result) {
                        is OmniResult.Ok -> {
                            actionMessage = "Import job ${result.value.jobId} → ${result.value.state}"
                            viewModel.refresh()
                            tabIndex = ModelHubTab.INSTALLED.ordinal
                        }
                        is OmniResult.Err -> {
                            actionMessage = result.error.message ?: "import failed"
                        }
                    }
                    actionBusy = false
                }
            }
        }
    }

    LaunchedEffect(viewModel, initialInstallationId) {
        viewModel?.refresh()
        if (initialInstallationId != null) {
            viewModel?.selectInstallation(initialInstallationId)
            tabIndex = ModelHubTab.DETAIL.ordinal
        }
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state.loading && state.snapshot == null -> ScreenPhase.LOADING
        state.lastError != null && state.snapshot == null -> ScreenPhase.ERROR
        state.snapshot == null -> ScreenPhase.EMPTY
        state.blockingIssues.isNotEmpty() -> ScreenPhase.DEGRADED
        else -> ScreenPhase.CONTENT
    }

    OmniScreenScaffold(
        title = stringResource(R.string.modelhub_title),
        modifier = modifier,
        onRefresh = { scope.launch { viewModel?.refresh() } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (phase == ScreenPhase.DEGRADED) {
                PhaseBanner(
                    phase = ScreenPhase.DEGRADED,
                    message = state.blockingIssues.joinToString(),
                )
            }
            // Smoke / lab: one-tap import of pushed e2e GGUF under public Downloads.
            if (localImporter != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    actionMessage?.let { msg ->
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .semantics { contentDescription = "modelhub_action_status" },
                        )
                    }
                    SecondaryActionButton(
                        label = if (actionBusy) "Importing…" else "Import e2e GGUF",
                        enabled = !actionBusy,
                        onClick = {
                            if (actionBusy) return@SecondaryActionButton
                            actionBusy = true
                            actionMessage = "Importing e2e GGUF from Downloads…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    localImporter.importFromPublicE2ePath(context)
                                }
                                actionMessage = when (result) {
                                    is OmniResult.Ok ->
                                        "E2E import ${result.value.jobId} → ${result.value.state}"
                                    is OmniResult.Err ->
                                        result.error.message ?: "e2e import failed"
                                }
                                actionBusy = false
                                viewModel?.refresh()
                                if (result is OmniResult.Ok) {
                                    tabIndex = ModelHubTab.INSTALLED.ordinal
                                }
                            }
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "modelhub_import_e2e_gguf"
                        },
                    )
                }
            }
            ScrollableTabRow(selectedTabIndex = tabIndex) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                        text = {
                            Text(
                                when (tab) {
                                    ModelHubTab.SUGGESTED ->
                                        stringResource(R.string.modelhub_suggested)
                                    ModelHubTab.INSTALLED ->
                                        stringResource(R.string.modelhub_installed)
                                    ModelHubTab.DOWNLOADS ->
                                        stringResource(R.string.modelhub_downloads)
                                    ModelHubTab.DETAIL ->
                                        stringResource(R.string.modelhub_detail)
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
                    onRetry = { scope.launch { viewModel?.refresh() } },
                )
                ScreenPhase.EMPTY -> EmptyBody(
                    title = stringResource(R.string.modelhub_title),
                    body = stringResource(R.string.modelhub_empty_installed),
                    primaryActionLabel = stringResource(R.string.action_start_setup),
                    onPrimaryAction = onOpenSetup,
                )
                else -> when (tabs[tabIndex]) {
                    ModelHubTab.SUGGESTED -> ModelCardList(
                        cards = state.suggested,
                        emptyLabel = stringResource(R.string.modelhub_empty_suggested),
                        density = density,
                        onSelect = { card ->
                            scope.launch {
                                viewModel?.selectRevision(card.modelRevisionId)
                                tabIndex = ModelHubTab.DETAIL.ordinal
                            }
                        },
                    )
                    ModelHubTab.INSTALLED -> ModelCardList(
                        cards = state.installed,
                        emptyLabel = stringResource(R.string.modelhub_empty_installed),
                        density = density,
                        onSelect = { card ->
                            scope.launch {
                                card.installationId?.let { viewModel?.selectInstallation(it) }
                                    ?: viewModel?.selectRevision(card.modelRevisionId)
                                tabIndex = ModelHubTab.DETAIL.ordinal
                            }
                        },
                    )
                    ModelHubTab.DOWNLOADS -> DownloadsList(state)
                    ModelHubTab.DETAIL -> ModelDetailPane(
                        card = state.selectedCard,
                        density = density,
                        actionBusy = actionBusy,
                        actionMessage = actionMessage ?: state.lastError?.message,
                        onAction = { action, card ->
                            when (action) {
                                "DOWNLOAD" -> {
                                    if (viewModel == null) return@ModelDetailPane
                                    actionBusy = true
                                    actionMessage = "Starting offline fixture download…"
                                    scope.launch {
                                        val jobId = UUID.randomUUID().toString()
                                        val installationId = UUID.randomUUID().toString()
                                        val digest = IdentityHashing.sha256Hex(
                                            "download|${card.modelRevisionId}|$jobId",
                                        )
                                        val result = viewModel.download(
                                            StartDownloadSpec(
                                                jobId = jobId,
                                                installationId = installationId,
                                                modelRevisionId = card.modelRevisionId,
                                                artifactPackageId = card.artifactPackageId,
                                                sourceUrl = FixtureArtifact.PINNED_HTTPS_URL,
                                                expectedSha256 = FixtureArtifact.blobIdHex(),
                                                expectedBytes = FixtureArtifact.BYTE_LENGTH,
                                                displayName = card.displayName,
                                                command = ModelHubCommandIdentity(
                                                    commandId = UUID.randomUUID().toString(),
                                                    idempotencyKey = "dl-$jobId",
                                                    canonicalInputDigest = digest,
                                                ),
                                            ),
                                        )
                                        actionMessage = when (result) {
                                            is OmniResult.Ok ->
                                                "Download job ${result.value.jobId} → ${result.value.state}"
                                            is OmniResult.Err ->
                                                result.error.message ?: "download failed"
                                        }
                                        actionBusy = false
                                        viewModel.refresh()
                                        if (result is OmniResult.Ok) {
                                            tabIndex = ModelHubTab.INSTALLED.ordinal
                                        }
                                    }
                                }
                                "IMPORT" -> {
                                    actionMessage = "Pick a GGUF file…"
                                    openDocument.launch(
                                        arrayOf(
                                            "application/octet-stream",
                                            "*/*",
                                        ),
                                    )
                                }
                                "ACCEPT_LICENSE" -> {
                                    if (viewModel == null) return@ModelDetailPane
                                    val licenseDigest: String = card.licenseDigest ?: run {
                                        actionMessage = "License digest missing — cannot accept"
                                        return@ModelDetailPane
                                    }
                                    val installationId: String = card.installationId ?: run {
                                        actionMessage = "Installation id missing — cannot accept"
                                        return@ModelDetailPane
                                    }
                                    actionBusy = true
                                    actionMessage = "Accepting license terms…"
                                    scope.launch {
                                        val commandId = UUID.randomUUID().toString()
                                        val digest = IdentityHashing.sha256Hex(
                                            "accept-license|$licenseDigest|$commandId",
                                        )
                                        val result = viewModel.acceptLicense(
                                            com.omnillm.features.modelhub.api.AcceptLicenseSpec(
                                                installationId = installationId,
                                                licenseDigest = licenseDigest,
                                                sourceAssertion = installationId,
                                                command = ModelHubCommandIdentity(
                                                    commandId = commandId,
                                                    idempotencyKey = "accept-license-${commandId.take(8)}",
                                                    canonicalInputDigest = digest,
                                                ),
                                            ),
                                        )
                                        actionMessage = when (result) {
                                            is OmniResult.Ok -> "License accepted"
                                            is OmniResult.Err ->
                                                result.error.message ?: "license accept failed"
                                        }
                                        actionBusy = false
                                        viewModel.refresh()
                                    }
                                }
                                "LOAD", "UNLOAD" -> {
                                    if (viewModel == null) return@ModelDetailPane
                                    val installationId: String = card.installationId ?: run {
                                        actionMessage = "Installation id missing — cannot load"
                                        return@ModelDetailPane
                                    }
                                    actionBusy = true
                                    actionMessage = if (action == "LOAD") {
                                        "Loading model…"
                                    } else {
                                        "Unloading model…"
                                    }
                                    scope.launch {
                                        val commandId = UUID.randomUUID().toString()
                                        val digest = IdentityHashing.sha256Hex(
                                            "$action|$installationId|$commandId",
                                        )
                                        val cmd = ModelHubCommandIdentity(
                                            commandId = commandId,
                                            idempotencyKey = "mh-$action-${commandId.take(8)}",
                                            canonicalInputDigest = digest,
                                        )
                                        val result = if (action == "LOAD") {
                                            viewModel.load(
                                                com.omnillm.features.modelhub.api.StartLoadSpec(
                                                    installationId = installationId,
                                                    command = cmd,
                                                ),
                                            )
                                        } else {
                                            viewModel.unload(
                                                com.omnillm.features.modelhub.api.StartUnloadSpec(
                                                    installationId = installationId,
                                                    command = cmd,
                                                ),
                                            )
                                        }
                                        actionMessage = when (result) {
                                            is OmniResult.Ok ->
                                                "$action → ${result.value.state}" +
                                                    (result.value.loadedModelId?.let {
                                                        " (${it.take(16)}…)"
                                                    } ?: "")
                                            is OmniResult.Err ->
                                                result.error.message ?: "$action failed"
                                        }
                                        actionBusy = false
                                        viewModel.refresh()
                                    }
                                }
                                else -> {
                                    actionMessage = "Action $action not wired in smoke UI"
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCardList(
    cards: List<ModelCard>,
    emptyLabel: String,
    density: DensityMode,
    onSelect: (ModelCard) -> Unit,
) {
    if (cards.isEmpty()) {
        EmptyBody(title = emptyLabel, body = "")
        return
    }
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        // Composite key: suggested catalog may list the same revision more than once
        // (e.g. pin + SAF import hint) — LazyColumn keys must be unique.
        items(
            cards,
            key = {
                listOf(
                    it.installationId.orEmpty(),
                    it.modelRevisionId,
                    it.acquisitionChannel,
                    it.displayName,
                ).joinToString("|")
            },
        ) { card ->
            val cd = stringResource(
                R.string.modelhub_card_cd,
                card.displayName,
                card.installationState ?: stringResource(R.string.label_not_available),
                card.compatibilityStatus,
            )
            InfoCard(
                modifier = Modifier
                    .semantics { contentDescription = cd }
                    .clickable { onSelect(card) }
                    .heightIn(min = MinTouchTarget),
            ) {
                Text(card.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.modelhub_installation_state,
                        card.installationState ?: stringResource(R.string.label_not_available),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                TrustDimensionRow(
                    sourceLabel = card.acquisitionChannel,
                    compatibilityLabel = card.compatibilityStatus,
                    placementLabel = card.placementClass,
                    licenseLabel = card.licenseStatus,
                )
                if (density == DensityMode.EXPERT) {
                    Text(
                        stringResource(
                            R.string.label_canonical_id,
                            card.modelRevisionId.take(16) + "…",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsList(state: ModelHubUiState) {
    val jobs = state.downloadsAndImports
    if (jobs.isEmpty()) {
        EmptyBody(
            title = stringResource(R.string.modelhub_empty_jobs),
            body = "",
        )
        return
    }
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        items(jobs, key = { it.jobId }) { job ->
            InfoCard {
                Text(job.kind, style = MaterialTheme.typography.titleSmall)
                Text(job.state, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.label_job_id, job.jobId),
                    style = MaterialTheme.typography.labelSmall,
                )
                job.error?.let {
                    Text(
                        stringResource(R.string.label_error_code, it.code.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelDetailPane(
    card: ModelCard?,
    density: DensityMode,
    actionBusy: Boolean = false,
    actionMessage: String? = null,
    onAction: (String, ModelCard) -> Unit = { _, _ -> },
) {
    if (card == null) {
        EmptyBody(
            title = stringResource(R.string.modelhub_no_selection),
            body = "",
        )
        return
    }
    ScrollableScreenColumn(Modifier.fillMaxSize()) {
        item {
            SectionHeader(card.displayName)
        }
        item {
            InfoCard {
                TrustDimensionRow(
                    sourceLabel = card.acquisitionChannel,
                    compatibilityLabel = card.compatibilityStatus,
                    placementLabel = card.placementClass,
                    licenseLabel = card.licenseStatus,
                )
                Text(
                    stringResource(
                        R.string.modelhub_installation_state,
                        card.installationState ?: stringResource(R.string.label_not_available),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        R.string.modelhub_loaded_state,
                        card.loadedModelState ?: stringResource(R.string.label_not_available),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when (card.authenticityOk) {
                        true -> stringResource(R.string.modelhub_authenticity_ok)
                        false -> stringResource(R.string.modelhub_authenticity_fail)
                        null -> stringResource(R.string.modelhub_authenticity_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (card.pinned) {
                    Text(
                        stringResource(R.string.modelhub_pinned),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    stringResource(R.string.modelhub_refs, card.liveReferenceCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                PlacementRiskCopy(card.placementClass)
                Text(
                    stringResource(R.string.modelhub_compat_not_safety),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (card.riskFlags.isNotEmpty()) {
                    Text(
                        stringResource(R.string.modelhub_risk_flags),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    card.riskFlags.forEach { flag ->
                        Text("• $flag", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (density == DensityMode.EXPERT) {
                    Text(
                        stringResource(R.string.label_canonical_id, card.modelRevisionId),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        stringResource(R.string.label_canonical_id, card.artifactPackageId),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    card.quantizationDescriptorJson?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
                actionMessage?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .semantics { contentDescription = "modelhub_action_status" },
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    card.allowedActions.forEach { action ->
                        SecondaryActionButton(
                            label = actionLabel(action),
                            onClick = {
                                if (!actionBusy) onAction(action, card)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "modelhub_action_$action"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: String): String = when (action) {
    "DOWNLOAD" -> stringResource(R.string.modelhub_download)
    "IMPORT" -> stringResource(R.string.modelhub_import)
    "DELETE" -> stringResource(R.string.modelhub_delete)
    "PIN" -> stringResource(R.string.modelhub_pin)
    "UNPIN" -> stringResource(R.string.modelhub_unpin)
    "LOAD" -> stringResource(R.string.modelhub_load)
    "UNLOAD" -> stringResource(R.string.modelhub_unload)
    "BENCHMARK" -> stringResource(R.string.modelhub_benchmark)
    "VIEW_LICENSE" -> stringResource(R.string.modelhub_view_license)
    "VIEW_EVIDENCE" -> stringResource(R.string.modelhub_view_evidence)
    "ACCEPT_LICENSE" -> stringResource(R.string.modelhub_accept_license)
    "CANCEL" -> stringResource(R.string.action_cancel)
    else -> action
}
