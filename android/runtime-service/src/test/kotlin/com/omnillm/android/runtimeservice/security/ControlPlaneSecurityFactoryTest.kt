package com.omnillm.android.runtimeservice.security

import com.omnillm.core.ports.security.SecurityProfile
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.runtime.policy.security.CryptoPrimitives
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TST-04: hermetic ControlPlaneSecurityFactory stack construction.
 *
 * Uses the Context-free overload with an injected master key + an in-memory
 * SQLite ControlPlaneDatabase (no Android Keystore / Context required). Verifies
 * the production composition: Keystore-wrapped-master -> encrypted-blob vault
 * -> Secret Broker -> TokenService -> AccessControlEnforcer, and that the token
 * lifecycle (issue/verify/revoke-fence) really works through it.
 */
class ControlPlaneSecurityFactoryTest {

    private val fixedMaster: ByteArray = CryptoPrimitives.randomSecretKeyBytes()

    private fun stack(
        db: ControlPlaneDatabase = ControlPlaneDatabase.openInMemory(),
        masterKeyBytes: () -> ByteArray = { fixedMaster },
        clockMs: () -> Long = { 1_700_000_000_000L },
    ) = ControlPlaneSecurityFactory.createSecurityStack(
        controlPlaneDb = db,
        clockMs = clockMs,
        masterKeyBytes = masterKeyBytes,
    )

    @Test
    fun createSecurityStack_buildsAllLayers() {
        val s = stack()
        assertNotNull(s.policyManager)
        assertNotNull(s.secretBroker)
        assertNotNull(s.tokenService)
        assertNotNull(s.pairingChallenges)
        assertNotNull(s.accessControl)
        assertNotNull(s.revocation)
    }

    @Test
    fun masterKeyIsUsedForOperationalVaultKeys() {
        // Two stacks over the SAME durable store (the SQLite vault) with the
        // SAME master key must derive the SAME operational verifier key: a
        // token issued by stack A verifies in stack B (blob decrypt under the
        // shared master).
        val db = ControlPlaneDatabase.openInMemory()
        val s1 = stack(db)
        val s2 = stack(db)
        val issued = s1.tokenService.issue(
            com.omnillm.runtime.policy.security.TokenService.IssueRequest(
                registrationId = "reg-shared",
                principalId = com.omnillm.core.contracts.PrincipalId.parse("http-shared"),
                scopes = setOf("inference.create"),
                transportConstraint = com.omnillm.core.ports.security.TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 3600L,
                profile = com.omnillm.core.canonical.generated.AccessProfile.DEVELOPER_CLIENT,
                label = "shared",
                clientId = "client-shared",
            ),
        )
        assertTrue(issued is com.omnillm.core.canonical.generated.OmniResult.Ok)
        val plaintext = (issued as com.omnillm.core.canonical.generated.OmniResult.Ok).value.plaintextOnce
        val verified = s2.tokenService.authenticate(
            plaintext,
            com.omnillm.core.ports.security.TransportConstraint.LOOPBACK_ONLY,
        )
        assertTrue(
            "same master must unwrap the same verifier key across stacks: $verified",
            verified is com.omnillm.core.canonical.generated.OmniResult.Ok,
        )

        // Negative: a DIFFERENT master over the same store cannot decrypt the
        // vault blobs → verification fails CLOSED (hard failure, never silent
        // acceptance of stale key material).
        val otherMaster = stack(db, masterKeyBytes = { CryptoPrimitives.randomSecretKeyBytes() })
        assertThrows(IllegalStateException::class.java) {
            otherMaster.tokenService.authenticate(
                plaintext,
                com.omnillm.core.ports.security.TransportConstraint.LOOPBACK_ONLY,
            )
        }
    }

    @Test
    fun tokenIssueVerifyAndRevokeFence_workThroughTheStack() {
        val clock = mutableListOf(1_700_000_000_000L)
        val s = stack(clockMs = { clock[0] })
        val issued = s.tokenService.issue(
            com.omnillm.runtime.policy.security.TokenService.IssueRequest(
                registrationId = "reg-test",
                principalId = com.omnillm.core.contracts.PrincipalId.parse("http-test"),
                scopes = setOf("inference.create", "inference.read-own"),
                transportConstraint = com.omnillm.core.ports.security.TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 3600L,
                profile = com.omnillm.core.canonical.generated.AccessProfile.DEVELOPER_CLIENT,
                label = "test",
                clientId = "client-test",
            ),
        )
        assertTrue(issued is com.omnillm.core.canonical.generated.OmniResult.Ok)
        val token = (issued as com.omnillm.core.canonical.generated.OmniResult.Ok).value

        val verified = s.tokenService.authenticate(
            token.plaintextOnce,
            com.omnillm.core.ports.security.TransportConstraint.LOOPBACK_ONLY,
        )
        assertTrue(
            "issued token must verify: $verified",
            verified is com.omnillm.core.canonical.generated.OmniResult.Ok,
        )
        assertEquals(
            "http-test",
            (verified as com.omnillm.core.canonical.generated.OmniResult.Ok).value.principalId,
        )

        // Revocation epoch fence: bump + revoke → the same token must fail.
        s.revocation.revokeAndFence(
            scope = com.omnillm.core.ports.security.RevocationScope(
                "http-test",
                com.omnillm.core.ports.security.RevocationSubjectKind.PRINCIPAL,
            ),
            actorPrincipalId = com.omnillm.core.contracts.PrincipalId.parse("http-local-admin"),
            reason = "test-fence",
            authorised = true,
        )
        val afterRevoke = s.tokenService.authenticate(
            token.plaintextOnce,
            com.omnillm.core.ports.security.TransportConstraint.LOOPBACK_ONLY,
        )
        assertTrue(
            "revoked principal token must be rejected: $afterRevoke",
            afterRevoke is com.omnillm.core.canonical.generated.OmniResult.Err,
        )
    }

    @Test
    fun masterKeySize_mustBeSecretKeyBytes() {
        assertEquals(SecurityProfile.SECRET_KEY_BYTES, fixedMaster.size)
    }
}
