package com.omnillm.features.playground.usecase

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.AssetHandleView
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.CancelStatusUi
import com.omnillm.features.playground.api.CapabilityCellView
import com.omnillm.features.playground.api.ChatMessage
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.playground.api.ModelOptionView
import com.omnillm.features.playground.api.MultimodalChatRequestSpec
import com.omnillm.features.playground.api.PlaygroundApi
import com.omnillm.features.playground.api.PlaygroundScreenMode
import com.omnillm.features.playground.api.PlaygroundSnapshot
import com.omnillm.features.playground.api.PlaygroundTab
import com.omnillm.features.playground.api.RequestStripUi
import com.omnillm.features.playground.api.SourceSessionRef
import com.omnillm.features.playground.api.StructuredToolsRequestSpec
import com.omnillm.features.playground.api.TabCapabilityView
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.playground.projection.CancelPhaseProjection
import com.omnillm.features.playground.projection.CapabilityUiProjection
import com.omnillm.features.playground.projection.MetricsUiProjection
import com.omnillm.features.playground.projection.RequestUiProjection
import com.omnillm.interfaces.admin.LocalUiPrincipal
import java.util.concurrent.ConcurrentHashMap

/**
 * FEAT-PLAYGROUND control-plane use-case facade.
 *
 * - Capability negotiation before high-cost execution (fail closed)
 * - Client-generated requestId / idempotencyKey (ADR-004/005)
 * - Evidence labels on all metrics surfaces
 * - empty / loading / error / degraded screen modes
 * - Cancel phase disclosure
 * - No domain DB writes (ADR-010); UI never loads engines (INV-001)
 */
