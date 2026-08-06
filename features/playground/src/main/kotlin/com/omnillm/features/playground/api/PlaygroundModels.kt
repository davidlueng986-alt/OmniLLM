package com.omnillm.features.playground.api

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.playground.projection.PlaygroundUiAction
import com.omnillm.features.playground.projection.UiSeverity

/**
 * Workspace tabs (FEAT-PLAYGROUND §1). Entry is always visible; operability
 * is gated by capability negotiation for the selected model/engine.
 */
enum class PlaygroundTab {
    CHAT,
    EMBEDDINGS,
    VISION_AUDIO,
    STRUCTURED_TOOLS,
}

/**
 * Client-generated inference identity (ADR-004/005).
 * [requestId] is a UUID; [idempotencyKey] is principal+operation scoped.
 */
data class InferenceIdentity(
    val requestId: String,
    val idempotencyKey: String,
    /** Canonical input digest (hex SHA-256) of the request body. */
    val canonicalInputDigest: String,
) {
    init {
        require(REQUEST_ID_UUID.matches(requestId.lowercase())) {
            "requestId must be a UUID string"
        }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= 128) {
            "idempotencyKey exceeds 128 bytes"
        }
        require(HEX64.matches(canonicalInputDigest)) {
            "canonicalInputDigest must be 64-char lower-case hex SHA-256"
        }
    }

    companion object {
        private val REQUEST_ID_UUID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Source session policy for Chat (FEAT-PLAYGROUND §2).
 * First request uses [None]; continuation only when ownership/revision/load key
 * /tokenizer epoch/engine build/session epoch all match.
 */
sealed class SourceSessionRef {
    data object None : SourceSessionRef()

    data class Existing(
        val sessionId: String,
        val sessionEpoch: Long,
        val modelRevisionId: String,
        val engineBuildId: String,
        val loadKeyDigest: String,
        val tokenizerEpoch: Long,
        val templateEpoch: Long,
    ) : SourceSessionRef() {
        init {
            require(sessionId.isNotBlank()) { "sessionId must be non-blank" }
            require(sessionEpoch >= 0L) { "sessionEpoch must be non-negative" }
            require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
            require(engineBuildId.isNotBlank()) { "engineBuildId must be non-blank" }
            require(loadKeyDigest.isNotBlank()) { "loadKeyDigest must be non-blank" }
            require(tokenizerEpoch >= 0L) { "tokenizerEpoch must be non-negative" }
            require(templateEpoch >= 0L) { "templateEpoch must be non-negative" }
        }
    }
}

data class ChatMessage(
    val role: String,
    val content: String,
    val assetIds: List<String> = emptyList(),
) {
    init {
        require(role.isNotBlank()) { "role must be non-blank" }
    }
}

data class ChatRequestSpec(
    val identity: InferenceIdentity,
    val modelRevisionId: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: List<String> = emptyList(),
    val seed: Long? = null,
    val sourceSession: SourceSessionRef = SourceSessionRef.None,
    val deadlineElapsedRealtimeNanos: Long = Long.MAX_VALUE / 4,
    val responseFormatSchemaId: String? = null,
    val toolsSchemaId: String? = null,
) {
    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
        require(messages.isNotEmpty()) { "messages must be non-empty" }
        maxOutputTokens?.let { require(it > 0) { "maxOutputTokens must be positive" } }
        temperature?.let { require(it in 0.0..2.0) { "temperature out of range" } }
        topP?.let { require(it in 0.0..1.0) { "topP out of range" } }
    }
}

data class EmbeddingRequestSpec(
    val identity: InferenceIdentity,
    val modelRevisionId: String,
    val inputs: List<String>,
    val encodingFormat: String = "float",
    val dimensions: Int? = null,
    val deadlineElapsedRealtimeNanos: Long = Long.MAX_VALUE / 4,
) {
    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
        require(inputs.isNotEmpty()) { "inputs must be non-empty" }
        require(inputs.all { it.isNotEmpty() }) { "embedding inputs must be non-empty strings" }
        dimensions?.let { require(it > 0) { "dimensions must be positive" } }
        require(encodingFormat == "float" || encodingFormat == "base64") {
            "encodingFormat must be float or base64"
        }
    }
}

/**
 * Multimodal asset handle projection (FEAT-PLAYGROUND §4).
 * Only READY + owner-matched assets may be claimed by a request.
 */
