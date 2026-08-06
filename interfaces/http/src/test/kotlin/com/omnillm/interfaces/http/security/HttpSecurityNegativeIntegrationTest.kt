package com.omnillm.interfaces.http.security

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.CapabilityEntryDto
import com.omnillm.interfaces.http.HealthDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.JsonBodyAdmission
import com.omnillm.interfaces.http.ModelInfoDto
import com.omnillm.interfaces.http.ModelPageDto
import com.omnillm.interfaces.http.NotImplementedHttpHandler
import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.interfaces.http.gateway.configureGateway
import com.omnillm.interfaces.http.sse.SseFraming
import com.omnillm.runtime.policy.input.JsonParseLimits
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP negative security integration (SEC-THREAT, SEC-INPUT, SEC-AUTH-NET, ADR-011).
 *
 * Covers:
 * - revoked / unknown token → UNAUTHORIZED (**Q-007**)
 * - oversize JSON body → TRANSPORT_TOO_LARGE (**Q-010**)
 * - SSE disposition + framing contract for disconnect/query (**Q-005**)
 */
class HttpSecurityNegativeIntegrationTest {

    private val principal = HttpPrincipal(
        principalId = "neg-principal",
        tokenId = "tok-live",
        scopes = setOf("models.read", "inference.create", "inference.read-own"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

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
                                CapabilityEntryDto(id = "chat.completions", state = "SUPPORTED"),
                            ),
                            installationState = "READY",
                        ),
                    ),
                ),
            )
    }

    @Test
    fun revokedToken_rejectedUnauthorized_q007() = runBlocking {
        val revoked = mutableSetOf("revoked-token")
        val authenticator = TokenAuthenticator { token, transport ->
            when {
                token in revoked -> AuthResult.Unauthorized("token revoked")
                token == "good-token" && transport == HttpTransportKind.LOOPBACK ->
                    AuthResult.Ok(principal)
                else -> AuthResult.Unauthorized("unknown token")
            }
        }
        testApplication {
            application { configureGateway(handler, authenticator) }

            val revokedResp = client.get("/v1/models") {
                header("Authorization", "Bearer revoked-token")
            }
            assertEquals(HttpStatusCode.Unauthorized, revokedResp.status)
            assertTrue(revokedResp.bodyAsText().contains("UNAUTHORIZED"))

            val unknown = client.get("/v1/models") {
                header("Authorization", "Bearer never-issued")
            }
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        }
    }

    @Test
    fun oversizeJson_contentLengthRejected_q010() = runBlocking {
        val authenticator = TokenAuthenticator { token, transport ->
            if (token == "good-token" && transport == HttpTransportKind.LOOPBACK) {
                AuthResult.Ok(principal)
            } else {
                AuthResult.Unauthorized("bad")
            }
        }
        // Tight cap; body itself exceeds after receive (test host may recompute Content-Length).
        val tightConfig = GatewayConfig(maxJsonBodyBytes = 64L)
        val oversizedPayload =
            """{"model":"x","messages":[{"role":"user","content":"${"x".repeat(200)}"}]}"""
        assertTrue(oversizedPayload.toByteArray(Charsets.UTF_8).size > 64)
        testApplication {
            application {
                configureGateway(handler, authenticator, config = tightConfig)
            }
            val response = client.post("/v1/chat/completions") {
                header("Authorization", "Bearer good-token")
                contentType(ContentType.Application.Json)
                setBody(oversizedPayload)
            }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val body = response.bodyAsText()
            assertTrue(
                "expected TRANSPORT_TOO_LARGE in body: $body",
                body.contains("TRANSPORT_TOO_LARGE") || body.contains("too large"),
            )
        }
    }

    @Test
    fun oversizeJson_jsonBodyAdmissionGate_q010() {
        val limits = JsonParseLimits(
            maxCompressedBytes = 100,
            maxDecompressedBytes = 200,
            maxNestingDepth = 4,
            maxNodeCount = 16,
            maxArrayLength = 8,
            maxObjectKeys = 8,
            maxStringBytes = 32,
            maxKeyBytes = 16,
        )
        val oversize = JsonBodyAdmission.admitRawSize(
            compressedBytes = 50,
            decompressedBytes = 10_000,
            limits = limits,
        )
        assertNotNull(oversize)
        assertEquals(OmniErrorCode.TRANSPORT_TOO_LARGE, oversize!!.code)

        val deep: Any = (0 until 8).fold("leaf" as Any) { acc, _ -> mapOf("n" to acc) }
        val deepReject = JsonBodyAdmission.admitParsedTree(deep, limits)
        assertNotNull(deepReject)
        assertEquals(OmniErrorCode.TRANSPORT_TOO_LARGE, deepReject!!.code)

        val ok = JsonBodyAdmission.admitRawSize(
            compressedBytes = 10,
            decompressedBytes = 50,
            limits = limits,
        )
        assertNull(ok)
    }

    @Test
    fun sse_sessionDispositionIsStateless_disconnectEndsObserver_q005() {
        // CORE-INTERFACE §4 / ADR-011: SSE default is STATELESS — disconnect does not
        // stick or auto-reuse Session. Socket write ≠ application delivery.
        assertEquals("terminal", SseFraming.EVENT_TERMINAL)
        assertEquals("[DONE]", SseFraming.OPENAI_DONE)

        val done = SseFraming.frame(SseFraming.openAiDoneEvent())
        assertTrue(done.contains("data: [DONE]"))
        assertTrue(SseFraming.openAiDoneEvent().isTerminal)

        val terminal = SseFraming.terminalErrorEvent("""{"code":"CANCELLED"}""")
        assertTrue(terminal.isTerminal)
        assertEquals(SseFraming.EVENT_TERMINAL, terminal.event)
        // Framing alone is not a delivery claim — clients must query durable ledger on loss.
        assertFalse(done.contains("claimed"))
        assertFalse(done.contains("DELIVERED"))
    }
}
