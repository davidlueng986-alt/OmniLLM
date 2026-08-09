package com.omnillm.interfaces.http

import com.omnillm.core.errors.TransportDeliveryGuarantee
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.interfaces.http.sse.SseFraming
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream

/**
 * Ktor routes matching `specs/openapi/omnillm.openapi.yaml`.
 *
 * - Auth: bearer required except getHealth / completeLanPairing
 * - Scope check from [OpenApiScopes]
 * - SSE: pre-stream HTTP errors; post-stream terminal events (CORE-INTERFACE §4)
 * - No engine selection / Session mutation (ADR-011)
 */
fun Application.installOmniHttpRoutes(
    handler: OmniHttpHandlerPort,
    authenticator: TokenAuthenticator,
    config: GatewayConfig = GatewayConfig(),
    transport: HttpTransportKind = HttpTransportKind.LOOPBACK,
) {
    routing {
        omniHttpRoutes(handler, authenticator, config, transport)
    }
}

/** @deprecated Use [installOmniHttpRoutes] with a real handler. */
@Deprecated("Use installOmniHttpRoutes(handler, authenticator)", ReplaceWith("installOmniHttpRoutes(handler, authenticator)"))
fun Application.installOmniHttpRouteStubs() {
    // Kept for binary compatibility with earlier scaffold tests.
    installOmniHttpRoutes(
        handler = NotImplementedHttpHandler,
        authenticator = { _, _ -> AuthResult.Unauthorized() },
    )
}

