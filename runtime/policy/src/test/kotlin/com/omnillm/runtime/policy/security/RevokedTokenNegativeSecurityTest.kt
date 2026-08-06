package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.policy.RevocationEpochManager
import com.omnillm.runtime.policy.RevocationScope
import com.omnillm.runtime.policy.RevocationSubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Revoked token negatives (SEC-THREAT, SEC-AUTH-NET, TOKEN FSM).
 *
 * Quality scenario: **Q-007** — revocation fences active/queued/pooled use of tokens.
 */
class RevokedTokenNegativeSecurityTest {

    private var now = 1_700_000_000_000L
    private val broker = InMemorySecretBroker { now }
    private val revocation = RevocationEpochManager(clock = { now })
    private val tokens = TokenService(broker, revocation) { now }

    private fun issueLoopback(
        scopes: Set<String> = setOf("models.read", "inference.create"),
        principal: String = "http-revoked-1",
    ): TokenService.IssuedTokenView {
        val result = tokens.issue(
            TokenService.IssueRequest(
                registrationId = "reg-revoked-1",
                principalId = PrincipalId.parse(principal),
                scopes = scopes,
                transportConstraint = TokenService.TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 3_600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
                label = "revoked-neg",
                clientId = "client-revoked",
            ),
        )
        assertTrue("issue failed: $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }

    @Test
    fun revokedToken_cannotAuthenticate_q007() {
        val issued = issueLoopback()
        val revoked = tokens.revoke(
            tokenId = issued.tokenId,
            actor = PrincipalId.parse("admin"),
            reason = "security-negative-test",
        )
        assertTrue(revoked is OmniResult.Ok)
        assertEquals("REVOKED", (revoked as OmniResult.Ok).value.state)

        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TokenService.TransportConstraint.LOOPBACK_ONLY,
            now,
        )
        assertTrue(auth is OmniResult.Err)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (auth as OmniResult.Err).error.code)
    }

    @Test
    fun principalRevocationEpochBump_fencesToken_q007() {
        val issued = issueLoopback(principal = "http-revoked-epoch")
        val fence = revocation.revokeAndFence(
            scope = RevocationScope("http-revoked-epoch", RevocationSubjectKind.PRINCIPAL),
            actorPrincipalId = PrincipalId.parse("admin"),
            reason = "acl-change-negative",
        )
        assertTrue(fence is OmniResult.Ok)
        assertTrue((fence as OmniResult.Ok).value.epoch >= 1L)

        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TokenService.TransportConstraint.LOOPBACK_ONLY,
            now,
        )
        assertTrue(auth is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (auth as OmniResult.Err).error.code)
    }

    @Test
    fun loopbackToken_rejectedOnLanConstraint() {
        val issued = issueLoopback()
        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TokenService.TransportConstraint.LAN_ONLY,
            now,
        )
        assertTrue(auth is OmniResult.Err)
        // Transport constraint mismatch must fail closed (not leak loopback admin to LAN).
        assertTrue(
            (auth as OmniResult.Err).error.code == OmniErrorCode.UNAUTHORIZED ||
                auth.error.code == OmniErrorCode.FORBIDDEN,
        )
    }
}
