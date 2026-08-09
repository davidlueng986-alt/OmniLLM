package com.omnillm.data.persistence
import com.omnillm.core.ports.security.RevocationScope
import com.omnillm.core.ports.security.RevocationSubjectKind
import com.omnillm.core.ports.security.TransportConstraint

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.policy.security.CryptoPrimitives
import com.omnillm.runtime.policy.security.EncryptedBlobSecretKeyVault
import com.omnillm.runtime.policy.security.PairingChallengeService
import com.omnillm.core.ports.security.SecurityProfile
import com.omnillm.runtime.policy.security.TokenService
import com.omnillm.runtime.policy.security.VaultSecretBroker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Durable secrets / tokens / epoch fence (SW-DUR-04 / SEC-AUTH-NET / SEC-PROFILE).
 *
 * - Issue persists HMAC verifier only (no plaintext in SQLite)
 * - Revoke survives process reopen
 * - Principal epoch fence survives reopen
 * - EncryptedBlobSecretKeyVault master key reopens operational keys
 */
class SqlDelightSecretLedgerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private val masterKey = CryptoPrimitives.randomSecretKeyBytes()

    private fun openDb(file: File): ControlPlaneDatabase =
        ControlPlaneDatabase.openJdbcFile(file) { "2026-08-06T12:00:00Z" }

    private fun ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun securityStack(db: ControlPlaneDatabase): PolicyModule.SecurityStack {
        val vault = EncryptedBlobSecretKeyVault(
            store = db.secrets.keyBlobs,
            masterKeyBytes = masterKey,
            clockMs = { now },
        )
        val broker = VaultSecretBroker(vault = vault, clockMs = { now }, bootstrapKeys = true)
        return PolicyModule.createSecurityStack(
            clockMs = { now },
            broker = broker,
            accessTokenStore = db.secrets.accessTokens,
            pairingStore = db.secrets.pairingChallenges,
            epochStore = db.secrets.revocationEpochs,
        )
    }

    private fun issue(tokens: TokenService): TokenService.IssuedTokenView {
        val result = tokens.issue(
            TokenService.IssueRequest(
                registrationId = "reg-durable-1",
                principalId = PrincipalId.parse("http-dev-durable"),
                scopes = setOf("models.read", "inference.create"),
                transportConstraint = TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 3_600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
                label = "durable-test",
                clientId = "client-durable",
            ),
        ) as OmniResult.Ok
        return result.value
    }

    @Test
    fun issue_persistsHmacVerifierOnly_noPlaintextColumn() {
        val file = tmp.newFile("issue-hmac.db")
        lateinit var issued: TokenService.IssuedTokenView
        openDb(file).use { db ->
            val stack = securityStack(db)
            issued = issue(stack.tokenService)
            val row = db.secrets.accessTokens.get(issued.tokenId)!!
            assertEquals("ACTIVE", row.state)
            assertEquals(32, row.verifier.size)
            assertEquals(issued.scopes, row.scopes)
            assertFalse(String(row.verifier, Charsets.ISO_8859_1).contains(issued.plaintextOnce))
            val auth = stack.tokenService.authenticate(
                issued.plaintextOnce,
                TransportConstraint.LOOPBACK_ONLY,
                now,
            ) as OmniResult.Ok
            assertEquals(issued.tokenId, auth.value.tokenId)
        }

        openDb(file).use { db ->
            val stack = securityStack(db)
            val row = db.secrets.accessTokens.get(issued.tokenId)
            assertNotNull(row)
            assertEquals("ACTIVE", row!!.state)
            assertEquals(32, row.verifier.size)
            val auth = stack.tokenService.authenticate(
                issued.plaintextOnce,
                TransportConstraint.LOOPBACK_ONLY,
                now,
            ) as OmniResult.Ok
            assertEquals(issued.tokenId, auth.value.tokenId)
        }
    }

    @Test
    fun revoke_survivesReopen_andRejectsAuth() {
        val file = tmp.newFile("revoke.db")
        lateinit var issued: TokenService.IssuedTokenView
        openDb(file).use { db ->
            val stack = securityStack(db)
            issued = issue(stack.tokenService)
            val revoked = stack.tokenService.revoke(
                tokenId = issued.tokenId,
                actor = PrincipalId.parse("admin"),
                reason = "user-revoke",
            ) as OmniResult.Ok
            assertEquals("REVOKED", revoked.value.state)
            val auth = stack.tokenService.authenticate(
                issued.plaintextOnce,
                TransportConstraint.LOOPBACK_ONLY,
                now,
            ) as OmniResult.Err
            assertEquals(OmniErrorCode.UNAUTHORIZED, auth.error.code)
        }

        openDb(file).use { db ->
            val stack = securityStack(db)
            assertEquals("REVOKED", db.secrets.accessTokens.get(issued.tokenId)!!.state)
            val tokenEpoch = stack.revocation.currentEpoch(
                RevocationScope(issued.tokenId, RevocationSubjectKind.TOKEN),
            )
            assertTrue(tokenEpoch > 0L)
            val auth = stack.tokenService.authenticate(
                issued.plaintextOnce,
                TransportConstraint.LOOPBACK_ONLY,
                now,
            ) as OmniResult.Err
            assertEquals(OmniErrorCode.UNAUTHORIZED, auth.error.code)
        }
    }

    @Test
    fun principalEpochFence_survivesReopen() {
        val file = tmp.newFile("epoch-fence.db")
        lateinit var issued: TokenService.IssuedTokenView
        openDb(file).use { db ->
            val stack = securityStack(db)
            issued = issue(stack.tokenService)
            val fence = stack.revocation.revokeAndFence(
                scope = RevocationScope("http-dev-durable", RevocationSubjectKind.PRINCIPAL),
                actorPrincipalId = PrincipalId.parse("admin"),
                reason = "acl-change",
            ) as OmniResult.Ok
            assertEquals(1L, fence.value.epoch)
            val auth = stack.tokenService.authenticate(
                issued.plaintextOnce,
                TransportConstraint.LOOPBACK_ONLY,
                now,
            ) as OmniResult.Err
            assertEquals(OmniErrorCode.FORBIDDEN, auth.error.code)
        }

        openDb(file).use { db ->
            val stack = securityStack(db)
            assertEquals(
                1L,
                stack.revocation.currentEpoch(
                    RevocationScope("http-dev-durable", RevocationSubjectKind.PRINCIPAL),
                ),
            )
            val auth = stack.tokenService.authenticate(
                issued.plaintextOnce,
                TransportConstraint.LOOPBACK_ONLY,
                now,
            ) as OmniResult.Err
            assertEquals(OmniErrorCode.FORBIDDEN, auth.error.code)
        }
    }

    @Test
    fun encryptedKeyVault_roundTrip_acrossReopen() {
        val file = tmp.newFile("vault.db")
        lateinit var plaintext: String
        openDb(file).use { db ->
            val stack = securityStack(db)
            val mint = stack.secretBroker.mintBearerMaterial()
            plaintext = mint.plaintext
            assertEquals(SecurityProfile.BEARER_TOKEN_VERIFIER, mint.verifierAlgorithm)
            val again = stack.secretBroker.computeTokenVerifier(plaintext)!!
            assertArrayEquals(mint.verifier, again)
        }
        openDb(file).use { db ->
            val stack = securityStack(db)
            val again = stack.secretBroker.computeTokenVerifier(plaintext)
            assertNotNull(again)
            assertEquals(32, again!!.size)
        }
    }

    @Test
    fun pairingChallenge_encryptedSecret_survivesReopen() {
        val file = tmp.newFile("pairing.db")
        lateinit var challengeId: String
        openDb(file).use { db ->
            val stack = securityStack(db)
            val created = stack.pairingChallenges.createLanChallenge(
                PairingChallengeService.CreateLanChallengeRequest(
                    requestedScopes = setOf("models.read", "inference.create"),
                    serverSpkiSha256 = "a".repeat(64),
                    connectionEpoch = 1L,
                ),
            ) as OmniResult.Ok
            challengeId = created.value.challengeId
            assertEquals("PENDING", created.value.state)
            assertNotNull(created.value.secretPlaintextOnce)
            val stored = db.secrets.pairingChallenges.get(challengeId)!!
            assertNotNull(stored.secretEncrypted)
            assertNull(stored.secretPlaintextOnce)
        }
        openDb(file).use { db ->
            val stored = db.secrets.pairingChallenges.get(challengeId)!!
            assertEquals("PENDING", stored.state)
            assertNotNull(stored.secretEncrypted)
            assertNull(stored.secretPlaintextOnce)
            val stack = securityStack(db)
            val plain = stack.secretBroker.decryptRecord(stored.secretEncrypted!!) as OmniResult.Ok
            assertEquals(SecurityProfile.PAIRING_SECRET_BYTES, plain.value.size)
        }
    }
}
