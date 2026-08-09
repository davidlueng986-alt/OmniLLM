package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.GuardEvaluator
import com.omnillm.core.state.TransitionOutcome
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.CancelReportSpec
import com.omnillm.features.contentreport.api.DiscardReportSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.domain.ContentReportReceipt
import com.omnillm.features.contentreport.domain.ConsentGrantState
import com.omnillm.features.contentreport.ports.ContentReportStorePort
import com.omnillm.features.contentreport.ports.FakeContentReportEndpoint
import com.omnillm.features.contentreport.ports.InMemoryContentReportStore
import com.omnillm.features.contentreport.usecase.ContentReportFsm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full CONTENT_REPORT lifecycle per `specs/state-machines.yaml` machine
 * (RPT-001..RPT-020), driven through [ContentReportService] and [ContentReportFsm].
 *
 * Covers: catalog-edge exhaustiveness, happy path with per-state assertions,
 * discard (RPT-012), TTL expiry (RPT-020), cancel-while-submitting resolution
 * (RPT-009 → RPT-013/014/015), reconcile outcomes (RPT-016..RPT-019) and
 * atomic claim already covered by [ContentReportAtomicProposalTest] (COR-23g).
 */
class ContentReportFullFlowTest {

    private val spec = proposalSpec()

    @Test
    fun rptCatalog_everyEdge_acceptsWhenGuardSatisfied() {
        val machine = StateMachines.CONTENT_REPORT
        assertEquals("CONTENT_REPORT", machine.id)
        assertTrue(machine.transitions.isNotEmpty())
        for (t in machine.transitions) {
            val from = ContentReportState.requireFromCatalogName(t.from)
            val outcome = ContentReportFsm.transition(from, t.event, GuardEvaluator.ALWAYS_TRUE)
            assertTrue(
                "${t.id} ${t.from}+${t.event} must accept with guards satisfied, got $outcome",
                outcome is TransitionOutcome.Accepted,
            )
            assertEquals("${t.id} to", t.to, (outcome as TransitionOutcome.Accepted).to)
            assertEquals("${t.id} transitionId", t.id, outcome.transitionId)
        }
    }

    @Test
    fun rptCatalog_constantTrueEdges_neverDependOnNamedGuards() {
        val machine = StateMachines.CONTENT_REPORT
        for (t in machine.transitions) {
            val from = ContentReportState.requireFromCatalogName(t.from)
            val outcome = ContentReportFsm.transition(from, t.event, GuardEvaluator.ALWAYS_FALSE)
            if (t.guard.trim() == "true") {
                assertTrue(
                    "${t.id} guard 'true' must accept even with all atoms false, got $outcome",
                    outcome is TransitionOutcome.Accepted,
                )
            } else {
                assertTrue(
                    "${t.id} guard '${t.guard}' must fail closed when atoms are false, got $outcome",
                    outcome is TransitionOutcome.Rejected,
                )
            }
        }
    }

    @Test
    fun rptCatalog_terminalStates_haveNoOutboundEdges() {
        val machine = StateMachines.CONTENT_REPORT
        val events = machine.transitions.map { it.event }.toSet() + "__NOT_A_CATALOG_EVENT__"
        for (terminal in machine.terminal) {
            val from = ContentReportState.requireFromCatalogName(terminal)
            for (event in events) {
                val outcome = ContentReportFsm.transition(from, event, GuardEvaluator.ALWAYS_TRUE)
                assertTrue(
                    "terminal $terminal must reject $event, got $outcome",
                    outcome is TransitionOutcome.Rejected,
                )
            }
            assertTrue(ContentReportFsm.isTerminal(from))
        }
    }

