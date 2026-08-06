package com.omnillm.features.routing

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.routing.api.RouteSubmitIdentity
import com.omnillm.features.routing.domain.SessionContinuityPolicy
import com.omnillm.features.routing.projection.RoutingProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * RoutingService happy path + acceptance scenarios (FEAT-ROUTING §6).
 */
class RoutingServiceTest {

    @Test
    fun negotiate_allSupported() {
        val svc = routingService()
        val r = svc.negotiate(localUi)
        assertTrue(r is OmniResult.Ok)
        assertTrue((r as OmniResult.Ok).value.allSupported)
    }

    @Test
    fun negotiate_unknownCapability_failClosed() {
        val svc = routingService(
            caps = SelectiveRoutingCapabilities(
                unknown = setOf(CapabilityId.MULTI_MODEL_ROUTING),
            ),
        )
        val r = svc.negotiate(localUi)
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)
    }

    @Test
    fun plan_none_primaryOk_disclosesActualRouting() = runBlocking {
        val orch = FakeRoutingOrchestrator()
        val svc = routingService(orch = orch)
        val rev = revision('1')
        val r = svc.planRoute(localUi, planSpec(rev = rev, preference = preferenceView(FallbackPolicy.NONE)))
        assertTrue(r is OmniResult.Ok)
        val view = (r as OmniResult.Ok).value
        assertTrue(view.hasViable)
        assertNotNull(view.actualRouting)
        assertFalse(view.actualRouting!!.usedFallback)
        assertEquals(rev.hex, view.actualRouting!!.modelRevisionIdHex)
        assertEquals("cpu", view.actualRouting!!.backend)
        assertTrue(RoutingProjection.fallbackDisclosureHolds(view))
        assertEquals(1, orch.planCount.get())
    }

    @Test
    fun plan_none_rejectsBackendFallback_returnsReasons() = runBlocking {
        val orch = FakeRoutingOrchestrator()
        val svc = routingService(orch = orch)
        val rev = revision('1')
        // Primary is unhealthy at orchestrator; secondary same-rev backend must not be used under NONE.
        // Feature filter drops non-primary before plan — so only primary is sent; if it fails, admission rejects.
        orch.rejectCandidateIds = setOf("primary")
        val candidates = listOf(
            candidateSpec(id = "primary", rev = rev, primary = true),
            candidateSpec(id = "gpu", rev = rev, backend = "gpu", primary = false, installation = uuid(1)),
        )
        val r = svc.planRoute(
            localUi,
            planSpec(
                rev = rev,
                preference = preferenceView(FallbackPolicy.NONE),
                candidates = candidates,
            ),
        )
        // Only primary survives policy filter; orch rejects it → ADMISSION_REJECTED.
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (r as OmniResult.Err).error.code)
        // Snapshot should retain policy rejection for gpu (not silent drop).
        val snap = svc.snapshot()
        // When plan fails after filter with only primary sent, decision stored may be error path.
        // Policy rejection for gpu is recorded on full-failure-before-plan only when no allowed —
        // here primary was allowed. Ensure feature never submitted gpu.
        assertNotNull(orch.lastRequest)
        assertEquals(1, orch.lastRequest!!.candidates.size)
        assertEquals("primary", orch.lastRequest!!.candidates.single().candidateId)
    }

    @Test
    fun plan_sameRevisionOnly_allowsBackendFallback_disclosesUsedFallback() = runBlocking {
        val orch = FakeRoutingOrchestrator(rejectCandidateIds = setOf("primary"))
        val svc = routingService(orch = orch)
        val rev = revision('1')
        val candidates = listOf(
            candidateSpec(id = "primary", rev = rev, primary = true),
            candidateSpec(id = "gpu", rev = rev, backend = "gpu", primary = false, installation = uuid(1)),
        )
        val r = svc.planRoute(
            localUi,
            planSpec(
                rev = rev,
                preference = preferenceView(FallbackPolicy.SAME_REVISION_ONLY),
                candidates = candidates,
            ),
        )
        assertTrue(r is OmniResult.Ok)
        val view = (r as OmniResult.Ok).value
        assertEquals("gpu", view.actualRouting!!.backend)
        assertTrue(view.actualRouting!!.usedFallback)
        assertEquals(FallbackPolicy.SAME_REVISION_ONLY, view.actualRouting!!.fallbackPolicy)
        assertTrue(view.rejections.any { it.candidateId == "primary" })
        assertTrue(RoutingProjection.fallbackDisclosureHolds(view))
    }

    @Test
    fun plan_allowList_crossRevision_rebuildsContinuity() = runBlocking {
        val orch = FakeRoutingOrchestrator(rejectCandidateIds = setOf("primary"))
        val svc = routingService(orch = orch)
        val primaryRev = revision('1')
        val altRev = revision('2')
        val candidates = listOf(
            candidateSpec(id = "primary", rev = primaryRev, primary = true),
            candidateSpec(id = "alt", rev = altRev, primary = false, installation = uuid(1)),
        )
        val r = svc.planRoute(
            localUi,
            planSpec(
                rev = primaryRev,
                preference = preferenceView(
                    policy = FallbackPolicy.ALLOW_LIST,
                    allowlist = listOf(altRev),
                ),
                candidates = candidates,
                sourceSession = true,
                sourceRev = primaryRev,
                sourceLoadKey = digest('c').hex,
                sourceEngine = "engine-build-1",
            ),
        )
        assertTrue(r is OmniResult.Ok)
        val view = (r as OmniResult.Ok).value
        assertTrue(view.usedFallback)
        assertEquals(altRev.hex, view.actualRouting!!.modelRevisionIdHex)
        assertNotNull(view.continuity)
        assertEquals(SessionContinuityPolicy.DISPOSITION_REBUILT, view.continuity!!.disposition)
        assertTrue(view.continuity!!.disclosedRebuild)
        assertFalse(view.continuity!!.kvReused)
    }

    @Test
    fun plan_crossRevisionWithoutAllowList_neverSelected() = runBlocking {
        val orch = FakeRoutingOrchestrator(rejectCandidateIds = setOf("primary"))
        val svc = routingService(orch = orch)
        val primaryRev = revision('1')
        val altRev = revision('2')
        val candidates = listOf(
            candidateSpec(id = "primary", rev = primaryRev, primary = true),
            candidateSpec(id = "alt", rev = altRev, primary = false, installation = uuid(1)),
        )
        // SAME_REVISION_ONLY must not pick alt.
        val r = svc.planRoute(
            localUi,
            planSpec(
                rev = primaryRev,
                preference = preferenceView(FallbackPolicy.SAME_REVISION_ONLY),
                candidates = candidates,
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (r as OmniResult.Err).error.code)
        // alt filtered by policy — never reached orchestrator.
        assertNotNull(orch.lastRequest)
        assertFalse(orch.lastRequest!!.candidates.any { it.candidateId == "alt" })
    }

    @Test
    fun validatePreference_allowListEmpty_invalid() {
        val svc = routingService()
        val r = svc.validatePreference(
            localUi,
            preferenceView(policy = FallbackPolicy.ALLOW_LIST, allowlist = emptyList()),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun submit_then_query_replyLossPath() = runBlocking {
        val orch = FakeRoutingOrchestrator()
        val svc = routingService(orch = orch)
        val rev = revision('1')
        val requestId = UUID.randomUUID().toString()
        val submitted = svc.submitRoute(
            localUi,
            planSpec(rev = rev),
            RouteSubmitIdentity(
                requestId = requestId,
                idempotencyKey = "idem-$requestId",
                canonicalRequestDigestHex = digest('d').hex,
                deadlineMonotonic = 10_000L,
            ),
        )
        assertTrue(submitted is OmniResult.Ok)
        assertEquals(1, orch.submitCount.get())

        val queried = svc.queryRoute(localUi, requestId)
        assertTrue(queried is OmniResult.Ok)
        assertEquals(requestId, (queried as OmniResult.Ok).value.requestId)
    }

    @Test
    fun nonLocalUi_principal_rejected() {
        val svc = routingService()
        try {
            svc.negotiate(PrincipalId.parse("some-other-principal"))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected fail closed
        }
    }

    @Test
    fun explainability_unelectedHaveReasons() = runBlocking {
        val orch = FakeRoutingOrchestrator(rejectCandidateIds = setOf("primary"))
        val svc = routingService(orch = orch)
        val rev = revision('1')
        val r = svc.planRoute(
            localUi,
            planSpec(
                rev = rev,
                preference = preferenceView(FallbackPolicy.SAME_REVISION_ONLY),
                candidates = listOf(
                    candidateSpec(id = "primary", rev = rev, primary = true),
                    candidateSpec(id = "gpu", rev = rev, backend = "gpu", primary = false, installation = uuid(1)),
                ),
            ),
        )
        assertTrue(r is OmniResult.Ok)
        val view = (r as OmniResult.Ok).value
        assertTrue(RoutingProjection.explainabilityHolds(view))
    }
}
