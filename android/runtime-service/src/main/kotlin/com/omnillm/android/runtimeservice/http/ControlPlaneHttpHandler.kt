package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.JobId
import com.omnillm.interfaces.http.AcceptedRequestDto
import com.omnillm.interfaces.http.AssetCreateRequestDto
import com.omnillm.interfaces.http.AssetInfoDto
import com.omnillm.interfaces.http.AsyncInferenceRequestDto
import com.omnillm.interfaces.http.ChatCompletionChoiceDto
import com.omnillm.interfaces.http.ChatCompletionResponseDto
import com.omnillm.interfaces.http.ChatMessageDto
import com.omnillm.interfaces.http.ClientPageDto
import com.omnillm.interfaces.http.ClientSummaryDto
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.CommandResultDto
import com.omnillm.interfaces.http.ContentReportInfoDto
import com.omnillm.interfaces.http.ContentReportProposalRequestDto
import com.omnillm.interfaces.http.ContentReportReceiptDto
import com.omnillm.interfaces.http.DiagnosticExportRequestDto
import com.omnillm.interfaces.http.EmbeddingResponseDto
import com.omnillm.interfaces.http.HealthDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.HttpJson
import com.omnillm.interfaces.http.JobInfoDto
import com.omnillm.interfaces.http.JobPageDto
import com.omnillm.interfaces.http.JobSpecDto
import com.omnillm.interfaces.http.JsonRawBody
import com.omnillm.interfaces.http.LanEnableRequestDto
import com.omnillm.interfaces.http.LanPairingChallengeCreateRequestDto
import com.omnillm.interfaces.http.MetricSummaryDto
import com.omnillm.interfaces.http.ModelInfoDto
import com.omnillm.interfaces.http.ModelPageDto
import com.omnillm.interfaces.http.NativeChatPayloadDto
import com.omnillm.interfaces.http.NativeEmbeddingPayloadDto
import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.OpenAIChatRequestDto
import com.omnillm.interfaces.http.OpenAIEmbeddingRequestDto
import com.omnillm.interfaces.http.RequestStateDto
import com.omnillm.interfaces.http.SettingsPatchDto
import com.omnillm.interfaces.http.SettingsSnapshotDto
import com.omnillm.interfaces.http.SseEvent
import com.omnillm.interfaces.http.SseHandlerResult
import com.omnillm.interfaces.http.TokenInfoDto
import com.omnillm.interfaces.http.TokenIssueRequestDto
import com.omnillm.interfaces.http.TokenIssueResultDto
import com.omnillm.interfaces.http.TokenPageDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.sse.SseFraming
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.contentreport.api.CancelReportSpec
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.api.CreateProposalSpec
import com.omnillm.features.contentreport.api.DiscardReportSpec
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanCommandIdentity
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.tools.api.ToolsApi
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.policy.SettingValue
import com.omnillm.runtime.policy.acl.AccessControlEnforcer
import com.omnillm.runtime.requestregistry.ClaimOutcome
import com.omnillm.runtime.requestregistry.CommandLedger
import com.omnillm.runtime.requestregistry.RequestRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP handler that maps OpenAPI operations onto control-plane services.
 *
 * Hard rules:
 * - No engine selection in this adapter (ADR-011) — Orchestrator owns routing.
 * - Claim-or-return via [RequestRegistry] / [CommandLedger] (ADR-004/005).
 * - Plan has no domain mutation inside transport; submit goes to Orchestrator.
 * - Feature Pack mutations go through plane-hosted APIs/ports (wave B).
 *
 * Sync OpenAI chat/embeddings currently accept-and-query durable path when
 * Orchestrator is attached; otherwise CAPABILITY_UNSUPPORTED.
 */
