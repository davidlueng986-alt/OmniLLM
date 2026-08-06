package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Durable MODEL_INSTALLATION ledger tests (CORE-MODEL / ADR-010).
 */
class SqlDelightInstallationStoreTest {

    private val clock = { "2026-08-06T10:00:00Z" }

    @Test
    fun upsertAndReload_survivesReopen() {
        val file = File.createTempFile("omnillm-install", ".db")
        file.deleteOnExit()

        val id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val rev = "c".repeat(64)
        val pkg = "d".repeat(64)

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.installations.installations.upsert(
                InstallationRecordRow(
                    installationId = id,
                    revisionId = rev,
                    artifactPackageId = pkg,
                    state = "DISCOVERED",
                    storageRootKey = InstallationRecordRow.pendingStorageRootKey(id),
                    createdAt = clock(),
                    updatedAt = clock(),
                ),
            )
            assertEquals(1, db.installations.installations.listCatalog().size)
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val row = db.installations.installations.findByInstallationId(id)
            assertNotNull(row)
            assertEquals("DISCOVERED", row!!.state)
            assertEquals(rev, row.revisionId)
            assertEquals(pkg, row.artifactPackageId)
        }
    }

    @Test
    fun promoteReady_updatesStorageRootAndClearsQuarantine() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
            val rev = "c".repeat(64)
            val pkg = "d".repeat(64)
            db.installations.installations.upsert(
                InstallationRecordRow(
                    installationId = id,
                    revisionId = rev,
                    artifactPackageId = pkg,
                    state = "COMPATIBILITY_CHECK",
                    storageRootKey = InstallationRecordRow.pendingStorageRootKey(id),
                    quarantineJobId = "job-1",
                    quarantineAttemptId = "a1",
                    authenticityOk = true,
                    licenseOk = true,
                    compatibilityOk = true,
                    performanceRecorded = false,
                    placementClass = "PRIVILEGED_TRUSTED",
                    trustEpoch = 1L,
                    createdAt = clock(),
                    updatedAt = clock(),
                ),
            )
            val readyKey = "installations/$id"
            db.installations.tx.inTransaction {
                val cur = db.installations.installations.findByInstallationId(id)!!
                db.installations.installations.upsert(
                    cur.copy(
                        state = "READY",
                        storageRootKey = readyKey,
                        quarantineJobId = null,
                        quarantineAttemptId = null,
                        resourceVersion = cur.resourceVersion + 1,
                        updatedAt = "2026-08-06T10:01:00Z",
                    ),
                )
            }
            val ready = db.installations.installations.findByInstallationId(id)!!
            assertEquals("READY", ready.state)
            assertEquals(readyKey, ready.storageRootKey)
            assertNull(ready.quarantineJobId)
            assertNull(ready.quarantineAttemptId)
            assertTrue(ready.authenticityOk == true)
            assertEquals(1, db.installations.installations.listByState("READY").size)
        }
    }

    @Test
    fun revisionLease_activeBlocksDeleteProjection() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            val rev = "c".repeat(64)
            db.revisionLeases.leases.upsert(
                RevisionLeaseRecordRow(
                    leaseId = "lease-1",
                    revisionId = rev,
                    requestId = "req-1",
                    principalId = "p1",
                    runtimeEpoch = 1L,
                    state = "ACTIVE",
                    referenceCount = 1,
                    createdAt = clock(),
                    updatedAt = clock(),
                ),
            )
            val active = db.revisionLeases.leases.listActiveByRevisionId(rev)
            assertEquals(1, active.size)
            assertEquals("ACTIVE", active.single().state)

            db.revisionLeases.leases.upsert(
                active.single().copy(state = "DRAINING", updatedAt = "2026-08-06T10:02:00Z"),
            )
            assertEquals(1, db.revisionLeases.leases.listActiveByRevisionId(rev).size)

            db.revisionLeases.leases.upsert(
                active.single().copy(state = "RELEASED", updatedAt = "2026-08-06T10:03:00Z"),
            )
            assertEquals(0, db.revisionLeases.leases.listActiveByRevisionId(rev).size)
        }
    }

    @Test
    fun catalogTrust_singletonUpsert() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            assertNull(db.catalogTrust.get())
            db.catalogTrust.upsert(
                CatalogTrustStateRow(
                    highestSequence = 3L,
                    trustedClockEpochMs = 1_700_000_000_000L,
                    bootId = "boot-1",
                    uncertain = false,
                    rootDigest = "a".repeat(64),
                    updatedAt = clock(),
                ),
            )
            val row = db.catalogTrust.get()
            assertNotNull(row)
            assertEquals(3L, row!!.highestSequence)
            assertEquals(false, row.uncertain)
            assertEquals("a".repeat(64), row.rootDigest)
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
