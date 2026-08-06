package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.domain.ConsentGrantState
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.ports.DurableContentReportStore
import com.omnillm.features.contentreport.ports.FakeContentReportEndpoint
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.policy.security.EncryptedRecordCodec
import com.omnillm.runtime.policy.security.InMemorySecretBroker
import com.omnillm.runtime.policy.security.SecretBroker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * FEAT-AI-CONTENT-REPORT durability: draft → review → grant → submit / reconcile
 * survive process death via SQLite [DurableContentReportStore] + REPORT_QUEUE seal.
 *
 * Report stream remains AI_CONTENT_REPORT (≠ telemetry).
 */
class ContentReportDurableStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Shared broker so re-open decrypts with the same REPORT_QUEUE key material. */
    private val broker: SecretBroker = InMemorySecretBroker { 1_700_000_000_000L }

    private fun durableStore(
        db: ControlPlaneDatabase,
        clock: ClockControl = ClockControl(),
    ): DurableContentReportStore =
        DurableContentReportStore(
            ledger = db.contentReports,
            secretBroker = broker,
            clockMs = { clock.ms() },
        )

    @Test
    fun draft_survivesDbReopen() = runBlocking {
        val file = tmp.newFile("cr-draft.db")
        val spec = proposalSpec(reportId = uuid("d1"), command = cmd("d1"))
        val clock = ClockControl()

        withDb(file) { db ->
            val store = durableStore(db, clock)
            val api = service(ports(store = store, clock = clock))
            assertOk(seedDraft(api, spec))
            val view = assertOk(api.getReport(principal(), spec.reportId))
            assertEquals(ContentReportState.DRAFT, view.state)
        }

        withDb(file) { db ->
            val store = durableStore(db, clock)
            val api = service(ports(store = store, clock = clock))
            val view = assertOk(api.getReport(principal(), spec.reportId))
            assertEquals(ContentReportState.DRAFT, view.state)
            assertEquals(spec.reportId, view.reportId)
            // Idempotent re-propose returns same durable report.
            val again = assertOk(seedDraft(api, spec))
            assertEquals(spec.reportId, again.reportId)
            assertEquals(ContentReportState.DRAFT, again.state)
        }
    }

    @Test
    fun durableBlob_isSealedEnvelope_notPlaintextJson() = runBlocking {
        val file = tmp.newFile("cr-seal.db")
        val spec = proposalSpec(reportId = uuid("seal"), command = cmd("seal"))
        val clock = ClockControl()

        withDb(file) { db ->
            val store = durableStore(db, clock)
            val api = service(ports(store = store, clock = clock))
            assertOk(seedDraft(api, spec))
            val row = db.contentReports.reports.findByReportId(spec.reportId)!!
            assertTrue(
                "proposal blob must be OCR1 sealed envelope",
                EncryptedRecordCodec.isSealedEnvelope(row.encryptedProposal),
            )
            val asText = String(row.encryptedProposal, StandardCharsets.UTF_8)
            assertFalse(
                "sealed blob must not be readable plaintext JSON",
                asText.contains("\"reportId\"") || asText.startsWith("{"),
            )
            // Domain round-trip still yields payload.
            val domain = store.getReport(spec.reportId)
            assertNotNull(domain?.payload)
            assertEquals(spec.reportId, domain!!.payload!!.reportId)
        }
    }

    @Test
    fun submit_online_receiptDurable_andNotTelemetry() = runBlocking {
        val file = tmp.newFile("cr-submit.db")
        val spec = proposalSpec(reportId = uuid("s1"), command = cmd("s1"))
        val clock = ClockControl()
        val endpoint = FakeContentReportEndpoint(acceptOnSubmit = true)

        withDb(file) { db ->
            val store = durableStore(db, clock)
            val api = service(ports(store = store, endpoint = endpoint, clock = clock))
            assertOk(seedDraft(api, spec))

            val review = assertOk(
                api.beginLocalReview(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    BeginReviewSpec(spec.reportId, cmd("rev-s")),
                ),
            )
            val granted = assertOk(
                api.grantConsent(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    GrantConsentSpec(
                        reportId = spec.reportId,
                        command = cmd("g-s"),
                        canonicalPayloadDigest = review.canonicalPayloadDigest,
                        warningPolicyVersion = "warn-v1",
                        localUserProfileId = "user-1",
                    ),
                ),
            )
            val submitted = assertOk(
                api.submitReport(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    SubmitReportSpec(
                        reportId = spec.reportId,
                        consentGrantId = granted.grant.grantId,
                        command = cmd("sub-s"),
                        preferOnline = true,
                    ),
                ),
            )
            assertEquals(ContentReportState.SUBMITTED, submitted.report.state)
            assertNotNull(submitted.report.receiptId)
            assertFalse(submitted.report.hasEncryptedPayload)

            val snap = assertOk(api.getSnapshot(principal()))
            assertEquals("AI_CONTENT_REPORT", snap.dataStreamKind)
            assertFalse(snap.isTelemetryStream)
        }

        withDb(file) { db ->
            val store = durableStore(db, clock)
            val api = service(ports(store = store, endpoint = endpoint, clock = clock))
            val view = assertOk(api.getReport(principal(), spec.reportId))
            assertEquals(ContentReportState.SUBMITTED, view.state)
            assertNotNull(view.receiptId)
            assertFalse(view.hasEncryptedPayload)

            val receipt = assertOk(api.getReceipt(principal(), spec.reportId))
            assertEquals(ContentReportState.SUBMITTED, receipt.reportState)
            assertNotNull(receipt.receipt.receiptId)

            // Grant remains CONSUMED after reopen (cannot double-submit).
            val grant = store.getGrant(
                store.listGrantsForReport(spec.reportId).first().grantId,
            )
            assertNotNull(grant)
            assertEquals(ConsentGrantState.CONSUMED, grant!!.state)
        }
    }

    @Test
    fun queuedOffline_andReconcileAfterReplyLoss_surviveReopen() = runBlocking {
        val file = tmp.newFile("cr-reconcile.db")
        val spec = proposalSpec(reportId = uuid("q1"), command = cmd("q1"))
        val clock = ClockControl()

        // Phase 1: queue offline (no network).
        withDb(file) { db ->
            val store = durableStore(db, clock)
            val offline = FakeContentReportEndpoint(
                networkAvailable = false,
                endpointAvailable = false,
            )
            val api = service(ports(store = store, endpoint = offline, clock = clock))
            assertOk(seedDraft(api, spec))
            val review = assertOk(
                api.beginLocalReview(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    BeginReviewSpec(spec.reportId, cmd("rev-q")),
                ),
            )
            val granted = assertOk(
                api.grantConsent(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    GrantConsentSpec(
                        reportId = spec.reportId,
                        command = cmd("g-q"),
                        canonicalPayloadDigest = review.canonicalPayloadDigest,
                        warningPolicyVersion = "warn-v1",
                        localUserProfileId = "user-1",
                    ),
                ),
            )
            val queued = assertOk(
                api.submitReport(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    SubmitReportSpec(
                        reportId = spec.reportId,
                        consentGrantId = granted.grant.grantId,
                        command = cmd("sub-q"),
                        preferOnline = true,
                    ),
                ),
            )
            assertTrue(queued.queuedOffline)
            assertEquals(ContentReportState.QUEUED_OFFLINE, queued.report.state)
        }

        // Phase 2: process death — queue still durable + sealed.
        withDb(file) { db ->
            val store = durableStore(db, clock)
            val mid = assertOk(
                service(ports(store = store, clock = clock)).getReport(principal(), spec.reportId),
            )
            assertEquals(ContentReportState.QUEUED_OFFLINE, mid.state)
            assertTrue(mid.hasEncryptedPayload)
        }

        // Phase 3: reply-loss submit path → reconcile to single receipt.
        withDb(file) { db ->
            val store = durableStore(db, clock)
            val silent = FakeContentReportEndpoint(
                networkAvailable = true,
                endpointAvailable = true,
                silentDrop = true,
            )
            val api = service(ports(store = store, endpoint = silent, clock = clock))
            val retried = assertOk(
                api.retry(
                    principal(),
                    CallerSurface.LOCAL_TRUSTED_UI,
                    spec.reportId,
                    cmd("retry-q"),
                ),
            )
            // silentDrop accepts remotely but returns transport error → RECONCILING or SUBMITTED after query
            assertTrue(
                retried.report.state == ContentReportState.SUBMITTED ||
                    retried.report.state == ContentReportState.RECONCILING,
            )

            if (retried.report.state == ContentReportState.RECONCILING) {
                val reconciled = assertOk(api.reconcile(principal(), spec.reportId))
                assertEquals(ContentReportState.SUBMITTED, reconciled.state)
                assertNotNull(reconciled.receiptId)
            }
        }

        withDb(file) { db ->
            val store = durableStore(db, clock)
            val api = service(ports(store = store, clock = clock))
            val finalView = assertOk(api.getReport(principal(), spec.reportId))
            assertEquals(ContentReportState.SUBMITTED, finalView.state)
            assertNotNull(finalView.receiptId)
            // Single report identity — no second draft invented.
            assertEquals(1, store.listByPrincipal(principal().value).size)
            assertNull(store.getReport("other-invented-id"))
        }
    }

    @Test
    fun durableStore_reportStreamNotTelemetry() = runBlocking {
        val db = ControlPlaneDatabase.openInMemory()
        try {
            val store = DurableContentReportStore(
                ledger = db.contentReports,
                secretBroker = broker,
            )
            val api = ContentReportModule.createDurableApi(
                jobManager = JobManagerModule.createManager(),
                observability = ObservabilityModule.createFacade(),
                ledger = db.contentReports,
                secretBroker = broker,
            )
            val snap = assertOk(api.getSnapshot(principal()))
            assertEquals("AI_CONTENT_REPORT", snap.dataStreamKind)
            assertFalse(snap.isTelemetryStream)
            assertNotNull(store)
        } finally {
            db.close()
        }
    }

    private suspend fun <T> withDb(file: File, block: suspend (ControlPlaneDatabase) -> T): T {
        val db = ControlPlaneDatabase.openJdbcFile(file)
        return try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun <T> assertOk(result: OmniResult<T>): T {
        assertTrue("expected Ok, got $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }
}
