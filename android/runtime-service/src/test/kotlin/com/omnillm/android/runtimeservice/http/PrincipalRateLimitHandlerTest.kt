package com.omnillm.android.runtimeservice.http

import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.policy.acl.PrincipalRateLimiter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-11 handler tests: the HTTP auth entry (enforceAccess) enforces the
 * principal RATE token bucket — a burst over
 * security.principalRpsLimit is rejected with RATE_LIMITED (429 retryable),
 * and a limit of 0 (disabled) fails open.
 */
class PrincipalRateLimitHandlerTest {

    private val principal = HttpPrincipal(
        principalId = "c11-rate-principal",
        tokenId = "tok-c11-rate",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun handlerWithLimiter(
        rps: Int,
        concurrent: Int = 0,
    ): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            rateLimiter = PrincipalRateLimiter(
                rpsLimit = { rps },
                concurrencyLimit = { concurrent },
            ),
        )
    }

    @Test
    fun burstOverRateLimit_isRejectedWithRateLimited() = runBlocking {
        val h = handlerWithLimiter(rps = 2)
        assertTrue(h.listModels(principal, null) is HttpHandlerResult.Ok)
        assertTrue(h.listModels(principal, null) is HttpHandlerResult.Ok)
        val third = h.listModels(principal, null)
        assertTrue("burst over limit must be rejected: $third", third is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.RATE_LIMITED, (third as HttpHandlerResult.Err).error.code)
    }

    @Test
    fun rateLimit_appliesPerPrincipal() = runBlocking {
        val h = handlerWithLimiter(rps = 1)
        assertTrue(h.listModels(principal, null) is HttpHandlerResult.Ok)
        val other = principal.copy(principalId = "other-principal")
        assertTrue(
            "a different principal has its own bucket",
            h.listModels(other, null) is HttpHandlerResult.Ok,
        )
    }

    @Test
    fun rateLimitDisabled_zero_failsOpen() = runBlocking {
        val h = handlerWithLimiter(rps = 0)
        repeat(100) {
            assertTrue(
                "0 = disabled = fail-open (iteration $it)",
                h.listModels(principal, null) is HttpHandlerResult.Ok,
            )
        }
    }

    @Test
    fun rateLimit_doesNotConsumeConcurrencyLeases() = runBlocking {
        // The handler consumes RATE tokens only — concurrency leases stay
        // owned by the gateway dimension (no double counting).
        val h = handlerWithLimiter(rps = 2, concurrent = 1)
        assertTrue(h.listModels(principal, null) is HttpHandlerResult.Ok)
        assertTrue(h.listModels(principal, null) is HttpHandlerResult.Ok)
        val denied = h.listModels(principal, null)
        assertEquals(OmniErrorCode.RATE_LIMITED, (denied as HttpHandlerResult.Err).error.code)
    }
}
