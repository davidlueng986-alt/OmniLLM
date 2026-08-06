package com.omnillm.features.routing

import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.features.routing.domain.SessionContinuityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session boundary + continuity disclosure (FEAT-ROUTING §4, §6.3).
 * Cross-revision must never reuse KV or claim original continuity.
 */
class SessionContinuityPolicyTest {

    @Test
    fun noSourceSession_dispositionNone() {
        val selected = toRoutingCandidate(candidateSpec())
        val d = SessionContinuityPolicy.decide(
            requestedRevisionId = revision('1'),
            selected = selected,
            sourceSession = false,
        )
        assertEquals(SessionContinuityPolicy.DISPOSITION_NONE, d.disposition)
        assertFalse(d.kvReused)
        assertFalse(d.disclosedRebuild)
        assertEquals(PrefixDecision.NONE, d.prefixDecision)
    }

    @Test
    fun crossRevision_alwaysRebuild_noKvReuse() {
        val sourceRev = revision('1')
        val selected = toRoutingCandidate(
            candidateSpec(id = "alt", rev = revision('2'), primary = false, installation = uuid(1)),
        )
        val d = SessionContinuityPolicy.decide(
            requestedRevisionId = sourceRev,
            selected = selected,
            sourceSession = true,
            sourceRevision = sourceRev,
            sourceLoadKeyDigest = digest('c').hex,
            sourceEngineBuildId = "engine-build-1",
        )
        assertEquals(SessionContinuityPolicy.DISPOSITION_REBUILT, d.disposition)
        assertTrue(d.disclosedRebuild)
        assertFalse(d.kvReused)
        assertFalse(
            SessionContinuityPolicy.mayClaimRetainedKv(
                d.disposition,
                selected.modelRevisionId,
                sourceRev,
            ),
        )
    }

    @Test
    fun sameRevisionSameLoadKeyEngine_retained() {
        val rev = revision('1')
        val selected = toRoutingCandidate(candidateSpec(rev = rev, loadKeySeed = 'c'))
        val d = SessionContinuityPolicy.decide(
            requestedRevisionId = rev,
            selected = selected,
            sourceSession = true,
            sourceRevision = rev,
            sourceLoadKeyDigest = digest('c').hex,
            sourceEngineBuildId = "engine-build-1",
        )
        assertEquals(SessionContinuityPolicy.DISPOSITION_RETAINED, d.disposition)
        assertTrue(d.kvReused)
        assertFalse(d.disclosedRebuild)
        assertEquals(PrefixDecision.EXACT_SAME_SESSION, d.prefixDecision)
        assertTrue(
            SessionContinuityPolicy.mayClaimRetainedKv(
                d.disposition,
                selected.modelRevisionId,
                rev,
            ),
        )
    }

    @Test
    fun sameRevision_backendEngineDrift_rebuildWithoutProof() {
        val rev = revision('1')
        val selected = toRoutingCandidate(
            candidateSpec(
                id = "gpu",
                rev = rev,
                backend = "gpu",
                primary = false,
                installation = uuid(1),
                engine = "engine-build-2",
                loadKeySeed = 'd',
            ),
        )
        val d = SessionContinuityPolicy.decide(
            requestedRevisionId = rev,
            selected = selected,
            sourceSession = true,
            sourceRevision = rev,
            sourceLoadKeyDigest = digest('c').hex,
            sourceEngineBuildId = "engine-build-1",
            provenStateTransfer = false,
        )
        assertEquals(SessionContinuityPolicy.DISPOSITION_REBUILT, d.disposition)
        assertTrue(d.disclosedRebuild)
        assertFalse(d.kvReused)
    }

    @Test
    fun sameRevision_engineDrift_withProvenTransfer_mayRetain() {
        val rev = revision('1')
        val selected = toRoutingCandidate(
            candidateSpec(
                rev = rev,
                engine = "engine-build-2",
                loadKeySeed = 'd',
            ),
        )
        val d = SessionContinuityPolicy.decide(
            requestedRevisionId = rev,
            selected = selected,
            sourceSession = true,
            sourceRevision = rev,
            sourceLoadKeyDigest = digest('c').hex,
            sourceEngineBuildId = "engine-build-1",
            provenStateTransfer = true,
        )
        assertEquals(SessionContinuityPolicy.DISPOSITION_RETAINED, d.disposition)
        assertTrue(d.kvReused)
    }

    @Test
    fun mayClaimRetainedKv_falseAcrossRevisions() {
        assertFalse(
            SessionContinuityPolicy.mayClaimRetainedKv(
                SessionContinuityPolicy.DISPOSITION_RETAINED,
                revision('2'),
                revision('1'),
            ),
        )
    }
}
