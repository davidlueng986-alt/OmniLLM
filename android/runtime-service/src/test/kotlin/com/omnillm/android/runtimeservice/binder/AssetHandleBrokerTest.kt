package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.OmniAssetCreateRequest
import ai.omnillm.api.OmniCommandRequest
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.runtime.RequestRegistryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AssetHandleBrokerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun createGetDelete_ownerBound_noPathLeak() {
        val (_, commands, _) = RequestRegistryModule.createInMemory()
        val broker = AssetHandleBroker(
            commandLedger = commands,
            quarantineDir = tmp.newFolder("q"),
        )
        val principal = PrincipalId.parse("aidl:uid=1:user=0")
        val assetId = UUID.randomUUID().toString()
        val createCmd = command(
            id = UUID.randomUUID().toString(),
            key = "create-1",
            digest = IdentityHashing.sha256Hex("""{"op":"CREATE_ASSET","assetId":"$assetId","maxBytes":1024}"""),
        )
        val req = OmniAssetCreateRequest().apply {
            this.assetId = assetId
            this.command = createCmd
            purpose = "image"
            maxBytes = 1024L
            contentTypeHint = "image/png"
            expectedSha256 = null
            ttlSeconds = 600L
        }
        val created = broker.create(principal, req)
        assertEquals("CREATED", created.state)
        assertEquals(assetId, created.assetId)
        assertNull(created.error)

        val got = broker.get(principal, assetId)
        assertEquals("CREATED", got.state)

        val other = PrincipalId.parse("aidl:uid=2:user=0")
        val forbidden = broker.get(other, assetId)
        assertEquals("FORBIDDEN", forbidden.error?.code)

        val delCmd = command(
            id = UUID.randomUUID().toString(),
            key = "del-1",
            digest = IdentityHashing.sha256Hex("""{"op":"DELETE_ASSET","assetId":"$assetId"}"""),
        )
        val deleted = broker.delete(principal, assetId, delCmd)
        assertEquals("SUCCEEDED", deleted.state)
        assertEquals("DELETED", broker.get(principal, assetId).state)
    }

    @Test
    fun createRejectsNonPositiveMaxBytes() {
        val (_, commands, _) = RequestRegistryModule.createInMemory()
        val broker = AssetHandleBroker(commands, tmp.newFolder("q2"))
        val principal = PrincipalId.parse("aidl:uid=1:user=0")
        val assetId = UUID.randomUUID().toString()
        val req = OmniAssetCreateRequest().apply {
            this.assetId = assetId
            command = command(UUID.randomUUID().toString(), "k", IdentityHashing.sha256Hex("x"))
            purpose = "audio"
            maxBytes = 0L
            ttlSeconds = 60L
        }
        val info = broker.create(principal, req)
        assertEquals("REJECTED", info.state)
        assertEquals("INVALID_REQUEST", info.error?.code)
    }

    private fun command(id: String, key: String, digest: String): OmniCommandRequest =
        OmniCommandRequest().apply {
            commandId = id
            idempotencyKey = key
            hasExpectedVersion = false
            expectedVersion = 0L
            canonicalInputDigest = digest
        }
}
