package com.omnillm.features.lan

import com.omnillm.features.lan.domain.LanRouteDecision
import com.omnillm.features.lan.domain.LanRoutingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing: never silent cross-revision fallback.
 */
class LanRoutingPolicyTest {

    private val revA = "a".repeat(64)
    private val revB = "b".repeat(64)
    private val revC = "c".repeat(64)

    private val candidates = listOf(
        LanRoutingPolicy.RouteCandidate(revA, modelAlias = "chat", available = true),
        LanRoutingPolicy.RouteCandidate(revB, modelAlias = "chat", available = true),
        LanRoutingPolicy.RouteCandidate(revC, modelAlias = "embed", available = false),
    )

    @Test
    fun pinnedRevision_exactMatch() {
        val d = LanRoutingPolicy.select(
            LanRoutingPolicy.RouteRequest(requestedModelRevisionId = revA),
            candidates,
        )
        assertTrue(d is LanRouteDecision.Selected)
        val s = d as LanRouteDecision.Selected
        assertEquals(revA, s.modelRevisionId)
        assertFalse(s.substituted)
    }

    @Test
    fun pinnedRevision_unavailable_rejects() {
        val d = LanRoutingPolicy.select(
            LanRoutingPolicy.RouteRequest(requestedModelRevisionId = revC),
            candidates,
        )
        assertTrue(d is LanRouteDecision.Reject)
        assertEquals("REVISION_UNAVAILABLE", (d as LanRouteDecision.Reject).code)
    }

    @Test
    fun pinnedRevision_missing_rejects() {
        val d = LanRoutingPolicy.select(
            LanRoutingPolicy.RouteRequest(requestedModelRevisionId = "f".repeat(64)),
            candidates,
        )
        assertTrue(d is LanRouteDecision.Reject)
        assertEquals("REVISION_NOT_FOUND", (d as LanRouteDecision.Reject).code)
    }

    @Test
    fun aliasWithMultipleRevisions_noSilentFallback() {
        val d = LanRoutingPolicy.select(
            LanRoutingPolicy.RouteRequest(
                requestedModelRevisionId = null,
                requestedModelAlias = "chat",
                allowCrossRevisionFallback = false,
            ),
            candidates,
        )
        assertTrue(d is LanRouteDecision.Reject)
        assertEquals("AMBIGUOUS_REVISION", (d as LanRouteDecision.Reject).code)
        assertTrue(d.reason.contains("silent cross-revision"))
    }

    @Test
    fun aliasWithMultipleRevisions_explicitFallback_marksSubstituted() {
        val d = LanRoutingPolicy.select(
            LanRoutingPolicy.RouteRequest(
                requestedModelRevisionId = null,
                requestedModelAlias = "chat",
                allowCrossRevisionFallback = true,
            ),
            candidates,
        )
        assertTrue(d is LanRouteDecision.Selected)
        assertTrue((d as LanRouteDecision.Selected).substituted)
    }

    @Test
    fun assertNoSilentSubstitution_detectsMismatch() {
        val d = LanRoutingPolicy.assertNoSilentSubstitution(
            requestedModelRevisionId = revA,
            actualModelRevisionId = revB,
        )
        assertTrue(d is LanRouteDecision.Reject)
        assertEquals("CROSS_REVISION_FALLBACK", (d as LanRouteDecision.Reject).code)
    }

    @Test
    fun assertNoSilentSubstitution_exactOk() {
        val d = LanRoutingPolicy.assertNoSilentSubstitution(
            requestedModelRevisionId = revA,
            actualModelRevisionId = revA,
        )
        assertTrue(d is LanRouteDecision.Selected)
        assertFalse((d as LanRouteDecision.Selected).substituted)
    }

    @Test
    fun invalidRevisionId_failsClosed() {
        val d = LanRoutingPolicy.select(
            LanRoutingPolicy.RouteRequest(requestedModelRevisionId = "not-hex"),
            candidates,
        )
        assertTrue(d is LanRouteDecision.Reject)
        assertEquals("INVALID_REVISION_ID", (d as LanRouteDecision.Reject).code)
    }
}
