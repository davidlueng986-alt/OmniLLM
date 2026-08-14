package com.omnillm.data.persistence

import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.SingleWriterPolicy

/**
 * SQLDelight-backed [ClientRegistrationPorts] for control-plane sole writer
 * (C-08b / ADR-010). Open only via [ControlPlaneDatabase] in the `:runtime`
 * process — the AIDL/binder layer never holds a DB writer directly.
 */
class SqlDelightClientRegistrationStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : ClientRegistrationPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val registrations: ClientRegistrationDao = object : ClientRegistrationDao {
        override fun findByRegistrationId(registrationId: String): ClientRegistrationRow? =
            database.clientRegistrationsQueries
                .selectByRegistrationId(registrationId)
                .executeAsOneOrNull()
                ?.toRegistrationRow()

        override fun listAll(): List<ClientRegistrationRow> =
            database.clientRegistrationsQueries
                .listAll()
                .executeAsList()
                .map { it.toRegistrationRow() }

        override fun upsert(row: ClientRegistrationRow) {
            require(row.state in ClientRegistrationStates.ALL) {
                "unknown registration state: ${row.state}"
            }
            database.clientRegistrationsQueries.upsertRegistration(
                registration_id = row.registrationId,
                principal_id = row.principalId,
                observed_uid = row.observedUid?.toLong(),
                android_user_id = row.userId?.toLong(),
                transport = row.transport,
                state = row.state,
                scope_json = row.scopesEncoded(),
                revocation_epoch = row.revocationEpochAtIssue,
                package_candidates = row.packageCandidatesEncoded(),
                display_name = row.displayName,
                created_at = epochMillisToIso(row.createdAtEpochMillis),
                updated_at = epochMillisToIso(row.updatedAtEpochMillis),
            )
        }

        override fun updateState(registrationId: String, state: String, updatedAt: String): Boolean {
            require(state in ClientRegistrationStates.ALL) {
                "unknown registration state: $state"
            }
            if (findByRegistrationId(registrationId) == null) return false
            database.clientRegistrationsQueries.updateState(
                state = state,
                updated_at = updatedAt,
                registration_id = registrationId,
            )
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }

    override fun currentRevocationEpoch(): Long =
        database.clientRegistrationsQueries
            .selectEpoch()
            .executeAsOneOrNull()
            ?: 0L

    override fun bumpRevocationEpoch(): Long {
        val next = currentRevocationEpoch() + 1L
        tx.inTransaction {
            database.clientRegistrationsQueries.upsertEpoch(next)
        }
        return next
    }
}

private fun Client_registrations.toRegistrationRow(): ClientRegistrationRow =
    ClientRegistrationRow(
        registrationId = registration_id,
        principalId = principal_id,
        observedUid = observed_uid?.toInt(),
        userId = android_user_id?.toInt(),
        transport = transport,
        state = state,
        scopes = scope_json.split('\n').filter { it.isNotEmpty() }.toSet(),
        packageCandidates = package_candidates
            ?.split('\n')
            ?.filter { it.isNotEmpty() }
            .orEmpty(),
        displayName = display_name,
        revocationEpochAtIssue = revocation_epoch,
        createdAtEpochMillis = isoToEpochMillis(created_at),
        updatedAtEpochMillis = isoToEpochMillis(updated_at),
    )
