package com.omnillm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import com.omnillm.features.playground.api.PlaygroundScreenMode
import com.omnillm.features.playground.api.PlaygroundTab
import com.omnillm.features.playground.viewmodel.PlaygroundViewModel
import com.omnillm.ui.R
import com.omnillm.ui.components.EmptyBody
import com.omnillm.ui.components.ErrorBody
import com.omnillm.ui.components.EvidencedMetricRow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundScreen(
    viewModel: PlaygroundViewModel?,
    onOpenModelHub: () -> Unit,
    onOpenContentReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensityMode.current
    var prompt by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val state = if (viewModel != null) {
        observeListenerAsState(viewModel.state) { listener ->
            viewModel.addListener(listener)
            return@observeListenerAsState { viewModel.removeListener(listener) }
        }.value
    } else {
        PlaygroundViewModel.State()
    }

    LaunchedEffect(viewModel) {
        viewModel?.refresh()
    }

    val phase = when {
        viewModel == null -> ScreenPhase.EMPTY
        state.loading && state.snapshot == null -> ScreenPhase.LOADING
        state.screenMode == PlaygroundScreenMode.ERROR || state.error != null &&
            state.snapshot == null -> ScreenPhase.ERROR
        state.screenMode == PlaygroundScreenMode.EMPTY ||
            state.snapshot?.models.isNullOrEmpty() -> ScreenPhase.EMPTY
        state.screenMode == PlaygroundScreenMode.DEGRADED -> ScreenPhase.DEGRADED
        state.screenMode == PlaygroundScreenMode.LOADING -> ScreenPhase.LOADING
        else -> ScreenPhase.CONTENT
    }

    val tabs = PlaygroundTab.entries
    val selectedTab = state.activeTab

    OmniScreenScaffold(
        title = stringResource(R.string.playground_title),
        modifier = modifier,
        onRefresh = { viewModel?.refresh() },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel?.selectTab(tab) },
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                        text = {
                            Text(
                                when (tab) {
                                    PlaygroundTab.CHAT ->
                                        stringResource(R.string.playground_tab_chat)
                                    PlaygroundTab.EMBEDDINGS ->
                                        stringResource(R.string.playground_tab_embeddings)
                                    PlaygroundTab.VISION_AUDIO ->
                                        stringResource(R.string.playground_tab_multimodal)
                                    PlaygroundTab.STRUCTURED_TOOLS ->
                                        stringResource(R.string.playground_tab_tools)
                                },
                            )
                        },
                    )
                }
            }
            when (phase) {
                ScreenPhase.LOADING -> LoadingBody()
                ScreenPhase.ERROR -> ErrorBody(
                    message = state.error?.message ?: stringResource(R.string.state_error),
                    errorCode = state.error?.code?.code,
                    onRetry = { viewModel?.refresh() },
                )
                ScreenPhase.EMPTY -> EmptyBody(
                    title = stringResource(R.string.playground_empty_no_model),
                    body = stringResource(R.string.playground_empty_no_model_body),
                    primaryActionLabel = stringResource(R.string.action_open_modelhub),
                    onPrimaryAction = onOpenModelHub,
                )
                else -> {
                    if (phase == ScreenPhase.DEGRADED) {
                        PhaseBanner(ScreenPhase.DEGRADED)
                    }
                    ScrollableScreenColumn(Modifier.weight(1f)) {
                        item {
                            SectionHeader(stringResource(R.string.playground_select_model))
                        }
                        item {
                            val models = state.snapshot?.models.orEmpty()
                            val selectedName = models
                                .firstOrNull { it.modelRevisionId == state.selectedModelRevisionId }
                                ?.displayName
                                ?: stringResource(R.string.playground_select_model)
                            InfoCard {
                                ExposedDropdownMenuBox(
                                    expanded = modelMenuExpanded,
                                    onExpandedChange = { modelMenuExpanded = it },
                                ) {
                                    TextField(
                                        value = selectedName,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(
                                                expanded = modelMenuExpanded,
                                            )
                                        },
                                        modifier = Modifier
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                            .fillMaxWidth(),
                                        label = {
                                            Text(stringResource(R.string.playground_select_model))
                                        },
                                    )
                                    ExposedDropdownMenu(
                                        expanded = modelMenuExpanded,
                                        onDismissRequest = { modelMenuExpanded = false },
                                    ) {
                                        models.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model.displayName) },
                                                onClick = {
                                                    viewModel?.selectModel(model.modelRevisionId)
                                                    modelMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                                state.tabCapability?.let { cap ->
                                    if (!cap.operable) {
                                        Text(
                                            stringResource(R.string.playground_capability_blocked),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(top = 8.dp),
                                        )
                                        cap.blockingReasonKey?.let { key ->
                                            Text(
                                                stringResource(
                                                    R.string.playground_capability_reason,
                                                    key,
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    stringResource(
                                        R.string.playground_runtime,
                                        state.snapshot?.runtimeState
                                            ?: stringResource(R.string.label_unknown),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                        state.activeRequest?.let { req ->
                            item {
                                SectionHeader(stringResource(R.string.playground_request_strip))
                            }
                            item {
                                InfoCard {
                                    Text(req.labelKey, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        stringResource(R.string.label_request_id, req.requestId),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(req.state, style = MaterialTheme.typography.bodyMedium)
                                    if (req.degraded) {
                                        Text(
                                            stringResource(R.string.playground_degraded),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        req.degradedReasons.forEach {
                                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (density == DensityMode.EXPERT) {
                                        req.actualModelRevisionId?.let {
                                            Text(
                                                stringResource(
                                                    R.string.playground_actual_revision,
                                                    it.take(16) + "…",
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        req.engineBuildId?.let {
                                            Text(
                                                stringResource(R.string.playground_engine, it),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        req.backend?.let {
                                            Text(
                                                stringResource(R.string.playground_backend, it),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                    req.metrics.forEach { m ->
                                        EvidencedMetricRow(
                                            name = m.metricId,
                                            displayValue = m.value,
                                            unit = m.unit,
                                            evidenceLabel = m.evidenceLabel,
                                            allowsNumericDisplay = m.displayNumeric,
                                            source = m.source,
                                        )
                                    }
                                    req.assistantText?.let {
                                        Text(
                                            stringResource(R.string.playground_assistant),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(it, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (req.embeddingCount != null) {
                                        Text(
                                            stringResource(
                                                R.string.playground_embedding_result,
                                                req.embeddingCount ?: 0,
                                                req.embeddingDimensions ?: 0,
                                            ),
                                        )
                                    }
                                    // In-app report entry on generative result (not external web).
                                    val activeReportCd = stringResource(
                                        R.string.playground_report_content_cd,
                                    )
                                    SecondaryActionButton(
                                        label = stringResource(
                                            R.string.playground_report_content,
                                        ),
                                        onClick = onOpenContentReport,
                                        modifier = Modifier.semantics {
                                            contentDescription = activeReportCd
                                        },
                                    )
                                }
                            }
                        }
                        val conversation = state.snapshot?.conversation.orEmpty()
                        item {
                            SectionHeader(stringResource(R.string.playground_tab_chat))
                        }
                        if (conversation.isEmpty()) {
                            item {
                                InfoCard {
                                    Text(
                                        stringResource(R.string.playground_conversation_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        } else {
                            items(conversation) { msg ->
                                val isAssistant = !msg.role.equals("user", ignoreCase = true)
                                InfoCard {
                                    Text(
                                        if (isAssistant) {
                                            stringResource(R.string.playground_assistant)
                                        } else {
                                            stringResource(R.string.playground_user)
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(msg.content, style = MaterialTheme.typography.bodyMedium)
                                    // FEAT-AI-CONTENT-REPORT / PLAY-AI-REPORTING:
                                    // in-app report next to every locally generated result.
                                    if (isAssistant) {
                                        val reportCd = stringResource(
                                            R.string.playground_report_content_cd,
                                        )
                                        SecondaryActionButton(
                                            label = stringResource(
                                                R.string.playground_report_content,
                                            ),
                                            onClick = onOpenContentReport,
                                            modifier = Modifier.semantics {
                                                contentDescription = reportCd
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val sendCd = stringResource(R.string.playground_send_cd)
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.playground_prompt_hint)) },
                            maxLines = 4,
                        )
                        PrimaryActionButton(
                            label = if (selectedTab == PlaygroundTab.STRUCTURED_TOOLS) {
                                stringResource(R.string.playground_structured_send)
                            } else {
                                stringResource(R.string.playground_send)
                            },
                            onClick = {
                                // Client-generated requestId / idempotencyKey (ADR-004/005).
                                val model = state.selectedModelRevisionId
                                if (model != null && prompt.isNotBlank() && viewModel != null) {
                                    val requestId = java.util.UUID.randomUUID().toString()
                                    if (selectedTab == PlaygroundTab.STRUCTURED_TOOLS) {
                                        val idem = "pg-structured-$requestId"
                                        val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                                            "$requestId|$idem|$model|$prompt",
                                        )
                                        // Minimal object schema: { "result": string } from user prompt context.
                                        val schema = mapOf(
                                            "type" to "object",
                                            "properties" to mapOf(
                                                "result" to mapOf("type" to "string"),
                                            ),
                                            "required" to listOf("result"),
                                        )
                                        viewModel.sendStructuredTools(
                                            com.omnillm.features.playground.api.StructuredToolsRequestSpec(
                                                identity = com.omnillm.features.playground.api.InferenceIdentity(
                                                    requestId = requestId,
                                                    idempotencyKey = idem,
                                                    canonicalInputDigest = digest,
                                                ),
                                                modelRevisionId = model,
                                                schemaName = "playground_result",
                                                schema = schema,
                                                allowPostValidate = true,
                                            ),
                                        )
                                    } else {
                                        val idem = "pg-chat-$requestId"
                                        val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                                            "$requestId|$idem|$model|$prompt",
                                        )
                                        viewModel.sendChat(
                                            com.omnillm.features.playground.api.ChatRequestSpec(
                                                identity = com.omnillm.features.playground.api.InferenceIdentity(
                                                    requestId = requestId,
                                                    idempotencyKey = idem,
                                                    canonicalInputDigest = digest,
                                                ),
                                                modelRevisionId = model,
                                                messages = listOf(
                                                    com.omnillm.features.playground.api.ChatMessage(
                                                        role = "user",
                                                        content = prompt,
                                                    ),
                                                ),
                                                stream = true,
                                            ),
                                        )
                                    }
                                }
                                prompt = ""
                            },
                            enabled = prompt.isNotBlank() &&
                                state.tabCapability?.operable != false &&
                                !state.selectedModelRevisionId.isNullOrBlank() &&
                                viewModel != null,
                            contentDescription = sendCd,
                        )
                    }
                }
            }
        }
    }
}
