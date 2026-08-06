package com.omnillm.data.persistence

/**
 * Models for schema_metadata / schema_migration_attempts / schema_migration_history
 * (specs/database/omnillm-schema.sql + migration-policy.yaml).
 */

data class SchemaMetadataRow(
    val singletonId: Int = 1,
    val currentVersion: Int,
    val minReadableVersion: Int,
    val minWritableVersion: Int,
    val state: String,
    val updatedAt: String,
) {
    init {
        require(singletonId == 1)
        require(state == "ACTIVE" || state == "QUARANTINED")
        require(currentVersion >= 1)
        require(minReadableVersion in 1..currentVersion)
        require(minWritableVersion in minReadableVersion..currentVersion)
    }
}

data class SchemaMigrationAttemptRow(
    val migrationId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val planDigest: String,
    val state: String,
    val startedAt: String,
    val updatedAt: String,
    val errorCode: String? = null,
) {
    init {
        require(fromVersion >= 1)
        require(toVersion > fromVersion)
        require(planDigest.length == 64)
        require(state in SchemaAuthority.MIGRATION_ATTEMPT_STATES)
    }
}

data class SchemaMigrationHistoryRow(
    val migrationId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val planDigest: String,
    val committedAt: String,
) {
    init {
        require(planDigest.length == 64)
    }
}
