package com.omnillm.features.routing

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.routing.domain.FallbackPolicyRules
import com.omnillm.runtime.orchestrator.CandidateRejectionCodes
import com.omnillm.runtime.orchestrator.RoutingPreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative / fail-closed cases for FEAT-ROUTING.
 *
 * - No silent cross-revision fallback
 * - Capability negotiation fail closed
 * - Invalid policy shapes rejected before plan
 * - Empty candidate set after filter → ADMISSION_REJECTED with reasons
 */
class RoutingNegativeCasesTest {

    @Test
    fun silentCrossRevision_neverAllowedUnderNone() {
        val primaryRev = revision('1')
        val other = revision('2')
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE)
        assertFalse(FallbackPolicyRules.allowsCrossRevision(pref, primaryRev, other))
        val filtered = FallbackPolicyRules.apply(
            primaryRev,
            pref,
            listOf(
                toRoutingCandidate(candidateSpec(id = "p", rev = primaryRev, primary = true)),
                toRoutingCandidate(
                    candidateSpec(id = "x", rev = other, primary = false, installation = uuid(1)),
                ),
            ),
        )
        assertFalse(filtered.allowed.any { it.modelRevisionId.hex == other.hex })
        assertTrue(
            filtered.rejections.any {
                it.candidateId == "x" && it.code == CandidateRejectionCodes.FALLBACK_NOT_ALLOWED
            },
        )
    }

    @Test
    fun silentCrossRevision_neverAllowedUnderSameRevisionOnly() {
        val primaryRev = revision('1')
        val other = revision('2')
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY)
        assertFalse(FallbackPolicyRules.allowsCrossRevision(pref, primaryRev, other))
    }

    @Test
    fun allowList_missingRevision_rejectedWithStableCode() {
        val primaryRev = revision('1')
        val listed = revision('2')
        val unlisted = revision('3')
        val pref = RoutingPreference(
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            revisionAllowlist = listOf(listed),
        )
        val filtered = FallbackPolicyRules.apply(
            primaryRev,
            pref,
            listOf(
                toRoutingCandidate(candidateSpec(id = "p", rev = primaryRev)),
                toRoutingCandidate(
                    candidateSpec(id = "u", rev = unlisted, primary = false, installation = uuid(1)),
                ),
            ),
        )
        assertTrue(
            filtered.rejections.any {
                it.candidateId == "u" &&
                    it.code == CandidateRejectionCodes.FALLBACK_NOT_ALLOWED &&
                    it.message.contains("allowlist")
            },
        )
    }

    @Test
    fun capabilityUnsupported_blocksPlan() = runBlocking {
        val svc = routingService(
            caps = SelectiveRoutingCapabilities(
                unsupported = setOf(CapabilityId.FALLBACK_POLICY),
            ),
        )
        val r = svc.planRoute(localUi, planSpec())
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)
    }

    @Test
    fun capabilityUnknown_blocksPlan_failClosed() = runBlocking {
        val svc = routingService(
            caps = SelectiveRoutingCapabilities(
                unknown = setOf(CapabilityId.REQUEST_LIFECYCLE),
            ),
        )
        val r = svc.planRoute(localUi, planSpec())
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)
    }

    @Test
    fun none_primaryFails_doesNotPromoteSecondaryBackend() = runBlocking {
        val orch = FakeRoutingOrchestrator(rejectCandidateIds = setOf("primary"))
        val svc = routingService(orch = orch)
        val rev = revision('1')
        val r = svc.planRoute(
            localUi,
            planSpec(
                rev = rev,
                preference = preferenceView(FallbackPolicy.NONE),
                candidates = listOf(
                    candidateSpec(id = "primary", rev = rev, primary = true),
                    candidateSpec(
                        id = "gpu",
                        rev = rev,
                        backend = "gpu",
                        primary = false,
                        installation = uuid(1),
                    ),
                ),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (r as OmniResult.Err).error.code)
        // Secondary never planned under NONE.
        assertEquals(listOf("primary"), orch.lastRequest?.candidates?.map { it.candidateId })
    }

    @Test
    fun invalidRequestedRevision_rejected() = runBlocking {
        val svc = routingService()
        val r = svc.planRoute(
            localUi,
            planSpec().copy(requestedRevisionIdHex = "not-a-digest"),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun emptyCandidates_rejected() = runBlocking {
        val svc = routingService()
        val r = svc.planRoute(
            localUi,
            planSpec().copy(candidates = emptyList()),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun queryUnknownRequest_notFound() = runBlocking {
        val svc = routingService()
        val r = svc.queryRoute(localUi, java.util.UUID.randomUUID().toString())
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.NOT_FOUND, (r as OmniResult.Err).error.code)
    }

    @Test
    fun none_withAllowlistAttached_failsValidation() {
        val svc = routingService()
        val r = svc.validatePreference(
            localUi,
            preferenceView(
                policy = FallbackPolicy.NONE,
                allowlist = listOf(revision('2')),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }
}
