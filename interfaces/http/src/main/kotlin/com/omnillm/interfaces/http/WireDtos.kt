package com.omnillm.interfaces.http

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * OpenAPI wire DTOs for HTTP responses/requests beyond claim envelopes.
 * Authority: `specs/openapi/omnillm.openapi.yaml` components/schemas.
 * Field names are snake_case on the wire.
 */

@Serializable
data class OmniErrorDto(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class HealthDto(
    @SerialName("runtime_state") val runtimeState: String,
    @SerialName("resource_version") val resourceVersion: Long,
    @SerialName("degraded_reasons") val degradedReasons: List<String> = emptyList(),
)

/**
 * OpenAPI `CapabilityEntry` (:3002-3030): capability_id / state /
 * evidence_label required, reason_code optional. Enum-typed so unknown
 * capability IDs and labels fail closed at construction (INV-018).
 */
@Serializable
data class CapabilityEntryDto(
    @SerialName("capability_id") val capabilityId: CapabilityId,
    val state: CapabilityState,
    @SerialName("evidence_label") val evidenceLabel: EvidenceLabel,
    @SerialName("reason_code") val reasonCode: String? = null,
)

@Serializable
data class ModelInfoDto(
    @SerialName("model_revision_id") val modelRevisionId: String,
    @SerialName("display_name") val displayName: String,
    val capabilities: List<CapabilityEntryDto> = emptyList(),
    @SerialName("installation_state") val installationState: String? = null,
)

@Serializable
data class ModelPageDto(
    val items: List<ModelInfoDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    /** API-07: required by ModelPage spec (:2366-2368) — monotonic snapshot version. */
    @SerialName("snapshot_version") val snapshotVersion: Long = 0,
)

@Serializable
data class RequestStateDto(
    @SerialName("request_id") val requestId: String,
    val state: String,
    @SerialName("resource_version") val resourceVersion: Long = 0,
    @SerialName("actual_model_revision_id") val actualModelRevisionId: String? = null,
    @SerialName("engine_build_id") val engineBuildId: String? = null,
    val backend: String? = null,
    @SerialName("terminal_error") val terminalError: OmniErrorDto? = null,
)

/** OpenAPI `ChatMessage` (:1419-1440) — request-side message (role/content/asset_ids). */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
    @SerialName("asset_ids") val assetIds: List<String> = emptyList(),
)

/**
 * OpenAPI `AssistantMessage` (:2146-2161) — response-side message:
 * additionalProperties:false, content nullable, tool_calls optional.
 * Deliberately NOT ChatMessageDto (asset_ids would violate the contract).
 */
@Serializable
data class AssistantMessageDto(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
)

/** OpenAPI `ToolCall` (:2123-2145). */
@Serializable
data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunctionDto,
)

/** OpenAPI `ToolCall.function` (:2136-2145). */
@Serializable
data class ToolCallFunctionDto(
    val name: String,
    val arguments: String,
)

@Serializable
data class FallbackDto(
    val policy: String = "NONE",
    @SerialName("allowed_revision_ids") val allowedRevisionIds: List<String> = emptyList(),
)

@Serializable
data class OpenAIChatRequestDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /** API-09: spec oneOf string | array<string> (:1971-1979). */
    val stop: JsonElement? = null,
    /** API-09: spec ResponseFormat (:1980-1981). */
    @SerialName("response_format") val responseFormat: ResponseFormatDto? = null,
    /** API-09: spec ToolDefinition list (:1982-1986). */
    val tools: List<ToolDefinitionDto>? = null,
    /** API-09: spec oneOf string enum | NamedToolChoice (:1987-1994). */
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val user: String? = null,
    @SerialName("omnillm_fallback") val omnillmFallback: FallbackDto? = null,
    @SerialName("omnillm_deadline_ms") val omnillmDeadlineMs: Long? = null,
)

/** OpenAPI `ResponseFormat` (:2040-2053). */
@Serializable
data class ResponseFormatDto(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonSchemaResponseFormatDto? = null,
)

/** OpenAPI `JsonSchemaResponseFormat` (:2054-2074). */
@Serializable
data class JsonSchemaResponseFormatDto(
    val name: String,
    val description: String? = null,
    val strict: Boolean? = null,
    val schema: JsonObject = JsonObject(emptyMap()),
)

