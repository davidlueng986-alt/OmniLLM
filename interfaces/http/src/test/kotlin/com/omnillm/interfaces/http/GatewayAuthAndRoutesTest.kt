package com.omnillm.interfaces.http

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.interfaces.http.gateway.configureGateway
import com.omnillm.interfaces.http.sse.SseFraming
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
 * Loopback gateway auth + route smoke tests (CORE-INTERFACE, SEC-AUTH-NET).
 */
class GatewayAuthAndRoutesTest {

    private val principal = HttpPrincipal(
        principalId = "test-principal",
        tokenId = "tok-1",
        scopes = setOf(
            "models.read",
            "inference.create",
            "inference.read-own",
            "metrics.read-summary",
        ),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val authenticator = TokenAuthenticator { token, transport ->
        when {
            token == "good-token" && transport == HttpTransportKind.LOOPBACK ->
                AuthResult.Ok(principal)
            token == "loopback-only" && transport == HttpTransportKind.LAN_TLS13 ->
                AuthResult.Forbidden("loopback-only token rejected on LAN")
            else -> AuthResult.Unauthorized("bad token")
        }
    }

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
                            modelRevisionId = "a".repeat(64),
                            displayName = "demo",
                            capabilities = listOf(
                                CapabilityEntryDto(
                                    capabilityId = com.omnillm.core.canonical.generated.CapabilityId.TEXT_GENERATION,
                                    state = com.omnillm.core.canonical.generated.CapabilityState.SUPPORTED,
                                    evidenceLabel = com.omnillm.core.canonical.generated.EvidenceLabel.REPORTED,
                                ),
                            ),
                            installationState = "READY",
                        ),
                    ),
                ),
            )
    }

    @Test
    fun health_isUnauthenticatedAndMinimal() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator)
            }
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("READY"))
            assertFalse(body.contains("model_revision"))
        }
    }

    @Test
    fun models_requiresBearerToken() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator)
            }
            val unauth = client.get("/v1/models")
            assertEquals(HttpStatusCode.Unauthorized, unauth.status)
            assertTrue(unauth.bodyAsText().contains("UNAUTHORIZED"))

            val auth = client.get("/v1/models") {
                header("Authorization", "Bearer good-token")
            }
            assertEquals(HttpStatusCode.OK, auth.status)
            assertTrue(auth.bodyAsText().contains("capabilities"))
        }
    }

    @Test
    fun models_rejectsMissingScope() = runBlocking {
        val limited = TokenAuthenticator { _, _ ->
            AuthResult.Ok(
                principal.copy(scopes = setOf("metrics.read-summary")),
            )
        }
        testApplication {
            application {
                configureGateway(handler, limited)
            }
            val response = client.get("/v1/models") {
                header("Authorization", "Bearer x")
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("FORBIDDEN"))
        }
    }

    @Test
    fun sseFraming_terminalAndDone() {
        val frame = SseFraming.frame(SseFraming.openAiDoneEvent())
        assertTrue(frame.contains("data: [DONE]"))
        val term = SseFraming.frame(
            SseFraming.terminalErrorEvent("""{"code":"CANCELLED"}"""),
        )
        assertTrue(term.contains("event: terminal"))
        assertTrue(term.contains("CANCELLED"))
    }

    @Test
    fun openApiScopes_inventoryMatchesOperationIds() {
        for (op in OpenApiPaths.OPERATION_IDS) {
            // Fail closed if operationId missing from scope map.
            OpenApiScopes.requiredScope(op)
        }
        assertEquals(null, OpenApiScopes.requiredScope("getHealth"))
        assertEquals("inference.create", OpenApiScopes.requiredScope("createChatCompletion"))
    }

    @Test
    fun gatewayConfig_rejectsNonLoopbackHost() {
        try {
            GatewayConfig(host = "0.0.0.0")
            assertFalse("should have rejected non-loopback", true)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun chat_invalidJson_returnsInvalidRequest() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator)
            }
            val response = client.post("/v1/chat/completions") {
                header("Authorization", "Bearer good-token")
                contentType(ContentType.Application.Json)
                setBody("{not-json")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("INVALID_REQUEST"))
        }
    }
}