class ControlPlaneHttpHandler(
    private val runtimeState: () -> String,
    private val resourceVersion: () -> Long,
    private val requestRegistry: RequestRegistry,
    private val commandLedger: CommandLedger,
    internal val tokenService: LoopbackTokenService,
    private val jobManager: JobManager = JobManager(),
    private val policyManager: PolicyManager = PolicyManager(),
    /**
     * API-50: principal/ACL enforcement (profile + transport + revocation epoch).
     * Default is always-on; production injects the plane stack's enforcer so the
     * revocation epoch manager is shared with the token service. The existing
     * token-scope checks stay (defense in depth).
     */
    private val accessControl: AccessControlEnforcer = AccessControlEnforcer(),
    private val orchestrator: Orchestrator? = null,
    private val modelCatalog: () -> List<ModelInfoDto> = { emptyList() },
    private val metricSummary: () -> MetricSummaryDto = { MetricSummaryDto() },
    private val metricDetail: () -> MetricSummaryDto = { MetricSummaryDto() },
    private val clock: () -> Instant = { Instant.now() },
    private val lanPorts: LanRuntimePorts? = null,
    private val diagnosticsApi: DiagnosticsApi? = null,
    private val contentReportApi: ContentReportApi? = null,
    private val routingApi: RoutingApi? = null,
    private val toolsApi: ToolsApi? = null,
    private val benchmarkApi: BenchmarkApi? = null,
    /**
     * Exploratory engine access (engine execute binding + model manager).
     * Production default resolves the attached RuntimeControlPlane; tests inject
     * a hermetic source. Fail-closed null when the plane is not attached.
     */
    private val exploratorySource: () -> ExploratoryInferenceSource? = {
        val plane = com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane.get()
        if (plane == null) {
            null
        } else {
            try {
                plane.ensureEnginePacksAttached()
                ExploratoryInferenceSource(
                    binding = plane.engineExecute,
                    modelManager = plane.modelManager,
                )
            } catch (_: Exception) {
                null
            }
        }
    },
) : OmniHttpHandlerPort {

    private val assets = ConcurrentHashMap<String, AssetRecord>()
    private val clients = ConcurrentHashMap<String, ClientSummaryDto>()

    /**
     * API-16: background pump coroutines drive durable-request execution through
     * the existing Orchestrator pump API (no Orchestrator changes needed).
     */
    private val pumpScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class AssetRecord(
        val info: AssetInfoDto,
        val ownerPrincipalId: String,
        val maxBytes: Long,
        val expiresAtEpochMs: Long,
        var content: ByteArray? = null,
        /** COR-23i: integrity facts validated at upload time, re-checked at commit. */
        var validatedSha256: String? = null,
        var validatedBytes: Long? = null,
    ) {
        fun isExpired(nowEpochMs: Long): Boolean = expiresAtEpochMs <= nowEpochMs
    }

    // ----- Health ------------------------------------------------------------

    override suspend fun getHealth(): HttpHandlerResult<HealthDto> {
        // Minimal unauth liveness — no model/device detail (CORE-INTERFACE §3).
        val state = runtimeState()
        val degraded = if (state == "DEGRADED" || state == "RECOVERING") {
            listOf(state)
        } else {
            emptyList()
        }
        return HttpHandlerResult.Ok(
            HealthDto(
                runtimeState = state,
                resourceVersion = resourceVersion(),
                degradedReasons = degraded,
            ),
        )
    }

    // ----- Models / capabilities ---------------------------------------------

    override suspend fun listModels(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<ModelPageDto> {
        enforceAccess(principal, "list-models", AccessScope.models_read)?.let { return it }
        // COR-23a: real cursor pagination — pageToken is honored, never ignored.
        val all = modelCatalog()
        val from = decodePageCursor(pageToken)
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(message = "malformed page token"),
            )
        if (from > all.size) {
            return HttpHandlerResult.Err(
                OmniError.CURSOR_GONE(
                    message = "models page cursor beyond catalog",
                    details = mapOf("cursor" to from.toString()),
                ),
            )
        }
        val page = all.drop(from).take(MODELS_PAGE_SIZE)
        val nextToken = if (from + page.size < all.size) {
            encodePageCursor(from + page.size)
        } else {
            null
        }
        return HttpHandlerResult.Ok(ModelPageDto(items = page, nextPageToken = nextToken))
    }

    /**
     * Opaque cursor: `base64url("models-v1:<index>")`. Unparseable tokens fail
     * closed with INVALID_REQUEST (never silently treated as page one).
     */
    private fun encodePageCursor(index: Int): String {
        val raw = "models-v1:$index".toByteArray(Charsets.UTF_8)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun decodePageCursor(token: String?): Int? {
        if (token == null || token.isBlank()) return 0
        val raw = try {
            Base64.getUrlDecoder().decode(token)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val text = String(raw, Charsets.UTF_8)
        if (!text.startsWith("models-v1:")) return null
        val index = text.removePrefix("models-v1:").toIntOrNull() ?: return null
        if (index < 0) return null
        return index
    }

    // ----- Inference ---------------------------------------------------------

    override suspend fun createChatCompletion(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): HttpHandlerResult<ChatCompletionResponseDto> {
        enforceAccess(principal, "chat", AccessScope.inference_create)?.let { return it }
        if (orchestrator == null) {
            return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "inference orchestrator not attached",
                ),
            )
        }
        // Sync OpenAI profile: optional client IDs; generate when omitted.
        val requestId = requestIdHeader?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val idem = idempotencyKeyHeader?.takeIf { it.isNotBlank() } ?: "sync-chat-$requestId"
        // Exploratory path when plane engine bound + flag; otherwise honest fail-closed.
        // Never invents SUPPORTED cells. Prefer durable /omni/v1/requests for production clients.
        val source = exploratorySource()
        if (source != null) {
            val binding = source.binding
            if (binding.isEngineBound() && binding.isExploratoryExecuteEnabled()) {
                val messages = request.messages.filter { it.role.isNotBlank() }
                val userText = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
                    ?: messages.lastOrNull()?.content
                    ?: ""
                if (messages.isEmpty() || userText.isBlank()) {
                    return HttpHandlerResult.Err(
                        OmniError.INVALID_REQUEST(
                            message = "chat messages must include non-blank user content",
                        ),
                    )
                }
                // COR-13: idempotency digest covers the FULL message content (same-length
                // different-content must conflict, not return Existing).
                val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                    "http-sync-chat|$requestId|$idem|${request.model}|${chatMessageDigest(messages)}",
                )
                val revisionHex = request.model.lowercase().let {
                    if (it.matches(Regex("^[0-9a-f]{64}$"))) it
                    else com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|${request.model}")
                }
                val port = com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts
                    .playgroundInference(
                        orchestrator = orchestrator,
                        binding = binding,
                        modelManager = source.modelManager,
                        clockMs = { clock().toEpochMilli() },
                        runtimeEpoch = { resourceVersion() },
                    )
                val spec = com.omnillm.features.playground.api.ChatRequestSpec(
                    identity = com.omnillm.features.playground.api.InferenceIdentity(
                        requestId = requestId,
                        idempotencyKey = idem,
                        canonicalInputDigest = digest,
                    ),
                    modelRevisionId = revisionHex,
                    // Multi-turn: forward the full conversation, not just the last user turn.
                    messages = messages.map {
                        com.omnillm.features.playground.api.ChatMessage(
                            role = it.role,
                            content = it.content,
                        )
                    },
                    stream = false,
                )
                return when (
                    val r = port.startChat(
                        com.omnillm.core.contracts.PrincipalId.parse(principal.principalId),
                        spec,
                    )
                ) {
                    is OmniResult.Err -> HttpHandlerResult.Err(r.error)
                    is OmniResult.Ok -> {
                        val handle = r.value
                        if (handle.error != null) {
                            HttpHandlerResult.Err(handle.error!!)
                        } else {
                            val text = handle.assistantText
                            if (text.isNullOrBlank()) {
                                // COR-09: never return a fake placeholder success. Token deltas
                                // are not surfaced by the control-plane stream API (digests only),
                                // so honest fail-closed beats invented "stop" text.
                                HttpHandlerResult.Err(
                                    OmniError.CAPABILITY_UNSUPPORTED(
                                        message = "sync chat executed without aggregated token text; " +
                                            "engine token deltas are not exposed on the control-plane API — " +
                                            "use the SSE chat stream or durable /omni/v1/requests events",
                                        details = mapOf(
                                            "requestId" to requestId,
                                            "state" to handle.state,
                                        ),
                                    ),
                                )
                            } else {
                                HttpHandlerResult.Ok(
                                    ChatCompletionResponseDto(
                                        id = "chatcmpl-$requestId",
                                        created = clock().epochSecond,
                                        model = handle.actualModelRevisionId ?: request.model,
                                        choices = listOf(
                                            ChatCompletionChoiceDto(
                                                index = 0,
                                                message = ChatMessageDto(
                                                    role = "assistant",
                                                    content = text,
                                                ),
                                                finishReason = "stop",
                                            ),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        return HttpHandlerResult.Err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "sync chat requires engine attached + runtime.exploratoryExecuteEnabled " +
                    "(or READY model candidates via durable /omni/v1/requests)",
                details = mapOf(
                    "requestId" to requestId,
                    "idempotencyKey" to idem,
                    "model" to request.model,
                    "setting" to com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
                        .SETTING_EXPLORATORY_EXECUTE,
                ),
            ),
        )
    }

    override suspend fun createChatCompletionStream(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): SseHandlerResult {
        // Pre-stream error (HTTP) — stream not committed (CORE-INTERFACE §4).
        enforceAccess(principal, "chat-stream", AccessScope.inference_create)
            ?.let { return SseHandlerResult.PreStreamError((it as HttpHandlerResult.Err).error) }
        if (orchestrator == null) {
            return SseHandlerResult.PreStreamError(
                OmniError.CAPABILITY_UNSUPPORTED(message = "inference orchestrator not attached"),
            )
        }
        val source = exploratorySource()
        if (source != null) {
            val binding = source.binding
            if (binding.isEngineBound() && binding.isExploratoryExecuteEnabled()) {
                val messages = request.messages.filter { it.role.isNotBlank() }
                val userText = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
                    ?: messages.lastOrNull()?.content
                    ?: ""
                if (messages.isEmpty() || userText.isBlank()) {
                    return SseHandlerResult.PreStreamError(
                        OmniError.INVALID_REQUEST(
                            message = "chat messages must include non-blank user content",
                        ),
                    )
                }
                val requestId = requestIdHeader?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val idem = idempotencyKeyHeader?.takeIf { it.isNotBlank() }
                    ?: "stream-chat-$requestId"
                // COR-13: digest covers the FULL message content, not the length.
                val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                    "http-stream-chat|$requestId|$idem|${request.model}|${chatMessageDigest(messages)}",
                )
                val revisionHex = request.model.lowercase().let {
                    if (it.matches(Regex("^[0-9a-f]{64}$"))) it
                    else com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|${request.model}")
                }
                val port = com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts
                    .playgroundInference(
                        orchestrator = orchestrator,
                        binding = binding,
                        modelManager = source.modelManager,
                        clockMs = { clock().toEpochMilli() },
                        runtimeEpoch = { resourceVersion() },
                    )
                val spec = com.omnillm.features.playground.api.ChatRequestSpec(
                    identity = com.omnillm.features.playground.api.InferenceIdentity(
                        requestId = requestId,
                        idempotencyKey = idem,
                        canonicalInputDigest = digest,
                    ),
                    modelRevisionId = revisionHex,
                    messages = messages.map {
                        com.omnillm.features.playground.api.ChatMessage(
                            role = it.role,
                            content = it.content,
                        )
                    },
                    stream = true,
                )
                return when (
                    val r = port.startChat(
                        com.omnillm.core.contracts.PrincipalId.parse(principal.principalId),
                        spec,
                    )
                ) {
                    is OmniResult.Err -> SseHandlerResult.PreStreamError(r.error)
                    is OmniResult.Ok -> {
                        val handle = r.value
                        if (handle.error != null) {
                            SseHandlerResult.PreStreamError(handle.error!!)
                        } else {
                            // COR-03: authorize BEFORE committing the SSE stream. Owner-only
                            // by default (inference.read-own); jobs.read-all is the explicit
                            // cross-owner read exception (same semantics as getRequest).
                            val owned = requestRegistry
                                .queryRequest(RequestId.parse(handle.requestId))
                                ?.let {
                                    it.principalId == principal.principalId ||
                                        principal.hasScope("jobs.read-all")
                                } ?: false
                            if (!owned) {
                                SseHandlerResult.PreStreamError(
                                    OmniError.FORBIDDEN(message = "not owner of request"),
                                )
                            } else {
                                streamOpenAiChatChunks(
                                    requestId = handle.requestId,
                                    model = request.model,
                                    principal = principal,
                                    port = port,
                                )
                            }
                        }
                    }
                }
            }
        }
        return SseHandlerResult.PreStreamError(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "streaming chat requires engine attached + " +
                    "runtime.exploratoryExecuteEnabled",
                details = mapOf(
                    "requestId" to (requestIdHeader ?: "client-generated"),
                    "setting" to com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
                        .SETTING_EXPLORATORY_EXECUTE,
                ),
            ),
        )
    }

    /**
     * OpenAI SSE chunk framing over the durable inference-port projection
     * (CORE-INTERFACE §4: post-commit errors are terminal events, never late HTTP).
     * Stateless Session default: disconnect does not auto-continue.
     *
     * COR-03: polls the [PlaygroundInferencePort] (never the LOCAL_UI-gated
     * PlaygroundService) and NEVER throws inside the flow — any poll failure is
     * routed through the terminal event channel so the SSE connection closes
     * gracefully instead of crashing mid-stream.
     */
    internal fun streamOpenAiChatChunks(
        requestId: String,
        model: String,
        principal: HttpPrincipal,
        port: com.omnillm.features.playground.ports.PlaygroundInferencePort,
    ): SseHandlerResult.Stream {
        val created = clock().epochSecond
        val events = flow {
            // Role preamble chunk (OpenAI convention).
            emit(
                SseEvent(
                    data = openAiChunkJson(requestId, created, model, roleDelta = "assistant"),
                    event = null,
                    id = "0",
                    isTerminal = false,
                ),
            )
            var seqId = 1L
            var done = false
            var attempts = 0
            while (!done && attempts < MAX_STREAM_POLLS) {
                attempts++
                val batch: OmniResult<com.omnillm.features.playground.ports.InferenceHandle>? = try {
                    port.query(
                        PrincipalId.parse(principal.principalId),
                        requestId,
                    )
                } catch (t: Throwable) {
                    // Never throw inside the flow — terminal error event (COR-03).
                    val errorJson = HttpJson.codec.encodeToString(
                        JsonElement.serializer(),
                        JsonObject(
                            mapOf(
                                "error" to JsonObject(
                                    mapOf(
                                        "message" to JsonPrimitive(t.message ?: "stream projection failed"),
                                    ),
                                ),
                            ),
                        ),
                    )
                    emit(
                        SseEvent(
                            data = errorJson,
                            event = SseFraming.EVENT_TERMINAL,
                            id = (seqId++).toString(),
                            isTerminal = true,
                        ),
                    )
                    done = true
                    null
                }
                if (done) continue
                when (batch) {
                    null -> Unit // unreachable: done==false implies non-null
                    is OmniResult.Err -> {
                        val errorJson = HttpJson.codec.encodeToString(
                            JsonElement.serializer(),
                            JsonObject(
                                mapOf(
                                    "error" to JsonObject(
                                        mapOf(
                                            "message" to JsonPrimitive(batch.error.message ?: "stream error"),
                                        ),
                                    ),
                                ),
                            ),
                        )
                        emit(
                            SseEvent(
                                data = errorJson,
                                event = SseFraming.EVENT_TERMINAL,
                                id = (seqId++).toString(),
                                isTerminal = true,
                            ),
                        )
                        done = true
                    }
                    is OmniResult.Ok -> {
                        val handle = batch.value
                        handle.assistantText?.takeIf { it.isNotEmpty() }?.let { text ->
                            emit(
                                SseEvent(
                                    data = openAiChunkJson(
                                        requestId,
                                        created,
                                        model,
                                        contentDelta = text,
                                    ),
                                    event = null,
                                    id = (seqId++).toString(),
                                    isTerminal = false,
                                ),
                            )
                        }
                        val terminal = handle.state in STREAM_TERMINAL_STATES || handle.error != null
                        if (terminal) {
                            // Final chunk with finish_reason + usage summary, then [DONE].
                            emit(
                                SseEvent(
                                    data = openAiChunkJson(
                                        requestId,
                                        created,
                                        model,
                                        finishReason = "stop",
                                        terminal = true,
                                    ),
                                    event = null,
                                    id = (seqId++).toString(),
                                    isTerminal = false,
                                ),
                            )
                            emit(
                                SseEvent(
                                    data = "[DONE]",
                                    event = SseFraming.EVENT_TERMINAL,
                                    id = (seqId++).toString(),
                                    isTerminal = true,
                                ),
                            )
                            done = true
                        } else {
                            kotlinx.coroutines.delay(STREAM_POLL_MS)
                        }
                    }
                }
            }
            if (!done) {
                // Bounded safety terminal — never silently hang the client.
                emit(
                    SseEvent(
                        data = "[DONE]",
                        event = SseFraming.EVENT_TERMINAL,
                        id = (seqId++).toString(),
                        isTerminal = true,
                    ),
                )
            }
        }
        return SseHandlerResult.Stream(
            requestId = requestId,
            events = events,
            headers = mapOf("X-OmniLLM-Request-Id" to requestId),
        )
    }

    /** OpenAI `chat.completion.chunk` data payload (escaped JSON string). */
    private fun openAiChunkJson(
        requestId: String,
        created: Long,
        model: String,
        roleDelta: String? = null,
        contentDelta: String? = null,
        finishReason: String? = null,
        terminal: Boolean = false,
    ): String {
        val delta = buildMap {
            roleDelta?.let { put("role", JsonPrimitive(it)) }
            contentDelta?.let { put("content", JsonPrimitive(it)) }
        }
        val choice = buildMap {
            put("index", JsonPrimitive(0))
            put("delta", JsonObject(delta))
            put("finish_reason", finishReason?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        val obj = buildMap {
            put("id", JsonPrimitive("chatcmpl-$requestId"))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(created))
            put("model", JsonPrimitive(model))
            put("choices", JsonArray(listOf(JsonObject(choice))))
            if (terminal) {
                put("usage", JsonObject(
                    mapOf(
                        "prompt_tokens" to JsonPrimitive(0),
                        "completion_tokens" to JsonPrimitive(0),
                        "total_tokens" to JsonPrimitive(0),
                    ),
                ))
            }
        }
        return JsonObject(obj).toString()
    }

    override suspend fun createEmbedding(
        principal: HttpPrincipal,
        request: OpenAIEmbeddingRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): HttpHandlerResult<EmbeddingResponseDto> {
        enforceAccess(principal, "embedding", AccessScope.inference_create)?.let { return it }
        return HttpHandlerResult.Err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "embeddings require READY model candidates",
                details = mapOf("model" to request.model),
            ),
        )
    }

    override suspend fun createAsyncInferenceRequest(
        principal: HttpPrincipal,
        request: AsyncInferenceRequestDto,
    ): HttpHandlerResult<AcceptedRequestDto> {
        enforceAccess(principal, "create-request", AccessScope.inference_create)?.let { return it }
        // API-16: durable requests are claim + EXECUTE. Without an orchestrator the
        // claim could never run — fail closed honestly instead of 202-never-execute.
        val orch = orchestrator
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "durable request execution unavailable: orchestrator not attached",
                    details = mapOf("requestId" to request.requestId),
                ),
            )
        if (request.operation == "EMBEDDING") {
            return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNKNOWN(
                    message = "durable EMBEDDING execution is not qualified on the " +
                        "llama-cpp exploratory path",
                    details = mapOf(
                        "capability" to com.omnillm.core.canonical.generated.CapabilityId.EMBEDDING.id,
                    ),
                ),
            )
        }
        val payload = request.chat
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "operation CHAT requires a chat payload",
                    details = mapOf("requestId" to request.requestId),
                ),
            )
        val source = exploratorySource()
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "durable request execution requires engine binding on the control plane",
                    details = mapOf("requestId" to request.requestId),
                ),
            )
        if (!source.binding.isEngineBound()) {
            return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "durable request execution requires an attached engine",
                    details = mapOf("requestId" to request.requestId),
                ),
            )
        }
        // API-01: the canonical digest covers the FULL wire payload (chat/embedding)
        // and is persisted durably in the registry claim row — identical content
        // replays as Existing; changed content conflicts (IDEMPOTENCY_CONFLICT).
        val canonicalPayload = canonicalPayloadJson(request)
        val digest = sha256Hex(
            "${request.requestId}|${request.idempotencyKey}|${request.operation}|$canonicalPayload",
        )
        val revision = com.omnillm.android.runtimeservice.featurehost.FeatureRequestMapper
            .normalizeRevision(payload.model)
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(message = "invalid model in chat payload"),
            )
        val candidate = when (
            val c = com.omnillm.android.runtimeservice.featurehost.FeatureRequestMapper
                .resolveCandidate(
                    binding = source.binding,
                    modelManager = source.modelManager,
                    revision = revision,
                    deviceFingerprint = com.omnillm.core.contracts.DeviceExecutionFingerprint
                        .parse("device-fp-http-durable"),
                )
        ) {
            is OmniResult.Err -> return HttpHandlerResult.Err(c.error)
            is OmniResult.Ok -> c.value
        }
        val orchestrationRequest = com.omnillm.runtime.orchestrator.OrchestrationRequest(
            requestId = RequestId.parse(request.requestId),
            principalId = PrincipalId.parse(principal.principalId),
            idempotencyKey = IdempotencyKey.parse(request.idempotencyKey),
            operationKind = "CHAT",
            canonicalRequestDigest = Sha256Digest.parse(digest),
            requiredCapabilities = setOf(
                com.omnillm.core.canonical.generated.CapabilityId.TEXT_GENERATION,
            ),
            requestedRevisionId = revision,
            candidates = listOf(candidate),
            routing = com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts
                .exploratoryRouting(),
            costClass = com.omnillm.runtime.orchestrator.CostClassLabels.GENERATION,
            runtimeEpoch = resourceVersion(),
            revocationEpoch = principal.revocationEpoch,
            deadlineMonotonic = Long.MAX_VALUE / 8,
        )
        return when (val submitted = orch.submit(orchestrationRequest)) {
            is OmniResult.Err -> HttpHandlerResult.Err(submitted.error)
            is OmniResult.Ok -> {
                // Drive execution through the EXISTING public orchestrator pump API.
                pumpScope.launch {
                    runCatching { orch.pumpAll(64) }
                }
                HttpHandlerResult.Ok(
                    body = AcceptedRequestDto(
                        requestId = submitted.value.requestId.value,
                        state = submitted.value.state,
                        queryUrl = "/omni/v1/requests/${submitted.value.requestId.value}",
                        eventsUrl = "/omni/v1/requests/${submitted.value.requestId.value}/events",
                    ),
                    status = 202,
                )
            }
        }
    }

    /** Canonical JSON of the wire payload for durable digesting (API-01). */
    private fun canonicalPayloadJson(request: AsyncInferenceRequestDto): String =
        try {
            when (request.operation) {
                "CHAT" -> HttpJson.codec.encodeToString(
                    NativeChatPayloadDto.serializer(),
                    request.chat ?: NativeChatPayloadDto(model = "", messages = emptyList()),
                )
                "EMBEDDING" -> HttpJson.codec.encodeToString(
                    NativeEmbeddingPayloadDto.serializer(),
                    request.embedding ?: NativeEmbeddingPayloadDto(model = "", input = JsonNull),
                )
                else -> request.operation
            }
        } catch (_: Exception) {
            request.operation
        }

    override suspend fun getRequest(
        principal: HttpPrincipal,
        requestId: String,
    ): HttpHandlerResult<RequestStateDto> {
        val row = requestRegistry.queryRequest(RequestId.parse(requestId))
            ?: return HttpHandlerResult.Err(
                OmniError.NOT_FOUND(
                    message = "request not found",
                    details = mapOf("requestId" to requestId),
                ),
            )
        enforceAccess(
            principal,
            "query-own-request",
            AccessScope.inference_read_own,
            resourceOwnerPrincipalId = row.principalId,
        )?.let { return it }
        if (row.principalId != principal.principalId && !principal.hasScope("jobs.read-all")) {
            // Own-only by default (inference.read-own).
            return HttpHandlerResult.Err(
                OmniError.FORBIDDEN(message = "not owner of request"),
            )
        }
        val orch = orchestrator?.query(RequestId.parse(requestId))
        val term = requestRegistry.queryRequestTerminal(RequestId.parse(requestId))
        return HttpHandlerResult.Ok(
            RequestStateDto(
                requestId = row.requestId,
                state = orch?.terminalState ?: orch?.state ?: row.state,
                resourceVersion = row.resourceVersion,
                actualModelRevisionId = orch?.actualRouting?.modelRevisionId?.hex,
                engineBuildId = orch?.actualRouting?.engineBuildId?.value,
                backend = orch?.actualRouting?.backend,
                terminalError = term?.errorCode?.let {
                    OmniError.ofCode(it, message = it).let { e ->
                        com.omnillm.interfaces.http.OmniErrorDto(
                            code = e.code.code,
                            message = e.message ?: e.code.code,
                            retryable = e.retryable,
                        )
                    }
                },
            ),
        )
    }

    override suspend fun cancelRequest(
        principal: HttpPrincipal,
        requestId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "cancel-own-request", AccessScope.inference_cancel)
            ?.let { return it }
        // CORE-INTERFACE durable Command: non-create mutations require expectedVersion.
        if (command.expectedVersion == null) {
            return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "expectedVersion required for cancelRequest (CAS)",
                    details = mapOf("requestId" to requestId),
                ),
            )
        }
        val claim = claimCommand(principal, "CANCEL_REQUEST", command)
        if (claim is HttpHandlerResult.Err) return claim
        // COR-05: look up the request + verify ownership BEFORE any cancel side-effect.
        // Own-only by default; jobs.read-all is the explicit cross-owner exception
        // (same semantics as getRequest).
        val row = requestRegistry.queryRequest(RequestId.parse(requestId))
            ?: run {
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = "FAILED",
                    errorCode = OmniError.NOT_FOUND().code.code,
                    affectedResourceId = requestId,
                )
                return HttpHandlerResult.Err(
                    OmniError.NOT_FOUND(
                        message = "request not found",
                        details = mapOf("requestId" to requestId),
                    ),
                )
            }
        if (row.principalId != principal.principalId && !principal.hasScope("jobs.read-all")) {
            commandLedger.recordResult(
                CommandId.parse(command.commandId),
                state = "FAILED",
                errorCode = OmniError.FORBIDDEN().code.code,
                affectedResourceId = requestId,
            )
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of request"))
        }
        val orch = orchestrator
        val result = if (orch != null) {
            when (val c = orch.cancel(RequestId.parse(requestId))) {
                is OmniResult.Ok -> CommandResultDto(
                    commandId = command.commandId,
                    state = "SUCCEEDED",
                    resourceVersion = command.expectedVersion ?: 1,
                    affectedResourceId = requestId,
                )
                is OmniResult.Err -> {
                    // Honest failure (STATE_CONFLICT when STREAMING etc.) — never
                    // invent a SUCCEEDED cancellation.
                    commandLedger.recordResult(
                        CommandId.parse(command.commandId),
                        state = "FAILED",
                        errorCode = c.error.code.code,
                        affectedResourceId = requestId,
                    )
                    return HttpHandlerResult.Err(c.error)
                }
            }
        } else {
            // COR-22: no orchestrator ⇒ registry-level cancel (mirrors the binder
            // path OmniRuntimeFacade) instead of a fabricated SUCCEEDED.
            val terminal = requestRegistry.queryRequestTerminal(RequestId.parse(requestId))
            if (terminal == null) {
                when (
                    val t = requestRegistry.recordTerminal(
                        requestId = RequestId.parse(requestId),
                        terminalState = "CANCELLED",
                        terminalSeq = System.nanoTime(),
                        errorCode = OmniError.CANCELLED().code.code,
                    )
                ) {
                    is OmniResult.Err -> {
                        commandLedger.recordResult(
                            CommandId.parse(command.commandId),
                            state = "FAILED",
                            errorCode = t.error.code.code,
                            affectedResourceId = requestId,
                        )
                        return HttpHandlerResult.Err(t.error)
                    }
                    is OmniResult.Ok -> Unit
                }
            }
            CommandResultDto(
                commandId = command.commandId,
                state = "SUCCEEDED",
                resourceVersion = command.expectedVersion ?: 1,
                affectedResourceId = requestId,
            )
        }
        commandLedger.recordResult(
            CommandId.parse(command.commandId),
            state = result.state,
            resultJson = """{"requestId":"$requestId","resourceVersion":${result.resourceVersion}}""",
            affectedResourceId = requestId,
        )
        return HttpHandlerResult.Ok(result)
    }

    override suspend fun streamRequestEvents(
        principal: HttpPrincipal,
        requestId: String,
        afterSeq: Long?,
    ): SseHandlerResult {
        val row = requestRegistry.queryRequest(RequestId.parse(requestId))
            ?: return SseHandlerResult.PreStreamError(
                OmniError.NOT_FOUND(message = "request not found", details = mapOf("requestId" to requestId)),
            )
        enforceAccess(
            principal,
            "resume-own-stream",
            AccessScope.inference_read_own,
            resourceOwnerPrincipalId = row.principalId,
        )?.let { return SseHandlerResult.PreStreamError((it as HttpHandlerResult.Err).error) }
        if (row.principalId != principal.principalId) {
            return SseHandlerResult.PreStreamError(
                OmniError.FORBIDDEN(message = "not owner of request"),
            )
        }
        // API-16: events reflect REAL orchestrator state, and each poll itself
        // drives queued execution through the existing pump API (no Orchestrator
        // changes needed — REPORTED for follow-up if a dedicated worker is wanted).
        val orch = orchestrator
            ?: return SseHandlerResult.PreStreamError(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "request events require an orchestrator",
                    details = mapOf("requestId" to requestId),
                ),
            )
        runCatching { orch.pumpOnce() }
        val view = orch.query(RequestId.parse(requestId))
        val state = view?.terminalState ?: view?.state ?: row.state
        val errorCode = view?.errorCode
        val start = afterSeq ?: 0L
        val terminal = state in DURABLE_TERMINAL_STATES
        val events = flow {
            if (start < 1L) {
                emit(
                    SseEvent(
                        data = """{"request_id":"$requestId","state":"$state","seq":0}""",
                        event = "state",
                        id = "0",
                        isTerminal = false,
                    ),
                )
            }
            if (terminal && start < 2L) {
                val data = HttpJson.codec.encodeToString(
                    JsonElement.serializer(),
                    JsonObject(
                        buildMap {
                            put("request_id", JsonPrimitive(requestId))
                            put("state", JsonPrimitive(state))
                            put("terminal", JsonPrimitive(true))
                            errorCode?.let { put("error", JsonPrimitive(it)) }
                        },
                    ),
                )
                emit(
                    SseEvent(
                        data = data,
                        event = SseFraming.EVENT_TERMINAL,
                        id = "1",
                        isTerminal = true,
                    ),
                )
            }
        }
        return SseHandlerResult.Stream(
            requestId = requestId,
            events = events,
            headers = mapOf("X-OmniLLM-Request-Id" to requestId),
        )
    }

    // ----- Commands ----------------------------------------------------------

    override suspend fun getCommand(
        principal: HttpPrincipal,
        commandId: String,
    ): HttpHandlerResult<CommandResultDto> {
        val row = commandLedger.queryCommand(CommandId.parse(commandId))
            ?: return HttpHandlerResult.Err(
                OmniError.NOT_FOUND(
                    message = "command not found",
                    details = mapOf("commandId" to commandId),
                ),
            )
        enforceAccess(
            principal,
            "query-own-command-result",
            AccessScope.commands_read_own,
            resourceOwnerPrincipalId = row.principalId,
        )?.let { return it }
        if (row.principalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of command"))
        }
        return HttpHandlerResult.Ok(
            CommandResultDto(
                commandId = row.commandId,
                state = row.state,
                resourceVersion = row.resourceVersion,
                affectedResourceId = row.affectedResourceId,
            ),
        )
    }

    // ----- Assets ------------------------------------------------------------

    override suspend fun createAsset(
        principal: HttpPrincipal,
        request: AssetCreateRequestDto,
    ): HttpHandlerResult<AssetInfoDto> {
        enforceAccess(principal, "create-upload-handle", AccessScope.assets_create)
            ?.let { return it }
        if (!ClaimShapeOk.command(request.command)) {
            return HttpHandlerResult.Err(OmniError.INVALID_REQUEST(message = "invalid command claim"))
        }
        val claim = claimCommand(principal, "CREATE_ASSET", request.command)
        if (claim is HttpHandlerResult.Err) return claim

        // COR-17/SEC-06: evict expired entries and enforce the upper bound before
        // admitting a new asset (the assets map must stay bounded).
        evictExpiredAssets()
        if (assets.size >= MAX_ASSETS) {
            return HttpHandlerResult.Err(
                OmniError.ADMISSION_REJECTED(
                    message = "asset store at capacity",
                    details = mapOf("max" to MAX_ASSETS.toString()),
                ),
            )
        }
        val now = clock().toEpochMilli()
        val assetId = UUID.randomUUID().toString()
        val expiresAtEpochMs = now + request.ttlSeconds * 1000L
        val info = AssetInfoDto(
            assetId = assetId,
            state = "CREATED",
            expiresAt = Instant.ofEpochMilli(expiresAtEpochMs).toString(),
            bytes = 0,
            uploadUrl = "/omni/v1/assets/$assetId/content",
        )
        assets[assetId] = AssetRecord(
            info = info,
            ownerPrincipalId = principal.principalId,
            maxBytes = request.maxBytes,
            expiresAtEpochMs = expiresAtEpochMs,
        )
        commandLedger.recordResult(
            CommandId.parse(request.command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = assetId,
        )
        return HttpHandlerResult.Ok(info, status = 201)
    }

    override suspend fun uploadAsset(
        principal: HttpPrincipal,
        assetId: String,
        body: ByteArray,
        contentLength: Long?,
        command: CommandRequestDto?,
        expectedSha256: String?,
        expectedBytes: Long?,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "upload", AccessScope.assets_create)?.let { return it }
        // API-06: multipart uploads carry a CommandRequest (idempotency claim) and
        // optional expected_bytes / expected_sha256 integrity assertions.
        val claimedCommandId: String? = if (command != null) {
            if (!ClaimShapeOk.command(command)) {
                return HttpHandlerResult.Err(OmniError.INVALID_REQUEST(message = "invalid command claim"))
            }
            val claim = claimCommand(principal, "UPLOAD_ASSET", command)
            if (claim is HttpHandlerResult.Err) return claim
            command.commandId
        } else {
            null
        }
        val rec = assets[assetId]
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "asset not found"))
        if (rec.isExpired(clock().toEpochMilli())) {
            // Non-destructive: the record stays until admission-time eviction
            // (evictExpiredAssets) so every op reports ASSET_EXPIRED, not NOT_FOUND.
            return HttpHandlerResult.Err(
                OmniError.ASSET_EXPIRED(
                    message = "asset expired",
                    details = mapOf("assetId" to assetId),
                ),
            )
        }
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        if (body.size.toLong() > rec.maxBytes) {
            return HttpHandlerResult.Err(
                OmniError.TRANSPORT_TOO_LARGE(message = "exceeds asset max_bytes"),
            )
        }
        if (expectedBytes != null && body.size.toLong() != expectedBytes) {
            return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "content size mismatch vs expected_bytes",
                    details = mapOf(
                        "actual" to body.size.toString(),
                        "expected" to expectedBytes.toString(),
                    ),
                ),
            )
        }
        if (expectedSha256 != null &&
            sha256Hex(body) != expectedSha256.lowercase()
        ) {
            return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "content sha256 mismatch vs expected_sha256",
                ),
            )
        }
        if (rec.info.state !in setOf("CREATED", "UPLOADING")) {
            return HttpHandlerResult.Err(
                OmniError.STATE_CONFLICT(message = "asset not accepting upload", details = mapOf("state" to rec.info.state)),
            )
        }
        rec.content = body
        assets[assetId] = rec.copy(
            info = rec.info.copy(state = "UPLOADING", bytes = body.size.toLong()),
            // COR-23i: record the integrity facts validated at upload time so
            // commitAsset can re-validate against them (TOCTOU guard).
            validatedSha256 = expectedSha256?.lowercase() ?: sha256Hex(body),
            validatedBytes = expectedBytes ?: body.size.toLong(),
        )
        val cmdId = claimedCommandId ?: UUID.randomUUID().toString()
        if (claimedCommandId != null) {
            commandLedger.recordResult(
                CommandId.parse(claimedCommandId),
                state = "SUCCEEDED",
                affectedResourceId = assetId,
            )
        }
        return HttpHandlerResult.Ok(
            CommandResultDto(commandId = cmdId, state = "SUCCEEDED", resourceVersion = 1, affectedResourceId = assetId),
        )
    }

    override suspend fun commitAsset(
        principal: HttpPrincipal,
        assetId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        val claim = claimCommand(principal, "COMMIT_ASSET", command)
        if (claim is HttpHandlerResult.Err) return claim
        val rec = assets[assetId]
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "asset not found"))
        enforceAccess(
            principal,
            "commit",
            AccessScope.assets_create,
            resourceOwnerPrincipalId = rec.ownerPrincipalId,
        )?.let { return it }
        if (rec.isExpired(clock().toEpochMilli())) {
            // Non-destructive: the record stays until admission-time eviction
            // (evictExpiredAssets) so every op reports ASSET_EXPIRED, not NOT_FOUND.
            return HttpHandlerResult.Err(
                OmniError.ASSET_EXPIRED(
                    message = "asset expired",
                    details = mapOf("assetId" to assetId),
                ),
            )
        }
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        val bytes = rec.content
            ?: return HttpHandlerResult.Err(OmniError.ASSET_NOT_READY(message = "no content uploaded"))
        // COR-23i: TOCTOU guard — the bytes being committed must still match the
        // integrity facts validated at upload time. A content mutation between
        // upload and commit fails closed instead of being committed.
        val digest = sha256Hex(bytes)
        if (rec.validatedSha256 != null && digest != rec.validatedSha256) {
            return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "content sha256 changed since upload (commit rejected)",
                ),
            )
        }
        if (rec.validatedBytes != null && bytes.size.toLong() != rec.validatedBytes) {
            return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "content size changed since upload (commit rejected)",
                ),
            )
        }
        assets[assetId] = rec.copy(
            info = rec.info.copy(
                state = "READY",
                bytes = bytes.size.toLong(),
                sha256 = digest,
            ),
        )
        commandLedger.recordResult(
            CommandId.parse(command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = assetId,
        )
        return HttpHandlerResult.Ok(
            CommandResultDto(
                commandId = command.commandId,
                state = "SUCCEEDED",
                resourceVersion = 1,
                affectedResourceId = assetId,
            ),
        )
    }

    override suspend fun getAsset(
        principal: HttpPrincipal,
        assetId: String,
    ): HttpHandlerResult<AssetInfoDto> {
        val rec = assets[assetId]
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "asset not found"))
        enforceAccess(
            principal,
            "query-own-asset",
            AccessScope.assets_read_own,
            resourceOwnerPrincipalId = rec.ownerPrincipalId,
        )?.let { return it }
        if (rec.isExpired(clock().toEpochMilli())) {
            // Read-only probe: keep the record (eviction happens at admission via
            // evictExpiredAssets) so every op reports ASSET_EXPIRED, not NOT_FOUND.
            return HttpHandlerResult.Err(
                OmniError.ASSET_EXPIRED(
                    message = "asset expired",
                    details = mapOf("assetId" to assetId),
                ),
            )
        }
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        // Never return filesystem paths (CORE-INTERFACE §9).
        return HttpHandlerResult.Ok(rec.info)
    }

    override suspend fun deleteAsset(
        principal: HttpPrincipal,
        assetId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        // API-05: DELETE routes through the CommandLedger idempotency claim like
        // every other durable mutation (the OpenAPI body is mandatory).
        val claim = claimCommand(principal, "DELETE_ASSET", command)
        if (claim is HttpHandlerResult.Err) return claim
        val rec = assets[assetId]
            ?: run {
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = "FAILED",
                    errorCode = OmniError.NOT_FOUND().code.code,
                    affectedResourceId = assetId,
                )
                return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "asset not found"))
            }
        enforceAccess(
            principal,
            "delete-own-asset",
            AccessScope.assets_delete_own,
            resourceOwnerPrincipalId = rec.ownerPrincipalId,
        )?.let { return it }
        if (rec.isExpired(clock().toEpochMilli())) {
            // Non-destructive: the record stays until admission-time eviction
            // (evictExpiredAssets) so every op reports ASSET_EXPIRED, not NOT_FOUND.
            commandLedger.recordResult(
                CommandId.parse(command.commandId),
                state = "FAILED",
                errorCode = OmniError.ASSET_EXPIRED().code.code,
                affectedResourceId = assetId,
            )
            return HttpHandlerResult.Err(
                OmniError.ASSET_EXPIRED(
                    message = "asset expired",
                    details = mapOf("assetId" to assetId),
                ),
            )
        }
        if (rec.ownerPrincipalId != principal.principalId) {
            commandLedger.recordResult(
                CommandId.parse(command.commandId),
                state = "FAILED",
                errorCode = OmniError.FORBIDDEN().code.code,
                affectedResourceId = assetId,
            )
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        if (rec.info.state == "PINNED") {
            commandLedger.recordResult(
                CommandId.parse(command.commandId),
                state = "FAILED",
                errorCode = OmniError.STATE_CONFLICT().code.code,
                affectedResourceId = assetId,
            )
            return HttpHandlerResult.Err(
                OmniError.STATE_CONFLICT(message = "asset pinned by request"),
            )
        }
        assets[assetId] = rec.copy(info = rec.info.copy(state = "DELETED"), content = null)
        commandLedger.recordResult(
            CommandId.parse(command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = assetId,
        )
        return HttpHandlerResult.Ok(
            CommandResultDto(
                commandId = command.commandId,
                state = "SUCCEEDED",
                resourceVersion = 1,
                affectedResourceId = assetId,
            ),
        )
    }

    /** COR-17/SEC-06: drop expired asset entries (bounded in-memory asset store). */
    private fun evictExpiredAssets() {
        val now = clock().toEpochMilli()
        val expired = assets.entries.filter { it.value.isExpired(now) }.map { it.key }
        for (key in expired) {
            assets.remove(key)
        }
    }

    // ----- Jobs --------------------------------------------------------------

    override suspend fun createJob(
        principal: HttpPrincipal,
        request: JobSpecDto,
    ): HttpHandlerResult<JobInfoDto> {
        enforceAccess(principal, "create-control-jobs", AccessScope.jobs_manage)?.let { return it }
        val kind = JobKind.fromCatalogName(request.kind)
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(message = "unknown job kind", details = mapOf("kind" to request.kind)),
            )
        val claim = claimCommand(principal, "CREATE_JOB", request.command)
        if (claim is HttpHandlerResult.Err) return claim

        val params = mapJobParameters(kind, request.parameters)
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(message = "invalid or incomplete job parameters for kind ${kind.name}"),
            )
        val identity = JobIdentity(
            jobId = JobId(request.jobId),
            principalId = PrincipalId.parse(principal.principalId),
            kind = kind,
            idempotencyKey = IdempotencyKey.parse(request.command.idempotencyKey),
            canonicalSpecDigest = request.command.canonicalInputDigest,
        )
        return when (val created = jobManager.create(identity, params)) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(request.command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = request.jobId,
                )
                HttpHandlerResult.Ok(toJobInfo(created.value.record))
            }
            is OmniResult.Err -> HttpHandlerResult.Err(created.error)
        }
    }

    override suspend fun listOwnJobs(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<JobPageDto> {
        enforceAccess(principal, "read-own-jobs", AccessScope.jobs_read_own)?.let { return it }
        val items = jobManager.listOwn(PrincipalId.parse(principal.principalId)).map { toJobInfo(it) }
        return HttpHandlerResult.Ok(JobPageDto(items = items))
    }

    override suspend fun getJob(
        principal: HttpPrincipal,
        jobId: String,
    ): HttpHandlerResult<JobInfoDto> {
        enforceAccess(
            principal,
            "read-own-jobs",
            AccessScope.jobs_read_own,
            resourceOwnerPrincipalId = jobManager.query(JobId(jobId))
                ?.let { if (it is OmniResult.Ok) it.value.identity.principalId.value else null },
        )?.let { return it }
        return when (val q = jobManager.query(JobId(jobId))) {
            is OmniResult.Ok -> {
                if (q.value.identity.principalId.value != principal.principalId) {
                    HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of job"))
                } else {
                    HttpHandlerResult.Ok(toJobInfo(q.value))
                }
            }
            is OmniResult.Err -> HttpHandlerResult.Err(q.error)
        }
    }

    override suspend fun cancelJob(
        principal: HttpPrincipal,
        jobId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "create-control-jobs", AccessScope.jobs_manage)?.let { return it }
        val claim = claimCommand(principal, "CANCEL_JOB", command)
        if (claim is HttpHandlerResult.Err) return claim
        // COR-04: verify the job exists + ownership BEFORE the cancel side-effect,
        // so a 403 can never cancel a job the caller does not own.
        val job = when (val q = jobManager.query(JobId(jobId))) {
            is OmniResult.Err -> {
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = "FAILED",
                    errorCode = q.error.code.code,
                    affectedResourceId = jobId,
                )
                return HttpHandlerResult.Err(q.error)
            }
            is OmniResult.Ok -> q.value
        }
        if (job.identity.principalId.value != principal.principalId) {
            commandLedger.recordResult(
                CommandId.parse(command.commandId),
                state = "FAILED",
                errorCode = OmniError.FORBIDDEN().code.code,
                affectedResourceId = jobId,
            )
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of job"))
        }
        return when (val c = jobManager.cancel(JobId(jobId))) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = jobId,
                )
                HttpHandlerResult.Ok(
                    CommandResultDto(
                        commandId = command.commandId,
                        state = "SUCCEEDED",
                        resourceVersion = c.value.resourceVersion,
                        affectedResourceId = jobId,
                    ),
                )
            }
            is OmniResult.Err -> {
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = "FAILED",
                    errorCode = c.error.code.code,
                    affectedResourceId = jobId,
                )
                HttpHandlerResult.Err(c.error)
            }
        }
    }

    // ----- Metrics / settings / clients --------------------------------------

    override suspend fun getMetricSummary(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto> {
        enforceAccess(principal, "read-redacted-summary", AccessScope.metrics_read_summary)
            ?.let { return it }
        return HttpHandlerResult.Ok(metricSummary())
    }

    override suspend fun getMetricDetail(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto> {
        enforceAccess(principal, "read-local-detailed-metrics", AccessScope.metrics_read_detail)
            ?.let { return it }
        return HttpHandlerResult.Ok(metricDetail())
    }

    override suspend fun getSettings(principal: HttpPrincipal): HttpHandlerResult<SettingsSnapshotDto> {
        enforceAccess(principal, "read-effective-settings", AccessScope.settings_read)
            ?.let { return it }
        val snap = policyManager.settingsSnapshot()
        return HttpHandlerResult.Ok(
            SettingsSnapshotDto(
                resourceVersion = snap.resourceVersion,
                values = snap.values.mapValues { settingValueToJson(it.value) },
            ),
        )
    }

    override suspend fun patchSettings(
        principal: HttpPrincipal,
        request: SettingsPatchDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "change-settings", AccessScope.settings_write)?.let { return it }
        val claim = claimCommand(principal, "PATCH_SETTINGS", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        val changes = request.changes.mapValues { (_, v) -> jsonToSettingValue(v) }
            .mapNotNull { (k, v) -> v?.let { k to it } }
            .toMap()
        if (changes.size != request.changes.size) {
            return HttpHandlerResult.Err(OmniError.INVALID_REQUEST(message = "unsupported setting value type"))
        }
        val base = request.command.expectedVersion
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(message = "settings patch requires expected_version"),
            )
        return when (val p = policyManager.patchSettings(baseVersion = base, changes = changes)) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(request.command.commandId),
                    state = "SUCCEEDED",
                )
                // API-04: the OpenAPI 200 response for PATCH /settings is CommandResult,
                // not SettingsSnapshot — never return the snapshot shape on this route.
                HttpHandlerResult.Ok(
                    CommandResultDto(
                        commandId = request.command.commandId,
                        state = "SUCCEEDED",
                        resourceVersion = p.value.resourceVersion,
                        affectedResourceId = "settings",
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(p.error)
        }
    }

    override suspend fun listClients(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<ClientPageDto> {
        enforceAccess(principal, "list-client-registrations", AccessScope.clients_read)
            ?.let { return it }
        return HttpHandlerResult.Ok(ClientPageDto(items = clients.values.toList()))
    }

    override suspend fun revokeClient(
        principal: HttpPrincipal,
        clientId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "approve-suspend-revoke-clients", AccessScope.clients_manage)
            ?.let { return it }
        val claim = claimCommand(principal, "REVOKE_CLIENT", command)
        if (claim is HttpHandlerResult.Err) return claim
        val existing = clients[clientId]
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "client not found"))
        clients[clientId] = existing.copy(state = "REVOKED")
        commandLedger.recordResult(
            CommandId.parse(command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = clientId,
        )
        return HttpHandlerResult.Ok(
            CommandResultDto(
                commandId = command.commandId,
                state = "SUCCEEDED",
                resourceVersion = 1,
                affectedResourceId = clientId,
            ),
        )
    }

    // ----- LAN (features:lan control-plane host) -----------------------------

    override suspend fun enableLan(
        principal: HttpPrincipal,
        request: LanEnableRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "enable-disable-configure-lan", AccessScope.lan_manage)
            ?.let { return it }
        val claim = claimCommand(principal, "ENABLE_LAN", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        val ports = lanPorts
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "LAN feature pack not attached"),
            )
        val spec = EnableLanSpec(
            command = LanCommandIdentity(
                commandId = request.command.commandId,
                idempotencyKey = request.command.idempotencyKey,
            ),
        )
        return when (val r = ports.service.enable(PrincipalId.parse(principal.principalId), spec)) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(request.command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = "lan",
                )
                HttpHandlerResult.Ok(
                    CommandResultDto(
                        commandId = request.command.commandId,
                        state = "SUCCEEDED",
                        resourceVersion = r.value.resourceVersion,
                        affectedResourceId = "lan",
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun disableLan(
        principal: HttpPrincipal,
        request: LanEnableRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "enable-disable-configure-lan", AccessScope.lan_manage)
            ?.let { return it }
        val claim = claimCommand(principal, "DISABLE_LAN", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        val ports = lanPorts
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "LAN feature pack not attached"),
            )
        val spec = DisableLanSpec(
            command = LanCommandIdentity(
                commandId = request.command.commandId,
                idempotencyKey = request.command.idempotencyKey,
            ),
        )
        return when (val r = ports.service.disable(PrincipalId.parse(principal.principalId), spec)) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(request.command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = "lan",
                )
                HttpHandlerResult.Ok(
                    CommandResultDto(
                        commandId = request.command.commandId,
                        state = "SUCCEEDED",
                        resourceVersion = r.value.resourceVersion,
                        affectedResourceId = "lan",
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun createLanPairingChallenge(
        principal: HttpPrincipal,
        request: LanPairingChallengeCreateRequestDto,
    ): HttpHandlerResult<JsonRawBody> {
        enforceAccess(principal, "enable-disable-configure-lan", AccessScope.lan_manage)
            ?.let { return it }
        val ports = lanPorts
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "LAN feature pack not attached"),
            )
        val scopes = request.scopes.toSet().ifEmpty { LanScopePolicy.DEFAULT_INFER_SCOPES }
        val challengeId = UUID.randomUUID().toString()
        val spec = CreatePairingChallengeSpec(
            command = LanCommandIdentity(
                commandId = request.command.commandId,
                idempotencyKey = request.command.idempotencyKey,
            ),
            challengeId = challengeId,
            requestedScopes = scopes,
            explicitlyApprovedScopes = scopes,
        )
        return when (
            val r = ports.pairing.createChallenge(PrincipalId.parse(principal.principalId), spec)
        ) {
            is OmniResult.Ok -> {
                val v = r.value
                // SEC-10: build the wire body through the JSON codec — every field
                // escaped, key order preserved, null semantics explicit.
                val body = HttpJson.codec.encodeToString(
                    JsonElement.serializer(),
                    JsonObject(
                        mapOf(
                            "challenge_id" to JsonPrimitive(v.challengeId),
                            "protocol_label" to JsonPrimitive(v.protocolLabel),
                            "server_spki_sha256" to JsonPrimitive(v.serverSpkiSha256),
                            "connection_epoch" to JsonPrimitive(v.connectionEpoch),
                            "pairing_secret" to
                                (v.pairingSecret?.let { JsonPrimitive(it) } ?: JsonNull),
                            "requested_scopes" to
                                JsonArray(v.requestedScopes.sorted().map { JsonPrimitive(it) }),
                            "expires_at" to JsonPrimitive(
                                java.time.Instant.ofEpochMilli(v.expiresAtEpochMs).toString(),
                            ),
                            "qr_payload" to
                                (v.qrPayload?.let { JsonPrimitive(it) } ?: JsonNull),
                            "state" to JsonPrimitive(v.state),
                            "attempts_remaining" to JsonPrimitive(v.attemptsRemaining),
                        ),
                    ),
                )
                HttpHandlerResult.Ok(JsonRawBody(body))
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun completeLanPairing(body: String): HttpHandlerResult<JsonRawBody> {
        // Pairing exchange requires ACTIVE TLS identity — fail closed until listener ready.
        val ports = lanPorts
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "LAN feature pack not attached"),
            )
        val st = when (val s = ports.service.status()) {
            is OmniResult.Ok -> s.value
            is OmniResult.Err -> return HttpHandlerResult.Err(s.error)
        }
        if (!st.mayAcceptClients) {
            return HttpHandlerResult.Err(
                OmniError.STATE_CONFLICT(
                    message = "LAN pairing exchange requires TLS-ready ACTIVE/ADVERTISING service",
                    details = mapOf(
                        "enabled" to st.enabled.toString(),
                        "state" to st.state,
                        "tlsReady" to st.tlsReady.toString(),
                    ),
                ),
            )
        }
        val parsed = parseLanPairingExchange(body)
            ?: return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(message = "invalid LAN pairing exchange JSON"),
            )
        val observedSpki = st.serverSpkiSha256
            ?: return HttpHandlerResult.Err(
                OmniError.STATE_CONFLICT(message = "TLS SPKI unavailable on LAN listener"),
            )
        val spec = CompletePairingExchangeSpec(
            command = LanCommandIdentity(
                commandId = parsed.commandId,
                idempotencyKey = parsed.idempotencyKey,
            ),
            exchangeId = parsed.exchangeId,
            challengeId = parsed.challengeId,
            clientPublicKey = parsed.clientPublicKey,
            requestedScopes = parsed.requestedScopes,
            proofBase64Url = parsed.proofBase64Url,
            observedSpkiSha256 = observedSpki,
            observedConnectionEpoch = st.connectionEpoch,
        )
        return when (
            val r = ports.pairing.completeExchange(
                PrincipalId.parse("http-lan-pairing"),
                spec,
            )
        ) {
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
            is OmniResult.Ok -> {
                val t = r.value
                // SEC-10: codec-built body — plaintext token escaped, order preserved.
                val bodyOut = HttpJson.codec.encodeToString(
                    JsonElement.serializer(),
                    JsonObject(
                        mapOf(
                            "exchange_id" to JsonPrimitive(t.exchangeId),
                            "state" to JsonPrimitive("SUCCEEDED"),
                            "client_id" to JsonPrimitive(t.clientId),
                            "token_id" to JsonPrimitive(t.tokenId),
                            "token" to JsonPrimitive(t.tokenPlaintext),
                            "scopes" to JsonArray(t.scopes.sorted().map { JsonPrimitive(it) }),
                            "expires_at" to JsonPrimitive(
                                java.time.Instant.ofEpochMilli(t.expiresAtEpochMs).toString(),
                            ),
                            "receipt_expires_at" to JsonPrimitive(
                                java.time.Instant.ofEpochMilli(t.receiptExpiresAtEpochMs).toString(),
                            ),
                            "server_spki_sha256" to JsonPrimitive(t.serverSpkiSha256),
                        ),
                    ),
                )
                HttpHandlerResult.Ok(JsonRawBody(bodyOut))
            }
        }
    }

    private data class ParsedLanExchange(
        val commandId: String,
        val idempotencyKey: String,
        val exchangeId: String,
        val challengeId: String,
        val clientPublicKey: String,
        val requestedScopes: Set<String>,
        val proofBase64Url: String,
    )

    private fun parseLanPairingExchange(body: String): ParsedLanExchange? {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(body)
            if (root !is JsonObject) return null
            val command = root["command"] as? JsonObject ?: return null
            val commandId = command["command_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val idem = command["idempotency_key"]?.jsonPrimitive?.contentOrNull ?: return null
            val exchangeId = root["exchange_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val challengeId = root["challenge_id"]?.jsonPrimitive?.contentOrNull ?: return null
            val clientPublicKey = root["client_public_key"]?.jsonPrimitive?.contentOrNull
                ?: return null
            val proof = root["proof_base64url"]?.jsonPrimitive?.contentOrNull ?: return null
            if (clientPublicKey.length < 32 || proof.length < 43) return null
            val scopesEl = root["requested_scopes"] as? JsonArray ?: return null
            val scopes = scopesEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() }
                .toSet()
            if (scopes.isEmpty()) return null
            ParsedLanExchange(
                commandId = commandId,
                idempotencyKey = idem,
                exchangeId = exchangeId,
                challengeId = challengeId,
                clientPublicKey = clientPublicKey,
                requestedScopes = scopes,
                proofBase64Url = proof,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ----- Diagnostics (features:diagnostics) --------------------------------

    override suspend fun createDiagnosticExport(
        principal: HttpPrincipal,
        request: DiagnosticExportRequestDto,
    ): HttpHandlerResult<JobInfoDto> {
        enforceAccess(principal, "create-redacted-diagnostic-export", AccessScope.diagnostics_export)
            ?.let { return it }
        val claim = claimCommand(principal, "DIAGNOSTIC_EXPORT", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        // diagnosticsApi is plane-hosted (LOCAL_UI composition); HTTP creates the same
        // JobKind.DIAGNOSTIC_EXPORT via the shared plane JobManager (ADR-010 single writer).
        // Pack reachability is asserted via [attachedFeaturePackMarkers].
        if (diagnosticsApi == null) {
            return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "diagnostics feature pack not attached"),
            )
        }
        val jobId = UUID.randomUUID().toString()
        val identity = JobIdentity(
            jobId = JobId(jobId),
            principalId = PrincipalId.parse(principal.principalId),
            kind = JobKind.DIAGNOSTIC_EXPORT,
            idempotencyKey = IdempotencyKey.parse(request.command.idempotencyKey),
            canonicalSpecDigest = request.command.canonicalInputDigest,
        )
        val params = JobParameters.DiagnosticExport(includeDetail = request.includeDetail)
        return when (val created = jobManager.create(identity, params)) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(request.command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = jobId,
                )
                HttpHandlerResult.Ok(toJobInfo(created.value.record))
            }
            is OmniResult.Err -> HttpHandlerResult.Err(created.error)
        }
    }

    // ----- Content reports (features:ai-content-report — not telemetry) ------

    override suspend fun createContentReportProposal(
        principal: HttpPrincipal,
        request: ContentReportProposalRequestDto,
    ): HttpHandlerResult<ContentReportInfoDto> {
        enforceAccess(principal, "create-ai-output-report-proposal", AccessScope.content_reports_propose)
            ?.let { return it }
        val claim = claimCommand(principal, "CONTENT_REPORT_PROPOSE", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        val api = contentReportApi
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "content report pack not attached"),
            )
        // Report stream ≠ telemetry (SEC-PRIVACY / FEAT-AI-CONTENT-REPORT).
        check(ContentReportPolicy.DATA_STREAM_KIND != ContentReportPolicy.TELEMETRY_STREAM_KIND)
        val surface = if (principal.loopbackOnly) {
            CallerSurface.LOOPBACK_HTTP
        } else {
            CallerSurface.LAN_HTTP
        }
        val profileId = if (principal.loopbackOnly) "LOCAL_ADMIN_HTTP" else "LAN_CLIENT"
        val spec = CreateProposalSpec(
            command = ContentReportCommandIdentity(
                commandId = request.command.commandId,
                idempotencyKey = request.command.idempotencyKey,
            ),
            reportId = request.reportId,
            category = request.category,
            createdAt = request.createdAt,
            appBuild = request.appBuild,
            modelRevisionId = request.modelRevisionId,
            engineBuildId = request.engineBuildId,
            backend = request.backend,
            localPolicyVersion = request.localPolicyVersion,
            outputDigest = request.outputDigest,
            userLocale = request.userLocale,
            description = request.description,
            promptExcerpt = request.promptExcerpt,
            outputExcerpt = request.outputExcerpt,
            diagnosticSummary = request.diagnosticSummary,
            userConfirmed = false,
        )
        return when (
            val r = api.createProposal(
                principal = PrincipalId.parse(principal.principalId),
                surface = surface,
                profileAuthenticated = true,
                accessProfileId = profileId,
                spec = spec,
            )
        ) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(request.command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = request.reportId,
                )
                HttpHandlerResult.Ok(toContentReportInfoDto(r.value))
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun getContentReport(
        principal: HttpPrincipal,
        reportId: String,
    ): HttpHandlerResult<ContentReportInfoDto> {
        val api = contentReportApi
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "content report pack not attached"),
            )
        enforceAccess(principal, "read-own-content-report-status", AccessScope.content_reports_read_own)
            ?.let { return it }
        return when (
            val r = api.getReport(PrincipalId.parse(principal.principalId), reportId)
        ) {
            is OmniResult.Ok -> HttpHandlerResult.Ok(toContentReportInfoDto(r.value))
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun cancelContentReport(
        principal: HttpPrincipal,
        reportId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "cancel-own-content-report", AccessScope.content_reports_manage_own)
            ?.let { return it }
        val claim = claimCommand(principal, "CONTENT_REPORT_CANCEL", command)
        if (claim is HttpHandlerResult.Err) return claim
        val api = contentReportApi
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "content report pack not attached"),
            )
        val surface = if (principal.loopbackOnly) {
            CallerSurface.LOOPBACK_HTTP
        } else {
            CallerSurface.LAN_HTTP
        }
        val profileId = if (principal.loopbackOnly) "LOCAL_ADMIN_HTTP" else "LAN_CLIENT"
        return when (
            val r = api.cancelReport(
                principal = PrincipalId.parse(principal.principalId),
                surface = surface,
                accessProfileId = profileId,
                authenticated = true,
                spec = CancelReportSpec(
                    reportId = reportId,
                    command = ContentReportCommandIdentity(
                        commandId = command.commandId,
                        idempotencyKey = command.idempotencyKey,
                    ),
                ),
            )
        ) {
            is OmniResult.Ok -> {
                val state = if (r.value.state.name == "CANCELLING") "UNCERTAIN" else "SUCCEEDED"
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = state,
                    affectedResourceId = reportId,
                )
                HttpHandlerResult.Ok(
                    CommandResultDto(
                        commandId = command.commandId,
                        state = state,
                        resourceVersion = r.value.resourceVersion,
                        affectedResourceId = reportId,
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun discardContentReport(
        principal: HttpPrincipal,
        reportId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "discard-own-content-report", AccessScope.content_reports_manage_own)
            ?.let { return it }
        val claim = claimCommand(principal, "CONTENT_REPORT_DISCARD", command)
        if (claim is HttpHandlerResult.Err) return claim
        val api = contentReportApi
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "content report pack not attached"),
            )
        val surface = if (principal.loopbackOnly) {
            CallerSurface.LOOPBACK_HTTP
        } else {
            CallerSurface.LAN_HTTP
        }
        val profileId = if (principal.loopbackOnly) "LOCAL_ADMIN_HTTP" else "LAN_CLIENT"
        return when (
            val r = api.discardReport(
                principal = PrincipalId.parse(principal.principalId),
                surface = surface,
                accessProfileId = profileId,
                authenticated = true,
                spec = DiscardReportSpec(
                    reportId = reportId,
                    command = ContentReportCommandIdentity(
                        commandId = command.commandId,
                        idempotencyKey = command.idempotencyKey,
                    ),
                ),
            )
        ) {
            is OmniResult.Ok -> {
                commandLedger.recordResult(
                    CommandId.parse(command.commandId),
                    state = "SUCCEEDED",
                    affectedResourceId = reportId,
                )
                HttpHandlerResult.Ok(
                    CommandResultDto(
                        commandId = command.commandId,
                        state = "SUCCEEDED",
                        resourceVersion = r.value.resourceVersion,
                        affectedResourceId = reportId,
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    override suspend fun getContentReportReceipt(
        principal: HttpPrincipal,
        reportId: String,
    ): HttpHandlerResult<ContentReportReceiptDto> {
        val api = contentReportApi
            ?: return HttpHandlerResult.Err(
                OmniError.CAPABILITY_UNSUPPORTED(message = "content report pack not attached"),
            )
        enforceAccess(principal, "read-own-content-report-receipt", AccessScope.content_reports_read_own)
            ?.let { return it }
        return when (
            val r = api.getReceipt(PrincipalId.parse(principal.principalId), reportId)
        ) {
            is OmniResult.Ok -> {
                val receipt = r.value.receipt
                HttpHandlerResult.Ok(
                    ContentReportReceiptDto(
                        receiptId = receipt.receiptId,
                        reportId = receipt.reportId,
                        acceptedAt = receipt.acceptedAt,
                        statusUrl = receipt.statusUrl,
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
        }
    }

    /**
     * Reachability markers for control-plane bootstrap tests / health extensions.
     * Not exposed as public HTTP routes.
     */
    fun attachedFeaturePackMarkers(): Map<String, Boolean> = mapOf(
        "lan" to (lanPorts != null),
        "benchmark" to (benchmarkApi != null),
        "diagnostics" to (diagnosticsApi != null),
        "routing" to (routingApi != null),
        "tools" to (toolsApi != null),
        "ai-content-report" to (contentReportApi != null),
    )

    // ----- Tokens ------------------------------------------------------------

    override suspend fun issueLoopbackAdminToken(
        principal: HttpPrincipal,
        request: TokenIssueRequestDto,
    ): HttpHandlerResult<TokenIssueResultDto> {
        enforceAccess(principal, "issue-rotate-revoke-tokens", AccessScope.tokens_manage)
            ?.let { return it }
        val claim = claimCommand(principal, "ISSUE_TOKEN", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        val scopes = request.scopes.ifEmpty { LoopbackTokenService.BOOTSTRAP_SCOPES.toList() }.toSet()
        val ttl = request.expiresInSeconds ?: 86_400L
        val issued = tokenService.issue(
            principalId = "http-issued:${principal.principalId}",
            scopes = scopes,
            ttlSeconds = ttl,
            loopbackOnly = true,
            label = request.displayName,
            clientId = request.clientId,
        )
        commandLedger.recordResult(
            CommandId.parse(request.command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = issued.tokenId,
        )
        return HttpHandlerResult.Ok(
            body = TokenIssueResultDto(
                tokenId = issued.tokenId,
                // API-02: spec field names client_id / display_name / expires_in_seconds;
                // internal naming (label/ttlSeconds) maps at this boundary.
                clientId = request.clientId,
                token = issued.plaintext,
                scopes = issued.scopes.toList(),
                expiresAt = issued.expiresAt.toString(),
                revocationEpoch = issued.revocationEpoch,
                receiptExpiresAt = clock().plusSeconds(300L).toString(),
                loopbackOnly = true,
            ),
            status = 201,
        )
    }

    override suspend fun listTokens(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<TokenPageDto> {
        enforceAccess(principal, "issue-rotate-revoke-tokens", AccessScope.tokens_manage)
            ?.let { return it }
        val now = clock()
        val items = tokenService.listMetadata().map {
            val state = when {
                it.revoked -> "REVOKED"
                it.expiresAt.isBefore(now) -> "EXPIRED"
                else -> "ACTIVE"
            }
            TokenInfoDto(
                tokenId = it.tokenId,
                clientId = it.clientId,
                state = state,
                scopes = it.scopes.toList(),
                issuedAt = it.issuedAt.toString(),
                expiresAt = it.expiresAt.toString(),
                revocationEpoch = it.revocationEpoch,
                lastSeenAt = it.lastSeenAt?.toString(),
            )
        }
        return HttpHandlerResult.Ok(TokenPageDto(items = items))
    }

    override suspend fun revokeToken(
        principal: HttpPrincipal,
        tokenId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        enforceAccess(principal, "issue-rotate-revoke-tokens", AccessScope.tokens_manage)
            ?.let { return it }
        val claim = claimCommand(principal, "REVOKE_TOKEN", command)
        if (claim is HttpHandlerResult.Err) return claim
        // COR-23f: ownership before the revoke side-effect — self (the token's
        // own principal) or an admin (tokens.manage scope) may revoke; anyone
        // else gets FORBIDDEN and the token is NOT revoked.
        val rec = tokenService.get(tokenId)
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "token not found"))
        val admin = principal.hasScope("tokens.manage")
        if (rec.principalId != principal.principalId && !admin) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of token"))
        }
        if (!tokenService.revokeAs(tokenId, principal.principalId)) {
            return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "token not found"))
        }
        commandLedger.recordResult(
            CommandId.parse(command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = tokenId,
        )
        return HttpHandlerResult.Ok(
            CommandResultDto(
                commandId = command.commandId,
                state = "SUCCEEDED",
                resourceVersion = 1,
                affectedResourceId = tokenId,
            ),
        )
    }

    // ----- helpers -----------------------------------------------------------

    private fun toContentReportInfoDto(
        view: com.omnillm.features.contentreport.api.ContentReportInfoView,
    ): ContentReportInfoDto =
        ContentReportInfoDto(
            reportId = view.reportId,
            state = view.state.name,
            expiresAt = Instant.ofEpochMilli(view.expiresAtEpochMs).toString(),
            resourceVersion = view.resourceVersion,
            receiptId = view.receiptId,
        )

    private fun claimCommand(
        principal: HttpPrincipal,
        operationKind: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<Unit>? {
        return when (
            val c = commandLedger.claim(
                principal = PrincipalId.parse(principal.principalId),
                operationKind = operationKind,
                idempotencyKey = IdempotencyKey.parse(command.idempotencyKey),
                canonicalHash = Sha256Digest.parse(command.canonicalInputDigest),
                commandId = CommandId.parse(command.commandId),
                expectedVersion = command.expectedVersion,
            )
        ) {
            is ClaimOutcome.Conflict -> HttpHandlerResult.Err(c.error)
            is ClaimOutcome.Existing, is ClaimOutcome.New -> null // proceed
        }
    }

    /**
     * API-50: principal/ACL enforcement on the HTTP authorization path.
     * Runs AFTER the token was verified (the gateway authenticator) and BEFORE
     * any domain side-effect, using the AccessControlEnforcer's public API with
     * the transport/profile/epoch derived from the authenticated principal.
     * Returns an [HttpHandlerResult.Err] on denial (null = authorized).
     *
     * Defense in depth: the existing token-scope / ownership checks below stay.
     */
    private fun enforceAccess(
        principal: HttpPrincipal,
        operationId: String,
        requiredScope: AccessScope,
        resourceOwnerPrincipalId: String? = null,
    ): HttpHandlerResult<Nothing>? {
        if (principal.scopes.isEmpty()) {
            // Fail closed: a principal with no granted scopes cannot be admitted.
            return HttpHandlerResult.Err(
                OmniError.FORBIDDEN(message = "no granted scopes (fail closed)"),
            )
        }
        val loopback = principal.loopbackOnly
        val ctx = AccessControlEnforcer.PrincipalContext(
            principalId = PrincipalId.parse(principal.principalId),
            kind = if (loopback) PrincipalKind.HTTP_LOCAL_ADMIN else PrincipalKind.HTTP_LAN,
            profile = if (loopback) AccessProfile.LOCAL_ADMIN_HTTP else AccessProfile.LAN_CLIENT,
            grantedScopes = principal.scopes,
            revocationEpoch = principal.revocationEpoch,
            transport = if (loopback) {
                AccessControlEnforcer.AccessTransport.LOOPBACK_HTTP
            } else {
                AccessControlEnforcer.AccessTransport.LAN_TLS_HTTP
            },
            registrationId = principal.clientId,
            tokenId = principal.tokenId,
            loopbackOnlyToken = loopback,
        )
        return when (
            val r = accessControl.authorize(
                ctx,
                AccessControlEnforcer.OperationRequest(
                    operationId = operationId,
                    requiredScope = requiredScope,
                    resourceOwnerPrincipalId = resourceOwnerPrincipalId,
                    observedRevocationEpoch = principal.revocationEpoch,
                ),
            )
        ) {
            is OmniResult.Err -> HttpHandlerResult.Err(r.error)
            is OmniResult.Ok -> null
        }
    }

    private fun commandConflict(): HttpHandlerResult.Err =
        HttpHandlerResult.Err(OmniError.INTERNAL(message = "command claim path error"))

    private fun toJobInfo(record: JobRecord): JobInfoDto =
        JobInfoDto(
            jobId = record.identity.jobId.value,
            state = record.state,
            resourceVersion = record.resourceVersion,
            progress = record.progress.ratioOrNull(),
        )

    private fun mapJobParameters(kind: JobKind, params: JsonObject): JobParameters? {
        return when (kind) {
            JobKind.DOWNLOAD -> {
                val url = params.string("source_url") ?: return null
                JobParameters.Download(
                    sourceUrl = url,
                    expectedSha256 = params.string("expected_sha256"),
                    expectedBytes = params.long("expected_bytes"),
                    targetName = params.string("target_name"),
                )
            }
            JobKind.IMPORT -> {
                val assetId = params.string("asset_id") ?: return null
                JobParameters.Import(assetId = assetId, expectedSha256 = params.string("expected_sha256"))
            }
            JobKind.BENCHMARK -> {
                JobParameters.Benchmark(
                    modelRevisionId = params.string("model_revision_id") ?: return null,
                    engineBuildId = params.string("engine_build_id") ?: return null,
                    backend = params.string("backend") ?: return null,
                    measurementProfileId = params.string("measurement_profile_id") ?: return null,
                    iterations = params.long("iterations")?.toInt(),
                )
            }
            JobKind.DELETE -> {
                val rk = params.string("resource_kind") ?: return null
                val kindEnum = com.omnillm.runtime.job.DeleteResourceKind.fromCatalogName(rk) ?: return null
                JobParameters.Delete(
                    resourceKind = kindEnum,
                    resourceId = params.string("resource_id") ?: return null,
                    expectedResourceVersion = params.long("expected_resource_version") ?: return null,
                    forceAfterDrain = params.bool("force_after_drain") ?: false,
                )
            }
            JobKind.DIAGNOSTIC_EXPORT ->
                JobParameters.DiagnosticExport(
                    includeDetail = params.bool("include_detail") ?: false,
                )
            JobKind.CONTENT_REPORT ->
                JobParameters.ContentReport(reportId = params.string("report_id") ?: return null)
        }
    }

    private fun settingValueToJson(v: SettingValue): JsonElement = when (v) {
        is SettingValue.BoolValue -> JsonPrimitive(v.value)
        is SettingValue.IntValue -> JsonPrimitive(v.value)
        is SettingValue.NumberValue -> JsonPrimitive(v.value)
        is SettingValue.StringValue -> JsonPrimitive(v.value)
        is SettingValue.EnumValue -> JsonPrimitive(v.value)
        is SettingValue.StringListValue -> JsonArray(v.value.map { JsonPrimitive(it) })
    }

    private fun jsonToSettingValue(el: JsonElement): SettingValue? = when (el) {
        is JsonPrimitive -> when {
            // COR-23b: JSON strings ALWAYS stay strings — never numeric/boolean
            // coercion of "0123" / "true" (the JSON type is the wire authority).
            el.isString -> SettingValue.StringValue(el.content)
            el.booleanOrNull != null -> SettingValue.BoolValue(el.booleanOrNull!!)
            el.longOrNull != null -> SettingValue.IntValue(el.longOrNull!!)
            el.doubleOrNull != null -> SettingValue.NumberValue(el.doubleOrNull!!)
            el.contentOrNull != null -> SettingValue.StringValue(el.contentOrNull!!)
            else -> null
        }
        is JsonArray -> SettingValue.StringListValue(
            el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
        JsonNull -> null
        is JsonObject -> null
    }

    private fun sha256Hex(text: String): String =
        sha256Hex(text.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    /**
     * Canonical digest of the full chat message list (COR-13): covers content,
     * not length — same-length different-content must not collide.
     */
    private fun chatMessageDigest(messages: List<ChatMessageDto>): String =
        sha256Hex(messages.joinToString(separator = "\u0000") { m -> "${m.role}\u0000${m.content}" })

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    companion object {
        /** Bounded SSE stream polling (bounded poll × delay ≈ 5 min max). */
        const val MAX_STREAM_POLLS: Int = 15_000
        const val STREAM_POLL_MS: Long = 20L

        /** COR-17/SEC-06: upper bound for the in-memory asset store. */
        const val MAX_ASSETS: Int = 256

        /** COR-23a: listModels page size (cursor-paginated). */
        const val MODELS_PAGE_SIZE: Int = 50

        /** REQUEST states treated as stream-terminal for SSE projection. */
        private val STREAM_TERMINAL_STATES: Set<String> = setOf(
            "COMPLETED",
            "SUCCEEDED",
            "CANCELLED",
            "FAILED",
            "TERMINAL_SUCCESS",
            "TERMINAL_FAILED",
            "ABORTED_UNCERTAIN",
        )

        /** Durable-request terminal states for the events endpoint (API-16). */
        private val DURABLE_TERMINAL_STATES: Set<String> = setOf(
            "COMPLETED",
            "SUCCEEDED",
            "CANCELLED",
            "FAILED",
            "ABORTED_UNCERTAIN",
            "TERMINAL_SUCCESS",
            "TERMINAL_FAILED",
        )
    }
}

/**
 * Exploratory engine access resolved from the attached control plane.
 * Fail-closed null when the plane (or engine binding) is unavailable.
 */
data class ExploratoryInferenceSource(
    val binding: com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding,
    val modelManager: com.omnillm.runtime.modelmanager.ModelManager,
)

private object ClaimShapeOk {
    private val UUID =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun command(dto: CommandRequestDto): Boolean {
        if (!UUID.matches(dto.commandId)) return false
        if (dto.idempotencyKey.isBlank() || dto.idempotencyKey.length > 128) return false
        if (!SHA.matches(dto.canonicalInputDigest)) return false
        return true
    }
}