    @Test
    fun fullLifecycle_proposal_review_grant_submit_matchesCatalogEdges() = runBlocking {
        val store = InMemoryContentReportStore()
        val api = service(ports(store = store))

        // RPT-001 DRAFT -> REVIEWING
        val draft = assertOk(seedDraft(api, spec))
        assertEquals(ContentReportState.DRAFT, draft.state)
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        assertEquals(ContentReportState.REVIEWING, review.state)

        // RPT-002 REVIEWING -> CONSENT_GRANTED
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
        assertEquals(ConsentGrantState.ISSUED, granted.grant.state)

        // RPT-004 CONSENT_GRANTED -> SUBMITTING (online) -> RPT-006 SUBMITTED
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
        assertNotNull(submitted.report.receiptId)
        assertFalse(submitted.report.hasEncryptedPayload)

        // Grant consumed atomically with the transition (RPT-003/004 action).
        val consumedGrant = store.listGrantsForReport(spec.reportId).single()
        assertEquals(ConsentGrantState.CONSUMED, consumedGrant.state)

        // Exactly one report identity; receipt queryable; not moderation.
        assertEquals(1, store.listByPrincipal(principal().value).size)
        val receipt = assertOk(api.getReceipt(principal(), spec.reportId))
        assertFalse(receipt.isModerationOutcome)
        assertEquals(ContentReportState.SUBMITTED, receipt.reportState)
        // Reconcile on a settled report is a no-op (no second identity).
        val reconciled = assertOk(api.reconcile(principal(), spec.reportId))
        assertEquals(ContentReportState.SUBMITTED, reconciled.state)
        assertEquals(1, store.listByPrincipal(principal().value).size)
    }

    @Test
    fun rpt003_thenRpt005_offlineQueueThenNetworkSubmit() = runBlocking {
        val endpoint = FakeContentReportEndpoint(networkAvailable = false)
        val api = service(ports(endpoint = endpoint))
        val grantId = prepareGranted(api)

        // RPT-003 CONSENT_GRANTED -> QUEUED_OFFLINE
        val queued = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(spec.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertEquals(ContentReportState.QUEUED_OFFLINE, queued.report.state)
        assertTrue(queued.report.hasEncryptedPayload)

        // RPT-005 QUEUED_OFFLINE -> SUBMITTING -> RPT-006 SUBMITTED
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
        assertNotNull(retried.report.receiptId)
    }

    @Test
    fun rpt012_discard_wipesSensitivePayload_fromEveryEligibleState() = runBlocking {
        val store = InMemoryContentReportStore()

        // DRAFT
        discardFromDraftState(store)
        // REVIEWING
        discardFromReviewingState(store)
        // CONSENT_GRANTED
        discardFromConsentGrantedState(store)
        // QUEUED_OFFLINE
        discardFromQueuedOfflineState(store)
        // FAILED_RETRYABLE
        discardFromFailedRetryableState(store)
    }

    @Test
    fun rpt020_ttlExpired_fromEveryEligibleState_wipesPayload() = runBlocking {
        val store = InMemoryContentReportStore()

        ttlExpireFromDraft(store)
        ttlExpireFromReviewing(store)
        ttlExpireFromConsentGranted(store)
        ttlExpireFromQueuedOffline(store)
        ttlExpireFromFailedRetryable(store)
    }

    @Test
    fun rpt013_cancelWhileSubmitting_remoteNotAccepted_discards() = runBlocking {
        val store = InMemoryContentReportStore()
        val endpoint = FakeContentReportEndpoint()
        val api = service(ports(store = store, endpoint = endpoint))
        forceSubmitting(api, store)

        val cancelled = assertOk(
            api.cancelReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                "LOCAL_ADMIN",
                true,
                CancelReportSpec(spec.reportId, cmd("c")),
            ),
        )
        assertEquals(ContentReportState.DISCARDED, cancelled.state)
        assertFalse(cancelled.hasEncryptedPayload)
        assertNull(store.getReport(spec.reportId)!!.payload)
        assertTrue(endpoint.wasCancelRequested(spec.reportId))
    }

