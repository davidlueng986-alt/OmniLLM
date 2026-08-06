package com.omnillm.data

import app.cash.sqldelight.db.SqlDriver
import com.omnillm.data.persistence.ControlPlaneDatabase
import java.io.File

/**
 * Module marker for `:data:persistence`.
 *
 * Schema authority: `specs/database/omnillm-schema.sql`
 * Migration policy: `specs/database/migration-policy.yaml`
 *
 * **ADR-010 / DATA-OWNERSHIP:** only the runtime control plane may write this database.
 * UI, gateway, workers, isolated parser, and companion must not open a second writer.
 *
 * Production open path: [ControlPlaneDatabase.open] with an Android `SqlDriver`,
 * or [ControlPlaneDatabase.openJdbcFile] for JVM tests / non-Android hosts.
 * Durable ledgers: claims, commits, sessions, **jobs** ([ControlPlaneDatabase.jobs]).
 * In-memory claim/commit/session stores are **test-only** (not bound on live control plane).
 */
object PersistenceModule {
    const val MODULE_PATH: String = ":data:persistence"
    const val SCHEMA_SPEC_PATH: String = "specs/database/omnillm-schema.sql"
    const val MIGRATION_POLICY_PATH: String = "specs/database/migration-policy.yaml"
    const val PACKAGED_SCHEMA_RESOURCE: String = "db/omnillm-schema.sql"
    const val PACKAGED_MIGRATION_POLICY_RESOURCE: String = "db/migration-policy.yaml"

    /** Open control-plane DB from an already-constructed [SqlDriver] (e.g. AndroidSqliteDriver). */
    fun openControlPlane(
        driver: SqlDriver,
        applySchema: Boolean = true,
        clock: () -> String = { java.time.Instant.now().toString() },
    ): ControlPlaneDatabase =
        ControlPlaneDatabase.open(driver = driver, applySchema = applySchema, clock = clock)

    /** File-backed durable DB (unit/integration + process-death recovery tests). */
    fun openControlPlaneFile(
        path: File,
        clock: () -> String = { java.time.Instant.now().toString() },
    ): ControlPlaneDatabase =
        ControlPlaneDatabase.openJdbcFile(path = path, clock = clock)
}
