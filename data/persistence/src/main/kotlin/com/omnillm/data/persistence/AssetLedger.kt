package com.omnillm.data.persistence

import com.omnillm.core.ports.ledger.ClaimLedgerTransaction
import com.omnillm.core.ports.ledger.ControlPlaneWriter

/**
 * Asset metadata ledger models + ports (C-08c hybrid).
 *
 * Authority: specs/state-machines.yaml#ASSET (states: CREATED | UPLOADING |
 * VERIFYING | READY | PINNED | CONSUMED | REJECTED | EXPIRED | DELETED) and
 * specs/database/omnillm-schema.sql#assets.
 *
 * Hybrid durability: metadata rows durable; content BYTES live in the
 * runtime-owned quarantine dir (storageKey) and are never stored in SQL.
 * TTL is enforced at access time — expired records advance to EXPIRED lazily
 * and content is refused.
 */
object AssetRecordStates {
    val ALL: Set<String> = setOf(
        "CREATED",
        "UPLOADING",
        "VERIFYING",
        "READY",
        "PINNED",
        "CONSUMED",
        "REJECTED",
        "EXPIRED",
        "DELETED",
    )
}

data class AssetRecordRow(
    val assetId: String,
    val ownerPrincipalId: String,
    val purpose: String,
    val state: String,
    val maxBytes: Long,
    val bytes: Long,
    val expectedSha256: String?,
    val sha256: String?,
    val contentTypeHint: String?,
    val storageKey: String?,
    val expiresAtEpochMillis: Long,
    val resourceVersion: Long,
    val pinCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(assetId.isNotEmpty()) { "assetId must be non-empty" }
        require(ownerPrincipalId.isNotEmpty()) { "ownerPrincipalId must be non-empty" }
        require(purpose.isNotEmpty()) { "purpose must be non-empty" }
        require(state in AssetRecordStates.ALL) { "unknown asset state: $state" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
        require(bytes >= 0L && bytes <= maxBytes) { "bytes out of [0, maxBytes]" }
        require(resourceVersion >= 0L) { "resourceVersion must be >= 0" }
        require(pinCount >= 0) { "pinCount must be >= 0" }
    }
}

interface AssetRecordDao {
    fun findByAssetId(assetId: String): AssetRecordRow?

    fun listAll(): List<AssetRecordRow>

    fun upsert(row: AssetRecordRow)
}

/**
 * Bundled asset-metadata ports injected into the asset broker.
 * Marker [ControlPlaneWriter] documents single-writer ownership (ADR-010):
 * only the runtime control plane may write asset records.
 */
interface AssetLedgerPorts : ControlPlaneWriter {
    val assets: AssetRecordDao
    val tx: ClaimLedgerTransaction
}