data class AssetHandleView(
    val assetId: String,
    val purpose: String,
    val mimeHint: String?,
    val actualMime: String?,
    val sizeBytes: Long?,
    val digestSha256: String?,
    val ttlExpiresAtEpochMs: Long?,
    val state: String,
    val preprocessingLabel: String?,
    val singleUse: Boolean,
    val ownerPrincipalId: String,
) {
    init {
        require(assetId.isNotBlank()) { "assetId must be non-blank" }
        require(purpose.isNotBlank()) { "purpose must be non-blank" }
        require(state.isNotBlank()) { "state must be non-blank" }
        require(ownerPrincipalId.isNotBlank()) { "ownerPrincipalId must be non-blank" }
    }

    val isReady: Boolean get() = state == "READY"
}

data class MultimodalChatRequestSpec(
    val chat: ChatRequestSpec,
    val assetIds: List<String>,
) {
    init {
        require(assetIds.isNotEmpty()) { "multimodal request requires at least one assetId" }
    }
}

/**
 * Structured output / tool-calling request for STRUCTURED_TOOLS tab
 * (FEAT-PLAYGROUND + FEAT-TOOLS). Schema is a JSON-compatible map tree;
 * platform admits schema bombs before engine compile.
 *
 * [toolDefinitionsJson] is optional — when non-null, path is tool-calling
 * (proposals only; OmniLLM never executes host tools).
 */
data class StructuredToolsRequestSpec(
    val identity: InferenceIdentity,
    val modelRevisionId: String,
    /** Schema name for structured response_format. */
    val schemaName: String = "response",
    /**
     * JSON Schema subset as nested maps/lists/primitives.
     * Required for structured-only; for tool-calling may be empty object.
     */
    val schema: Map<String, Any?> = emptyMap(),
    /**
     * When non-null, invoke tool-calling path. Each entry: toolId → parameters schema.
     */
    val tools: List<PlaygroundToolDef> = emptyList(),
    /** Prefer post-validate when native constrained unavailable (explicit, never silent). */
    val allowPostValidate: Boolean = true,
    val deadlineElapsedRealtimeNanos: Long = Long.MAX_VALUE / 4,
) {
    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
        require(schemaName.isNotBlank()) { "schemaName must be non-blank" }
        require(schema.isNotEmpty() || tools.isNotEmpty()) {
            "structured request requires schema and/or tools"
        }
    }

    val isToolCalling: Boolean get() = tools.isNotEmpty()
}

data class PlaygroundToolDef(
    val toolId: String,
    val description: String = "",
    val parametersSchema: Map<String, Any?>,
) {
    init {
        require(toolId.isNotBlank()) { "toolId must be non-blank" }
    }
}

