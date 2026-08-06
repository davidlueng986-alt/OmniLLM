package com.omnillm.data.persistence

import com.omnillm.data.PersistenceModule
import java.io.InputStream

/**
 * Resolves persistence design authority (DATA-OWNERSHIP).
 *
 * Full SQL (indexes, triggers, FKs) lives in the packaged omnillm-schema.sql.
 * SQLDelight `.sq` files project a **subset** used by control-plane ledgers for
 * compile-time queries; they must not diverge from the authority SQL for shared tables.
 */
object SchemaAuthority {
    const val SPEC_REPO_PATH: String = PersistenceModule.SCHEMA_SPEC_PATH
    const val MIGRATION_POLICY_PATH: String = PersistenceModule.MIGRATION_POLICY_PATH
    const val PACKAGED_SCHEMA: String = PersistenceModule.PACKAGED_SCHEMA_RESOURCE
    const val PACKAGED_POLICY: String = PersistenceModule.PACKAGED_MIGRATION_POLICY_RESOURCE

    /** From specs/database/migration-policy.yaml compatibility block. */
    const val CURRENT_VERSION: Int = 2
    const val MIN_READABLE_VERSION: Int = 2
    const val MIN_WRITABLE_VERSION: Int = 2
    const val METADATA_TABLE: String = "schema_metadata"
    const val ATTEMPTS_TABLE: String = "schema_migration_attempts"
    const val HISTORY_TABLE: String = "schema_migration_history"

    val MIGRATION_ATTEMPT_STATES: Set<String> = setOf(
        "STARTED",
        "COMMITTED",
        "ROLLED_BACK",
        "FAILED",
        "QUARANTINED",
    )

    val MIGRATION_TERMINAL_STATES: Set<String> = setOf(
        "COMMITTED",
        "ROLLED_BACK",
        "FAILED",
        "QUARANTINED",
    )

    fun openPackagedSchema(): InputStream? =
        SchemaAuthority::class.java.classLoader.getResourceAsStream(PACKAGED_SCHEMA)

    fun requirePackagedSchema(): InputStream =
        openPackagedSchema()
            ?: error("Schema authority missing from classpath: $PACKAGED_SCHEMA")
}
