package com.omnillm.android.runtimeservice.http

import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.TokenIssueRequestDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D23d wire-contract drift: TokenIssueRequest (spec :2650-2684) requires
 * `scopes` (minItems 1) and `expires_in_seconds` (min 60, max 31536000) —
 * the handler silently fell back to BOOTSTRAP_SCOPES / 86400, minting
 * broad-scoped tokens for sloppy callers and accepting invalid TTLs.
 */
class TokenIssueStrictnessTest {

    private val principal = HttpPrincipal(
        principalId = "d23d-principal",
        tokenId = "tok-d23d",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val digest64 = "a".repeat(64)

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun handler(): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
    }

    private fun issue(
        h: ControlPlaneHttpHandler,
        scopes: List<String> = listOf("inference.create"),
        ttl: Long? = 3600L,
    ): HttpHandlerResult<*> = runBlocking {
        h.issueLoopbackAdminToken(
            principal,
            TokenIssueRequestDto(
                command = CommandRequestDto(
                    commandId = uuid(),
                    idempotencyKey = "d23d-${uuid()}",
                    canonicalInputDigest = digest64,
                ),
                clientId = "client-d23d",
                displayName = "strict token",
                scopes = scopes,
                expiresInSeconds = ttl,
            ),
        )
    }

    private fun assertInvalid(r: HttpHandlerResult<*>, context: String) {
        assertTrue("$context must be rejected (400): $r", r is HttpHandlerResult.Err)
        assertEquals(
            OmniErrorCode.INVALID_REQUEST,
            (r as HttpHandlerResult.Err).error.code,
        )
    }

    @Test
    fun issueToken_emptyScopes_isRejected() {
        val h = handler()
        // Spec minItems 1 — empty scopes must not fall back to BOOTSTRAP_SCOPES.
        assertInvalid(issue(h, scopes = emptyList()), "empty scopes")
    }

    @Test
    fun issueToken_nullTtl_isRejected() {
        val h = handler()
        // Spec requires expires_in_seconds — no silent 86400 default.
        assertInvalid(issue(h, ttl = null), "null TTL")
    }

    @Test
    fun issueToken_ttlBelowMinimum_isRejected() {
        val h = handler()
        assertInvalid(issue(h, ttl = 10L), "TTL below 60")
        assertInvalid(issue(h, ttl = 0L), "TTL 0")
    }

    @Test
    fun issueToken_ttlAboveMaximum_isRejected() {
        val h = handler()
        assertInvalid(issue(h, ttl = 31_536_001L), "TTL above 31536000")
    }

    @Test
    fun issueToken_validScopesAndTtl_succeeds() {
        val h = handler()
        val r = issue(h, scopes = listOf("inference.create"), ttl = 3600L)
        assertTrue("valid issuance must succeed: $r", r is HttpHandlerResult.Ok)
        assertEquals(201, (r as HttpHandlerResult.Ok).status)
        // Boundary values are legal (min 60 / max 31536000).
        assertTrue("TTL 60 boundary must succeed", issue(h, ttl = 60L) is HttpHandlerResult.Ok)
        assertTrue(
            "TTL 31536000 boundary must succeed",
            issue(h, ttl = 31_536_000L) is HttpHandlerResult.Ok,
        )
    }
}
