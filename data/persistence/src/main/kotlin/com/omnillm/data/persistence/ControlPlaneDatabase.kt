package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.ContentReportLedgerPorts
import com.omnillm.core.ports.ledger.ToolProposalLedgerPorts
import com.omnillm.core.ports.ledger.SingleWriterPolicy

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.time.Instant

/**
 * Single-writer control-plane SQLite handle (ADR-010 / DATA-OWNERSHIP / INV-001).
 *
 * Opens [OmniLlmDatabase] (SQLDelight subset), ensures schema + `schema_metadata`,
 * and exposes durable claim/commit/session/job/content-report/installation/lease
 * [ports] for RequestRegistry / CommitLedger / SessionManager / JobManager /
 * ContentReportModule / ModelManager.
 *
 * **Must** be constructed only in the `:runtime` process control plane.
 * UI / workers / companion must never open a second domain writer.
 */
class ControlPlaneDatabase private constructor(
    val driver: SqlDriver,
    val database: OmniLlmDatabase,
    val claims: ClaimLedgerPorts,
    val commits: CommitLedgerPorts,
    val sessions: SessionLedgerPorts,
    val jobs: JobLedgerPorts,
    val contentReports: ContentReportLedgerPorts,
    val installations: InstallationLedgerPorts,
    val revisionLeases: RevisionLeaseLedgerPorts,
    val catalogTrust: CatalogTrustStateDao,
    /** Token verifiers / pairing challenges / revocation epochs / key blobs (SEC-AUTH-NET). */
    val secrets: SqlDelightSecretLedgerStore,
    /** Tool proposals + host result claims (FEAT-TOOLS / ADR-010). */
    val toolProposals: ToolProposalLedgerPorts,
    private val clock: () -> String,
) {
    /**
     * True when control-plane ledgers are SQLite-backed (process-crash durable).
     */
    val durable: Boolean = true

    fun close() {
        driver.close()
    }

    /**
     * REL-RECOVERY restart path: fence non-terminal commits into RECONCILING
     * so recovery does not blind-replay EXECUTE. Terminal rows are left intact
     * (queryCommit / queryRequest / queryCommand remain durable after reinject).
     */
    fun reconcileUnfinishedCommits(now: String = clock()): CommitReconcileResult {
        val open = commits.commits.listOpen()
        var marked = 0
        commits.tx.inTransaction {
            for (row in open) {
                if (row.state == "RECONCILING") continue
                val ok = commits.commits.updateState(
                    commitId = row.commitId,
                    state = "RECONCILING",
                    resultJson = row.resultJson,
                    errorCode = row.errorCode,
                    reconciliationDisposition = row.reconciliationDisposition,
                    updatedAt = now,
                )
                if (ok) marked++
            }
        }
        return CommitReconcileResult(
            openBefore = open.size,
            markedReconciling = marked,
            durable = true,
            reason = null,
        )
    }

    /**
     * COR-19 / REL-RECOVERY restart path for the REQUEST machine: fence every
     * request still in a non-terminal state (QUEUED / RESERVED / … / STREAMING)
     * into RECONCILING so a restarted control plane can never resume blind
     * execution. Terminal rows (COMPLETED / FAILED / CANCELLED /
     * ABORTED_UNCERTAIN) are left intact (exactly-one-terminal invariant).
     *
     * Mirrors [reconcileUnfinishedCommits]; recovery resolution follows the
     * REQUEST catalog (REQ-020/020C/021/021C/022 — query, never blind replay).
     */
    fun reconcileUnfinishedRequests(now: String = clock()): RequestReconcileResult {
        val open = claims.requests.listNonTerminal()
        var marked = 0
        claims.tx.inTransaction {
            for (row in open) {
                if (row.state == "RECONCILING") continue
                val ok = claims.requests.updateState(
                    requestId = row.requestId,
                    state = "RECONCILING",
                    updatedAt = now,
                    resourceVersion = row.resourceVersion + 1,
                )
                if (ok) marked++
            }
        }
        return RequestReconcileResult(
            openBefore = open.size,
            markedReconciling = marked,
            durable = true,
            reason = null,
        )
    }

    companion object {
        /**
         * Wrap an already-opened [SqlDriver].
         *
         * @param applySchema when true (Jdbc file/memory tests), create SQLDelight tables if missing.
         *   Android [app.cash.sqldelight.driver.android.AndroidSqliteDriver] applies schema itself —
         *   pass `applySchema = false` and only seed metadata.
         */
        fun open(
            driver: SqlDriver,
            applySchema: Boolean = true,
            writerRole: String = SingleWriterPolicy.WRITER_ROLE,
            clock: () -> String = { Instant.now().toString() },
        ): ControlPlaneDatabase {
            SingleWriterPolicy.assertWriterAllowed(writerRole)
            if (applySchema) {
                ensureSqlDelightSchema(driver)
            }
            val database = OmniLlmDatabase(driver)
            ensureSchemaMetadata(database, clock)
            return ControlPlaneDatabase(
                driver = driver,
                database = database,
                claims = SqlDelightClaimLedgerStore(database, writerRole),
                commits = SqlDelightCommitLedgerStore(database, writerRole),
                sessions = SqlDelightSessionStore(database, writerRole),
                jobs = SqlDelightJobLedgerStore(database, writerRole),
                contentReports = SqlDelightContentReportStore(database, writerRole),
                installations = SqlDelightInstallationStore(database, writerRole),
                revisionLeases = SqlDelightRevisionLeaseStore(database, writerRole),
                catalogTrust = SqlDelightCatalogTrustStore(database, writerRole),
                secrets = SqlDelightSecretLedgerStore(database, writerRole),
                toolProposals = SqlDelightToolProposalStore(database, writerRole),
                clock = clock,
            )
        }

        /**
         * Open a durable file-backed SQLite DB (unit/integration tests and non-Android hosts).
         * Creates parent dirs; creates schema when the file is new/empty.
         */
        fun openJdbcFile(
            path: File,
            writerRole: String = SingleWriterPolicy.WRITER_ROLE,
            clock: () -> String = { Instant.now().toString() },
        ): ControlPlaneDatabase {
            SingleWriterPolicy.assertWriterAllowed(writerRole)
            path.parentFile?.mkdirs()
            val isNew = !path.exists() || path.length() == 0L
            val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePath}")
            if (isNew) {
                OmniLlmDatabase.Schema.create(driver)
            } else if (!hasTable(driver, "schema_metadata")) {
                OmniLlmDatabase.Schema.create(driver)
            }
            return open(driver, applySchema = false, writerRole = writerRole, clock = clock)
        }

        /** In-memory SQLite (ephemeral; useful for pure unit tests of DAO mapping). */
        fun openInMemory(
            writerRole: String = SingleWriterPolicy.WRITER_ROLE,
            clock: () -> String = { Instant.now().toString() },
        ): ControlPlaneDatabase {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OmniLlmDatabase.Schema.create(driver)
            return open(driver, applySchema = false, writerRole = writerRole, clock = clock)
        }

        private fun ensureSqlDelightSchema(driver: SqlDriver) {
            if (!hasTable(driver, "schema_metadata")) {
                OmniLlmDatabase.Schema.create(driver)
            }
        }

        private fun ensureSchemaMetadata(database: OmniLlmDatabase, clock: () -> String) {
            val existing = database.schemaMetadataQueries.selectMetadata().executeAsOneOrNull()
            if (existing != null) {
                val version = existing.current_version.toInt()
                check(version >= SchemaAuthority.MIN_READABLE_VERSION) {
                    "schema version $version below min_readable ${SchemaAuthority.MIN_READABLE_VERSION}"
                }
                check(existing.state == "ACTIVE" || existing.state == "QUARANTINED") {
                    "schema_metadata state=${existing.state}"
                }
                if (existing.state == "QUARANTINED") {
                    error("schema_metadata QUARANTINED — control plane must not write (migration-policy)")
                }
                return
            }
            val now = clock()
            database.schemaMetadataQueries.upsertMetadata(
                singleton_id = 1L,
                current_version = SchemaAuthority.CURRENT_VERSION.toLong(),
                min_readable_version = SchemaAuthority.MIN_READABLE_VERSION.toLong(),
                min_writable_version = SchemaAuthority.MIN_WRITABLE_VERSION.toLong(),
                state = "ACTIVE",
                updated_at = now,
            )
        }

        private fun hasTable(driver: SqlDriver, name: String): Boolean {
            return try {
                val result = driver.executeQuery(
                    identifier = null,
                    sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    mapper = { cursor ->
                        QueryResult.Value(cursor.next().value)
                    },
                    parameters = 1,
                ) {
                    bindString(0, name)
                }
                result.value
            } catch (_: Exception) {
                false
            }
        }
    }
}

/** Outcome of restart commit reconcile (REL-RECOVERY RECONCILING path). */
data class CommitReconcileResult(
    val openBefore: Int,
    val markedReconciling: Int,
    val durable: Boolean,
    /** Non-null only when recovery is incomplete / forced DEGRADED. */
    val reason: String?,
) {
    val recoveryComplete: Boolean get() = durable && reason == null
}

/** Outcome of restart REQUEST-request fence (COR-19 / REL-RECOVERY). */
data class RequestReconcileResult(
    val openBefore: Int,
    val markedReconciling: Int,
    val durable: Boolean,
    /** Non-null only when recovery is incomplete / forced DEGRADED. */
    val reason: String?,
) {
    val recoveryComplete: Boolean get() = durable && reason == null
}

