package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable catalog trust / sequence projection (SEC-SUPPLY / ADR-010).
 *
 * Table: `catalog_trust_state` (singleton row, control-plane sole writer).
 * Real JDBC driver + TemporaryFolder + reopen verification (process-death).
 */
class SqlDelightCatalogTrustStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = "2026-08-06T00:00:00Z"
    private val clock = { now }

    private fun row(
        highestSequence: Long = 42L,
        uncertain: Boolean = false,
    ) = CatalogTrustStateRow(
        highestSequence = highestSequence,
        trustedClockEpochMs = 1_700_000_000_000L,
        bootId = "boot-1",
        elapsedRealtimeAnchorMs = 12_345L,
        uncertain = uncertain,
        rootDigest = "a".repeat(64),
        updatedAt = now,
    )

    @Test
    fun get_onEmptyStore_returnsNull() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            assertNull(db.catalogTrust.get())
        }
    }

    @Test
    fun upsert_thenGet_roundTripsAllFields() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.catalogTrust.upsert(row())
            val loaded = db.catalogTrust.get()
            assertEquals(42L, loaded!!.highestSequence)
            assertEquals(1_700_000_000_000L, loaded.trustedClockEpochMs)
            assertEquals("boot-1", loaded.bootId)
            assertEquals(12_345L, loaded.elapsedRealtimeAnchorMs)
            assertFalse(loaded.uncertain)
            assertEquals("a".repeat(64), loaded.rootDigest)
            assertEquals(now, loaded.updatedAt)
        }
    }

    @Test
    fun upsert_singletonOverwrite_advancesSequence() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.catalogTrust.upsert(row(highestSequence = 1L))
            db.catalogTrust.upsert(row(highestSequence = 2L))
            val loaded = db.catalogTrust.get()
            assertEquals(2L, loaded!!.highestSequence)
        }
    }

    @Test
    fun uncertainFlag_roundTripsAsBoolean() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.catalogTrust.upsert(row(uncertain = true))
            assertTrue(db.catalogTrust.get()!!.uncertain)
            db.catalogTrust.upsert(row(uncertain = false))
            assertFalse(db.catalogTrust.get()!!.uncertain)
        }
    }

    @Test
    fun reopen_persistsTrustState() {
        val file = tmp.newFile("catalog-trust.db")
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.catalogTrust.upsert(row())
        }
        // Process death: reopen same file.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val loaded = db.catalogTrust.get()
            assertEquals(42L, loaded!!.highestSequence)
            assertEquals("boot-1", loaded.bootId)
            assertEquals(1_700_000_000_000L, loaded.trustedClockEpochMs)
        }
    }

    private fun ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
