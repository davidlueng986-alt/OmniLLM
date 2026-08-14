package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.OmniAssetCreateRequest
import ai.omnillm.api.OmniCommandRequest
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.data.persistence.AssetRecordRow
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.runtime.RequestRegistryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * C-08c (hybrid): asset METADATA must be durable while content bytes stay in
 * the quarantine dir with TTL enforced at access time.
 *
 * RED on current code: AssetHandleBroker keeps records in a process-local
 * ConcurrentHashMap — no `durable` constructor parameter exists, so nothing
 * survives a restart.
 */
class AssetHandleBrokerDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = { "2026-08-13T00:00:00Z" }

    private fun principal(uid: Int) = PrincipalId.parse("aidl:uid=$uid:user=0")

    private fun createRequest(assetId: String, ttlSeconds: Long = 600L): OmniAssetCreateRequest =
        OmniAssetCreateRequest().apply {
            this.assetId = assetId
            this.command = command(
                id = UUID.randomUUID().toString(),
                key = "create-$assetId",
                digest = IdentityHashing.sha256Hex(
                    """{"op":"CREATE_ASSET","assetId":"$assetId","maxBytes":1024}""",
                ),
            )
            purpose = "image"
            maxBytes = 1024L
            contentTypeHint = "image/png"
            expectedSha256 = null
            this.ttlSeconds = ttlSeconds
        }

    private fun command(id: String, key: String, digest: String): OmniCommandRequest =
        OmniCommandRequest().apply {
            commandId = id
            idempotencyKey = key
            hasExpectedVersion = false
            expectedVersion = 0L
            canonicalInputDigest = digest
        }

    private fun brokerFor(
        db: ControlPlaneDatabase,
        commands: com.omnillm.runtime.requestregistry.CommandLedger,
    ): AssetHandleBroker =
        AssetHandleBroker(
            commandLedger = commands,
            quarantineDir = tmp.newFolder("q-${UUID.randomUUID()}"),
            durable = db.assetRecords,
        )

    @Test
    fun metadataSurvivesBrokerRecreation_stateAndOwnerIntact() {
        val file = tmp.newFile("assets-durable.db")
        val p = principal(10051)
        val assetId = UUID.randomUUID().toString()

        // First "process": create via the durable-backed broker.
        val createdState = ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val (_, commands, _) = RequestRegistryModule.createInMemory()
            val broker = brokerFor(db, commands)
            val info = broker.create(p, createRequest(assetId))
            assertEquals("CREATED", info.state)
            assertNotNull(broker.get(p, assetId))
            info.state
        }
        assertEquals("CREATED", createdState)

        // Restart: fresh broker on the same DB file.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val (_, commands, _) = RequestRegistryModule.createInMemory()
            val broker = brokerFor(db, commands)
            val got = broker.get(p, assetId)
            assertEquals("CREATED", got.state)
            assertEquals(assetId, got.assetId)
            assertTrue(got.expiresAtEpochMillis > 0L)
        }
    }

    @Test
    fun deleteState_survivesBrokerRecreation() {
        val file = tmp.newFile("assets-delete-durable.db")
        val p = principal(10052)
        val assetId = UUID.randomUUID().toString()

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val (_, commands, _) = RequestRegistryModule.createInMemory()
            val broker = brokerFor(db, commands)
            broker.create(p, createRequest(assetId))
            val del = broker.delete(
                p,
                assetId,
                command(
                    id = UUID.randomUUID().toString(),
                    key = "del-$assetId",
                    digest = IdentityHashing.sha256Hex("""{"op":"DELETE_ASSET","assetId":"$assetId"}"""),
                ),
            )
            assertEquals("SUCCEEDED", del.state)
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val (_, commands, _) = RequestRegistryModule.createInMemory()
            val broker = brokerFor(db, commands)
            assertEquals("DELETED", broker.get(p, assetId).state)
        }
    }

    @Test
    fun expiredContent_refusedAfterRestart() {
        val file = tmp.newFile("assets-expired-durable.db")
        val p = principal(10053)
        val assetId = UUID.randomUUID().toString()

        // Write an already-expired record directly (TTL elapsed before restart).
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val now = System.currentTimeMillis()
            db.assetRecords.tx.inTransaction {
                db.assetRecords.assets.upsert(
                    AssetRecordRow(
                        assetId = assetId,
                        ownerPrincipalId = p.value,
                        purpose = "image",
                        state = "READY",
                        maxBytes = 1024L,
                        bytes = 512L,
                        expectedSha256 = null,
                        sha256 = "a".repeat(64),
                        contentTypeHint = "image/png",
                        storageKey = null,
                        expiresAtEpochMillis = now - 1_000L,
                        resourceVersion = 1L,
                        pinCount = 0,
                        createdAtEpochMillis = now - 60_000L,
                        updatedAtEpochMillis = now - 60_000L,
                    ),
                )
            }
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val (_, commands, _) = RequestRegistryModule.createInMemory()
            val broker = brokerFor(db, commands)
            val info = broker.get(p, assetId)
            assertEquals("EXPIRED", info.state)
            assertEquals("ASSET_EXPIRED", info.error?.code)
        }
    }
}

private fun <T> ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
