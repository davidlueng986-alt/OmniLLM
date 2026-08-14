package com.omnillm.data.persistence

import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.ControlPlaneWriter
import java.time.Instant

/**
 * ClientRegistration ledger models + ports (C-08b).
 *
 * Authority: specs/database/omnillm-schema.sql#client_registrations
 * (CLIENT_REGISTRATION states from the catalog: PENDING | ACTIVE | SUSPENDED |
 * REVOCATION_REQUESTED | DRAINING | REVOKED | EXPIRED).
 *
 * Durable: handle, principal (observed UID + Android user), transport, state,
 * granted scopes, revocation epoch, display metadata. The process-global
 * revocation epoch lives in the `client_registration_epoch` singleton so the
 * INV-017 epoch fence survives restart.
 *
 * Encoding: `scopes` / `packageCandidates` are '\n'-joined (catalog scope ids
 * and package names cannot contain '\n'; the facade rejects any that do).
 */
object ClientRegistrationStates {
    val ALL: Set<String> = setOf(
        "PENDING",
        "ACTIVE",
        "SUSPENDED",
        "REVOCATION_REQUESTED",
        "DRAINING",
        "REVOKED",
        "EXPIRED",
    )
}

object ClientRegistrationTransports {
    val ALL: Set<String> = setOf("LOCAL_UI", "AIDL", "HTTP_LOOPBACK", "HTTP_LAN")
}

data class ClientRegistrationRow(
    val registrationId: String,
    val principalId: String,
    val observedUid: Int?,
    val userId: Int?,
    val transport: String,
    val state: String,
    val scopes: Set<String>,
    val packageCandidates: List<String>,
    val displayName: String?,
    val revocationEpochAtIssue: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(registrationId.isNotEmpty()) { "registrationId must be non-empty" }
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(transport in ClientRegistrationTransports.ALL) { "unknown transport: $transport" }
        require(state in ClientRegistrationStates.ALL) { "unknown registration state: $state" }
        require(revocationEpochAtIssue >= 0L) { "revocationEpochAtIssue must be >= 0" }
        for (s in scopes) {
            require('\n' !in s) { "scope must not contain newline" }
        }
        for (p in packageCandidates) {
            require('\n' !in p) { "package candidate must not contain newline" }
        }
    }

    fun scopesEncoded(): String = scopes.joinToString("\n")

    fun packageCandidatesEncoded(): String? =
        if (packageCandidates.isEmpty()) null else packageCandidates.joinToString("\n")
}

interface ClientRegistrationDao {
    fun findByRegistrationId(registrationId: String): ClientRegistrationRow?

    fun listAll(): List<ClientRegistrationRow>

    fun upsert(row: ClientRegistrationRow)

    fun updateState(registrationId: String, state: String, updatedAt: String): Boolean
}

/**
 * Bundled client-registration ports injected into the binder layer.
 * Marker [ControlPlaneWriter] documents single-writer ownership (ADR-010):
 * only the runtime control plane may write registrations.
 */
interface ClientRegistrationPorts : ControlPlaneWriter {
    val registrations: ClientRegistrationDao
    val tx: ClaimLedgerTransaction

    /** Process-global revocation epoch (INV-017 fence, durable singleton). */
    fun currentRevocationEpoch(): Long

    /** Advance the global epoch; returns the new value. */
    fun bumpRevocationEpoch(): Long
}

/** ISO-8601 (Instant.toString()) ↔ epoch millis mapping for registration rows. */
internal fun epochMillisToIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

internal fun isoToEpochMillis(iso: String): Long = Instant.parse(iso).toEpochMilli()
