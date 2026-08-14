package com.omnillm.interfaces.http

import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.configureGateway
import com.omnillm.runtime.policy.acl.PrincipalRateLimiter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-11 gateway tests: the HTTP gateway enforces the principal CONCURRENCY
 * quota — leases are acquired at the auth entry and released when the
 * response completes (a request with the quota held out is rejected with
 * RATE_LIMITED 429, and the slot frees after the response).
 */
class GatewayConcurrencyAdmissionTest {

    private val principal = HttpPrincipal(
        principalId = "c11-gw-principal",
        tokenId = "tok-c11-gw",
        scopes = setOf("models.read"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val authenticator = TokenAuthenticator { token, _ ->
        if (token == "good-token") AuthResult.Ok(principal) else AuthResult.Unauthorized("bad")
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
                        ),
                    ),
                ),
            )
    }

    @Test
    fun concurrentOverQuota_isRejectedWithRateLimited() = runBlocking {
        // Quota of 2 held out by the test — the gateway must admit nothing more.
        val admission = PrincipalRateLimiter(
            rpsLimit = { 0 },
            concurrencyLimit = { 2 },
        )
        val held = listOf(
            admission.tryAcquireConcurrency(principal.principalId),
            admission.tryAcquireConcurrency(principal.principalId),
        )
        assertTrue(held.all { it != null })
        try {
            testApplication {
                application {
                    configureGateway(handler, authenticator, admission = admission)
                }
                val ok = client.get("/v1/models") {
                    header("Authorization", "Bearer good-token")
                }
                assertEquals("quota full -> 429 RATE_LIMITED", HttpStatusCode.TooManyRequests, ok.status)
                assertTrue(ok.bodyAsText().contains("RATE_LIMITED"))
            }
        } finally {
            held.forEach { it?.release() }
        }
    }

    @Test
    fun slotReleasedAfterResponse_admitsNextRequest() = runBlocking {
        val admission = PrincipalRateLimiter(
            rpsLimit = { 0 },
            concurrencyLimit = { 1 },
        )
        testApplication {
            application {
                configureGateway(handler, authenticator, admission = admission)
            }
            val first = client.get("/v1/models") {
                header("Authorization", "Bearer good-token")
            }
            assertEquals(HttpStatusCode.OK, first.status)
            // The first request's lease was released on response completion —
            // the second request must be admitted.
            val second = client.get("/v1/models") {
                header("Authorization", "Bearer good-token")
            }
            assertEquals(HttpStatusCode.OK, second.status)
            assertEquals(0, admission.currentInFlight(principal.principalId))
        }
    }

    @Test
    fun disabled_zero_failsOpen() = runBlocking {
        val admission = PrincipalRateLimiter(
            rpsLimit = { 0 },
            concurrencyLimit = { 0 },
        )
        testApplication {
            application {
                configureGateway(handler, authenticator, admission = admission)
            }
            repeat(5) {
                val r = client.get("/v1/models") {
                    header("Authorization", "Bearer good-token")
                }
                assertEquals(HttpStatusCode.OK, r.status)
            }
        }
    }

    @Test
    fun noAdmissionWired_failsOpen() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator)
            }
            val r = client.get("/v1/models") {
                header("Authorization", "Bearer good-token")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }
    }
}
