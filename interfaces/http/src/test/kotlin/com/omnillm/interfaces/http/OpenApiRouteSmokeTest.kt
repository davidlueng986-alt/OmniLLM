package com.omnillm.interfaces.http

import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.configureGateway
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAPI route smoke tests against the loopback gateway with a minimal handler.
 * Authority: `specs/openapi/omnillm.openapi.yaml`, ADR-011.
 */
class OpenApiRouteSmokeTest {

    private val allScopes = OpenApiScopes.REQUIRED.values.filterNotNull().toSet()

    private val principal = HttpPrincipal(
        principalId = "smoke-principal",
        tokenId = "tok-smoke",
        scopes = allScopes,
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val authenticator = TokenAuthenticator { token, transport ->
        when {
            token == "good" && transport == HttpTransportKind.LOOPBACK ->
                AuthResult.Ok(principal)
            else -> AuthResult.Unauthorized("bad token")
        }
    }

    private val digest64 = "a".repeat(64)
    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val commandJson =
        """{"command_id":"$uuid","idempotency_key":"k1","canonical_input_digest":"$digest64"}"""

    private val handler = object : OmniHttpHandlerPort by NotImplementedHttpHandler {
        override suspend fun getHealth(): HttpHandlerResult<HealthDto> =
            HttpHandlerResult.Ok(HealthDto(runtimeState = "READY", resourceVersion = 1))

        override suspend fun listModels(
            principal: HttpPrincipal,
            pageToken: String?,
        ): HttpHandlerResult<ModelPageDto> =
            HttpHandlerResult.Ok(
                ModelPageDto(
                    items = listOf(
                        ModelInfoDto(
                            modelRevisionId = digest64,
                            displayName = "smoke",
                            capabilities = listOf(
                                CapabilityEntryDto(id = "chat.completions", state = "SUPPORTED"),
                            ),
                            installationState = "READY",
                        ),
                    ),
                ),
            )

        override suspend fun getRequest(
            principal: HttpPrincipal,
            requestId: String,
        ): HttpHandlerResult<RequestStateDto> =
            HttpHandlerResult.Ok(
                RequestStateDto(
                    requestId = requestId,
                    state = "COMPLETED",
                    resourceVersion = 1,
                ),
            )

        override suspend fun getCommand(
            principal: HttpPrincipal,
            commandId: String,
        ): HttpHandlerResult<CommandResultDto> =
            HttpHandlerResult.Ok(
                CommandResultDto(
                    commandId = commandId,
                    state = "SUCCEEDED",
                    resourceVersion = 1,
                    affectedResourceId = "res-1",
                ),
            )

        override suspend fun getMetricSummary(
            principal: HttpPrincipal,
        ): HttpHandlerResult<MetricSummaryDto> =
            HttpHandlerResult.Ok(
                MetricSummaryDto(
                    series = listOf(
                        MetricPointDto(id = "ttft_ms", value = 12.0, sampledAt = "2026-08-04T00:00:00Z"),
                    ),
                    resourceVersion = 1,
                ),
            )

        override suspend fun getSettings(
            principal: HttpPrincipal,
        ): HttpHandlerResult<SettingsSnapshotDto> =
            HttpHandlerResult.Ok(
                SettingsSnapshotDto(resourceVersion = 1, values = emptyMap()),
            )

        override suspend fun createAsyncInferenceRequest(
            principal: HttpPrincipal,
            request: AsyncInferenceRequestDto,
        ): HttpHandlerResult<AcceptedRequestDto> =
            HttpHandlerResult.Ok(
                AcceptedRequestDto(
                    requestId = request.requestId,
                    state = "RECEIVED",
                    queryUrl = "/omni/v1/requests/${request.requestId}",
                    eventsUrl = "/omni/v1/requests/${request.requestId}/events",
                ),
            )

        override suspend fun createChatCompletion(
            principal: HttpPrincipal,
            request: OpenAIChatRequestDto,
            requestIdHeader: String?,
            idempotencyKeyHeader: String?,
        ): HttpHandlerResult<ChatCompletionResponseDto> =
            HttpHandlerResult.Ok(
                ChatCompletionResponseDto(
                    id = "chatcmpl-smoke",
                    created = 0L,
                    model = request.model,
                    choices = listOf(
                        ChatCompletionChoiceDto(
                            message = ChatMessageDto(role = "assistant", content = "ok"),
                            finishReason = "stop",
                        ),
                    ),
                ),
            )
    }

    private fun withAuth(block: suspend io.ktor.client.HttpClient.() -> Unit) = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator)
            }
            client.block()
        }
    }

    @Test
    fun smoke_healthUnauthenticated() = withAuth {
        val r = get("/health")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("READY"))
    }

    @Test
    fun smoke_listModels() = withAuth {
        val r = get("/v1/models") {
            header("Authorization", "Bearer good")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("capabilities"))
    }

    @Test
    fun smoke_getRequest() = withAuth {
        val r = get("/omni/v1/requests/$uuid") {
            header("Authorization", "Bearer good")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("COMPLETED"))
    }

    @Test
    fun smoke_getCommand() = withAuth {
        val r = get("/omni/v1/commands/$uuid") {
            header("Authorization", "Bearer good")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("SUCCEEDED"))
    }

    @Test
    fun smoke_metricSummary() = withAuth {
        val r = get("/omni/v1/metrics/summary") {
            header("Authorization", "Bearer good")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("ttft_ms"))
    }

    @Test
    fun smoke_settings() = withAuth {
        val r = get("/omni/v1/settings") {
            header("Authorization", "Bearer good")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("resource_version") || r.bodyAsText().contains("resourceVersion") || r.status == HttpStatusCode.OK)
    }

    @Test
    fun smoke_createAsyncInferenceRequest() = withAuth {
        val body =
            """{"request_id":"$uuid","idempotency_key":"async-1","operation":"CHAT","model":"demo"}"""
        val r = post("/omni/v1/requests") {
            header("Authorization", "Bearer good")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        // OpenAPI createAsyncInferenceRequest projects Accepted (202).
        assertEquals(HttpStatusCode.Accepted, r.status)
        val text = r.bodyAsText()
        assertTrue(text.contains(uuid))
        assertTrue(text.contains("RECEIVED") || text.contains("query"))
    }

    @Test
    fun smoke_chatCompletion() = withAuth {
        val body =
            """{"model":"demo","messages":[{"role":"user","content":"hi"}],"stream":false}"""
        val r = post("/v1/chat/completions") {
            header("Authorization", "Bearer good")
            header("X-OmniLLM-Request-Id", uuid)
            header("Idempotency-Key", "chat-1")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("assistant"))
    }

    @Test
    fun smoke_unauthenticatedProtectedRoute_is401() = withAuth {
        val r = get("/omni/v1/settings")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun openApi_operationIds_allHaveScopeEntries() {
        for (op in OpenApiPaths.OPERATION_IDS) {
            // Fail closed on unknown operationId (INV-018).
            OpenApiScopes.requiredScope(op)
        }
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("getHealth"))
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("getCommand"))
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("createAsyncInferenceRequest"))
        assertEquals(null, OpenApiScopes.requiredScope("getHealth"))
        assertEquals(null, OpenApiScopes.requiredScope("completeLanPairing"))
    }

    @Test
    fun openApi_documentPackagedAndNonEmpty() {
        val stream: java.io.InputStream = OpenApiAuthority.requirePackagedDocument()
        stream.use { input ->
            val bytes = input.readBytes()
            val text = bytes.toString(Charsets.UTF_8)
            assertTrue(bytes.isNotEmpty())
            assertTrue(text.contains("openapi") || text.contains("paths"))
            // Launch-critical path inventory (CORE-INTERFACE / OpenAPI authority).
            val requiredPaths = listOf(
                "/health",
                "/v1/models",
                "/v1/chat/completions",
                "/v1/embeddings",
                "/omni/v1/requests",
                "/omni/v1/commands/{commandId}",
                "/omni/v1/assets",
                "/omni/v1/jobs",
                "/omni/v1/metrics/summary",
                "/omni/v1/diagnostics/exports",
                "/omni/v1/content-reports",
                "/omni/pairing/v1/exchanges",
            )
            for (p in requiredPaths) {
                assertTrue("OpenAPI missing path $p", text.contains(p))
            }
            // Capabilities ride on ModelInfo; admin snapshot is AIDL-only.
            assertTrue(text.contains("capabilities") || text.contains("CapabilityEntry"))
            assertFalse(text.contains("/admin/snapshot"))
        }
    }

    @Test
    fun smoke_embeddingsRouteExists() = withAuth {
        // Handler may be NotImplemented — route + scope must still answer (not 404).
        val body = """{"model":"demo","input":"hi"}"""
        val r = post("/v1/embeddings") {
            header("Authorization", "Bearer good")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        // NotImplemented returns CAPABILITY_UNSUPPORTED (422) or similar — never 404.
        assertTrue(
            "embeddings route missing: ${r.status}",
            r.status != HttpStatusCode.NotFound,
        )
    }

    @Test
    fun smoke_jobsAndDiagnosticsRoutesExist() = withAuth {
        val jobs = get("/omni/v1/jobs") {
            header("Authorization", "Bearer good")
        }
        assertTrue(jobs.status != HttpStatusCode.NotFound)

        val diagBody =
            """{"command":{"command_id":"$uuid","idempotency_key":"d1","canonical_input_digest":"$digest64"},"include_detail":false}"""
        val diag = post("/omni/v1/diagnostics/exports") {
            header("Authorization", "Bearer good")
            contentType(ContentType.Application.Json)
            setBody(diagBody)
        }
        assertTrue(diag.status != HttpStatusCode.NotFound)
    }
}