/** OpenAPI `ToolDefinition` (:2093-2103). */
@Serializable
data class ToolDefinitionDto(
    val type: String = "function",
    val function: ToolFunctionDefinitionDto,
)

/** OpenAPI `ToolFunctionDefinition` (:2075-2092). */
@Serializable
data class ToolFunctionDefinitionDto(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class OpenAIEmbeddingRequestDto(
    val model: String,
    val input: JsonElement,
    @SerialName("encoding_format") val encodingFormat: String = "float",
    val dimensions: Int? = null,
    val user: String? = null,
    @SerialName("omnillm_deadline_ms") val omnillmDeadlineMs: Long? = null,
)

@Serializable
data class ChatCompletionChoiceDto(
    val index: Int = 0,
    val message: AssistantMessageDto,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/**
 * OpenAPI `OmniExecutionInfo` (:2179-2209) — required on chat/embedding
 * responses. request_id is always populated; routing facts are omitted
 * (not fabricated) when the engine does not expose them.
 */
@Serializable
data class OmniExecutionInfoDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("actual_model_revision_id") val actualModelRevisionId: String? = null,
    @SerialName("engine_build_id") val engineBuildId: String? = null,
    val backend: String? = null,
    val degradations: List<String> = emptyList(),
    @SerialName("evidence_label") val evidenceLabel: EvidenceLabel? = null,
)

/**
 * API-07: ChatCompletionResponse requires usage + omnillm (:2210-2261).
 * additionalProperties:false — no extra envelope fields.
 */
@Serializable
data class ChatCompletionResponseDto(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoiceDto>,
    val usage: UsageDto = UsageDto(),
    val omnillm: OmniExecutionInfoDto,
)

@Serializable
data class EmbeddingDataDto(
    @SerialName("object") val objectType: String = "embedding",
    val index: Int = 0,
    val embedding: List<Double> = emptyList(),
)

/**
 * API-07: EmbeddingResponse requires usage + omnillm (:2262-2311).
 * Embedding usage schema only requires prompt_tokens/total_tokens.
 */
@Serializable
data class EmbeddingResponseDto(
    val id: String? = null,
    @SerialName("object") val objectType: String = "list",
    val model: String,
    val data: List<EmbeddingDataDto>,
    val usage: UsageDto = UsageDto(),
    val omnillm: OmniExecutionInfoDto,
)

@Serializable
data class AsyncInferenceRequestDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    val operation: String,
    /** OpenAPI oneOf: CHAT ⇒ this payload; EMBEDDING ⇒ [embedding]. */
    val chat: NativeChatPayloadDto? = null,
    /** OpenAPI oneOf: EMBEDDING ⇒ this payload; CHAT ⇒ [chat]. */
    val embedding: NativeEmbeddingPayloadDto? = null,
)

/**
 * OpenAPI `#/components/schemas/NativeChatPayload` (durable chat payload).
 *
 * D8: response_format / tools / tool_choice are spec properties (:2624-2649)
 * — modeled here so they can never be silently dropped (the codec used to
 * swallow them via ignoreUnknownKeys). The durable path fails closed when
 * they are present but unsupported (see ControlPlaneHttpHandler).
 */
@Serializable
data class NativeChatPayloadDto(
    val model: String,
    val messages: List<ChatMessageDto>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: List<String>? = null,
    /** D8: spec ResponseFormat (:1980-1981 mirror) — modeled, fail-closed on durable path. */
    @SerialName("response_format") val responseFormat: ResponseFormatDto? = null,
    /** D8: spec ToolDefinition list — modeled, fail-closed on durable path. */
    val tools: List<ToolDefinitionDto>? = null,
    /** D8: spec oneOf string enum | NamedToolChoice — modeled, fail-closed on durable path. */
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val user: String? = null,
    @SerialName("omnillm_fallback") val omnillmFallback: FallbackDto? = null,
    @SerialName("omnillm_deadline_ms") val omnillmDeadlineMs: Long? = null,
)

/** OpenAPI `#/components/schemas/NativeEmbeddingPayload` (durable embedding payload). */
@Serializable
data class NativeEmbeddingPayloadDto(
    val model: String,
    val input: JsonElement,
    @SerialName("encoding_format") val encodingFormat: String = "float",
    val dimensions: Int? = null,
    val user: String? = null,
    @SerialName("omnillm_deadline_ms") val omnillmDeadlineMs: Long? = null,
)

