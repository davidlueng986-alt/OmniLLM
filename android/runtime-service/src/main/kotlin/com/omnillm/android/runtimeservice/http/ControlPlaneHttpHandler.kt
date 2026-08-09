package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.OmniResult
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
import com.omnillm.interfaces.http.JobInfoDto
import com.omnillm.interfaces.http.JobPageDto
import com.omnillm.interfaces.http.JobSpecDto
import com.omnillm.interfaces.http.JsonRawBody
import com.omnillm.interfaces.http.LanEnableRequestDto
import com.omnillm.interfaces.http.LanPairingChallengeCreateRequestDto
import com.omnillm.interfaces.http.MetricSummaryDto
import com.omnillm.interfaces.http.ModelInfoDto
import com.omnillm.interfaces.http.ModelPageDto
import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.OpenAIChatRequestDto
import com.omnillm.interfaces.http.OpenAIEmbeddingRequestDto
import com.omnillm.interfaces.http.RequestStateDto
import com.omnillm.interfaces.http.SettingsPatchDto
import com.omnillm.interfaces.http.SettingsSnapshotDto
import com.omnillm.interfaces.http.SseEvent
import com.omnillm.interfaces.http.SseHandlerResult
import com.omnillm.interfaces.http.TokenIssueRequestDto
import com.omnillm.interfaces.http.TokenIssueResultDto
import com.omnillm.interfaces.http.TokenMetaDto
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
import com.omnillm.runtime.requestregistry.ClaimOutcome
import com.omnillm.runtime.requestregistry.CommandLedger
import com.omnillm.runtime.requestregistry.RequestRegistry
import kotlinx.coroutines.flow.flow
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
    private val tokenService: LoopbackTokenService,
    private val jobManager: JobManager = JobManager(),
    private val policyManager: PolicyManager = PolicyManager(),
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
) : OmniHttpHandlerPort {

    private val assets = ConcurrentHashMap<String, AssetRecord>()
    private val clients = ConcurrentHashMap<String, ClientSummaryDto>()

    private data class AssetRecord(
        val info: AssetInfoDto,
        val ownerPrincipalId: String,
        val maxBytes: Long,
        var content: ByteArray? = null,
    )

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
        // Capabilities are projected on each ModelInfo (OpenAPI ModelInfo.capabilities).
        return HttpHandlerResult.Ok(ModelPageDto(items = modelCatalog()))
    }

    // ----- Inference ---------------------------------------------------------

    override suspend fun createChatCompletion(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): HttpHandlerResult<ChatCompletionResponseDto> {
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
        val plane = com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane.get()
        if (plane != null) {
            plane.ensureEnginePacksAttached()
            val binding = try {
                plane.engineExecute
            } catch (_: Exception) {
                null
            }
            if (binding != null && binding.isEngineBound() && binding.isExploratoryExecuteEnabled()) {
                val userText = request.messages
                    .lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
                    ?: request.messages.lastOrNull()?.content
                    ?: ""
                if (userText.isBlank()) {
                    return HttpHandlerResult.Err(
                        OmniError.INVALID_REQUEST(message = "chat messages must include content"),
                    )
                }
                val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                    "http-sync-chat|$requestId|$idem|${request.model}|${userText.length}",
                )
                val revisionHex = request.model.lowercase().let {
                    if (it.matches(Regex("^[0-9a-f]{64}$"))) it
                    else com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|${request.model}")
                }
                val port = com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts
                    .playgroundInference(
                        orchestrator = orchestrator,
                        binding = binding,
                        modelManager = plane.modelManager,
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
                    messages = listOf(
                        com.omnillm.features.playground.api.ChatMessage(
                            role = "user",
                            content = userText,
                        ),
                    ),
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
                                                content = handle.assistantText
                                                    ?: "[CONDITIONAL exploratory execute; no token text]",
                                            ),
                                            finishReason = "stop",
                                        ),
                                    ),
                                    // Degraded exploratory path — not device-qualified SUPPORTED.
                                ),
                            )
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
        if (orchestrator == null) {
            return SseHandlerResult.PreStreamError(
                OmniError.CAPABILITY_UNSUPPORTED(message = "inference orchestrator not attached"),
            )
        }
        val plane = com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane.get()
        if (plane != null) {
            plane.ensureEnginePacksAttached()
            val binding = try {
                plane.engineExecute
            } catch (_: Exception) {
                null
            }
            if (binding != null && binding.isEngineBound() && binding.isExploratoryExecuteEnabled()) {
                val userText = request.messages
                    .lastOrNull { it.role.equals("user", ignoreCase = true) }?.content
                    ?: request.messages.lastOrNull()?.content
                    ?: ""
                if (userText.isBlank()) {
                    return SseHandlerResult.PreStreamError(
                        OmniError.INVALID_REQUEST(message = "chat messages must include content"),
                    )
                }
                val requestId = requestIdHeader?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()
                val idem = idempotencyKeyHeader?.takeIf { it.isNotBlank() }
                    ?: "stream-chat-$requestId"
                val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
                    "http-stream-chat|$requestId|$idem|${request.model}|${userText.length}",
                )
                val revisionHex = request.model.lowercase().let {
                    if (it.matches(Regex("^[0-9a-f]{64}$"))) it
                    else com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|${request.model}")
                }
                val port = com.omnillm.android.runtimeservice.featurehost.ControlPlaneFeaturePorts
                    .playgroundInference(
                        orchestrator = orchestrator,
                        binding = binding,
                        modelManager = plane.modelManager,
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
                    messages = listOf(
                        com.omnillm.features.playground.api.ChatMessage(
                            role = "user",
                            content = userText,
                        ),
                    ),
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
                            streamOpenAiChatChunks(
                                requestId = handle.requestId,
                                model = request.model,
                                principal = principal,
                                port = plane.playgroundApi,
                            )
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
     * OpenAI SSE chunk framing over the durable playground stream projection
     * (CORE-INTERFACE §4: post-commit errors are terminal events, never late HTTP).
     * Stateless Session default: disconnect does not auto-continue.
     */
    private fun streamOpenAiChatChunks(
        requestId: String,
        model: String,
        principal: HttpPrincipal,
        port: com.omnillm.features.playground.api.PlaygroundApi,
    ): SseHandlerResult.Stream {        val created = clock().epochSecond
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
            var afterSeq = 0L
            var seqId = 1L
            var done = false
            var attempts = 0
            while (!done && attempts < MAX_STREAM_POLLS) {
                attempts++
                when (val batch = port.streamEvents(
                    PrincipalId.parse(principal.principalId),
                    requestId,
                    afterSeq,
                )) {
                    is OmniResult.Err -> {
                        emit(
                            SseEvent(
                                data = """{"error":{"message":${jsonEscape(batch.error.message ?: "stream error")}}}""",
                                event = SseFraming.EVENT_TERMINAL,
                                id = (seqId++).toString(),
                                isTerminal = true,
                            ),
                        )
                        done = true
                    }
                    is OmniResult.Ok -> {
                        val b = batch.value
                        for (ev in b.events) {
                            afterSeq = maxOf(afterSeq, ev.seq + 1L)
                            if (ev.kind == "delta") {
                                ev.textDelta?.takeIf { it.isNotEmpty() }?.let { text ->
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
                            }
                            if (ev.isTerminal || ev.kind == "terminal") {
                                done = true
                            }
                        }
                        if (b.isTerminal || done) {
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
    ): HttpHandlerResult<EmbeddingResponseDto> =
        HttpHandlerResult.Err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "embeddings require READY model candidates",
                details = mapOf("model" to request.model),
            ),
        )

    override suspend fun createAsyncInferenceRequest(
        principal: HttpPrincipal,
        request: AsyncInferenceRequestDto,
    ): HttpHandlerResult<AcceptedRequestDto> {
        val digest = sha256Hex(
            "${request.requestId}|${request.idempotencyKey}|${request.operation}|${request.model}|${request.payload}",
        )
        val claim = requestRegistry.claim(
            principal = PrincipalId.parse(principal.principalId),
            operationKind = request.operation,
            idempotencyKey = IdempotencyKey.parse(request.idempotencyKey),
            canonicalHash = Sha256Digest.parse(digest),
            requestId = RequestId.parse(request.requestId),
            revisionId = null,
        )
        return when (claim) {
            is ClaimOutcome.Conflict -> HttpHandlerResult.Err(claim.error)
            is ClaimOutcome.Existing, is ClaimOutcome.New -> {
                val row = when (claim) {
                    is ClaimOutcome.Existing -> claim.value
                    is ClaimOutcome.New -> claim.value
                    else -> error("unreachable")
                }
                // Orchestrator full planning needs candidates — accept claim only when
                // no orchestrator, or leave QUEUED for pump when wired with candidates later.
                HttpHandlerResult.Ok(
                    body = AcceptedRequestDto(
                        requestId = row.requestId,
                        state = row.state,
                        queryUrl = "/omni/v1/requests/${row.requestId}",
                        eventsUrl = "/omni/v1/requests/${row.requestId}/events",
                    ),
                    status = 202,
                )
            }
        }
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
        // CORE-INTERFACE durable Command: non-create mutations require expectedVersion.
        if (command.expectedVersion == null) {
            return HttpHandlerResult.Err(
                OmniError.INVALID_REQUEST(
                    message = "expectedVersion required for cancelRequest (CAS)",
                    details = mapOf("requestId" to requestId),
                ),
            )
        }
        val claim = claimCommand(principal, "CANCEL_REQUEST", command) ?: return commandConflict()
        if (claim is HttpHandlerResult.Err) return claim
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
        if (row.principalId != principal.principalId) {
            return SseHandlerResult.PreStreamError(
                OmniError.FORBIDDEN(message = "not owner of request"),
            )
        }
        // Stateless Session default: one-shot snapshot stream + terminal (CORE-INTERFACE §4).
        val state = orchestrator?.query(RequestId.parse(requestId))?.state ?: row.state
        val events = flow {
            emit(
                SseEvent(
                    data = """{"request_id":"$requestId","state":"$state","seq":${afterSeq ?: 0}}""",
                    event = "state",
                    id = "0",
                    isTerminal = false,
                ),
            )
            emit(
                SseEvent(
                    data = """{"request_id":"$requestId","state":"$state","terminal":true}""",
                    event = SseFraming.EVENT_TERMINAL,
                    id = "1",
                    isTerminal = true,
                ),
            )
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
        if (!ClaimShapeOk.command(request.command)) {
            return HttpHandlerResult.Err(OmniError.INVALID_REQUEST(message = "invalid command claim"))
        }
        val claim = claimCommand(principal, "CREATE_ASSET", request.command)
        if (claim is HttpHandlerResult.Err) return claim

        val assetId = UUID.randomUUID().toString()
        val expires = clock().plusSeconds(request.ttlSeconds).toString()
        val info = AssetInfoDto(
            assetId = assetId,
            state = "CREATED",
            expiresAt = expires,
            bytes = 0,
            uploadUrl = "/omni/v1/assets/$assetId/content",
        )
        assets[assetId] = AssetRecord(
            info = info,
            ownerPrincipalId = principal.principalId,
            maxBytes = request.maxBytes,
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
        commandJson: String?,
    ): HttpHandlerResult<CommandResultDto> {
        val rec = assets[assetId]
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "asset not found"))
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        if (body.size.toLong() > rec.maxBytes) {
            return HttpHandlerResult.Err(
                OmniError.TRANSPORT_TOO_LARGE(message = "exceeds asset max_bytes"),
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
        )
        val cmdId = UUID.randomUUID().toString()
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
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        val bytes = rec.content
            ?: return HttpHandlerResult.Err(OmniError.ASSET_NOT_READY(message = "no content uploaded"))
        val digest = sha256Hex(bytes)
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
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        // Never return filesystem paths (CORE-INTERFACE §9).
        return HttpHandlerResult.Ok(rec.info)
    }

    override suspend fun deleteAsset(
        principal: HttpPrincipal,
        assetId: String,
    ): HttpHandlerResult<CommandResultDto> {
        val rec = assets[assetId]
            ?: return HttpHandlerResult.Err(OmniError.NOT_FOUND(message = "asset not found"))
        if (rec.ownerPrincipalId != principal.principalId) {
            return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of asset"))
        }
        if (rec.info.state == "PINNED") {
            return HttpHandlerResult.Err(
                OmniError.STATE_CONFLICT(message = "asset pinned by request"),
            )
        }
        assets[assetId] = rec.copy(info = rec.info.copy(state = "DELETED"), content = null)
        val cmdId = UUID.randomUUID().toString()
        return HttpHandlerResult.Ok(
            CommandResultDto(commandId = cmdId, state = "SUCCEEDED", resourceVersion = 1, affectedResourceId = assetId),
        )
    }

    // ----- Jobs --------------------------------------------------------------

    override suspend fun createJob(
        principal: HttpPrincipal,
        request: JobSpecDto,
    ): HttpHandlerResult<JobInfoDto> {
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
        val items = jobManager.listOwn(PrincipalId.parse(principal.principalId)).map { toJobInfo(it) }
        return HttpHandlerResult.Ok(JobPageDto(items = items))
    }

    override suspend fun getJob(
        principal: HttpPrincipal,
        jobId: String,
    ): HttpHandlerResult<JobInfoDto> {
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
        val claim = claimCommand(principal, "CANCEL_JOB", command)
        if (claim is HttpHandlerResult.Err) return claim
        return when (val c = jobManager.cancel(JobId(jobId))) {
            is OmniResult.Ok -> {
                if (c.value.identity.principalId.value != principal.principalId) {
                    return HttpHandlerResult.Err(OmniError.FORBIDDEN(message = "not owner of job"))
                }
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
            is OmniResult.Err -> HttpHandlerResult.Err(c.error)
        }
    }

    // ----- Metrics / settings / clients --------------------------------------

    override suspend fun getMetricSummary(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto> =
        HttpHandlerResult.Ok(metricSummary())

    override suspend fun getMetricDetail(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto> =
        HttpHandlerResult.Ok(metricDetail())

    override suspend fun getSettings(principal: HttpPrincipal): HttpHandlerResult<SettingsSnapshotDto> {
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
    ): HttpHandlerResult<SettingsSnapshotDto> {
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
                HttpHandlerResult.Ok(
                    SettingsSnapshotDto(
                        resourceVersion = p.value.resourceVersion,
                        values = p.value.values.mapValues { settingValueToJson(it.value) },
                    ),
                )
            }
            is OmniResult.Err -> HttpHandlerResult.Err(p.error)
        }
    }

    override suspend fun listClients(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<ClientPageDto> =
        HttpHandlerResult.Ok(ClientPageDto(items = clients.values.toList()))

    override suspend fun revokeClient(
        principal: HttpPrincipal,
        clientId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
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
                val scopesJson = v.requestedScopes.sorted()
                    .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
                val secretJson = v.pairingSecret?.let { "\"${jsonEscape(it)}\"" } ?: "null"
                val qrJson = v.qrPayload?.let { "\"${jsonEscape(it)}\"" } ?: "null"
                val body =
                    """{"challenge_id":"${v.challengeId}","protocol_label":"${v.protocolLabel}","server_spki_sha256":"${v.serverSpkiSha256}","connection_epoch":${v.connectionEpoch},"pairing_secret":$secretJson,"requested_scopes":$scopesJson,"expires_at":"${java.time.Instant.ofEpochMilli(v.expiresAtEpochMs)}","qr_payload":$qrJson,"state":"${v.state}","attempts_remaining":${v.attemptsRemaining}}"""
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
                val scopesJson = t.scopes.sorted()
                    .joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
                val bodyOut =
                    """{"exchange_id":"${t.exchangeId}","state":"SUCCEEDED","client_id":"${t.clientId}","token_id":"${t.tokenId}","token":"${jsonEscape(t.tokenPlaintext)}","scopes":$scopesJson,"expires_at":"${java.time.Instant.ofEpochMilli(t.expiresAtEpochMs)}","receipt_expires_at":"${java.time.Instant.ofEpochMilli(t.receiptExpiresAtEpochMs)}","server_spki_sha256":"${t.serverSpkiSha256}"}"""
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

    private fun jsonEscape(s: String): String =
        buildString(s.length + 8) {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }

    // ----- Diagnostics (features:diagnostics) --------------------------------

    override suspend fun createDiagnosticExport(
        principal: HttpPrincipal,
        request: DiagnosticExportRequestDto,
    ): HttpHandlerResult<JobInfoDto> {
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
        val claim = claimCommand(principal, "ISSUE_TOKEN", request.command)
        if (claim is HttpHandlerResult.Err) return claim
        val scopes = request.scopes.ifEmpty { LoopbackTokenService.BOOTSTRAP_SCOPES.toList() }.toSet()
        val ttl = request.ttlSeconds ?: 86_400L
        val issued = tokenService.issue(
            principalId = "http-issued:${principal.principalId}",
            scopes = scopes,
            ttlSeconds = ttl,
            loopbackOnly = true,
            label = request.label,
            clientId = principal.clientId,
        )
        commandLedger.recordResult(
            CommandId.parse(request.command.commandId),
            state = "SUCCEEDED",
            affectedResourceId = issued.tokenId,
        )
        return HttpHandlerResult.Ok(
            body = TokenIssueResultDto(
                tokenId = issued.tokenId,
                token = issued.plaintext,
                scopes = issued.scopes.toList(),
                expiresAt = issued.expiresAt.toString(),
                loopbackOnly = true,
            ),
            status = 201,
        )
    }

    override suspend fun listTokens(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<TokenPageDto> {
        val items = tokenService.listMetadata().map {
            TokenMetaDto(
                tokenId = it.tokenId,
                scopes = it.scopes.toList(),
                expiresAt = it.expiresAt.toString(),
                revoked = it.revoked,
                label = it.label,
            )
        }
        return HttpHandlerResult.Ok(TokenPageDto(items = items))
    }

    override suspend fun revokeToken(
        principal: HttpPrincipal,
        tokenId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto> {
        val claim = claimCommand(principal, "REVOKE_TOKEN", command)
        if (claim is HttpHandlerResult.Err) return claim
        if (!tokenService.revoke(tokenId)) {
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
    }
}

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
