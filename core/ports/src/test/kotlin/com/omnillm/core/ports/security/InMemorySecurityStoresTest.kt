package com.omnillm.core.ports.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D14a: in-memory store port contracts (ARC-01) — the exact CRUD shape
 * `:data:persistence` SQLite adapters must mirror: upsert-replaces, delete
 * reports whether a row was present, listing never leaks internal mutation.
 */
class InMemorySecurityStoresTest {

    private val hex64 = "a".repeat(64)
    private val verifier = ByteArray(32) { 3 }

    private fun token(id: String = "tok-1") = AccessTokenRecord(
        tokenId = id,
        registrationId = "reg-$id",
        principalId = "principal:u0",
        state = "ACTIVE",
        verifier = verifier,
        verifierAlgorithm = "HMAC-SHA-256",
        verifierKeyVersion = 1,
        transportConstraint = TransportConstraint.LAN_ONLY,
        scopes = setOf("inference.create"),
        revocationEpoch = 0L,
        issuedAtEpochMs = 1_000L,
        expiresAtEpochMs = 2_000L,
        label = null,
        clientId = null,
        updatedAtEpochMs = 1_000L,
    )

    // ----- InMemoryAccessTokenStore -----------------------------------------

    @Test
    fun accessTokenStore_upsertGetDeleteListContract() {
        val store = InMemoryAccessTokenStore()
        assertNull(store.get("tok-1"))
        assertFalse(store.delete("tok-1"))

        store.upsert(token("tok-1"))
        assertEquals("tok-1", store.get("tok-1")!!.tokenId)
        assertEquals(1, store.listAll().size)

        // Upsert replaces the full record under the same id.
        store.upsert(token("tok-1").copy(state = "REVOKED", revocationEpoch = 9L))
        val replaced = store.get("tok-1")!!
        assertEquals("REVOKED", replaced.state)
        assertEquals(9L, replaced.revocationEpoch)
        assertEquals(1, store.listAll().size)

        assertTrue(store.delete("tok-1"))
        assertNull(store.get("tok-1"))
        assertTrue(store.listAll().isEmpty())

        // clear() resets the whole store (bootstrap/teardown semantics).
        store.upsert(token("tok-2"))
        store.upsert(token("tok-3"))
        store.clear()
        assertTrue(store.listAll().isEmpty())
    }

    // ----- InMemoryPairingChallengeStore ------------------------------------

    private fun challenge(id: String, state: String = "PENDING") = PairingChallengeRecord(
        challengeId = id,
        kind = ChallengeKind.LAN_HMAC,
        state = state,
        principalId = null,
        observedUid = null,
        androidUserId = null,
        protocolLabel = SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL,
        requestedScopes = setOf("inference.create"),
        serverSpkiSha256 = hex64,
        connectionEpoch = 1L,
        serverNonceBase64Url = "bm9uY2U",
        secretEncrypted = EncryptedRecord(
            profileId = "p", recordType = "pairing-secret", recordId = "e-$id",
            schemaVersion = 1, keyVersion = 1, expiresAtEpochMs = 2_000L,
            nonce = ByteArray(12), ciphertext = ByteArray(16),
        ),
        attemptsRemaining = 5,
        expiresAtEpochMs = 2_000L,
        approvedAtEpochMs = null,
        consumedAtEpochMs = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        secretPlaintextOnce = null,
    )

    @Test
    fun pairingChallengeStore_crudContractAndReplace() {
        val store = InMemoryPairingChallengeStore()
        assertNull(store.get("c1"))
        store.upsert(challenge("c1"))
        assertEquals("PENDING", store.get("c1")!!.state)

        store.upsert(challenge("c1", state = "APPROVED"))
        assertEquals("APPROVED", store.get("c1")!!.state)
        assertEquals(1, store.listAll().size)

        store.upsert(challenge("c2"))
        assertEquals(2, store.listAll().size)

        assertTrue(store.delete("c1"))
        assertNull(store.get("c1"))
        assertFalse(store.delete("c1"))
        assertEquals(1, store.listAll().size)

        store.clear()
        assertTrue(store.listAll().isEmpty())
    }

    // ----- InMemoryRevocationEpochStore -------------------------------------

    private fun revocation(key: String, epoch: Long) = RevocationRecord(
        scope = RevocationScope(key, RevocationSubjectKind.TOKEN),
        epoch = epoch,
        state = "ACTIVE",
        reason = null,
        actorPrincipalId = "local-ui",
        updatedAtEpochMs = 1_000L,
    )

