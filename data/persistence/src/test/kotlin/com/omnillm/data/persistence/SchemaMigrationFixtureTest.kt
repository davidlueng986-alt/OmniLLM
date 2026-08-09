package com.omnillm.data.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Executable migration fixture (MIG-001-TO-002) from
 * `specs/database/migration-fixtures.yaml` + `specs/database/migrations/0001_to_0002.sql`
 * (packaged byte-identical copy at classpath `db/migrations/0001_to_0002.sql`).
 *
 * Verifies:
 * - forward migration terminates COMMITTED with expectedMetadata
 *   (currentVersion 2, minReadable 2, minWritable 2, ACTIVE)
 * - after migration the control plane reopens and domain ledgers still work
 *   (kill-at-every-step leaves a valid schema, migration-policy rule)
 * - fail-closed CHECK constraints on attempt state and plan digest shape
 * - QUARANTINED metadata blocks any domain writer (negative fixture)
 */
class SchemaMigrationFixtureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun migrationSql(): String =
        SchemaMigrationFixtureTest::class.java.classLoader
            .getResourceAsStream("db/migrations/0001_to_0002.sql")
            ?.bufferedReader()?.readText()
            ?: error("packaged migration fixture db/migrations/0001_to_0002.sql missing")

    private fun openRaw(file: File): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    }

    /**
     * Apply the executable forward migration (MIG-001-TO-002) verbatim through
     * xerial's raw `sqlite3_exec` entry point (`DB._exec`, reached reflectively
     * because sqlite-jdbc is a runtime-only transitive dependency).
     *
     * The fixture uses explicit transaction control (BEGIN IMMEDIATE / COMMIT),
     * which the JDBC Statement API cannot honor: the driver wraps every
     * auto-commit statement in an implicit BEGIN/COMMIT pair and opens its own
     * transaction when auto-commit is disabled. `_exec` executes the script
     * exactly as authored, including `PRAGMA foreign_keys = ON` (mandatory per
     * migration-policy) and the per-phase transactions.
     */
    private fun applyForwardMigration(file: File) {
        openRaw(file).use { conn ->
            val dbClass = Class.forName("org.sqlite.SQLiteConnection")
            val db = dbClass.getMethod("getDatabase").invoke(conn)
            val rc = db.javaClass.getMethod("_exec", String::class.java)
                .invoke(db, migrationSql()) as Int
            check(rc == 0) { "migration script returned sqlite rc=$rc" }
        }
    }

    @Test
    fun mig001_forwardMigration_terminatesCommitted_withExpectedMetadata() {
        val file = tmp.newFile("mig-forward.db")
        applyForwardMigration(file)
        openRaw(file).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT migration_id, from_version, to_version, plan_digest, state " +
                        "FROM schema_migration_attempts WHERE migration_id = 'MIG-001-TO-002'",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals("MIG-001-TO-002", rs.getString(1))
                    assertEquals(1, rs.getInt(2))
                    assertEquals(2, rs.getInt(3))
                    assertEquals("a".repeat(64), rs.getString(4))
                    assertEquals("COMMITTED", rs.getString(5))
                }
                st.executeQuery(
                    "SELECT current_version, min_readable_version, min_writable_version, state " +
                        "FROM schema_metadata WHERE singleton_id = 1",
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(2, rs.getInt(1))
                    assertEquals(2, rs.getInt(2))
                    assertEquals(2, rs.getInt(3))
                    assertEquals("ACTIVE", rs.getString(4))
                }
                st.executeQuery(
                    "SELECT migration_id, plan_digest FROM schema_migration_history " +
                        "WHERE migration_id = 'MIG-001-TO-002'",
                ).use { rs ->
                    assertTrue("history row must exist for COMMITTED migration", rs.next())
                    assertEquals("MIG-001-TO-002", rs.getString(1))
                    assertEquals("a".repeat(64), rs.getString(2))
                }
            }
        }
    }

    @Test
    fun afterMigration_controlPlaneReopens_andDomainLedgersWork() {
        val file = tmp.newFile("mig-reopen.db")
        // Phase 0: v1 database already carries the full domain schema.
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OmniLlmDatabase.Schema.create(driver)
        driver.close()
        // Phase 1: apply forward migration MIG-001-TO-002.
        openRaw(file).use { conn ->
            applyForwardMigration(file)
        }
        // Phase 2: control plane restart keeps metadata; domain writes work.
        ControlPlaneDatabase.openJdbcFile(file) { "2026-08-06T00:00:00Z" }.use { db ->
            val row = InferenceRequestClaimRow(
                requestId = "33333333-3333-3333-3333-333333333333",
                principalId = "principal-1",
                operationKind = "CHAT",
                idempotencyKey = "idem-mig-1",
                canonicalRequestDigest = "c".repeat(64),
                state = "RECEIVED",
                createdAt = "2026-08-06T00:00:00Z",
                updatedAt = "2026-08-06T00:00:00Z",
            )
            db.claims.tx.inTransaction { db.claims.requests.insert(row) }
            assertEquals(row.requestId, db.claims.requests.findByRequestId(row.requestId)!!.requestId)
        }
    }

    @Test
    fun attemptState_unknownValue_failsClosedOnCheckConstraint() {
        val file = tmp.newFile("mig-bad-state.db")
        openRaw(file).use { conn ->
            conn.createStatement().use { st ->
                applyForwardMigration(file)
                var constraint = false
                try {
                    st.execute(
                        "INSERT INTO schema_migration_attempts(" +
                            "migration_id, from_version, to_version, plan_digest, state, started_at, updated_at, error_code" +
                            ") VALUES ('M-X', 1, 2, '" + "a".repeat(64) + "', 'NONSENSE', '2026-08-02T00:00:00Z', '2026-08-02T00:00:00Z', NULL)",
                    )
                } catch (_: SQLException) {
                    constraint = true
                }
                assertTrue("invalid attempt state must be rejected by CHECK", constraint)
            }
        }
    }

    @Test
    fun planDigest_wrongLength_failsClosedOnCheckConstraint() {
        val file = tmp.newFile("mig-bad-digest.db")
        openRaw(file).use { conn ->
            conn.createStatement().use { st ->
                applyForwardMigration(file)
                var constraint = false
                try {
                    st.execute(
                        "INSERT INTO schema_migration_attempts(" +
                            "migration_id, from_version, to_version, plan_digest, state, started_at, updated_at, error_code" +
                            ") VALUES ('M-Y', 1, 2, 'short-digest', 'STARTED', '2026-08-02T00:00:00Z', '2026-08-02T00:00:00Z', NULL)",
                    )
                } catch (_: SQLException) {
                    constraint = true
                }
                assertTrue("plan_digest must be 64 hex chars (CHECK)", constraint)
            }
        }
    }

    @Test
    fun quarantinedMetadata_blocksDomainWriter() {
        val file = tmp.newFile("mig-quarantine.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        OmniLlmDatabase.Schema.create(driver)
        driver.close()
        openRaw(file).use { conn ->
            conn.createStatement().use { st ->
                applyForwardMigration(file)
                st.executeUpdate(
                    "UPDATE schema_metadata SET state = 'QUARANTINED', updated_at = '2026-08-06T00:00:00Z'",
                )
            }
        }
        var blocked = false
        try {
            ControlPlaneDatabase.openJdbcFile(file) { "2026-08-06T00:00:00Z" }
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("QUARANTINED"))
            blocked = true
        }
        assertTrue("QUARANTINED schema_metadata must block the writer (negative fixture)", blocked)
    }

    @Test
    fun journalStates_matchMigrationPolicy() {
        assertEquals(
            setOf("STARTED", "COMMITTED", "ROLLED_BACK", "FAILED", "QUARANTINED"),
            SchemaAuthority.MIGRATION_ATTEMPT_STATES,
        )
        assertEquals(
            setOf("COMMITTED", "ROLLED_BACK", "FAILED", "QUARANTINED"),
            SchemaAuthority.MIGRATION_TERMINAL_STATES,
        )
    }

    private fun ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}