class PlaygroundService(
    private val ports: PlaygroundFeaturePorts,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : PlaygroundApi {

    private val conversation = ConcurrentHashMap<String, MutableList<ChatMessage>>()
    private val lastRequest = ConcurrentHashMap<String, RequestStripUi>()
    private val lastError = ConcurrentHashMap<String, OmniError>()
    private val loadingFlags = ConcurrentHashMap<String, Boolean>()

    override suspend fun getSnapshot(
        principal: PrincipalId,
        activeTab: PlaygroundTab,
        selectedModelRevisionId: String?,
    ): OmniResult<PlaygroundSnapshot> {
        requireLocalUi(principal)
        if (!allowsInferenceRead()) return forbiddenRead()

        val runtimeState = ports.runtimeStatus.runtimeState()
        val degradedReasons = ports.runtimeStatus.degradedReasons()
        val models = projectModels()
        val selected = selectedModelRevisionId
            ?: models.firstOrNull()?.modelRevisionId
        val tabs = PlaygroundTab.entries.map { tab ->
            if (selected == null) {
                TabCapabilityView(
                    tab = tab,
                    required = emptyList(),
                    operable = false,
                    blockingReasonKey = "playground.no-model-selected",
                )
            } else {
                when (val n = negotiateCapabilities(principal, tab, selected)) {
                    is OmniResult.Ok -> n.value
                    is OmniResult.Err -> TabCapabilityView(
                        tab = tab,
                        required = emptyList(),
                        operable = false,
                        blockingReasonKey = n.error.code.code,
                    )
                }
            }
        }
        val metrics = ports.metrics.sampleWorkspaceMetrics(clockMs())
            .map { MetricsUiProjection.project(it) }
        val active = lastRequest[principal.value]
        val err = lastError[principal.value]
        val conv = conversation[principal.value].orEmpty().toList()
        val loading = loadingFlags[principal.value] == true
        val screenMode = resolveScreenMode(
            loading = loading,
            models = models,
            runtimeState = runtimeState,
            degradedReasons = degradedReasons,
            error = err,
            active = active,
        )

        return OmniResult.ok(
            PlaygroundSnapshot(
                screenMode = screenMode,
                activeTab = activeTab,
                models = models,
                selectedModelRevisionId = selected,
                tabCapabilities = tabs,
                conversation = conv,
                activeRequest = active,
                assets = emptyList(),
                runtimeState = runtimeState,
                runtimeDegradedReasons = degradedReasons,
                metrics = metrics,
                lastError = err,
            ),
        )
    }

    override suspend fun negotiateCapabilities(
        principal: PrincipalId,
        tab: PlaygroundTab,
        modelRevisionId: String,
    ): OmniResult<TabCapabilityView> {
        requireLocalUi(principal)
        if (!allowsInferenceRead()) return forbiddenRead()
        if (modelRevisionId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "modelRevisionId required"))
        }
        val required = CapabilityUiProjection.requiredCapabilities(tab)
        val cells = required.map { cap ->
            val state = ports.capabilities.state(cap, modelRevisionId)
            val conditions = ports.capabilities.conditions(cap, modelRevisionId)
            CapabilityUiProjection.projectCell(cap, state, conditions)
        }
        return OmniResult.ok(CapabilityUiProjection.projectTab(tab, cells))
    }

    override suspend fun startChat(
        principal: PrincipalId,
        spec: ChatRequestSpec,
    ): OmniResult<RequestStripUi> {
        requireLocalUi(principal)
        if (!allowsInferenceCreate()) return forbiddenCreate()

        val gate = gateCapabilities(
            principal = principal,
            tab = PlaygroundTab.CHAT,
            modelRevisionId = spec.modelRevisionId,
        )
        if (gate != null) {
            lastError[principal.value] = gate
            return OmniResult.err(gate)
        }

        loadingFlags[principal.value] = true
        return try {
            when (val result = ports.inference.startChat(principal, spec)) {
                is OmniResult.Err -> {
                    lastError[principal.value] = result.error
                    OmniResult.err(result.error)
                }
                is OmniResult.Ok -> {
                    val strip = RequestUiProjection.project(result.value)
                    lastRequest[principal.value] = strip
                    lastError.remove(principal.value)
                    appendConversation(principal, spec.messages, strip.assistantText)
                    OmniResult.ok(strip)
                }
            }
        } finally {
            loadingFlags[principal.value] = false
        }
    }

    override suspend fun startEmbedding(
        principal: PrincipalId,
        spec: EmbeddingRequestSpec,
    ): OmniResult<RequestStripUi> {
        requireLocalUi(principal)
        if (!allowsInferenceCreate()) return forbiddenCreate()

        val gate = gateCapabilities(
            principal = principal,
            tab = PlaygroundTab.EMBEDDINGS,
            modelRevisionId = spec.modelRevisionId,
        )
        if (gate != null) {
            lastError[principal.value] = gate
            return OmniResult.err(gate)
        }

        loadingFlags[principal.value] = true
        return try {
            when (val result = ports.inference.startEmbedding(principal, spec)) {
                is OmniResult.Err -> {
                    lastError[principal.value] = result.error
                    OmniResult.err(result.error)
                }
                is OmniResult.Ok -> {
                    val strip = RequestUiProjection.project(result.value)
                    lastRequest[principal.value] = strip
                    lastError.remove(principal.value)
                    // Embeddings must not create a generation Session (FEAT-PLAYGROUND §3).
                    if (strip.sessionId != null) {
                        val err = OmniError.INTERNAL(
                            message = "embedding path exposed generation session (invariant violation)",
                            details = mapOf("sessionId" to strip.sessionId),
                        )
                        lastError[principal.value] = err
                        return OmniResult.err(err)
                    }
                    OmniResult.ok(strip)
                }
            }
        } finally {
            loadingFlags[principal.value] = false
        }
    }

    override suspend fun startMultimodalChat(
        principal: PrincipalId,
        spec: MultimodalChatRequestSpec,
    ): OmniResult<RequestStripUi> {
        requireLocalUi(principal)
        if (!allowsInferenceCreate()) return forbiddenCreate()

        val gate = gateCapabilities(
            principal = principal,
            tab = PlaygroundTab.VISION_AUDIO,
            modelRevisionId = spec.chat.modelRevisionId,
        )
        if (gate != null) {
            lastError[principal.value] = gate
            return OmniResult.err(gate)
        }

        for (assetId in spec.assetIds) {
            val asset = ports.inference.getAsset(assetId)
                ?: return failAsset(
                    principal,
                    OmniError.NOT_FOUND(
                        message = "asset not found",
                        details = mapOf("assetId" to assetId),
                    ),
                )
            val assetErr = validateAssetForClaim(asset, principal)
            if (assetErr != null) {
                return failAsset(principal, assetErr)
            }
        }

        // Merge asset ids onto messages for the chat path.
        val mergedMessages = spec.chat.messages.mapIndexed { index, msg ->
            if (index == spec.chat.messages.lastIndex) {
                msg.copy(assetIds = (msg.assetIds + spec.assetIds).distinct())
            } else {
                msg
            }
        }
        val chatSpec = spec.chat.copy(messages = mergedMessages)
        return startChat(principal, chatSpec)
    }

    override suspend fun startStructuredTools(
        principal: PrincipalId,
        spec: StructuredToolsRequestSpec,
    ): OmniResult<RequestStripUi> {
        requireLocalUi(principal)
        if (!allowsInferenceCreate()) return forbiddenCreate()

        val gate = gateCapabilities(
            principal = principal,
            tab = PlaygroundTab.STRUCTURED_TOOLS,
            modelRevisionId = spec.modelRevisionId,
        )
        if (gate != null) {
            lastError[principal.value] = gate
            return OmniResult.err(gate)
        }

        loadingFlags[principal.value] = true
        return try {
            val result = if (spec.isToolCalling) {
                ports.structured.startToolCalling(principal, spec)
            } else {
                ports.structured.startStructured(principal, spec)
            }
            when (result) {
                is OmniResult.Err -> {
                    lastError[principal.value] = result.error
                    OmniResult.err(result.error)
                }
                is OmniResult.Ok -> {
                    val strip = RequestUiProjection.project(result.value)
                    lastRequest[principal.value] = strip
                    lastError.remove(principal.value)
                    if (!strip.assistantText.isNullOrBlank()) {
                        appendConversation(
                            principal,
                            listOf(ChatMessage(role = "assistant", content = strip.assistantText)),
                            null,
                        )
                    }
                    OmniResult.ok(strip)
                }
            }
        } finally {
            loadingFlags[principal.value] = false
        }
    }

    override suspend fun cancelRequest(
        principal: PrincipalId,
        spec: CancelInferenceSpec,
    ): OmniResult<CancelStatusUi> {
        requireLocalUi(principal)
        if (!allowsInferenceCancel()) return forbiddenCancel()

        val current = lastRequest[principal.value]
        if (current != null && current.requestId != spec.requestId) {
            // Prefer querying the requested id; do not cancel a different strip silently.
        }
        if (current != null && current.isTerminal && current.requestId == spec.requestId) {
            return OmniResult.ok(
                CancelStatusUi(
                    requestId = spec.requestId,
                    phase = CancelPhase.TERMINAL,
                    labelKey = CancelPhaseProjection.labelKey(CancelPhase.TERMINAL),
                    requestState = current.state,
                    isTerminal = true,
                ),
            )
        }

        return when (val result = ports.inference.cancel(principal, spec)) {
            is OmniResult.Err -> {
                lastError[principal.value] = result.error
                OmniResult.err(result.error)
            }
            is OmniResult.Ok -> {
                val phase = result.value.phase
                val status = CancelStatusUi(
                    requestId = result.value.requestId,
                    phase = phase,
                    labelKey = CancelPhaseProjection.labelKey(phase),
                    requestState = result.value.requestState,
                    isTerminal = CancelPhaseProjection.isTerminal(phase),
                    error = result.value.error,
                )
                // Refresh strip after cancel.
                when (val q = ports.inference.query(principal, spec.requestId)) {
                    is OmniResult.Ok -> {
                        lastRequest[principal.value] = RequestUiProjection.project(
                            q.value.copy(cancelPhase = phase),
                        )
                    }
                    is OmniResult.Err -> {
                        // Keep last strip but annotate cancel phase when possible.
                        current?.let {
                            lastRequest[principal.value] = it.copy(
                                cancelPhase = phase,
                                state = result.value.requestState ?: it.state,
                                isTerminal = status.isTerminal || it.isTerminal,
                                allowedActions = RequestUiProjection.allowedActions(
                                    state = result.value.requestState ?: it.state,
                                    cancelPhase = phase,
                                    terminal = status.isTerminal || it.isTerminal,
                                    known = true,
                                ),
                            )
                        }
                    }
                }
                lastError.remove(principal.value)
                OmniResult.ok(status)
            }
        }
    }

    override suspend fun queryRequest(
        principal: PrincipalId,
        requestId: String,
    ): OmniResult<RequestStripUi> {
        requireLocalUi(principal)
        if (!allowsInferenceRead()) return forbiddenRead()
        if (requestId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "requestId required"))
        }
        // Reply-loss path: query only, never re-execute (ADR-004/005).
        return when (val result = ports.inference.query(principal, requestId)) {
            is OmniResult.Err -> {
                lastError[principal.value] = result.error
                OmniResult.err(result.error)
            }
            is OmniResult.Ok -> {
                val strip = RequestUiProjection.project(result.value)
                lastRequest[principal.value] = strip
                OmniResult.ok(strip)
            }
        }
    }

    override suspend fun streamEvents(
        principal: PrincipalId,
        requestId: String,
        afterSeq: Long,
    ): OmniResult<com.omnillm.features.playground.api.PlaygroundStreamBatch> {
        // COR-03: HTTP/SSE transports poll this method with non-LOCAL_UI principals.
        // A `require()` here would throw inside the caller's stream flow and break the
        // SSE connection mid-stream — return an honest error instead (never throw).
        if (principal.value != LocalUiPrincipal.ID.value) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "Playground stream projection accepts LOCAL_UI principal only (INV-011)",
                    details = mapOf("principal" to principal.value),
                ),
            )
        }
        if (!allowsInferenceRead()) return forbiddenRead()
        if (requestId.isBlank()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "requestId required"))
        }
        if (afterSeq < 0L) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "afterSeq must be >= 0"))
        }
        // Stream projection from durable query — never re-executes (CORE-INTERFACE §4).
        return when (val result = ports.inference.query(principal, requestId)) {
            is OmniResult.Err -> {
                lastError[principal.value] = result.error
                OmniResult.err(result.error)
            }
            is OmniResult.Ok -> {
                val handle = result.value
                val strip = RequestUiProjection.project(handle)
                lastRequest[principal.value] = strip
                val events = projectStreamEvents(handle, afterSeq)
                OmniResult.ok(
                    com.omnillm.features.playground.api.PlaygroundStreamBatch(
                        requestId = requestId,
                        streamEpoch = 1L,
                        afterSeq = afterSeq,
                        events = events,
                        requestState = handle.state,
                        cancelPhase = handle.cancelPhase,
                        isTerminal = strip.isTerminal || events.any { it.isTerminal },
                    ),
                )
            }
        }
    }

    /**
     * Project durable handle into ordered stream events after exclusive [afterSeq].
     * Stateless Session default: one state snapshot + optional delta + terminal.
     */
    private fun projectStreamEvents(
        handle: com.omnillm.features.playground.ports.InferenceHandle,
        afterSeq: Long,
    ): List<com.omnillm.features.playground.api.PlaygroundStreamEvent> {
        val terminal = handle.state in setOf(
            "COMPLETED", "SUCCEEDED", "CANCELLED", "FAILED", "TERMINAL_SUCCESS", "TERMINAL_FAILED",
        )
        val all = buildList {
            add(
                com.omnillm.features.playground.api.PlaygroundStreamEvent(
                    seq = 0L,
                    kind = "state",
                    state = handle.state,
                    isTerminal = false,
                ),
            )
            handle.assistantText?.takeIf { it.isNotEmpty() }?.let { text ->
                add(
                    com.omnillm.features.playground.api.PlaygroundStreamEvent(
                        seq = 1L,
                        kind = "delta",
                        textDelta = text,
                        isTerminal = false,
                    ),
                )
            }
            if (terminal || handle.error != null) {
                add(
                    com.omnillm.features.playground.api.PlaygroundStreamEvent(
                        seq = 2L,
                        kind = "terminal",
                        state = handle.state,
                        isTerminal = true,
                        error = handle.error,
                    ),
                )
            }
        }
        return all.filter { it.seq > afterSeq }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Fail closed on unsupported / unknown / temporarily unavailable.
     * Returns a catalog OmniError or null when operable.
     */
    private suspend fun gateCapabilities(
        principal: PrincipalId,
        tab: PlaygroundTab,
        modelRevisionId: String,
    ): OmniError? {
        val negotiation = when (val n = negotiateCapabilities(principal, tab, modelRevisionId)) {
            is OmniResult.Err -> return n.error
            is OmniResult.Ok -> n.value
        }
        if (negotiation.operable) return null
        val blocking = negotiation.required.firstOrNull {
            it.state != CapabilityState.SUPPORTED && it.state != CapabilityState.CONDITIONAL
        } ?: return OmniError.CAPABILITY_UNSUPPORTED(
            message = "tab not operable",
            details = mapOf("tab" to tab.name),
        )
        return mapCapabilityBlocker(blocking)
    }

    /**
     * Distinct errors for UNSUPPORTED vs UNKNOWN vs TEMPORARILY_UNAVAILABLE
     * (UX-STATE §6 / INV-018 — unknown is not "unsupported").
     */
    internal fun mapCapabilityBlocker(cell: CapabilityCellView): OmniError {
        val details = mutableMapOf(
            "capability" to cell.capabilityId.id,
            "state" to cell.state.name,
            "explanationKey" to cell.explanationKey,
        )
        if (cell.conditions.isNotEmpty()) {
            details["conditions"] = cell.conditions.joinToString(",")
        }
        return when (cell.state) {
            CapabilityState.UNSUPPORTED -> OmniError.CAPABILITY_UNSUPPORTED(
                message = "capability unsupported: ${cell.capabilityId.id}",
                details = details,
            )
            CapabilityState.UNKNOWN -> OmniError.CAPABILITY_UNKNOWN(
                // UNKNOWN must not be presented as "device unsupported" (UX-STATE §8).
                message = "capability unknown (fail closed): ${cell.capabilityId.id}",
                details = details + ("failClosed" to "true"),
            )
            CapabilityState.TEMPORARILY_UNAVAILABLE -> OmniError.ADMISSION_REJECTED(
                message = "capability temporarily unavailable: ${cell.capabilityId.id}",
                details = details,
            )
            CapabilityState.CONDITIONAL, CapabilityState.SUPPORTED ->
                OmniError.INTERNAL(message = "mapCapabilityBlocker called for operable cell")
        }
    }

    private fun validateAssetForClaim(
        asset: AssetHandleView,
        principal: PrincipalId,
    ): OmniError? {
        if (!asset.isReady) {
            return OmniError.ASSET_NOT_READY(
                message = "asset not READY",
                details = mapOf("assetId" to asset.assetId, "state" to asset.state),
            )
        }
        if (asset.ownerPrincipalId != principal.value) {
            return OmniError.FORBIDDEN(
                message = "asset owner mismatch",
                details = mapOf(
                    "assetId" to asset.assetId,
                    "owner" to asset.ownerPrincipalId,
                ),
            )
        }
        val now = clockMs()
        val expires = asset.ttlExpiresAtEpochMs
        if (expires != null && expires <= now) {
            return OmniError.ASSET_EXPIRED(
                message = "asset expired",
                details = mapOf("assetId" to asset.assetId),
            )
        }
        return null
    }

    private fun failAsset(principal: PrincipalId, error: OmniError): OmniResult<RequestStripUi> {
        lastError[principal.value] = error
        return OmniResult.err(error)
    }

    private fun appendConversation(
        principal: PrincipalId,
        messages: List<ChatMessage>,
        assistantText: String?,
    ) {
        val list = conversation.getOrPut(principal.value) { mutableListOf() }
        // Prefer the last user message for history display.
        messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.let { list += it }
        if (!assistantText.isNullOrEmpty()) {
            list += ChatMessage(role = "assistant", content = assistantText)
        }
    }

    private fun projectModels(): List<ModelOptionView> =
        ports.models.listModels().map { row ->
            // Surface generation + embedding cells on the model card for selection UX.
            val caps = listOf(
                CapabilityId.TEXT_GENERATION,
                CapabilityId.EMBEDDING,
                CapabilityId.VISION_INPUT,
                CapabilityId.AUDIO_INPUT,
            ).map { cap ->
                CapabilityUiProjection.projectCell(
                    cap,
                    ports.capabilities.state(cap, row.modelRevisionId),
                    ports.capabilities.conditions(cap, row.modelRevisionId),
                )
            }
            ModelOptionView(
                modelRevisionId = row.modelRevisionId,
                displayName = row.displayName,
                installationState = row.installationState,
                capabilities = caps,
            )
        }

    private fun resolveScreenMode(
        loading: Boolean,
        models: List<ModelOptionView>,
        runtimeState: String,
        degradedReasons: List<String>,
        error: OmniError?,
        active: RequestStripUi?,
    ): PlaygroundScreenMode {
        if (loading && active == null) return PlaygroundScreenMode.LOADING
        if (error != null && active == null && models.isEmpty()) return PlaygroundScreenMode.ERROR
        if (runtimeState == "FAULTED") return PlaygroundScreenMode.ERROR
        if (runtimeState == "DEGRADED" || degradedReasons.isNotEmpty()) {
            return if (active != null && !active.isTerminal) {
                PlaygroundScreenMode.WORKING
            } else {
                PlaygroundScreenMode.DEGRADED
            }
        }
        if (active != null && !active.isTerminal) return PlaygroundScreenMode.WORKING
        // No installed models → EMPTY workspace; otherwise READY for first chat.
        if (models.isEmpty()) return PlaygroundScreenMode.EMPTY
        return PlaygroundScreenMode.READY
    }

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "Playground accepts LOCAL_UI principal only (INV-011)"
        }
    }

    private fun allowsInferenceCreate(): Boolean =
        LocalUiPrincipal.allows(AccessScope.inference_create)

    private fun allowsInferenceCancel(): Boolean =
        LocalUiPrincipal.allows(AccessScope.inference_cancel)

    private fun allowsInferenceRead(): Boolean =
        LocalUiPrincipal.allows(AccessScope.inference_read_own) ||
            LocalUiPrincipal.allows(AccessScope.inference_create)

    private fun <T> forbiddenCreate(): OmniResult<T> =
        OmniResult.err(
            OmniError.FORBIDDEN(
                message = "missing scope inference.create",
                details = mapOf("scope" to AccessScope.inference_create.name),
            ),
        )

    private fun <T> forbiddenCancel(): OmniResult<T> =
        OmniResult.err(
            OmniError.FORBIDDEN(
                message = "missing scope inference.cancel",
                details = mapOf("scope" to AccessScope.inference_cancel.name),
            ),
        )

    private fun <T> forbiddenRead(): OmniResult<T> =
        OmniResult.err(
            OmniError.FORBIDDEN(
                message = "missing scope inference.read-own",
                details = mapOf("scope" to AccessScope.inference_read_own.name),
            ),
        )
}

/** First-request session policy helper (FEAT-PLAYGROUND §2). */
object SessionPolicy {
    fun firstRequest(): SourceSessionRef = SourceSessionRef.None

    /**
     * Continuation is legal only when owner, revision, load key, tokenizer/template
     * epoch, engine build and session epoch all match the prior handle.
     */
    fun mayContinue(
        previous: SourceSessionRef.Existing,
        candidate: SourceSessionRef.Existing,
        ownerMatches: Boolean,
    ): Boolean {
        if (!ownerMatches) return false
        return previous.sessionId == candidate.sessionId &&
            previous.sessionEpoch == candidate.sessionEpoch &&
            previous.modelRevisionId == candidate.modelRevisionId &&
            previous.engineBuildId == candidate.engineBuildId &&
            previous.loadKeyDigest == candidate.loadKeyDigest &&
            previous.tokenizerEpoch == candidate.tokenizerEpoch &&
            previous.templateEpoch == candidate.templateEpoch
    }
}