    @Test
    fun rpt014_cancelWhileSubmitting_remoteAlreadyAccepted_staysSubmitted() = runBlocking {
        val store = InMemoryContentReportStore()
        val endpoint = FakeContentReportEndpoint()
        val api = service(ports(store = store, endpoint = endpoint))
        forceSubmitting(api, store)
        endpoint.forceAccept(spec.reportId)

        val cancelled = assertOk(
            api.cancelReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                "LOCAL_ADMIN",
                true,
                CancelReportSpec(spec.reportId, cmd("c")),
            ),
        )
        assertEquals(ContentReportState.SUBMITTED, cancelled.state)
        assertNotNull(cancelled.receiptId)
        assertFalse(cancelled.hasEncryptedPayload)
        assertNotNull(store.getReceipt(spec.reportId))
    }

    @Test
    fun rpt015_thenRpt016_cancelOutcomeUnknown_reconcilesToSubmitted() = runBlocking {
        val store = InMemoryContentReportStore()
        val unknown = UnknownOutcomeEndpoint()
        val api = service(ports(store = store, endpoint = unknown))
        forceSubmitting(api, store)

        // RPT-009 SUBMITTING -> CANCELLING -> RPT-015 OUTCOME_UNKNOWN -> RECONCILING
        val cancelled = assertOk(
            api.cancelReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                "LOCAL_ADMIN",
                true,
                CancelReportSpec(spec.reportId, cmd("c")),
            ),
        )
        assertEquals(ContentReportState.RECONCILING, cancelled.state)

        // RPT-016 RECONCILING -> RECEIPT_FOUND -> SUBMITTED
        val accepting = FakeContentReportEndpoint()
        accepting.forceAccept(spec.reportId)
        val api2 = service(ports(store = store, endpoint = accepting))
        val reconciled = assertOk(api2.reconcile(principal(), spec.reportId))
        assertEquals(ContentReportState.SUBMITTED, reconciled.state)
        assertNotNull(reconciled.receiptId)
    }

    @Test
    fun rpt017_reconciling_notAcceptedCancelPending_discards() = runBlocking {
        val store = InMemoryContentReportStore()
        val api = service(ports(store = store))
        seedAndAdvanceToReconciling(api, store, cancelPending = true)

        val reconciled = assertOk(api.reconcile(principal(), spec.reportId))
        assertEquals(ContentReportState.DISCARDED, reconciled.state)
        assertFalse(reconciled.hasEncryptedPayload)
        assertNull(store.getReport(spec.reportId)!!.payload)
    }

    @Test
    fun rpt018_reconciling_notAcceptedWithinTtl_failedRetryable_thenRetrySubmits() = runBlocking {
        val store = InMemoryContentReportStore()
        val endpoint = FakeContentReportEndpoint(networkAvailable = false)
        val api = service(ports(store = store, endpoint = endpoint))
        seedAndAdvanceToReconciling(api, store, cancelPending = false)

        // RPT-018 RECONCILING -> NOT_ACCEPTED_RETRYABLE -> FAILED_RETRYABLE (payload retained)
        val reconciled = assertOk(api.reconcile(principal(), spec.reportId))
        assertEquals(ContentReportState.FAILED_RETRYABLE, reconciled.state)
        assertTrue(reconciled.hasEncryptedPayload)
        assertNotNull(store.getReport(spec.reportId)!!.payload)

        // RPT-011 FAILED_RETRYABLE -> RETRY -> SUBMITTING -> RPT-006 SUBMITTED
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
        assertNotNull(retried.report.receiptId)
    }

    @Test
    fun rpt019_reconciling_notAcceptedAfterTtl_finalRejection_wipesPayload() = runBlocking {
        val store = InMemoryContentReportStore()
        val clock = ClockControl()
        val api = service(ports(store = store, clock = clock))
        seedAndAdvanceToReconciling(api, store, cancelPending = false)
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)

        val reconciled = assertOk(api.reconcile(principal(), spec.reportId))
        assertEquals(ContentReportState.FAILED_FINAL, reconciled.state)
        assertFalse(reconciled.hasEncryptedPayload)
        assertNull(store.getReport(spec.reportId)!!.payload)
        assertTrue(ContentReportFsm.isTerminal(ContentReportState.FAILED_FINAL))
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private suspend fun prepareGranted(
        api: com.omnillm.features.contentreport.api.ContentReportApi,
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

    /** Seed draft + review + grant, then advance the stored record to SUBMITTING. */
    private suspend fun forceSubmitting(
        api: com.omnillm.features.contentreport.api.ContentReportApi,
        store: InMemoryContentReportStore,
    ) {
        val grantId = prepareGranted(api)
        val record = store.getReport(spec.reportId)!!
        store.putReport(
            record.copy(
                state = ContentReportState.SUBMITTING,
                activeGrantId = grantId,
            ),
        )
    }

    private suspend fun seedAndAdvanceToReconciling(
        api: com.omnillm.features.contentreport.api.ContentReportApi,
        store: InMemoryContentReportStore,
        cancelPending: Boolean,
    ) {
        val grantId = prepareGranted(api)
        val record = store.getReport(spec.reportId)!!
        store.putReport(
            record.copy(
                state = ContentReportState.RECONCILING,
                cancelPending = cancelPending,
                activeGrantId = grantId,
            ),
        )
    }

    private suspend fun discardFromDraftState(store: InMemoryContentReportStore) {
        val api = service(ports(store = store))
        val s = proposalSpec(reportId = uuid("d-draft"), command = cmd("d-draft"))
        assertOk(seedDraft(api, s))
        val discarded = assertOk(discard(api, s.reportId))
        assertEquals(ContentReportState.DISCARDED, discarded.state)
        assertNull(store.getReport(s.reportId)!!.payload)
    }

    private suspend fun discardFromReviewingState(store: InMemoryContentReportStore) {
        val api = service(ports(store = store))
        val s = proposalSpec(reportId = uuid("d-review"), command = cmd("d-review"))
        assertOk(seedDraft(api, s))
        assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(s.reportId, cmd("rev")),
            ),
        )
        val discarded = assertOk(discard(api, s.reportId))
        assertEquals(ContentReportState.DISCARDED, discarded.state)
        assertNull(store.getReport(s.reportId)!!.payload)
    }

    private suspend fun discardFromConsentGrantedState(store: InMemoryContentReportStore) {
        val api = service(ports(store = store))
        val s = proposalSpec(reportId = uuid("d-grant"), command = cmd("d-grant"))
        assertOk(seedDraft(api, s))
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(s.reportId, cmd("rev")),
            ),
        )
        assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = s.reportId,
                    command = cmd("g"),
                    canonicalPayloadDigest = review.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        val discarded = assertOk(discard(api, s.reportId))
        assertEquals(ContentReportState.DISCARDED, discarded.state)
        assertNull(store.getReport(s.reportId)!!.payload)
    }

    private suspend fun discardFromQueuedOfflineState(store: InMemoryContentReportStore) {
        val endpoint = FakeContentReportEndpoint(networkAvailable = false)
        val api = service(ports(store = store, endpoint = endpoint))
        val s = proposalSpec(reportId = uuid("d-queue"), command = cmd("d-queue"))
        val grantId = prepareGrantedFor(api, s)
        assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(s.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        val discarded = assertOk(discard(api, s.reportId))
        assertEquals(ContentReportState.DISCARDED, discarded.state)
        assertNull(store.getReport(s.reportId)!!.payload)
    }

    private suspend fun discardFromFailedRetryableState(store: InMemoryContentReportStore) {
        val endpoint = FakeContentReportEndpoint(
            networkAvailable = true,
            retryableFail = true,
        )
        val api = service(ports(store = store, endpoint = endpoint))
        val s = proposalSpec(reportId = uuid("d-retry"), command = cmd("d-retry"))
        val grantId = prepareGrantedFor(api, s)
        val failed = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(s.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertEquals(ContentReportState.FAILED_RETRYABLE, failed.report.state)
        val discarded = assertOk(discard(api, s.reportId))
        assertEquals(ContentReportState.DISCARDED, discarded.state)
        assertNull(store.getReport(s.reportId)!!.payload)
    }

    private suspend fun ttlExpireFromDraft(store: InMemoryContentReportStore) {
        val clock = ClockControl()
        val api = service(ports(store = store, clock = clock))
        val s = proposalSpec(reportId = uuid("t-draft"), command = cmd("t-draft"))
        assertOk(seedDraft(api, s))
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)
        val expired = assertOk(api.getReport(principal(), s.reportId))
        assertEquals(ContentReportState.EXPIRED, expired.state)
        assertFalse(expired.hasEncryptedPayload)
    }

    private suspend fun ttlExpireFromReviewing(store: InMemoryContentReportStore) {
        val clock = ClockControl()
        val api = service(ports(store = store, clock = clock))
        val s = proposalSpec(reportId = uuid("t-review"), command = cmd("t-review"))
        assertOk(seedDraft(api, s))
        assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(s.reportId, cmd("rev")),
            ),
        )
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)
        val expired = assertOk(api.getReport(principal(), s.reportId))
        assertEquals(ContentReportState.EXPIRED, expired.state)
        assertFalse(expired.hasEncryptedPayload)
    }

    private suspend fun ttlExpireFromConsentGranted(store: InMemoryContentReportStore) {
        val clock = ClockControl()
        val api = service(ports(store = store, clock = clock))
        val s = proposalSpec(reportId = uuid("t-grant"), command = cmd("t-grant"))
        assertOk(seedDraft(api, s))
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(s.reportId, cmd("rev")),
            ),
        )
        assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = s.reportId,
                    command = cmd("g"),
                    canonicalPayloadDigest = review.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)
        val expired = assertOk(api.getReport(principal(), s.reportId))
        assertEquals(ContentReportState.EXPIRED, expired.state)
        assertFalse(expired.hasEncryptedPayload)
    }

    private suspend fun ttlExpireFromQueuedOffline(store: InMemoryContentReportStore) {
        val clock = ClockControl()
        val endpoint = FakeContentReportEndpoint(networkAvailable = false)
        val api = service(ports(store = store, endpoint = endpoint, clock = clock))
        val s = proposalSpec(reportId = uuid("t-queue"), command = cmd("t-queue"))
        val grantId = prepareGrantedFor(api, s)
        assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(s.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)
        val expired = assertOk(api.getReport(principal(), s.reportId))
        assertEquals(ContentReportState.EXPIRED, expired.state)
        assertFalse(expired.hasEncryptedPayload)
    }

    private suspend fun ttlExpireFromFailedRetryable(store: InMemoryContentReportStore) {
        val clock = ClockControl()
        val endpoint = FakeContentReportEndpoint(
            networkAvailable = true,
            retryableFail = true,
        )
        val api = service(ports(store = store, endpoint = endpoint, clock = clock))
        val s = proposalSpec(reportId = uuid("t-retry"), command = cmd("t-retry"))
        val grantId = prepareGrantedFor(api, s)
        val failed = assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(s.reportId, grantId, cmd("s"), preferOnline = true),
            ),
        )
        assertEquals(ContentReportState.FAILED_RETRYABLE, failed.report.state)
        clock.advance(ContentReportPolicy.DEFAULT_TTL_MS + 1)
        val expired = assertOk(api.getReport(principal(), s.reportId))
        assertEquals(ContentReportState.EXPIRED, expired.state)
        assertFalse(expired.hasEncryptedPayload)
    }

    private suspend fun prepareGrantedFor(
        api: com.omnillm.features.contentreport.api.ContentReportApi,
        s: com.omnillm.features.contentreport.api.CreateProposalSpec,
    ): String {
        assertOk(seedDraft(api, s))
        val review = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(s.reportId, cmd("rev")),
            ),
        )
        val granted = assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = s.reportId,
                    command = cmd("g"),
                    canonicalPayloadDigest = review.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        return granted.grant.grantId
    }

    private suspend fun discard(
        api: com.omnillm.features.contentreport.api.ContentReportApi,
        reportId: String,
    ): OmniResult<com.omnillm.features.contentreport.api.ContentReportInfoView> =
        api.discardReport(
            principal(),
            CallerSurface.LOCAL_TRUSTED_UI,
            "LOCAL_ADMIN",
            true,
            DiscardReportSpec(reportId, cmd("d")),
        )

    private fun <T> assertOk(r: OmniResult<T>): T {
        assertTrue("expected Ok got $r", r is OmniResult.Ok)
        return (r as OmniResult.Ok).value
    }

    /** Endpoint whose outcome query always fails — RPT-015 OUTCOME_UNKNOWN path. */
    private class UnknownOutcomeEndpoint(
        private val delegate: FakeContentReportEndpoint = FakeContentReportEndpoint(),
    ) : com.omnillm.features.contentreport.ports.ContentReportEndpointPort by delegate {
        override suspend fun queryReceipt(reportId: String): OmniResult<ContentReportReceipt?> =
            OmniResult.err(
                OmniError.CONTENT_REPORT_UNAVAILABLE(message = "remote outcome unknown"),
            )
    }
}
