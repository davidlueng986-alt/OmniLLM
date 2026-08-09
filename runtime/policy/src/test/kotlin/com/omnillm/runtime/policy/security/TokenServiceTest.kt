package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.ports.security.RevocationScope
import com.omnillm.core.ports.security.RevocationSubjectKind
import com.omnillm.core.ports.security.TransportConstraint
import com.omnillm.runtime.policy.RevocationEpochManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenServiceTest {

    private var now = 1_700_000_000_000L
    private val broker = InMemorySecretBroker { now }
    private val revocation = RevocationEpochManager(clock = { now })
    private val tokens = TokenService(broker, revocation) { now }

    private fun issueLoopback(
        scopes: Set<String> = setOf("models.read", "inference.create"),
        profile: AccessProfile = AccessProfile.DEVELOPER_CLIENT,
        ttl: Long = 3_600L,
    ): TokenService.IssuedTokenView {
        val result = tokens.issue(
            TokenService.IssueRequest(
                registrationId = "reg-1",
                principalId = PrincipalId.parse("http-dev-1"),
                scopes = scopes,
                transportConstraint = TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = ttl,
                profile = profile,
                label = "test",
                clientId = "client-1",
            ),
        ) as OmniResult.Ok
        return result.value
    }

    @Test
    fun issue_storesHmacOnly_plaintextReturnedOnce() {
        val issued = issueLoopback()
        assertTrue(issued.plaintextOnce.isNotBlank())
        assertEquals(32, CryptoPrimitives.decodeBase64Url(issued.plaintextOnce)!!.size)

        val stored = tokens.get(issued.tokenId)!!
        assertEquals("ACTIVE", stored.state)
        assertEquals(32, stored.verifier.size)
        // No plaintext field on durable record.
        assertEquals(issued.scopes, stored.scopes)

        // Authenticate with plaintext succeeds.
        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Ok
        assertEquals(issued.tokenId, auth.value.tokenId)

        // Wrong secret fails.
        val bad = tokens.authenticate(
            "not-a-real-token-value-xxxxxxxxxxxxxxxx",
            TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.UNAUTHORIZED, bad.error.code)
    }

    @Test
    fun loopbackToken_rejectedOnLanListener() {
        val issued = issueLoopback()
        val forbidden = tokens.authenticate(
            issued.plaintextOnce,
            TransportConstraint.LAN_ONLY,
            now,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, forbidden.error.code)
    }

    @Test
    fun unknownScope_failClosed() {
        val err = tokens.issue(
            TokenService.IssueRequest(
                registrationId = "reg",
                principalId = PrincipalId.parse("p"),
                scopes = setOf("not.a.real.scope"),
                transportConstraint = TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.INVALID_REQUEST, err.error.code)
    }

    @Test
    fun scopeOutsideProfile_failClosed() {
        val err = tokens.issue(
            TokenService.IssueRequest(
                registrationId = "reg",
                principalId = PrincipalId.parse("p"),
                scopes = setOf("tokens.manage"),
                transportConstraint = TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, err.error.code)
    }

    @Test
    fun revoke_fencesEpoch_andRejectsAuth() {
        val issued = issueLoopback()
        val revoked = tokens.revoke(
            tokenId = issued.tokenId,
            actor = PrincipalId.parse("admin"),
            reason = "user-revoke",
        ) as OmniResult.Ok
        assertEquals("REVOKED", revoked.value.state)

        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.UNAUTHORIZED, auth.error.code)
    }

    @Test
    fun principalEpochBump_rejectsToken() {
        val issued = issueLoopback()
        val fence = revocation.revokeAndFence(
            scope = RevocationScope(
                "http-dev-1",
                RevocationSubjectKind.PRINCIPAL,
            ),
            actorPrincipalId = PrincipalId.parse("admin"),
            reason = "acl-change",
        ) as OmniResult.Ok
        assertEquals(1L, fence.value.epoch)

        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, auth.error.code)
    }

    @Test
    fun requireScope_checksGrant() {
        val issued = issueLoopback(scopes = setOf("models.read"))
        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Ok
        assertTrue(tokens.requireScope(auth.value, AccessScope.models_read) is OmniResult.Ok)
        val denied = tokens.requireScope(auth.value, AccessScope.inference_create) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
    }

    @Test
    fun expiredToken_rejected() {
        val issued = issueLoopback(ttl = 60L)
        now += 61_000L
        val auth = tokens.authenticate(
            issued.plaintextOnce,
            TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.UNAUTHORIZED, auth.error.code)
    }

    @Test
    fun oneTimeReceipt_viaBroker() {
        val issued = issueLoopback()
        // Issue already staged receipt; take once succeeds then fails.
        val first = tokens.takePlaintextOnce(issued.issuanceKey) as OmniResult.Ok
        assertEquals(issued.plaintextOnce, first.value)
        assertTrue(tokens.takePlaintextOnce(issued.issuanceKey) is OmniResult.Err)
    }

    @Test
    fun listMetadata_excludesVerifierMaterial() {
        issueLoopback()
        val meta = tokens.listMetadata()
        assertEquals(1, meta.size)
        assertFalse(meta[0].tokenId.isBlank())
        // Metadata type has no verifier field by design.
    }
}
