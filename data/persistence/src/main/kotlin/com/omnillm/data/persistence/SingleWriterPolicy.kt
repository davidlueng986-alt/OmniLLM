package com.omnillm.data.persistence

/**
 * ## Single-writer policy (ADR-010 / DATA-OWNERSHIP / INV-001)
 *
 * Runtime control plane is the **sole** authoritative writer of:
 * - domain DB (this module)
 * - model store projection
 * - trust state
 * - request / command / job / commit ledgers
 * - Asset and ClientRegistration durable state
 * - token vault material
 *
 * ### Forbidden writers
 * | Component | May write DB? |
 * |---|---|
 * | `:android:app-ui` | **No** (INV-001) |
 * | HTTP / AIDL transport adapters | **No** — only forward typed commands |
 * | Engine workers / isolated parser | **No** — evidence frames only |
 * | External companion (ADR-007) | **No** — different UID, untrusted |
 *
 * ### Allowed writer
 * | Component | Role |
 * |---|---|
 * | `:android:runtime-service` hosting `:runtime:*` | Single writer / control plane |
 *
 * Opening Room/DataStore/SQLite from a second process as a domain writer is an
 * architecture violation. Read-only projections for diagnostics may be added later
 * only under explicit product policy with quarantine semantics.
 *
 * Migration barrier: only the control plane runs migrations; attempt journal tables
 * `schema_migration_attempts` / `schema_migration_history` / `schema_metadata` track
 * STARTED → COMMITTED|ROLLED_BACK|FAILED|QUARANTINED (migration-policy.yaml).
 */
object SingleWriterPolicy {
    const val WRITER_ROLE: String = "runtime-control-plane"
    const val ADR: String = "ADR-010"
    const val INVARIANT: String = "INV-001"

    /** Logical process roles that must never hold a domain DB write connection. */
    val FORBIDDEN_WRITER_ROLES: Set<String> = setOf(
        "app-ui",
        "http-gateway",
        "aidl-transport",
        "engine-worker",
        "isolated-parser",
        "companion-sandbox",
    )

    fun assertWriterAllowed(role: String) {
        require(role == WRITER_ROLE) {
            "Domain DB write refused for role='$role' ($ADR / $INVARIANT). " +
                "Only $WRITER_ROLE may write."
        }
    }
}

/**
 * Marker interface for repositories that perform durable mutations.
 * Implementations must be constructed only inside the control plane process.
 */
interface ControlPlaneWriter {
    val writerRole: String get() = SingleWriterPolicy.WRITER_ROLE
}
