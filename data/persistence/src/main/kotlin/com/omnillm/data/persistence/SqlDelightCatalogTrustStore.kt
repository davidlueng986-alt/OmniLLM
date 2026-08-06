package com.omnillm.data.persistence

/**
 * SQLDelight-backed catalog trust projection (SEC-SUPPLY / ADR-010).
 *
 * Table: `catalog_trust_state` — highest sequence + trusted clock anchor.
 * Control-plane sole writer; never opened from UI (INV-001).
 */
class SqlDelightCatalogTrustStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : CatalogTrustStateDao, ControlPlaneWriter {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override fun get(): CatalogTrustStateRow? =
        database.catalogTrustStateQueries
            .selectSingleton()
            .executeAsOneOrNull()
            ?.let {
                CatalogTrustStateRow(
                    highestSequence = it.highest_sequence,
                    trustedClockEpochMs = it.trusted_clock_epoch_ms,
                    bootId = it.boot_id,
                    elapsedRealtimeAnchorMs = it.elapsed_realtime_anchor_ms,
                    uncertain = it.uncertain != 0L,
                    rootDigest = it.root_digest,
                    updatedAt = it.updated_at,
                )
            }

    override fun upsert(row: CatalogTrustStateRow) {
        database.catalogTrustStateQueries.upsert(
            highest_sequence = row.highestSequence,
            trusted_clock_epoch_ms = row.trustedClockEpochMs,
            boot_id = row.bootId,
            elapsed_realtime_anchor_ms = row.elapsedRealtimeAnchorMs,
            uncertain = if (row.uncertain) 1L else 0L,
            root_digest = row.rootDigest,
            updated_at = row.updatedAt,
        )
    }
}
