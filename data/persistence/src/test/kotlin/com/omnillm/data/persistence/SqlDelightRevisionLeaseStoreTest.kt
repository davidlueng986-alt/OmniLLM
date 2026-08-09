package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable REVISION_LEASE projection (CORE-MODEL §9 / DATA-OWNERSHIP).
 *
 * Table: `revision_leases` — REVISION_LEASE FSM states (ACTIVE / DRAINING /
 * RELEASED) from specs/state-machines.yaml. Real JDBC driver + TemporaryFolder
 * + reopen verification.
 */
class SqlDelightRevisionLeaseStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = "2026-08-06T00:00:00Z"
    private val clock = { now }

    private fun lease(
        leaseId: String = "lease-1",
        revisionId: String = "a".repeat(64),
        state: String = "ACTIVE",
    ) = RevisionLeaseRecordRow(
        leaseId = leaseId,
        revisionId = revisionId,
        requestId = "req-1",
        principalId = "principal-1",
        runtimeEpoch = 3L,
        state = state,
        installationId = "550e8400-e29b-41d4-a716-446655440000",
        referenceCount = 1,
        expiresAt = now,
        expiresAtMonotonic = 99L,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun upsert_thenFindByLeaseId_roundTripsAllFields() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.revisionLeases.leases.upsert(lease())
            val loaded = db.revisionLeases.leases.findByLeaseId("lease-1")
            assertNotNull(loaded)
            assertEquals("lease-1", loaded!!.leaseId)
            assertEquals("a".repeat(64), loaded.revisionId)
            assertEquals("req-1", loaded.requestId)
            assertEquals("principal-1", loaded.principalId)
            assertEquals(3L, loaded.runtimeEpoch)
            assertEquals("ACTIVE", loaded.state)
            assertEquals("550e8400-e29b-41d4-a716-446655440000", loaded.installationId)
            assertEquals(1, loaded.referenceCount)
            assertEquals(99L, loaded.expiresAtMonotonic)
        }
    }

    @Test
    fun listActiveByRevisionId_onlyReturnsActiveAndDraining() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.revisionLeases.leases.upsert(lease(leaseId = "l-1", state = "ACTIVE"))
            db.revisionLeases.leases.upsert(lease(leaseId = "l-2", state = "DRAINING"))
            db.revisionLeases.leases.upsert(lease(leaseId = "l-3", state = "RELEASED"))
            val active = db.revisionLeases.leases.listActiveByRevisionId("a".repeat(64))
            assertEquals(
                setOf("l-1", "l-2"),
                active.map { it.leaseId }.toSet(),
            )
        }
    }

    @Test
    fun upsert_sameLeaseId_updatesStateInPlace() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.revisionLeases.leases.upsert(lease(leaseId = "l-1", state = "ACTIVE"))
            db.revisionLeases.leases.upsert(
                lease(leaseId = "l-1", state = "RELEASED"),
            )
            val loaded = db.revisionLeases.leases.findByLeaseId("l-1")
            assertEquals("RELEASED", loaded!!.state)
            assertEquals(1, db.revisionLeases.leases.listAll().size)
        }
    }

    @Test
    fun unknownState_rejectedFailClosed() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            var rejected = false
            try {
                db.revisionLeases.leases.upsert(lease(state = "NOT_A_STATE"))
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertNull(db.revisionLeases.leases.findByLeaseId("lease-1"))
        }
    }

    @Test
    fun delete_removesRow_andMissingDeleteReturnsFalse() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            assertFalse(db.revisionLeases.leases.delete("missing"))
            db.revisionLeases.leases.upsert(lease())
            assertTrue(db.revisionLeases.leases.delete("lease-1"))
            assertNull(db.revisionLeases.leases.findByLeaseId("lease-1"))
            assertFalse(db.revisionLeases.leases.delete("lease-1"))
        }
    }

    @Test
    fun reopen_persistsLeases() {
        val file = tmp.newFile("revision-leases.db")
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.revisionLeases.leases.upsert(lease(leaseId = "l-1", state = "DRAINING"))
        }
        // Process death: reopen same file.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val loaded = db.revisionLeases.leases.findByLeaseId("l-1")
            assertEquals("DRAINING", loaded!!.state)
            assertEquals(3L, loaded.runtimeEpoch)
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
