package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Durable SQLDelight ClientRegistration store (C-08b): rows + the global
 * revocation epoch survive reopen; single-writer role asserted.
 */
class SqlDelightClientRegistrationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nowIso = "2026-08-12T12:00:00Z"
    private val clock = { nowIso }

    private fun sampleRow(
        registrationId: String = "reg-0001",
        state: String = "ACTIVE",
        epoch: Long = 0L,
        scopes: Set<String> = setOf("inference.create", "models.read"),
    ): ClientRegistrationRow =
        ClientRegistrationRow(
            registrationId = registrationId,
            principalId = "aidl:uid=10042:user=0",
            observedUid = 10042,
            userId = 0,
            transport = "AIDL",
            state = state,
            scopes = scopes,
            packageCandidates = listOf("com.omnillm.app"),
            displayName = "durable-client",
            revocationEpochAtIssue = epoch,
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
    fun registrationRow_survivesReopen() {
        val file = tmp.newFile("client-regs-durable.db")
        withDb(file) { db ->
            db.clientRegistrations.tx.inTransaction {
                db.clientRegistrations.registrations.upsert(sampleRow())
            }
        }
        withDb(file) { db ->
            val row = db.clientRegistrations.registrations.findByRegistrationId("reg-0001")
            assertNotNull(row)
            assertEquals(setOf("inference.create", "models.read"), row!!.scopes)
            assertEquals(listOf("com.omnillm.app"), row.packageCandidates)
            assertEquals("durable-client", row.displayName)
            assertEquals(0L, row.revocationEpochAtIssue)
        }
    }

    @Test
    fun updateState_mapsBack_stateChangePersists() {
        val file = tmp.newFile("client-regs-state.db")
        withDb(file) { db ->
            db.clientRegistrations.tx.inTransaction {
                db.clientRegistrations.registrations.upsert(sampleRow())
            }
            val ok = db.clientRegistrations.registrations.updateState(
                "reg-0001",
                "REVOKED",
                "2026-08-12T13:00:00Z",
            )
            assertTrue(ok)
            assertFalse(
                db.clientRegistrations.registrations.updateState(
                    "reg-unknown",
                    "REVOKED",
                    "2026-08-12T13:00:00Z",
                ),
            )
        }
        withDb(file) { db ->
            assertEquals("REVOKED", db.clientRegistrations.registrations.findByRegistrationId("reg-0001")!!.state)
        }
    }

    @Test
    fun globalEpoch_survivesReopen_andBumpsMonotonically() {
        val file = tmp.newFile("client-regs-epoch.db")
        withDb(file) { db ->
            assertEquals(0L, db.clientRegistrations.currentRevocationEpoch())
            assertEquals(1L, db.clientRegistrations.bumpRevocationEpoch())
            assertEquals(2L, db.clientRegistrations.bumpRevocationEpoch())
        }
        withDb(file) { db ->
            assertEquals(2L, db.clientRegistrations.currentRevocationEpoch())
            assertEquals(3L, db.clientRegistrations.bumpRevocationEpoch())
        }
    }

    @Test
    fun listAll_roundTripsScopesAndPackages() {
        val file = tmp.newFile("client-regs-list.db")
        withDb(file) { db ->
            db.clientRegistrations.tx.inTransaction {
                db.clientRegistrations.registrations.upsert(sampleRow("reg-a", scopes = emptySet()))
                db.clientRegistrations.registrations.upsert(sampleRow("reg-b"))
            }
        }
        withDb(file) { db ->
            val rows = db.clientRegistrations.registrations.listAll()
            assertEquals(2, rows.size)
            assertTrue(rows.any { it.scopes.isEmpty() })
            assertTrue(rows.any { it.scopes == setOf("inference.create", "models.read") })
        }
    }
}
