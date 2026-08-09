package com.omnillm.core.ports.ledger

/**
 * Control-plane DAO surfaces for durable content reports (FEAT-AI-CONTENT-REPORT).
 *
 * Only the runtime control plane may open writers (ADR-010 / [SingleWriterPolicy]).
 * UI / workers / companion must not hold these DAOs as domain writers.
 * SQLite adapter lives in `:data:persistence`; the Feature Pack consumes these
 * ports directly (ARC-02) without compiling against `:data:*` writers.
 *
 * Authority tables: `content_reports`, `content_report_consent_grants`,
 * `content_report_receipts`.
 */

interface ContentReportRecordDao {
    fun findByReportId(reportId: String): ContentReportRecordRow?

    fun findByPrincipalAndIdempotency(
        principalId: String,
        idempotencyKey: String,
    ): ContentReportRecordRow?

    fun listByPrincipal(principalId: String): List<ContentReportRecordRow>

    fun listAll(): List<ContentReportRecordRow>

    fun upsert(row: ContentReportRecordRow)

    fun delete(reportId: String): Boolean
}

interface ContentReportGrantDao {
    fun findByGrantId(grantId: String): ContentReportGrantRow?

    fun listByReportId(reportId: String): List<ContentReportGrantRow>

    fun upsert(row: ContentReportGrantRow)

    fun delete(grantId: String): Boolean
}

interface ContentReportReceiptDao {
    fun findByReportId(reportId: String): ContentReportReceiptRow?

    fun findByReceiptId(receiptId: String): ContentReportReceiptRow?

    fun upsert(row: ContentReportReceiptRow)

    fun deleteByReportId(reportId: String): Boolean
}

/**
 * Bundled content-report ledger ports for control-plane sole writer (ADR-010).
 */
interface ContentReportLedgerPorts : ControlPlaneWriter {
    val reports: ContentReportRecordDao
    val grants: ContentReportGrantDao
    val receipts: ContentReportReceiptDao
    val tx: ClaimLedgerTransaction
}