    @Test
    fun revocationEpochStore_keysByStorageKeyAndReplaces() {
        val store = InMemoryRevocationEpochStore()
        assertNull(store.get("TOKEN\u0000tok-1"))

        store.upsert(revocation("tok-1", epoch = 3L))
        val read = store.get("TOKEN\u0000tok-1")!!
        assertEquals(3L, read.epoch)
        assertTrue("scope storageKey must match", read.scope.storageKey() == "TOKEN\u0000tok-1")

        // Same subject re-upserted must replace (monotonic epoch fence), not duplicate.
        store.upsert(revocation("tok-1", epoch = 4L))
        assertEquals(1, store.listAll().size)
        assertEquals(4L, store.get("TOKEN\u0000tok-1")!!.epoch)

        // Different subject kinds never collide.
        store.upsert(
            RevocationRecord(
                scope = RevocationScope("tok-1", RevocationSubjectKind.PRINCIPAL),
                epoch = 1L,
                state = "ACTIVE",
                reason = null,
                actorPrincipalId = null,
                updatedAtEpochMs = 1_000L,
            ),
        )
        assertEquals(2, store.listAll().size)

        assertTrue(store.delete("TOKEN\u0000tok-1"))
        assertFalse(store.delete("TOKEN\u0000tok-1"))
    }

    // ----- InMemoryEncryptedKeyBlobStore ------------------------------------

    private fun keyMeta(
        purpose: SecurityProfile.KeyPurpose,
        version: Int,
        state: SecurityProfile.KeyState = SecurityProfile.KeyState.ACTIVE,
    ) = BrokerKeyMetadata(
        purpose = purpose,
        keyVersion = version,
        state = state,
        createdAtEpochMs = 1_000L,
        rotationReason = null,
    )

    @Test
    fun encryptedBlobStore_upsertActiveAndPerPurposeMaxVersion() {
        val store = InMemoryEncryptedKeyBlobStore()
        assertEquals(0, store.maxVersion())
        assertNull(store.activeMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER))

        store.upsert(keyMeta(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, version = 1), ByteArray(8) { 1 })
        store.upsert(keyMeta(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, version = 2), ByteArray(8) { 2 })
        store.upsert(
            keyMeta(SecurityProfile.KeyPurpose.RECORD_ENCRYPTION, version = 1),
            ByteArray(8) { 3 },
        )

        assertEquals(2, store.maxVersion())
        val active = store.activeMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER)!!
        assertEquals(2, active.keyVersion)
        assertEquals(2, store.getMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, 2)!!.keyVersion)
        assertTrue(
            store.getCiphertext(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, 2)!!.contentEquals(
                ByteArray(8) { 2 },
            ),
        )

        // listMetadata is purpose-filtered and version-sorted.
        assertEquals(
            listOf(1, 2),
            store.listMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER).map { it.keyVersion },
        )
        assertEquals(
            listOf(1),
            store.listMetadata(SecurityProfile.KeyPurpose.RECORD_ENCRYPTION).map { it.keyVersion },
        )
    }

    @Test
    fun encryptedBlobStore_activePointerOnlyMovesForActiveState() {
        val store = InMemoryEncryptedKeyBlobStore()
        store.upsert(keyMeta(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, version = 1), ByteArray(2))
        assertEquals(1, store.activeMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER)!!.keyVersion)

        // RETIRED keys are stored but never become the active pointer.
        store.upsert(
            keyMeta(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, version = 2, state = SecurityProfile.KeyState.RETIRED),
            ByteArray(2),
        )
        assertEquals(1, store.activeMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER)!!.keyVersion)
        assertEquals(2, store.maxVersion())

        // New ACTIVE version moves the pointer.
        store.upsert(keyMeta(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, version = 3), ByteArray(2))
        assertEquals(3, store.activeMetadata(SecurityProfile.KeyPurpose.TOKEN_VERIFIER)!!.keyVersion)
    }

    @Test
    fun encryptedBlobStore_upsertCopiesCiphertextInput() {
        val store = InMemoryEncryptedKeyBlobStore()
        val ciphertext = ByteArray(4) { 5 }
        store.upsert(keyMeta(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, version = 1), ciphertext)

        // Caller mutating its own array after upsert must not corrupt the store.
        ciphertext[0] = 99
        assertTrue(
            store.getCiphertext(SecurityProfile.KeyPurpose.TOKEN_VERIFIER, 1)!!.contentEquals(
                ByteArray(4) { 5 },
            ),
        )
    }
}