fun Route.omniHttpRoutes(
    handler: OmniHttpHandlerPort,
    authenticator: TokenAuthenticator,
    config: GatewayConfig = GatewayConfig(),
    transport: HttpTransportKind = HttpTransportKind.LOOPBACK,
) {
    // --- Health (minimal unauth) ---
    get(OpenApiPaths.HEALTH) {
        // operationId: getHealth — security: []
        when (val r = handler.getHealth()) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Models ---
    get(OpenApiPaths.V1_MODELS) {
        val principal = call.requirePrincipal("listModels", authenticator, transport) ?: return@get
        when (val r = handler.listModels(principal, call.request.queryParameters["page_token"])) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Chat ---
    post(OpenApiPaths.V1_CHAT_COMPLETIONS) {
        val principal = call.requirePrincipal("createChatCompletion", authenticator, transport) ?: return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(OpenAIChatRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid chat request JSON"))
            return@post
        }
        val rid = call.request.header("X-OmniLLM-Request-Id")
        val idem = call.request.header("Idempotency-Key")
        if (req.stream) {
            when (val r = handler.createChatCompletionStream(principal, req, rid, idem)) {
                is SseHandlerResult.PreStreamError -> OmniErrorHttp.respond(
                    call,
                    r.error,
                    TransportDeliveryGuarantee.HTTP_SSE,
                )
                is SseHandlerResult.Stream -> {
                    val headers = r.headers.toMutableMap()
                    headers.putIfAbsent("X-OmniLLM-Request-Id", r.requestId)
                    SseFraming.writeStream(call, r.events, headers)
                }
            }
        } else {
            when (val r = handler.createChatCompletion(principal, req, rid, idem)) {
                is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
                is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
            }
        }
    }

    // --- Embeddings ---
    post(OpenApiPaths.V1_EMBEDDINGS) {
        val principal = call.requirePrincipal("createEmbedding", authenticator, transport) ?: return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(OpenAIEmbeddingRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid embedding request JSON"))
            return@post
        }
        val rid = call.request.header("X-OmniLLM-Request-Id")
        val idem = call.request.header("Idempotency-Key")
        when (val r = handler.createEmbedding(principal, req, rid, idem)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Durable requests ---
    post(OpenApiPaths.OMNI_REQUESTS) {
        val principal = call.requirePrincipal("createAsyncInferenceRequest", authenticator, transport) ?: return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(AsyncInferenceRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid async request JSON"))
            return@post
        }
        if (!ClaimShape.isValidAsyncInferenceRequest(req)) {
            OmniErrorHttp.respond(
                call,
                OmniError.INVALID_REQUEST(message = "invalid request claim shape or payload oneOf mismatch"),
            )
            return@post
        }
        when (val r = handler.createAsyncInferenceRequest(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status.coerceAtLeast(202), r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_REQUEST_BY_ID) {
        val principal = call.requirePrincipal("getRequest", authenticator, transport) ?: return@get
        val id = call.parameters["requestId"] ?: return@get call.missingPath("requestId")
        when (val r = handler.getRequest(principal, id)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_REQUEST_CANCEL) {
        val principal = call.requirePrincipal("cancelRequest", authenticator, transport) ?: return@post
        val id = call.parameters["requestId"] ?: return@post call.missingPath("requestId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.cancelRequest(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_REQUEST_EVENTS) {
        val principal = call.requirePrincipal("streamRequestEvents", authenticator, transport) ?: return@get
        val id = call.parameters["requestId"] ?: return@get call.missingPath("requestId")
        val after = call.request.queryParameters["after_seq"]?.toLongOrNull()
        when (val r = handler.streamRequestEvents(principal, id, after)) {
            is SseHandlerResult.PreStreamError -> OmniErrorHttp.respond(
                call,
                r.error,
                TransportDeliveryGuarantee.HTTP_SSE,
            )
            is SseHandlerResult.Stream -> SseFraming.writeStream(call, r.events, r.headers)
        }
    }

    get(OpenApiPaths.OMNI_COMMAND_BY_ID) {
        val principal = call.requirePrincipal("getCommand", authenticator, transport) ?: return@get
        val id = call.parameters["commandId"] ?: return@get call.missingPath("commandId")
        when (val r = handler.getCommand(principal, id)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Assets ---
    post(OpenApiPaths.OMNI_ASSETS) {
        val principal = call.requirePrincipal("createAsset", authenticator, transport) ?: return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(AssetCreateRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid asset create JSON"))
            return@post
        }
        when (val r = handler.createAsset(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status.coerceAtLeast(201), r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    put(OpenApiPaths.OMNI_ASSET_CONTENT) {
        val principal = call.requirePrincipal("uploadAsset", authenticator, transport) ?: return@put
        val id = call.parameters["assetId"] ?: return@put call.missingPath("assetId")
        val length = call.request.header("Content-Length")?.toLongOrNull()
        if (length != null && length > config.maxAssetUploadBytes) {
            OmniErrorHttp.respond(
                call,
                OmniError.TRANSPORT_TOO_LARGE(message = "asset upload exceeds gateway limit"),
            )
            return@put
        }
        // API-06: OpenAPI defines the upload body as multipart/form-data
        // (AssetUploadRequest: command + content + expected_sha256 + expected_bytes).
        val multipart = try {
            call.receiveMultipart()
        } catch (e: Exception) {
            OmniErrorHttp.respond(
                call,
                OmniError.INVALID_REQUEST(message = "expected multipart/form-data upload body"),
            )
            return@put
        }
        var commandJson: String? = null
        var expectedSha256: String? = null
        var expectedBytes: Long? = null
        var contentSeen = false
        var tooBig = false
        val seenNames = mutableListOf<String>()
        val content = ByteArrayOutputStream()
        multipart.forEachPart { part ->
            try {
                part.name?.let { seenNames += "$it:${part::class.simpleName}" }
                when (part) {
                    is PartData.FormItem -> when (part.name) {
                        "command" -> commandJson = part.value
                        "expected_sha256" -> expectedSha256 = part.value
                        "expected_bytes" -> expectedBytes = part.value.toLongOrNull()
                    }
                    is PartData.BinaryItem -> if (part.name == "content" && !tooBig) {
                        contentSeen = true
                        val chunk = part.provider().readByteArray()
                        if (content.size().toLong() + chunk.size.toLong() > config.maxAssetUploadBytes) {
                            tooBig = true
                        } else {
                            content.write(chunk)
                        }
                    }
                    // FileItem (filename present) extends PartData directly in Ktor 3 —
                    // its provider() yields a ByteReadChannel.
                    is PartData.FileItem -> if (part.name == "content" && !tooBig) {
                        contentSeen = true
                        val chunk = part.provider().readRemaining().readByteArray()
                        if (content.size().toLong() + chunk.size.toLong() > config.maxAssetUploadBytes) {
                            tooBig = true
                        } else {
                            content.write(chunk)
                        }
                    }
                    // Ktor streams binary parts without a filename as BinaryChannelItem.
                    is PartData.BinaryChannelItem -> if (part.name == "content" && !tooBig) {
                        contentSeen = true
                        val chunk = part.provider().readRemaining().readByteArray()
                        if (content.size().toLong() + chunk.size.toLong() > config.maxAssetUploadBytes) {
                            tooBig = true
                        } else {
                            content.write(chunk)
                        }
                    }
                    else -> Unit
                }
            } finally {
                part.dispose()
            }
        }
        if (tooBig) {
            OmniErrorHttp.respond(
                call,
                OmniError.TRANSPORT_TOO_LARGE(message = "asset upload exceeds gateway limit"),
            )
            return@put
        }
        if (!contentSeen || commandJson == null) {
            OmniErrorHttp.respond(
                call,
                OmniError.INVALID_REQUEST(
                    message = "multipart upload requires 'command' and 'content' parts",
                    details = mapOf("seenParts" to seenNames.joinToString(",")),
                ),
            )
            return@put
        }
        val cmd = runCatching {
            HttpJson.codec.decodeFromString(CommandRequestDto.serializer(), commandJson!!)
        }.getOrElse {
            OmniErrorHttp.respond(
                call,
                OmniError.INVALID_REQUEST(message = "invalid CommandRequest in multipart 'command' part"),
            )
            return@put
        }
        if (!ClaimShape.isValidCommandRequest(cmd)) {
            OmniErrorHttp.respond(
                call,
                OmniError.INVALID_REQUEST(message = "invalid CommandRequest claim shape"),
            )
            return@put
        }
        val bytes = content.toByteArray()
        when (
            val r = handler.uploadAsset(
                principal,
                id,
                bytes,
                bytes.size.toLong(),
                cmd,
                expectedSha256,
                expectedBytes,
            )
        ) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_ASSET_COMMIT) {
        val principal = call.requirePrincipal("commitAsset", authenticator, transport) ?: return@post
        val id = call.parameters["assetId"] ?: return@post call.missingPath("assetId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.commitAsset(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_ASSET_BY_ID) {
        val principal = call.requirePrincipal("getAsset", authenticator, transport) ?: return@get
        val id = call.parameters["assetId"] ?: return@get call.missingPath("assetId")
        when (val r = handler.getAsset(principal, id)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    delete(OpenApiPaths.OMNI_ASSET_BY_ID) {
        val principal = call.requirePrincipal("deleteAsset", authenticator, transport) ?: return@delete
        val id = call.parameters["assetId"] ?: return@delete call.missingPath("assetId")
        // API-05: OpenAPI DELETE /assets/{assetId} requires a CommandRequest body
        // (idempotency claim) and answers 204 No Content on success.
        val cmd = call.receiveCommandOrError() ?: return@delete
        when (val r = handler.deleteAsset(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondText(
                text = "",
                status = HttpStatusCode.NoContent,
            )
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Jobs ---
    post(OpenApiPaths.OMNI_JOBS) {
        val principal = call.requirePrincipal("createJob", authenticator, transport) ?: return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(JobSpecDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid job spec JSON"))
            return@post
        }
        when (val r = handler.createJob(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_JOBS) {
        val principal = call.requirePrincipal("listOwnJobs", authenticator, transport) ?: return@get
        when (val r = handler.listOwnJobs(principal, call.request.queryParameters["page_token"])) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_JOB_BY_ID) {
        val principal = call.requirePrincipal("getJob", authenticator, transport) ?: return@get
        val id = call.parameters["jobId"] ?: return@get call.missingPath("jobId")
        when (val r = handler.getJob(principal, id)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_JOB_CANCEL) {
        val principal = call.requirePrincipal("cancelJob", authenticator, transport) ?: return@post
        val id = call.parameters["jobId"] ?: return@post call.missingPath("jobId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.cancelJob(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Metrics / settings / clients ---
    get(OpenApiPaths.OMNI_METRICS_SUMMARY) {
        val principal = call.requirePrincipal("getMetricSummary", authenticator, transport) ?: return@get
        when (val r = handler.getMetricSummary(principal)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_METRICS_DETAIL) {
        val principal = call.requirePrincipal("getMetricDetail", authenticator, transport) ?: return@get
        if (!call.requireLoopbackOnly("getMetricDetail", transport)) return@get
        when (val r = handler.getMetricDetail(principal)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_SETTINGS) {
        val principal = call.requirePrincipal("getSettings", authenticator, transport) ?: return@get
        if (!call.requireLoopbackOnly("getSettings", transport)) return@get
        when (val r = handler.getSettings(principal)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    patch(OpenApiPaths.OMNI_SETTINGS) {
        val principal = call.requirePrincipal("patchSettings", authenticator, transport) ?: return@patch
        if (!call.requireLoopbackOnly("patchSettings", transport)) return@patch
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@patch
        val req = runCatching {
            HttpJson.codec.decodeFromString(SettingsPatchDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid settings patch JSON"))
            return@patch
        }
        when (val r = handler.patchSettings(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_CLIENTS) {
        val principal = call.requirePrincipal("listClients", authenticator, transport) ?: return@get
        if (!call.requireLoopbackOnly("listClients", transport)) return@get
        when (val r = handler.listClients(principal, call.request.queryParameters["page_token"])) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_CLIENT_REVOKE) {
        val principal = call.requirePrincipal("revokeClient", authenticator, transport) ?: return@post
        if (!call.requireLoopbackOnly("revokeClient", transport)) return@post
        val id = call.parameters["clientId"] ?: return@post call.missingPath("clientId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.revokeClient(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- LAN ---
    post(OpenApiPaths.OMNI_LAN_ENABLE) {
        val principal = call.requirePrincipal("enableLan", authenticator, transport) ?: return@post
        if (!call.requireLoopbackOnly("enableLan", transport)) return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(LanEnableRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid LAN enable JSON"))
            return@post
        }
        when (val r = handler.enableLan(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_LAN_DISABLE) {
        val principal = call.requirePrincipal("disableLan", authenticator, transport) ?: return@post
        if (!call.requireLoopbackOnly("disableLan", transport)) return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(LanEnableRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid LAN disable JSON"))
            return@post
        }
        when (val r = handler.disableLan(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_LAN_PAIRING_CHALLENGES) {
        val principal = call.requirePrincipal("createLanPairingChallenge", authenticator, transport) ?: return@post
        if (!call.requireLoopbackOnly("createLanPairingChallenge", transport)) return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(LanPairingChallengeCreateRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid pairing challenge JSON"))
            return@post
        }
        when (val r = handler.createLanPairingChallenge(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondRawJson(r.body.json, r.body.status)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_PAIRING_EXCHANGES) {
        // operationId: completeLanPairing — security: []; LAN_TLS13 only
        if (transport != HttpTransportKind.LAN_TLS13) {
            OmniErrorHttp.respond(
                call,
                OmniError.FORBIDDEN(
                    message = "pairing exchange only on LAN TLS listener",
                    details = mapOf("transport" to transport.name),
                ),
            )
            return@post
        }
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        when (val r = handler.completeLanPairing(bodyText)) {
            is HttpHandlerResult.Ok -> call.respondRawJson(r.body.json, r.body.status)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Diagnostics ---
    post(OpenApiPaths.OMNI_DIAGNOSTICS_EXPORTS) {
        val principal = call.requirePrincipal("createDiagnosticExport", authenticator, transport) ?: return@post
        if (!call.requireLoopbackOnly("createDiagnosticExport", transport)) return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(DiagnosticExportRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid diagnostic export JSON"))
            return@post
        }
        when (val r = handler.createDiagnosticExport(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Content reports ---
    post(OpenApiPaths.OMNI_CONTENT_REPORTS) {
        val principal = call.requirePrincipal("createContentReportProposal", authenticator, transport) ?: return@post
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(ContentReportProposalRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid content report JSON"))
            return@post
        }
        when (val r = handler.createContentReportProposal(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_CONTENT_REPORT_BY_ID) {
        val principal = call.requirePrincipal("getContentReport", authenticator, transport) ?: return@get
        val id = call.parameters["reportId"] ?: return@get call.missingPath("reportId")
        when (val r = handler.getContentReport(principal, id)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_CONTENT_REPORT_CANCEL) {
        val principal = call.requirePrincipal("cancelContentReport", authenticator, transport) ?: return@post
        val id = call.parameters["reportId"] ?: return@post call.missingPath("reportId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.cancelContentReport(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_CONTENT_REPORT_DISCARD) {
        val principal = call.requirePrincipal("discardContentReport", authenticator, transport) ?: return@post
        val id = call.parameters["reportId"] ?: return@post call.missingPath("reportId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.discardContentReport(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_CONTENT_REPORT_RECEIPT) {
        val principal = call.requirePrincipal("getContentReportReceipt", authenticator, transport) ?: return@get
        val id = call.parameters["reportId"] ?: return@get call.missingPath("reportId")
        when (val r = handler.getContentReportReceipt(principal, id)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    // --- Tokens (loopback) ---
    post(OpenApiPaths.OMNI_TOKENS) {
        val principal = call.requirePrincipal("issueLoopbackAdminToken", authenticator, transport) ?: return@post
        if (transport != HttpTransportKind.LOOPBACK) {
            OmniErrorHttp.respond(
                call,
                OmniError.FORBIDDEN(message = "token issuance is loopback-only"),
            )
            return@post
        }
        val bodyText = call.receiveBoundedText(config.maxJsonBodyBytes) ?: return@post
        val req = runCatching {
            HttpJson.codec.decodeFromString(TokenIssueRequestDto.serializer(), bodyText)
        }.getOrElse {
            OmniErrorHttp.respond(call, OmniError.INVALID_REQUEST(message = "invalid token issue JSON"))
            return@post
        }
        when (val r = handler.issueLoopbackAdminToken(principal, req)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status.coerceAtLeast(201), r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    get(OpenApiPaths.OMNI_TOKENS) {
        val principal = call.requirePrincipal("listTokens", authenticator, transport) ?: return@get
        if (!call.requireLoopbackOnly("listTokens", transport)) return@get
        when (val r = handler.listTokens(principal, call.request.queryParameters["page_token"])) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }

    post(OpenApiPaths.OMNI_TOKEN_REVOKE) {
        val principal = call.requirePrincipal("revokeToken", authenticator, transport) ?: return@post
        if (!call.requireLoopbackOnly("revokeToken", transport)) return@post
        val id = call.parameters["tokenId"] ?: return@post call.missingPath("tokenId")
        val cmd = call.receiveCommandOrError() ?: return@post
        when (val r = handler.revokeToken(principal, id, cmd)) {
            is HttpHandlerResult.Ok -> call.respondJson(r.body, r.status, r.headers)
            is HttpHandlerResult.Err -> OmniErrorHttp.respond(call, r.error)
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private suspend fun ApplicationCall.requirePrincipal(
    operationId: String,
    authenticator: TokenAuthenticator,
    transport: HttpTransportKind,
): HttpPrincipal? {
    val required = OpenApiScopes.requiredScope(operationId)
    if (required == null) return null // public routes should not call this

    val header = request.header("Authorization")
    if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) {
        OmniErrorHttp.respond(
            this,
            OmniError.UNAUTHORIZED(message = "missing bearer token"),
        )
        return null
    }
    val token = header.substringAfter(' ').trim()
    if (token.isEmpty()) {
        OmniErrorHttp.respond(
            this,
            OmniError.UNAUTHORIZED(message = "empty bearer token"),
        )
        return null
    }

    return when (val result = authenticator.authenticate(token, transport)) {
        is AuthResult.Ok -> {
            val principal = result.principal
            if (transport == HttpTransportKind.LAN_TLS13 && principal.loopbackOnly) {
                OmniErrorHttp.respond(
                    this,
                    OmniError.FORBIDDEN(
                        message = "loopback-only token rejected on LAN listener",
                    ),
                )
                return null
            }
            if (!principal.hasScope(required)) {
                OmniErrorHttp.respond(
                    this,
                    OmniError.FORBIDDEN(
                        message = "missing required scope",
                        details = mapOf("required_scope" to required),
                    ),
                )
                return null
            }
            principal
        }
        is AuthResult.Unauthorized -> {
            OmniErrorHttp.respond(
                this,
                OmniError.UNAUTHORIZED(message = result.message),
            )
            null
        }
        is AuthResult.Forbidden -> {
            OmniErrorHttp.respond(
                this,
                OmniError.FORBIDDEN(message = result.message, details = result.details),
            )
            null
        }
    }
}

private suspend fun ApplicationCall.receiveBoundedText(maxBytes: Long): String? {
    val length = request.header("Content-Length")?.toLongOrNull()
    if (length != null && length > maxBytes) {
        OmniErrorHttp.respond(
            this,
            OmniError.TRANSPORT_TOO_LARGE(message = "request body too large"),
        )
        return null
    }
    val text = receiveText()
    if (text.toByteArray(Charsets.UTF_8).size.toLong() > maxBytes) {
        OmniErrorHttp.respond(
            this,
            OmniError.TRANSPORT_TOO_LARGE(message = "request body too large"),
        )
        return null
    }
    return text
}

private suspend fun ApplicationCall.receiveCommandOrError(): CommandRequestDto? {
    val text = receiveBoundedText(64L * 1024L) ?: return null
    val dto = runCatching {
        HttpJson.codec.decodeFromString(CommandRequestDto.serializer(), text)
    }.getOrElse {
        OmniErrorHttp.respond(this, OmniError.INVALID_REQUEST(message = "invalid CommandRequest JSON"))
        return null
    }
    if (!ClaimShape.isValidCommandRequest(dto)) {
        OmniErrorHttp.respond(this, OmniError.INVALID_REQUEST(message = "invalid CommandRequest claim shape"))
        return null
    }
    return dto
}

/**
 * API-13: enforce `x-omnillm-allowed-transports` for the listed operations.
 * Rejects with FORBIDDEN when the current [transport] is not allowed.
 */
private suspend fun ApplicationCall.requireLoopbackOnly(
    operationId: String,
    transport: HttpTransportKind,
): Boolean {
    if (transport == HttpTransportKind.LOOPBACK) return true
    OmniErrorHttp.respond(
        this,
        OmniError.FORBIDDEN(
            message = "$operationId is loopback-only (x-omnillm-allowed-transports)",
            details = mapOf(
                "operation" to operationId,
                "transport" to transport.name,
                "allowed" to "LOOPBACK",
            ),
        ),
    )
    return false
}

private suspend fun ApplicationCall.missingPath(name: String) {
    OmniErrorHttp.respond(this, OmniError.INVALID_REQUEST(message = "missing path parameter $name"))
}

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: Int,
    headers: Map<String, String> = emptyMap(),
) {
    for ((k, v) in headers) response.header(k, v)
    val json = HttpJson.codec.encodeToString(serializer<T>(), body)
    respondText(
        text = json,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.fromValue(status),
    )
}

private suspend fun ApplicationCall.respondRawJson(json: String, status: Int) {
    respondText(
        text = json,
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.fromValue(status),
    )
}

/**
 * Scaffold handler that returns CAPABILITY_UNSUPPORTED for domain ops
 * and minimal health for liveness. Used only when control plane not wired.
 */
object NotImplementedHttpHandler : OmniHttpHandlerPort {
    private fun <T> unsupported(op: String): HttpHandlerResult<T> =
        HttpHandlerResult.Err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "handler not wired: $op",
                details = mapOf("operation" to op),
            ),
        )

    override suspend fun getHealth(): HttpHandlerResult<HealthDto> =
        HttpHandlerResult.Ok(HealthDto(runtimeState = "UNKNOWN", resourceVersion = 0))

    override suspend fun listModels(principal: HttpPrincipal, pageToken: String?) =
        unsupported<ModelPageDto>("listModels")

    override suspend fun createChatCompletion(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ) = unsupported<ChatCompletionResponseDto>("createChatCompletion")

    override suspend fun createChatCompletionStream(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ) = SseHandlerResult.PreStreamError(
        OmniError.CAPABILITY_UNSUPPORTED(message = "handler not wired: createChatCompletionStream"),
    )

    override suspend fun createEmbedding(
        principal: HttpPrincipal,
        request: OpenAIEmbeddingRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ) = unsupported<EmbeddingResponseDto>("createEmbedding")

    override suspend fun createAsyncInferenceRequest(
        principal: HttpPrincipal,
        request: AsyncInferenceRequestDto,
    ) = unsupported<AcceptedRequestDto>("createAsyncInferenceRequest")

    override suspend fun getRequest(principal: HttpPrincipal, requestId: String) =
        unsupported<RequestStateDto>("getRequest")

    override suspend fun cancelRequest(
        principal: HttpPrincipal,
        requestId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("cancelRequest")

    override suspend fun streamRequestEvents(
        principal: HttpPrincipal,
        requestId: String,
        afterSeq: Long?,
    ) = SseHandlerResult.PreStreamError(
        OmniError.CAPABILITY_UNSUPPORTED(message = "handler not wired: streamRequestEvents"),
    )

    override suspend fun getCommand(principal: HttpPrincipal, commandId: String) =
        unsupported<CommandResultDto>("getCommand")

    override suspend fun createAsset(principal: HttpPrincipal, request: AssetCreateRequestDto) =
        unsupported<AssetInfoDto>("createAsset")

    override suspend fun uploadAsset(
        principal: HttpPrincipal,
        assetId: String,
        body: ByteArray,
        contentLength: Long?,
        command: CommandRequestDto?,
        expectedSha256: String?,
        expectedBytes: Long?,
    ) = unsupported<CommandResultDto>("uploadAsset")

    override suspend fun commitAsset(
        principal: HttpPrincipal,
        assetId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("commitAsset")

    override suspend fun getAsset(principal: HttpPrincipal, assetId: String) =
        unsupported<AssetInfoDto>("getAsset")

    override suspend fun deleteAsset(
        principal: HttpPrincipal,
        assetId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("deleteAsset")

    override suspend fun createJob(principal: HttpPrincipal, request: JobSpecDto) =
        unsupported<JobInfoDto>("createJob")

    override suspend fun listOwnJobs(principal: HttpPrincipal, pageToken: String?) =
        unsupported<JobPageDto>("listOwnJobs")

    override suspend fun getJob(principal: HttpPrincipal, jobId: String) =
        unsupported<JobInfoDto>("getJob")

    override suspend fun cancelJob(
        principal: HttpPrincipal,
        jobId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("cancelJob")

    override suspend fun getMetricSummary(principal: HttpPrincipal) =
        unsupported<MetricSnapshotDto>("getMetricSummary")

    override suspend fun getMetricDetail(principal: HttpPrincipal) =
        unsupported<MetricSnapshotDto>("getMetricDetail")

    override suspend fun getSettings(principal: HttpPrincipal) =
        unsupported<SettingsSnapshotDto>("getSettings")

    override suspend fun patchSettings(principal: HttpPrincipal, request: SettingsPatchDto) =
        unsupported<CommandResultDto>("patchSettings")

    override suspend fun listClients(principal: HttpPrincipal, pageToken: String?) =
        unsupported<ClientPageDto>("listClients")

    override suspend fun revokeClient(
        principal: HttpPrincipal,
        clientId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("revokeClient")

    override suspend fun enableLan(principal: HttpPrincipal, request: LanEnableRequestDto) =
        unsupported<CommandResultDto>("enableLan")

    override suspend fun disableLan(principal: HttpPrincipal, request: LanEnableRequestDto) =
        unsupported<CommandResultDto>("disableLan")

    override suspend fun createLanPairingChallenge(
        principal: HttpPrincipal,
        request: LanPairingChallengeCreateRequestDto,
    ) = unsupported<JsonRawBody>("createLanPairingChallenge")

    override suspend fun completeLanPairing(body: String) =
        unsupported<JsonRawBody>("completeLanPairing")

    override suspend fun createDiagnosticExport(
        principal: HttpPrincipal,
        request: DiagnosticExportRequestDto,
    ) = unsupported<JobInfoDto>("createDiagnosticExport")

    override suspend fun createContentReportProposal(
        principal: HttpPrincipal,
        request: ContentReportProposalRequestDto,
    ) = unsupported<ContentReportInfoDto>("createContentReportProposal")

    override suspend fun getContentReport(principal: HttpPrincipal, reportId: String) =
        unsupported<ContentReportInfoDto>("getContentReport")

    override suspend fun cancelContentReport(
        principal: HttpPrincipal,
        reportId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("cancelContentReport")

    override suspend fun discardContentReport(
        principal: HttpPrincipal,
        reportId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("discardContentReport")

    override suspend fun getContentReportReceipt(principal: HttpPrincipal, reportId: String) =
        unsupported<ContentReportReceiptDto>("getContentReportReceipt")

    override suspend fun issueLoopbackAdminToken(
        principal: HttpPrincipal,
        request: TokenIssueRequestDto,
    ) = unsupported<TokenIssueResultDto>("issueLoopbackAdminToken")

    override suspend fun listTokens(principal: HttpPrincipal, pageToken: String?) =
        unsupported<TokenPageDto>("listTokens")

    override suspend fun revokeToken(
        principal: HttpPrincipal,
        tokenId: String,
        command: CommandRequestDto,
    ) = unsupported<CommandResultDto>("revokeToken")
}
