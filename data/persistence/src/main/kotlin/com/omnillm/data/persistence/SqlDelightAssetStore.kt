package com.omnillm.data.persistence

import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.SingleWriterPolicy

/**
 * SQLDelight-backed [AssetLedgerPorts] for control-plane sole writer
 * (C-08c / ADR-010). Open only via [ControlPlaneDatabase] in the `:runtime`
 * process ??transport/binder layers never hold a DB writer directly.
 *
 * Metadata only: content bytes stay in the quarantine dir (storageKey) and
 * are never stored in SQL.
 */
class SqlDelightAssetStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : AssetLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val assets: AssetRecordDao = object : AssetRecordDao {
        override fun findByAssetId(assetId: String): AssetRecordRow? =
            database.assetsQueries
                .selectByAssetId(assetId)
                .executeAsOneOrNull()
                ?.toAssetRow()

        override fun listAll(): List<AssetRecordRow> =
            database.assetsQueries
                .listAll()
                .executeAsList()
                .map { it.toAssetRow() }

        override fun upsert(row: AssetRecordRow) {
            require(row.state in AssetRecordStates.ALL) {
                "unknown asset state: ${row.state}"
            }
            database.assetsQueries.upsertAsset(
                asset_id = row.assetId,
                principal_id = row.ownerPrincipalId,
                purpose = row.purpose,
                state = row.state,
                max_bytes = row.maxBytes,
                actual_bytes = row.bytes,
                expected_digest = row.expectedSha256,
                actual_digest = row.sha256,
                content_type_hint = row.contentTypeHint,
                storage_key = row.storageKey,
                expires_at = epochMillisToIso(row.expiresAtEpochMillis),
                resource_version = row.resourceVersion,
                pin_count = row.pinCount.toLong(),
                created_at = epochMillisToIso(row.createdAtEpochMillis),
                updated_at = epochMillisToIso(row.updatedAtEpochMillis),
            )
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Assets.toAssetRow(): AssetRecordRow =
    AssetRecordRow(
        assetId = asset_id,
        ownerPrincipalId = principal_id,
        purpose = purpose,
        state = state,
        maxBytes = max_bytes,
        bytes = actual_bytes ?: 0L,
        expectedSha256 = expected_digest,
        sha256 = actual_digest,
        contentTypeHint = content_type_hint,
        storageKey = storage_key,
        expiresAtEpochMillis = isoToEpochMillis(expires_at),
        resourceVersion = resource_version,
        pinCount = pin_count.toInt(),
        createdAtEpochMillis = isoToEpochMillis(created_at),
        updatedAtEpochMillis = isoToEpochMillis(updated_at),
    )
