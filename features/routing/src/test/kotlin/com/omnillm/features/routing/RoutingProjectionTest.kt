package com.omnillm.features.routing

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.features.routing.api.RoutingUiPhase
import com.omnillm.features.routing.domain.FallbackPolicyRules
import com.omnillm.features.routing.domain.SessionContinuityPolicy
import com.omnillm.features.routing.projection.RoutingProjection
import com.omnillm.runtime.orchestrator.ActualRouting
import com.omnillm.runtime.orchestrator.CandidateRejection
import com.omnillm.runtime.orchestrator.CandidateRejectionCodes
import com.omnillm.runtime.orchestrator.PlannedCandidate
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.RoutingPreference
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.runtime.orchestrator.CostClassLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingProjectionTest {

    @Test
    fun decisionView_mergesPolicyAndPlanRejections() {
        val rev = revision('1')
        val primary = toRoutingCandidate(candidateSpec(id = "primary", rev = rev))
        val alt = toRoutingCandidate(
            candidateSpec(id = "alt", rev = revision('2'), primary = false, installation = uuid(1)),
        )
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE)
        val policy = FallbackPolicyRules.apply(rev, pref, listOf(primary, alt))
        val planning = PlanningResult(
            viable = listOf(
                PlannedCandidate(
                    candidate = primary,
                    resourceEnvelope = ResourceEnvelope(
                        steady = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 1L),
                        peak = ResourceVector(cpuAnonBytes = 200L, nativeThreads = 2L),
                    ),
                    planInputDigest = digest('e'),
                    costClass = CostClassLabels.GENERATION,
                    costUnits = 4L,
                ),
            ),
            rejections = emptyList(),
        )
        val actual = ActualRouting(
            modelRevisionId = primary.modelRevisionId,
            engineBuildId = primary.engineBuildId,
            backend = primary.backend,
            placementClass = primary.placementClass,
            installationId = primary.installationId,
            candidateId = primary.candidateId,
            usedFallback = false,
            fallbackPolicy = FallbackPolicy.NONE,
            rejectionTrail = policy.rejections,
        )
        val continuity = SessionContinuityPolicy.decide(
            requestedRevisionId = rev,
            selected = primary,
            sourceSession = false,
        )
        val view = RoutingProjection.decisionView(
            requestedRevisionHex = rev.hex,
            preference = pref,
            allCandidates = listOf(primary, alt),
            policyRejections = policy.rejections,
            planning = planning,
            selected = primary,
            actual = actual,
            continuity = continuity,
        )
        assertTrue(view.rejections.any { it.candidateId == "alt" })
        assertTrue(RoutingProjection.explainabilityHolds(view))
        assertTrue(RoutingProjection.fallbackDisclosureHolds(view))
        assertEquals(SessionContinuityPolicy.DISPOSITION_NONE, view.continuity!!.disposition)
    }

    @Test
    fun usedFallback_requiresDisclosureFields() {
        val rev = revision('1')
        val alt = toRoutingCandidate(
            candidateSpec(id = "gpu", rev = rev, backend = "gpu", primary = false, installation = uuid(1)),
        )
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY)
        val actual = ActualRouting(
            modelRevisionId = alt.modelRevisionId,
            engineBuildId = alt.engineBuildId,
            backend = alt.backend,
            placementClass = alt.placementClass,
            installationId = alt.installationId,
            candidateId = alt.candidateId,
            usedFallback = true,
            fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY,
            rejectionTrail = listOf(
                CandidateRejection(
                    candidateId = "primary",
                    code = CandidateRejectionCodes.HEALTH,
                    message = "unhealthy",
                ),
            ),
        )
        val view = RoutingProjection.decisionView(
            requestedRevisionHex = rev.hex,
            preference = pref,
            allCandidates = listOf(alt),
            policyRejections = emptyList(),
            planning = null,
            selected = alt,
            actual = actual,
            continuity = null,
        )
        assertTrue(view.usedFallback)
        assertTrue(RoutingProjection.fallbackDisclosureHolds(view))
        assertFalse(view.actualRouting!!.backend.isEmpty())
    }

    @Test
    fun resolveUiPhase() {
        assertEquals(RoutingUiPhase.EMPTY, RoutingProjection.resolveUiPhase(false, false, false))
        assertEquals(RoutingUiPhase.LOADING, RoutingProjection.resolveUiPhase(false, true, false))
        assertEquals(RoutingUiPhase.ERROR, RoutingProjection.resolveUiPhase(false, false, true))
        assertEquals(RoutingUiPhase.READY, RoutingProjection.resolveUiPhase(true, false, false))
    }
}