@Serializable
data class AssetCreateRequestDto(
    val command: CommandRequestDto,
    val purpose: String,
    @SerialName("max_bytes") val maxBytes: Long,
    @SerialName("ttl_seconds") val ttlSeconds: Long,
    @SerialName("content_type_hint") val contentTypeHint: String? = null,
    @SerialName("expected_sha256") val expectedSha256: String? = null,
)

@Serializable
data class AssetInfoDto(
    @SerialName("asset_id") val assetId: String,
    val state: String,
    @SerialName("expires_at") val expiresAt: String,
    val bytes: Long? = null,
    val sha256: String? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
    val error: OmniErrorDto? = null,
)

@Serializable
data class JobInfoDto(
    @SerialName("job_id") val jobId: String,
    val state: String,
    @SerialName("resource_version") val resourceVersion: Long = 0,
    val progress: Double? = null,
    val error: OmniErrorDto? = null,
)

@Serializable
data class JobPageDto(
    val items: List<JobInfoDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    /** API-07: required by JobPage spec (:2382-2384). */
    @SerialName("snapshot_version") val snapshotVersion: Long = 0,
)

@Serializable
data class JobSpecDto(
    val command: CommandRequestDto,
    @SerialName("job_id") val jobId: String,
    val kind: String,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

/**
 * API-08: OpenAPI `MetricSnapshot` (:2466-2481) — snapshot_version / samples
 * / next_page_token (the old `resource_version`/`series` names dropped data).
 */
@Serializable
data class MetricSnapshotDto(
    @SerialName("snapshot_version") val snapshotVersion: Long = 0,
    val samples: List<MetricSampleDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
)

/**
 * API-08: OpenAPI `MetricSample` (:2435-2465) — name/value/unit/
 * evidence_label/sampled_at required; evidence_label follows the
 * measurement-catalog semantics (MEASURED/ESTIMATED/REPORTED/
 * LAST_SAMPLED/UNKNOWN).
 */
@Serializable
data class MetricSampleDto(
    val name: String,
    val value: Double,
    val unit: String,
    @SerialName("evidence_label") val evidenceLabel: EvidenceLabel,
    @SerialName("sampled_at") val sampledAt: String,
    val dimensions: Map<String, String> = emptyMap(),
)

@Serializable
data class SettingsSnapshotDto(
    @SerialName("resource_version") val resourceVersion: Long,
    val values: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class SettingsPatchDto(
    val command: CommandRequestDto,
    val changes: Map<String, JsonElement>,
)

/**
 * API-07: OpenAPI `ClientInfo` (:2385-2418) — client_id/display_name/state/
 * scopes/revocation_epoch required (the old summary shape dropped
 * display_name + revocation_epoch).
 */
@Serializable
data class ClientInfoDto(
    @SerialName("client_id") val clientId: String,
    @SerialName("display_name") val displayName: String,
    val state: String,
    val scopes: List<String> = emptyList(),
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("revocation_epoch") val revocationEpoch: Long = 0,
)

@Serializable
data class ClientPageDto(
    val items: List<ClientInfoDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    /** API-07: required by ClientPage spec (:2432-2434). */
    @SerialName("snapshot_version") val snapshotVersion: Long = 0,
)

@Serializable
data class DiagnosticExportRequestDto(
    val command: CommandRequestDto,
    @SerialName("include_detail") val includeDetail: Boolean = false,
)

@Serializable
data class ContentReportProposalRequestDto(
    val command: CommandRequestDto,
    @SerialName("report_id") val reportId: String,
    val category: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("app_build") val appBuild: String,
    @SerialName("model_revision_id") val modelRevisionId: String,
    @SerialName("engine_build_id") val engineBuildId: String,
    val backend: String,
    @SerialName("local_policy_version") val localPolicyVersion: String,
    @SerialName("output_digest") val outputDigest: String,
    @SerialName("user_locale") val userLocale: String,
    val description: String? = null,
    @SerialName("prompt_excerpt") val promptExcerpt: String? = null,
    @SerialName("output_excerpt") val outputExcerpt: String? = null,
    @SerialName("diagnostic_summary") val diagnosticSummary: String? = null,
)

@Serializable
data class ContentReportInfoDto(
    @SerialName("report_id") val reportId: String,
    val state: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("resource_version") val resourceVersion: Long = 0,
    @SerialName("receipt_id") val receiptId: String? = null,
    val error: OmniErrorDto? = null,
)

@Serializable
data class ContentReportReceiptDto(
    @SerialName("receipt_id") val receiptId: String,
    @SerialName("report_id") val reportId: String,
    @SerialName("accepted_at") val acceptedAt: String,
    @SerialName("status_url") val statusUrl: String,
)

@Serializable
data class TokenIssueRequestDto(
    val command: CommandRequestDto,
    @SerialName("client_id") val clientId: String,
    @SerialName("display_name") val displayName: String,
    val scopes: List<String> = emptyList(),
    @SerialName("expires_in_seconds") val expiresInSeconds: Long? = null,
)

/**
 * OpenAPI `TokenIssueResult` (:2685-2714) — additionalProperties:false.
 * D23b: `loopback_only` is NOT a spec property (the token's loopback-only
 * constraint is a server-side transport fact, never a wire field) — it was
 * emitted anyway and strict spec clients rejected the 201 response.
 */
@Serializable
data class TokenIssueResultDto(
    @SerialName("token_id") val tokenId: String,
    @SerialName("client_id") val clientId: String,
    /** Plaintext returned only once at issuance (SEC-PROFILE). */
    val token: String,
    val scopes: List<String> = emptyList(),
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("revocation_epoch") val revocationEpoch: Long = 0L,
    @SerialName("receipt_expires_at") val receiptExpiresAt: String? = null,
)

/** OpenAPI `#/components/schemas/TokenInfo` (metadata list entry). */
@Serializable
data class TokenInfoDto(
    @SerialName("token_id") val tokenId: String,
    @SerialName("client_id") val clientId: String? = null,
    val state: String,
    val scopes: List<String> = emptyList(),
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("revocation_epoch") val revocationEpoch: Long = 0L,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class TokenPageDto(
    val items: List<TokenInfoDto> = emptyList(),
    @SerialName("next_page_token") val nextPageToken: String? = null,
    /** API-07: required by TokenPage spec (:2772-2774). */
    @SerialName("snapshot_version") val snapshotVersion: Long = 0,
)

@Serializable
data class LanEnableRequestDto(
    val command: CommandRequestDto,
)

/**
 * API-10: OpenAPI `LanPairingChallengeCreateRequest` (:2775-2799) —
 * challenge_id (client-generated) / requested_scopes required;
 * client_display_hint optional; ttl_seconds const 300.
 * The old `scopes` name and handler-fabricated challengeId violated the contract.
 */
@Serializable
data class LanPairingChallengeCreateRequestDto(
    val command: CommandRequestDto,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("requested_scopes") val requestedScopes: List<String> = emptyList(),
    @SerialName("client_display_hint") val clientDisplayHint: String? = null,
    @SerialName("ttl_seconds") val ttlSeconds: Int? = null,
)

/**
 * Handler-level result: either a JSON-serializable body + status, or a typed OmniError.
 * Transport maps [OmniError] → wire [OmniErrorDto] with catalog HTTP status.
 */
sealed class HttpHandlerResult<out T> {
    data class Ok<T>(
        val body: T,
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
    ) : HttpHandlerResult<T>()

    data class Err(
        val error: com.omnillm.core.errors.generated.OmniError,
    ) : HttpHandlerResult<Nothing>()
}

/**
 * SSE outcome for chat stream / request events.
 *
 * CORE-INTERFACE §4: pre-stream errors use HTTP status; once the first SSE event is
 * committed, subsequent failures use a terminal event (not a late HTTP status).
 * Default Session disposition for SSE is **stateless** — disconnect does not auto-resume Session.
 */
sealed class SseHandlerResult {
    /** Fail before any SSE bytes (HTTP JSON OmniError). */
    data class PreStreamError(
        val error: com.omnillm.core.errors.generated.OmniError,
    ) : SseHandlerResult()

    /**
     * Stream committed. [events] yields framed data payloads; [terminal] is required
     * after commit (including success terminal / [DONE] for OpenAI chat).
     */
    data class Stream(
        val requestId: String,
        val events: kotlinx.coroutines.flow.Flow<SseEvent>,
        val headers: Map<String, String> = emptyMap(),
    ) : SseHandlerResult()
}

/**
 * One SSE unit before framing.
 * [event] is optional event name; [data] is the data field (may be multi-line).
 * [isTerminal] marks the final event after which the stream ends.
 */
data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
    val isTerminal: Boolean = false,
)
