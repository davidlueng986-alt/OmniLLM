package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable SQLDelight asset-metadata store (C-08c hybrid): rows survive reopen;
 * single-writer role asserted.
 */
class SqlDelightAssetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nowIso = "2026-08-13T12:00:00Z"
    private val clock = { nowIso }

    private fun sampleRow(
        assetId: String = "asset-0001",
        state: String = "CREATED",
    ): AssetRecordRow =
        AssetRecordRow(
            assetId = assetId,
            ownerPrincipalId = "aidl:uid=10051:user=0",
            purpose = "image",
            state = state,
            maxBytes = 1024L,
            bytes = 0L,
            expectedSha256 = null,
            sha256 = null,
            contentTypeHint = "image/png",
            storageKey = null,
            expiresAtEpochMillis = 1_700_000_600_000L,
            resourceVersion = 1L,
            pinCount = 0,
            createdAtEpochMillis = 1_700_000_000_000L,
            updatedAtEpochMillis = 1_700_000_000_000L,
        )

    private fun <T> withDb(file: java.io.File, block: (ControlPlaneDatabase) -> T): T {
        val db = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun assetRow_survivesReopen() {
        val file = tmp.newFile("assets-durable.db")
        withDb(file) { db ->
            db.assetRecords.tx.inTransaction {
                db.assetRecords.assets.upsert(sampleRow())
            }
        }
        withDb(file) { db ->
            val row = db.assetRecords.assets.findByAssetId("asset-0001")
            assertNotNull(row)
            assertEquals("aidl:uid=10051:user=0", row!!.ownerPrincipalId)
            assertEquals(1024L, row.maxBytes)
            assertEquals(1L, row.resourceVersion)
            assertEquals(1_700_000_600_000L, row.expiresAtEpochMillis)
        }
    }

    @Test
    fun upsert_overwritesStateAndVersion() {
        val file = tmp.newFile("assets-upsert.db")
        withDb(file) { db ->
            db.assetRecords.tx.inTransaction {
                db.assetRecords.assets.upsert(sampleRow())
            }
            db.assetRecords.tx.inTransaction {
                db.assetRecords.assets.upsert(
                    sampleRow().copy(state = "READY", resourceVersion = 2L, bytes = 512L),
                )
            }
        }
        withDb(file) { db ->
            val row = db.assetRecords.assets.findByAssetId("asset-0001")!!
            assertEquals("READY", row.state)
            assertEquals(2L, row.resourceVersion)
            assertEquals(512L, row.bytes)
        }
    }

    @Test
    fun listAll_roundTripsRows() {
        val file = tmp.newFile("assets-list.db")
        withDb(file) { db ->
            db.assetRecords.tx.inTransaction {
                db.assetRecords.assets.upsert(sampleRow("asset-a"))
                db.assetRecords.assets.upsert(sampleRow("asset-b", state = "DELETED"))
            }
        }
        withDb(file) { db ->
            val rows = db.assetRecords.assets.listAll()
            assertEquals(2, rows.size)
            assertTrue(rows.any { it.state == "DELETED" })
        }
    }
}
