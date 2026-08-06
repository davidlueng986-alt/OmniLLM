package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.policy.InMemoryRevocationEpochStore
import com.omnillm.runtime.policy.RevocationScope
import com.omnillm.runtime.policy.RevocationSubjectKind
import com.omnillm.runtime.policy.storageKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue / revoke / epoch fence with pluggable durable-style stores
 * (in-memory implementations of the same ports SQLite binds).
 */
class DurableSecretBrokerTest {

    private var now = 1_700_000_000_000L

    @Test
    fun encryptedBlobVault_issueRevokeEpoch_throughSecurityStack() {
        val master = CryptoPrimitives.randomSecretKeyBytes()
        val blobStore = InMemoryEncryptedKeyBlobStore()
        val vault = EncryptedBlobSecretKeyVault(blobStore, master) { now }
        val broker = VaultSecretBroker(vault, clockMs = { now }, bootstrapKeys = true)
        val tokenStore = InMemoryAccessTokenStore()
        val epochStore = InMemoryRevocationEpochStore()
        val stack = PolicyModule.createSecurityStack(
            clockMs = { now },
            broker = broker,
            accessTokenStore = tokenStore,
            epochStore = epochStore,
        )

        val issued = stack.tokenService.issue(
            TokenService.IssueRequest(
                registrationId = "reg-1",
                principalId = PrincipalId.parse("p1"),
                scopes = setOf("models.read"),
                transportConstraint = TokenService.TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
            ),
        ) as OmniResult.Ok

        assertTrue(tokenStore.get(issued.value.tokenId)!!.verifier.isNotEmpty())
        // No second broker instance needed — same vault reloads from blob store.
        val broker2 = VaultSecretBroker(
            EncryptedBlobSecretKeyVault(blobStore, master) { now },
            clockMs = { now },
            bootstrapKeys = false,
        )
        val v1 = broker.computeTokenVerifier(issued.value.plaintextOnce)!!
        val v2 = broker2.computeTokenVerifier(issued.value.plaintextOnce)!!
        assertArrayEquals(v1, v2)

        stack.tokenService.revoke(
            issued.value.tokenId,
            PrincipalId.parse("admin"),
            "test",
        )
        assertEquals("REVOKED", tokenStore.get(issued.value.tokenId)!!.state)

        val issued2 = stack.tokenService.issue(
            TokenService.IssueRequest(
                registrationId = "reg-2",
                principalId = PrincipalId.parse("p2"),
                scopes = setOf("models.read"),
                transportConstraint = TokenService.TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
            ),
        ) as OmniResult.Ok
        stack.revocation.revokeAndFence(
            RevocationScope("p2", RevocationSubjectKind.PRINCIPAL),
            PrincipalId.parse("admin"),
            "fence",
        )
        val auth = stack.tokenService.authenticate(
            issued2.value.plaintextOnce,
            TokenService.TransportConstraint.LOOPBACK_ONLY,
            now,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, auth.error.code)
        assertEquals(
            1L,
            epochStore.get(
                RevocationScope("p2", RevocationSubjectKind.PRINCIPAL).storageKey(),
            )!!.epoch,
        )
    }
}