/** Cancel command identity (client-generated, ADR-004/005). */
data class CancelInferenceSpec(
    val requestId: String,
    val commandId: String,
    val idempotencyKey: String,
    val canonicalInputDigest: String,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must be non-blank" }
        require(commandId.isNotBlank()) { "commandId must be non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(HEX64.matches(canonicalInputDigest)) {
            "canonicalInputDigest must be 64-char lower-case hex SHA-256"
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * One generation / lifecycle event in a playground stream batch
 * (CORE-INTERFACE GenerationEvent projection — transport-agnostic).
 */
data class PlaygroundStreamEvent(
    val seq: Long,
    /** metadata | delta | usage | warning | state | terminal */
    val kind: String,
    val state: String? = null,
    val textDelta: String? = null,
    val isTerminal: Boolean = false,
    val error: OmniError? = null,
    val evidenceLabel: EvidenceLabel? = null,
) {
    init {
        require(seq >= 0L) { "seq must be non-negative" }
        require(kind.isNotBlank()) { "kind must be non-blank" }
        require(kind in STREAM_KINDS) {
            "unknown stream event kind (fail closed): $kind"
        }
    }

    companion object {
        val STREAM_KINDS: Set<String> = setOf(
            "metadata",
            "delta",
            "usage",
            "warning",
            "state",
            "terminal",
        )
    }
}

/**
 * Bounded stream batch after exclusive [afterSeq] (CORE-INTERFACE §4 SSE / AIDL credit window).
 * [streamEpoch] fences resume after process death / cursor rebuild.
 */
data class PlaygroundStreamBatch(
    val requestId: String,
    val streamEpoch: Long,
    val afterSeq: Long,
    val events: List<PlaygroundStreamEvent>,
    val requestState: String,
    val cancelPhase: CancelPhase? = null,
    val isTerminal: Boolean,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must be non-blank" }
        require(streamEpoch >= 1L) { "streamEpoch must be >= 1" }
        require(afterSeq >= 0L) { "afterSeq must be non-negative" }
        require(requestState.isNotBlank()) { "requestState must be non-blank" }
    }
}

/**
 * Capability cell for a selected model/tab (UX-STATE §6).
 * UNSUPPORTED / UNKNOWN / CONDITIONAL / TEMPORARILY_UNAVAILABLE use distinct copy.
 */
data class CapabilityCellView(
    val capabilityId: CapabilityId,
    val state: CapabilityState,
    val labelKey: String,
    val explanationKey: String,
    val conditions: List<String> = emptyList(),
    val severity: UiSeverity,
    val allowedActions: List<PlaygroundUiAction>,
)

data class TabCapabilityView(
    val tab: PlaygroundTab,
    val required: List<CapabilityCellView>,
    val operable: Boolean,
    val blockingReasonKey: String?,
)

data class ModelOptionView(
    val modelRevisionId: String,
    val displayName: String,
    val installationState: String?,
    val capabilities: List<CapabilityCellView>,
)

/**
 * Evidenced metric field (CORE-OBSERVABILITY / PROD-QUALITY §4).
 * UNKNOWN must never render as 0.
 */
data class EvidencedMetricUi(
    val metricId: String,
    val value: Double?,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val source: String? = null,
    val confidence: Double? = null,
    val displayNumeric: Boolean,
) {
    init {
        require(metricId.isNotBlank()) { "metricId must be non-blank" }
        require(unit.isNotBlank()) { "unit must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.UNKNOWN) {
            // numeric may be null; never invent zero
        }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) { "REPORTED metrics must disclose source" }
        }
    }
}

/**
 * Cancel progress (FEAT-PLAYGROUND §2):
 * requested → acknowledged → execution stopped → terminal.
 */
data class CancelStatusUi(
    val requestId: String,
    val phase: CancelPhase,
    val labelKey: String,
    val requestState: String?,
    val isTerminal: Boolean,
    val error: OmniError? = null,
)

/**
 * Live request strip: queue / load / TTFT / tokens / actual engine / session / degraded.
 */
data class RequestStripUi(
    val requestId: String,
    val operationKind: String,
    val state: String,
    val labelKey: String,
    val severity: UiSeverity,
    val isTerminal: Boolean,
    val cancelPhase: CancelPhase?,
    val allowedActions: List<PlaygroundUiAction>,
    val actualModelRevisionId: String?,
    val engineBuildId: String?,
    val backend: String?,
    val sessionId: String?,
    val degraded: Boolean,
    val degradedReasons: List<String>,
    val metrics: List<EvidencedMetricUi>,
    val error: OmniError?,
    val assistantText: String?,
    val embeddingDimensions: Int?,
    val embeddingCount: Int?,
)

/**
 * Workspace chrome presentation — not a second lifecycle (UX-STATE §1).
 * Canonical request/job/runtime states remain in [RequestStripUi] / runtime strip.
 */
enum class PlaygroundScreenMode {
    /** No installed models / empty conversation. */
    EMPTY,
    /** Initial catalog / capability load. */
    LOADING,
    /** Hard error preventing interaction. */
    ERROR,
    /** Runtime DEGRADED or partial capability set — interaction may be limited. */
    DEGRADED,
    /** Ready to accept input. */
    READY,
    /** Inference in flight. */
    WORKING,
}

data class PlaygroundSnapshot(
    val screenMode: PlaygroundScreenMode,
    val activeTab: PlaygroundTab,
    val models: List<ModelOptionView>,
    val selectedModelRevisionId: String?,
    val tabCapabilities: List<TabCapabilityView>,
    val conversation: List<ChatMessage>,
    val activeRequest: RequestStripUi?,
    val assets: List<AssetHandleView>,
    val runtimeState: String,
    val runtimeDegradedReasons: List<String>,
    val metrics: List<EvidencedMetricUi>,
    val lastError: OmniError?,
)
