package com.omnillm.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets

/**
 * Durable SQLDelight content-report ledger: draft / grant / receipt survive reopen.
 *
 * Authority: FEAT-AI-CONTENT-REPORT, DATA-OWNERSHIP (ADR-010 sole writer).
 * Report stream is not telemetry — only digests/status are asserted here.
 */
class SqlDelightContentReportStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val digest = "a".repeat(64)
    private val now = "2026-08-06T12:00:00Z"
    private val clock = { now }

    private fun sampleReport(
        reportId: String = "rpt-draft-0001",
        state: String = "DRAFT",
        proposalJson: String = """{"schemaVersion":1,"appBuild":"1.0","backend":"cpu","category":"HATE_HARASSMENT","createdAt":"$now","engineBuildId":"e1","localPolicyVersion":"p1","modelRevisionId":"$digest","outputDigest":"$digest","reportId":"$reportId","userLocale":"en-US"}""",
        encryptedPayload: ByteArray? = null,
        cancelPending: Boolean = false,
        activeGrantId: String? = null,
        receiptId: String? = null,
    ): ContentReportRecordRow =
        ContentReportRecordRow(
            reportId = reportId,
            principalId = "principal-u0",
            proposalCommandId = "cmd-$reportId",
            idempotencyKey = "idem-$reportId",
            resourceVersion = 1L,
            state = state,
            category = "HATE_HARASSMENT",
            appBuild = "1.0",
            modelRevisionId = digest,
            engineBuildId = "e1",
            backend = "cpu",
            localPolicyVersion = "p1",
            outputDigest = digest,
            userLocale = "en-US",
            encryptedProposal = proposalJson.toByteArray(StandardCharsets.UTF_8),
            canonicalPayloadDigest = if (state == "DRAFT") null else digest,
            encryptedPayload = encryptedPayload,
            cancelPending = cancelPending,
            expiresAt = "2026-08-07T12:00:00Z",
            errorCode = null,
            activeGrantId = activeGrantId,
            receiptId = receiptId,
            receiptAcceptedAt = null,
            receiptStatusUrl = null,
            payloadCreatedAt = now,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun draft_survivesReopen() {
        val file = tmp.newFile("content-report-draft.db")
        val row = sampleReport()

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.contentReports.tx.inTransaction {
                db.contentReports.reports.upsert(row)
            }
            val found = db.contentReports.reports.findByReportId(row.reportId)
            assertNotNull(found)
            assertEquals("DRAFT", found!!.state)
            assertEquals(row.idempotencyKey, found.idempotencyKey)
            assertTrue(found.encryptedProposal.isNotEmpty())
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val found = db.contentReports.reports.findByReportId(row.reportId)!!
            assertEquals("DRAFT", found.state)
            assertEquals(row.principalId, found.principalId)
            assertEquals(row.category, found.category)
            assertEquals(row.outputDigest, found.outputDigest)
            assertTrue(found.encryptedProposal.isNotEmpty())
            val byIdem = db.contentReports.reports.findByPrincipalAndIdempotency(
                row.principalId,
                row.idempotencyKey,
            )
            assertNotNull(byIdem)
            assertEquals(row.reportId, byIdem!!.reportId)
        }
    }

    @Test
    fun submitPath_grantConsumedAndReceipt_surviveReopen() {
        val file = tmp.newFile("content-report-submit.db")
        val reportId = "rpt-submit-0001"
        val grantId = "grant-1"
        val payloadJson =
            """{"schemaVersion":1,"appBuild":"1.0","backend":"cpu","category":"OTHER","createdAt":"$now","engineBuildId":"e1","localPolicyVersion":"p1","modelRevisionId":"$digest","outputDigest":"$digest","reportId":"$reportId","userLocale":"en-US"}"""
        val payloadBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.contentReports.tx.inTransaction {
                db.contentReports.reports.upsert(
                    sampleReport(
                        reportId = reportId,
                        state = "SUBMITTED",
                        proposalJson = payloadJson,
                        encryptedPayload = ByteArray(0),
                        activeGrantId = grantId,
                        receiptId = "rcpt-1",
                    ).copy(
                        encryptedProposal = ByteArray(0),
                        encryptedPayload = null,
                        receiptAcceptedAt = now,
                        receiptStatusUrl = "https://reports.example.invalid/status/$reportId",
                        canonicalPayloadDigest = digest,
                        category = "OTHER",
                    ),
                )
                db.contentReports.grants.upsert(
                    ContentReportGrantRow(
                        grantId = grantId,
                        reportId = reportId,
                        principalId = "principal-u0",
                        canonicalPayloadDigest = digest,
                        warningPolicyVersion = "warn-v1",
                        localUserProfileId = "user-1",
                        nonce = "nonce-1",
                        state = "CONSUMED",
                        issuedAt = now,
                        expiresAt = "2026-08-06T13:00:00Z",
                        consumedAt = now,
                    ),
                )
                db.contentReports.receipts.upsert(
                    ContentReportReceiptRow(
                        receiptId = "rcpt-1",
                        reportId = reportId,
                        acceptedAt = now,
                        statusUrl = "https://reports.example.invalid/status/$reportId",
                        responseDigest = digest,
                        lastQueriedAt = now,
                    ),
                )
            }
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val report = db.contentReports.reports.findByReportId(reportId)!!
            assertEquals("SUBMITTED", report.state)
            assertEquals("rcpt-1", report.receiptId)
            assertTrue(report.encryptedProposal.isEmpty())
            assertNull(report.encryptedPayload)

            val grant = db.contentReports.grants.findByGrantId(grantId)!!
            assertEquals("CONSUMED", grant.state)
            assertNotNull(grant.consumedAt)

            val receipt = db.contentReports.receipts.findByReportId(reportId)!!
            assertEquals("rcpt-1", receipt.receiptId)
            assertEquals(digest, receipt.responseDigest)
        }
    }

    @Test
    fun reconciling_cancelPending_survivesReopen() {
        val file = tmp.newFile("content-report-reconcile.db")
        val reportId = "rpt-recon-0001"
        val row = sampleReport(
            reportId = reportId,
            state = "RECONCILING",
            cancelPending = true,
            activeGrantId = "grant-r",
        ).copy(canonicalPayloadDigest = digest)

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            db.contentReports.tx.inTransaction {
                db.contentReports.reports.upsert(row)
            }
        }

        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val found = db.contentReports.reports.findByReportId(reportId)!!
            assertEquals("RECONCILING", found.state)
            assertTrue(found.cancelPending)
            assertEquals(digest, found.canonicalPayloadDigest)
            assertFalse(found.encryptedProposal.isEmpty())
        }
    }

    @Test
    fun listByPrincipal_ordersByUpdatedAtDesc() {
        ControlPlaneDatabase.openInMemory(clock = clock).use { db ->
            db.contentReports.tx.inTransaction {
                db.contentReports.reports.upsert(
                    sampleReport(reportId = "r-old").copy(updatedAt = "2026-08-06T10:00:00Z"),
                )
                db.contentReports.reports.upsert(
                    sampleReport(reportId = "r-new", proposalJson = sampleReport(reportId = "r-new").let {
                        String(it.encryptedProposal, StandardCharsets.UTF_8)
                    }).copy(updatedAt = "2026-08-06T14:00:00Z"),
                )
            }
            val list = db.contentReports.reports.listByPrincipal("principal-u0")
            assertEquals(2, list.size)
            assertEquals("r-new", list[0].reportId)
            assertEquals("r-old", list[1].reportId)
        }
    }

    private fun ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }
}
