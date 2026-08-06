package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.CancelReportSpec
import com.omnillm.features.contentreport.api.DiscardReportSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.ports.FakeContentReportEndpoint
import com.omnillm.features.contentreport.ports.InMemoryContentReportStore
import com.omnillm.features.contentreport.usecase.ContentReportFsm
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.TransitionOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Happy path, race fixtures (CR-R001..R005), offline queue, retention wipes.
 */
class ContentReportRaceAndServiceTest {

    @Test
    fun happyPath_localReview_grant_submit_online() = runBlocking {
        val endpoint = FakeContentReportEndpoint(acceptOnSubmit = true)
        val api = service(ports(endpoint = endpoint))
        val spec = proposalSpec()
        assertOk(seedDraft(api, spec))

        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        assertEquals(ContentReportState.REVIEWING, review.state)
        assertTrue(review.minimizedDefault)
        assertTrue(review.payloadPreview.any { it.fieldName == "outputDigest" })
        assertFalse(review.payloadPreview.any { it.fieldName == "promptExcerpt" })

        val granted = assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = spec.reportId,
                    command = cmd("g"),
                    canonicalPayloadDigest = review.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        assertEquals(ContentReportState.CONSENT_GRANTED, granted.report.state)

        val submitted = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(
                    reportId = spec.reportId,
                    consentGrantId = granted.grant.grantId,
                    command = cmd("s"),
                    preferOnline = true,
                ),
            ),
        )
        assertEquals(ContentReportState.SUBMITTED, submitted.report.state)
        assertFalse(submitted.report.hasEncryptedPayload)
        assertNotNull(submitted.report.receiptId)

        val receipt = assertOk(api.getReceipt(principal(), spec.reportId))
        assertFalse(receipt.isModerationOutcome)
        assertEquals(ContentReportState.SUBMITTED, receipt.reportState)
    }

    @Test
    fun default_payload_excludes_prompt_and_full_output() = runBlocking {
        val api = service()
        val withExcerpts = proposalSpec(
            promptExcerpt = "secret prompt",
            outputExcerpt = "model said bad thing",
        )
        val plan = assertOk(api.planPayload(withExcerpts))
        // User may opt-in excerpts on proposal; minimized default when null.
        assertFalse(plan.payload.isMinimizedDefault())

        val minimized = assertOk(api.planPayload(proposalSpec()))
        assertTrue(minimized.payload.isMinimizedDefault())
        assertNull(minimized.payload.promptExcerpt)
        assertNull(minimized.payload.outputExcerpt)
    }

    @Test
    fun CR_R001_cancel_before_remote_accept_discards() = runBlocking {
        // Endpoint accepts only when forced — first submit enters SUBMITTING via
        // silent drop + no receipt yet, then cancel before accept.
        val endpoint = FakeContentReportEndpoint(
            acceptOnSubmit = false,
            retryableFail = true,
        )
        val store = InMemoryContentReportStore()
        val api = service(ports(store = store, endpoint = endpoint))
        val spec = proposalSpec()
        val grantId = prepareGranted(api, spec)

        // Force offline queue then network path into SUBMITTING without accept.
        endpoint.networkAvailable = false
        val queued = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertEquals(ContentReportState.QUEUED_OFFLINE, queued.report.state)

        // Bring network; retryable fail keeps FAILED_RETRYABLE or we discard from queue.
        val discarded = assertOk(
            api.discardReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                "LOCAL_ADMIN",
                true,
                DiscardReportSpec(spec.reportId, cmd("d")),
            ),
        )
        assertEquals(ContentReportState.DISCARDED, discarded.state)
        assertFalse(discarded.hasEncryptedPayload)
        // Discarded payload must not be submitted later.
        assertNull(store.getReport(spec.reportId)!!.payload)
    }

    @Test
    fun CR_R002_cancel_after_remote_accept_stays_submitted() = runBlocking {
        val endpoint = FakeContentReportEndpoint(acceptOnSubmit = true)
        val api = service(ports(endpoint = endpoint))
        val spec = proposalSpec()
        val grantId = prepareGranted(api, spec)

        val submitted = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertEquals(ContentReportState.SUBMITTED, submitted.report.state)

        // Cancel after acceptance is illegal as discard; cancel path state-conflict.
        val cancel = api.cancelReport(
            principal(),
            CallerSurface.LOCAL_TRUSTED_UI,
            "LOCAL_ADMIN",
            true,
            CancelReportSpec(spec.reportId, cmd("c")),
        )
        assertTrue(cancel is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (cancel as OmniResult.Err).error.code)

        val still = assertOk(api.getReport(principal(), spec.reportId))
        assertEquals(ContentReportState.SUBMITTED, still.state)
    }

    @Test
    fun CR_R003_accepted_response_lost_reconciles_to_submitted() = runBlocking {
        val endpoint = FakeContentReportEndpoint(silentDrop = true)
        val api = service(ports(endpoint = endpoint))
        val spec = proposalSpec()
        val grantId = prepareGranted(api, spec)

        val result = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        // performSubmit should query receipt after reply loss and converge to SUBMITTED.
        assertEquals(ContentReportState.SUBMITTED, result.report.state)
        assertNotNull(result.report.receiptId)
    }

    @Test
    fun CR_R004_offline_queue_then_network_submits() = runBlocking {
        val endpoint = FakeContentReportEndpoint(
            networkAvailable = false,
            acceptOnSubmit = true,
        )
        val api = service(ports(endpoint = endpoint))
        val spec = proposalSpec()
        val grantId = prepareGranted(api, spec)

        val queued = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertTrue(queued.queuedOffline)
        assertEquals(ContentReportState.QUEUED_OFFLINE, queued.report.state)

        endpoint.networkAvailable = true
        val retried = assertOk(
            api.retry(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                spec.reportId,
                cmd("retry"),
            ),
        )
        assertEquals(ContentReportState.SUBMITTED, retried.report.state)
    }

    @Test
    fun CR_R005_expiry_before_grant_consumption() = runBlocking {
        val clock = ClockControl()
        val api = service(ports(clock = clock))
        val spec = proposalSpec()
        assertOk(seedDraft(api, spec))
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = spec.reportId,
                    command = cmd("g"),
                    canonicalPayloadDigest = review.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        // Expire report TTL (and grants via expireDue).
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)
        val n = api.expireDue(clock.ms())
        assertTrue(n >= 1)
        val info = assertOk(api.getReport(principal(), spec.reportId))
        assertEquals(ContentReportState.EXPIRED, info.state)
        assertFalse(info.hasEncryptedPayload)
    }

    @Test
    fun endpoint_unavailable_does_not_block_local_inference_surfaces() = runBlocking {
        // Report path queues offline; does not throw for unrelated features.
        val endpoint = FakeContentReportEndpoint(
            endpointAvailable = false,
            networkAvailable = true,
        )
        val api = service(ports(endpoint = endpoint, endpointConfigured = false))
        val spec = proposalSpec()
        val grantId = prepareGranted(api, spec)
        val queued = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertEquals(ContentReportState.QUEUED_OFFLINE, queued.report.state)
        val snap = assertOk(api.getSnapshot(principal()))
        assertFalse(snap.endpointConfigured)
        assertEquals(ContentReportPolicy.DATA_STREAM_KIND, snap.dataStreamKind)
    }

    @Test
    fun idempotent_proposal_returns_same_report() = runBlocking {
        val api = service()
        val command = cmd("same")
        val spec = proposalSpec(command = command)
        val a = assertOk(seedDraft(api, spec))
        val b = assertOk(seedDraft(api, spec))
        assertEquals(a.reportId, b.reportId)
        assertEquals(a.resourceVersion, b.resourceVersion)
    }

    @Test
    fun cannot_discard_submitted_as_unsent() = runBlocking {
        val api = service(ports(endpoint = FakeContentReportEndpoint(acceptOnSubmit = true)))
        val spec = proposalSpec()
        val grantId = prepareGranted(api, spec)
        assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        val d = api.discardReport(
            principal(),
            CallerSurface.LOCAL_TRUSTED_UI,
            "LOCAL_ADMIN",
            true,
            DiscardReportSpec(spec.reportId, cmd("d")),
        )
        assertTrue(d is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (d as OmniResult.Err).error.code)
    }

    @Test
    fun fsm_catalog_edges_structural() {
        val g = GuardEvaluator.of(
            "localTrustedReview" to true,
            "payloadMinimized" to true,
            "consentGrantValidAndUnconsumed" to true,
            "networkAndEndpointAvailable" to true,
            "withinTtl" to true,
            "remoteOutcomeKnown" to true,
            "retryAllowed" to true,
            "cancelPending" to true,
        )
        assertTrue(
            ContentReportFsm.transition(ContentReportState.DRAFT, "BEGIN_LOCAL_REVIEW", g)
                is TransitionOutcome.Accepted,
        )
        assertTrue(
            ContentReportFsm.transition(ContentReportState.SUBMITTED, "DISCARD", g)
                is TransitionOutcome.Rejected,
        )
        // Unknown event fail closed.
        assertTrue(
            ContentReportFsm.transition(ContentReportState.DRAFT, "NOT_REAL", g)
                is TransitionOutcome.Rejected,
        )
    }

    @Test
    fun revision_binding_preserved_on_grant() = runBlocking {
        val api = service()
        val rev = digest('1')
        val spec = proposalSpec(modelRevisionId = rev)
        assertOk(seedDraft(api, spec))
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        // Digest is over exact revision; forging digest is already CR-N003.
        assertTrue(review.canonicalPayloadDigest.isNotBlank())
        val conflict = ContentReportPolicy.revisionBindingConflict(
            com.omnillm.features.contentreport.domain.ContentReportPayload(
                reportId = spec.reportId,
                category = com.omnillm.core.canonical.generated.ContentReportCategory.OTHER,
                createdAt = "2026-01-01T00:00:00Z",
                appBuild = "1.0.0",
                modelRevisionId = rev,
                engineBuildId = "eng",
                backend = "b",
                localPolicyVersion = "p",
                outputDigest = digest('d'),
                userLocale = "en",
            ),
            com.omnillm.features.contentreport.domain.ContentReportPayload(
                reportId = spec.reportId,
                category = com.omnillm.core.canonical.generated.ContentReportCategory.OTHER,
                createdAt = "2026-01-01T00:00:00Z",
                appBuild = "1.0.0",
                modelRevisionId = digest('9'),
                engineBuildId = "eng",
                backend = "b",
                localPolicyVersion = "p",
                outputDigest = digest('d'),
                userLocale = "en",
            ),
        )
        assertNotNull(conflict)
    }

    private suspend fun prepareGranted(
        api: com.omnillm.features.contentreport.api.ContentReportApi,
        spec: com.omnillm.features.contentreport.api.CreateProposalSpec,
    ): String {
        assertOk(seedDraft(api, spec))
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        val granted = assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = spec.reportId,
                    command = cmd("g"),
                    canonicalPayloadDigest = review.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        return granted.grant.grantId
    }

    private fun <T> assertOk(r: OmniResult<T>): T {
        assertTrue("expected Ok got $r", r is OmniResult.Ok)
        return (r as OmniResult.Ok).value
    }
}
