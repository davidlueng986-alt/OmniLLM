package com.omnillm.features.routing

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.routing.domain.FallbackPolicyRules
import com.omnillm.features.routing.domain.PolicyBuildResult
import com.omnillm.features.routing.domain.PolicyValidation
import com.omnillm.runtime.orchestrator.CandidateRejectionCodes
import com.omnillm.runtime.orchestrator.RoutingPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy positive + negative cases (FEAT-ROUTING §1, §6.1–3).
 * Never silent cross-revision fallback.
 */
class FallbackPolicyRulesTest {

    @Test
    fun none_allowsOnlyPrimaryExactCandidate() {
        val primaryRev = revision('1')
        val altRev = revision('2')
        val primary = toRoutingCandidate(candidateSpec(id = "primary", rev = primaryRev, primary = true))
        val sameRevBackend = toRoutingCandidate(
            candidateSpec(
                id = "gpu",
                rev = primaryRev,
                backend = "gpu",
                primary = false,
                installation = uuid(1),
            ),
        )
        val crossRev = toRoutingCandidate(
            candidateSpec(
                id = "alt",
                rev = altRev,
                primary = false,
                installation = uuid(2),
            ),
        )
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE)
        val result = FallbackPolicyRules.apply(
            requestedRevisionId = primaryRev,
            preference = pref,
            candidates = listOf(primary, sameRevBackend, crossRev),
        )
        assertEquals(1, result.allowed.size)
        assertEquals("primary", result.allowed.single().candidateId)
        assertEquals(2, result.rejections.size)
        assertTrue(result.rejections.all { it.code == CandidateRejectionCodes.FALLBACK_NOT_ALLOWED })
        // Backend substitution under NONE is rejected (acceptance §6.1).
        assertTrue(result.rejections.any { it.candidateId == "gpu" })
        assertTrue(result.rejections.any { it.candidateId == "alt" })
    }

    @Test
    fun sameRevisionOnly_allowsBackendChange_rejectsCrossRevision() {
        val rev = revision('1')
        val primary = toRoutingCandidate(candidateSpec(id = "cpu", rev = rev, backend = "cpu", primary = true))
        val gpu = toRoutingCandidate(
            candidateSpec(
                id = "gpu",
                rev = rev,
                backend = "gpu",
                primary = false,
                installation = uuid(1),
            ),
        )
        val other = toRoutingCandidate(
            candidateSpec(
                id = "other",
                rev = revision('a'),
                primary = false,
                installation = uuid(2),
            ),
        )
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY)
        val result = FallbackPolicyRules.apply(rev, pref, listOf(primary, gpu, other))
        assertEquals(setOf("cpu", "gpu"), result.allowed.map { it.candidateId }.toSet())
        assertFalse(result.allowed.any { it.candidateId == "other" })
        assertTrue(result.rejections.any {
            it.candidateId == "other" && it.code == CandidateRejectionCodes.FALLBACK_NOT_ALLOWED
        })
    }

    @Test
    fun allowList_onlyExplicitRevisions_noSilentCrossRevision() {
        val primaryRev = revision('1')
        val allowedRev = revision('2')
        val blockedRev = revision('3')
        val primary = toRoutingCandidate(candidateSpec(id = "p", rev = primaryRev, primary = true))
        val allowed = toRoutingCandidate(
            candidateSpec(id = "a", rev = allowedRev, primary = false, installation = uuid(1)),
        )
        val blocked = toRoutingCandidate(
            candidateSpec(id = "b", rev = blockedRev, primary = false, installation = uuid(2)),
        )
        val pref = RoutingPreference(
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            revisionAllowlist = listOf(allowedRev),
        )
        val result = FallbackPolicyRules.apply(primaryRev, pref, listOf(primary, allowed, blocked))
        assertEquals(setOf("p", "a"), result.allowed.map { it.candidateId }.toSet())
        assertTrue(result.rejections.any { it.candidateId == "b" })
        assertFalse(FallbackPolicyRules.allowsCrossRevision(pref, primaryRev, blockedRev))
        assertTrue(FallbackPolicyRules.allowsCrossRevision(pref, primaryRev, allowedRev))
    }

    @Test
    fun allowList_emptyAllowlist_invalid() {
        val built = FallbackPolicyRules.buildPreference(
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            revisionAllowlist = emptyList(),
        )
        assertTrue(built is PolicyBuildResult.Invalid)
        // Catalog RoutingPreference init fails first with POLICY code mapping.
        assertTrue(
            (built as PolicyBuildResult.Invalid).code == CandidateRejectionCodes.POLICY ||
                built.code == CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
        )
        assertTrue(built.message.contains("allowlist") || built.message.contains("ALLOW_LIST"))
    }

    @Test
    fun none_withAllowlist_invalid() {
        val built = FallbackPolicyRules.buildPreference(
            fallbackPolicy = FallbackPolicy.NONE,
            revisionAllowlist = listOf(revision('2')),
        )
        // RoutingPreference constructor allows allowlist under NONE, but feature rules reject it.
        // buildPreference uses RoutingPreference first — ALLOW_LIST requires non-empty,
        // NONE does not throw on non-empty allowlist in constructor.
        // Our validatePreference rejects NONE + non-empty allowlist.
        assertTrue(built is PolicyBuildResult.Invalid)
    }

    @Test
    fun sameRevisionOnly_withAllowlist_invalid() {
        val built = FallbackPolicyRules.buildPreference(
            fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY,
            revisionAllowlist = listOf(revision('2')),
        )
        assertTrue(built is PolicyBuildResult.Invalid)
    }

    @Test
    fun preferredBackend_mustBeInAllowedBackends() {
        val v = FallbackPolicyRules.validatePreference(
            RoutingPreference(
                fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY,
                allowedBackends = setOf("cpu"),
                preferredBackend = "gpu",
            ),
        )
        assertTrue(v is PolicyValidation.Invalid)
        assertEquals(CandidateRejectionCodes.ENGINE_BACKEND, (v as PolicyValidation.Invalid).code)
    }

    @Test
    fun isFallbackUsage_trueWhenNonPrimaryOrCrossRevision() {
        val rev = revision('1')
        val primary = toRoutingCandidate(candidateSpec(id = "p", rev = rev, primary = true))
        val alt = toRoutingCandidate(
            candidateSpec(id = "a", rev = rev, primary = false, installation = uuid(1), backend = "gpu"),
        )
        assertFalse(FallbackPolicyRules.isFallbackUsage(rev, primary))
        assertTrue(FallbackPolicyRules.isFallbackUsage(rev, alt))
    }

    @Test
    fun none_rejectsAllWhenOnlySecondaryCandidates() {
        val rev = revision('1')
        val secondary = toRoutingCandidate(
            candidateSpec(id = "sec", rev = rev, primary = false, installation = uuid(1)),
        )
        val pref = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE)
        val result = FallbackPolicyRules.apply(rev, pref, listOf(secondary))
        assertFalse(result.hasAllowed)
        assertEquals(1, result.rejections.size)
        assertEquals(CandidateRejectionCodes.FALLBACK_NOT_ALLOWED, result.rejections.single().code)
    }

    @Test
    fun allowList_crossRevisionAllowedOnlyWhenListed() {
        val primaryRev = revision('1')
        val prefNone = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE)
        val prefSame = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY)
        val prefAllow = RoutingPreference(
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            revisionAllowlist = listOf(revision('2')),
        )
        val other = revision('2')
        assertFalse(FallbackPolicyRules.allowsCrossRevision(prefNone, primaryRev, other))
        assertFalse(FallbackPolicyRules.allowsCrossRevision(prefSame, primaryRev, other))
        assertTrue(FallbackPolicyRules.allowsCrossRevision(prefAllow, primaryRev, other))
        assertFalse(
            FallbackPolicyRules.allowsCrossRevision(
                prefAllow,
                primaryRev,
                revision('3'),
            ),
        )
    }

    @Test
    fun unknownPlacement_invalid() {
        val built = FallbackPolicyRules.buildPreference(
            fallbackPolicy = FallbackPolicy.NONE,
            minimumPlacementClass = "NOT_A_REAL_PLACEMENT",
        )
        assertTrue(built is PolicyBuildResult.Invalid)
    }

    @Test
    fun happyPath_none_primaryOnly_buildsOk() {
        val built = FallbackPolicyRules.buildPreference(
            fallbackPolicy = FallbackPolicy.NONE,
            minimumPlacementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
        )
        assertTrue(built is PolicyBuildResult.Ok)
        assertEquals(FallbackPolicy.NONE, (built as PolicyBuildResult.Ok).preference.fallbackPolicy)
    }
}
