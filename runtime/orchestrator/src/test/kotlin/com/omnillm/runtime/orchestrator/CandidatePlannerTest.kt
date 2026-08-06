package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.engines.api.PlacementClassLabels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candidate planning filters + fallback policy (CORE-ORCHESTRATOR §2, FEAT-ROUTING).
 */
class CandidatePlannerTest {

    private val engine = FakeInferenceEngine()

    private fun planner(
        caps: CapabilityLookup = AllSupportedCapabilities(),
        health: HealthLookup = HealthLookup { HealthSnapshot() },
    ) = CandidatePlanner(
        capabilities = caps,
        health = health,
        engine = engine,
    )

    @Test
    fun fallbackNone_rejectsCrossRevisionCandidate() = runBlocking {
        val primaryRev = revision('1')
        val otherRev = revision('2')
        val primary = candidate(id = "primary", rev = primaryRev, primary = true)
        val fallback = candidate(
            id = "fallback",
            rev = otherRev,
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440001",
        )
        val req = orchestrationRequest(
            revision = primaryRev,
            candidates = listOf(primary, fallback),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE),
        )
        val ordered = planner().orderByFallbackPolicy(req)
        assertEquals(1, ordered.size)
        assertEquals("primary", ordered.single().candidateId)

        val result = planner().plan(req)
        assertTrue(result is OmniResult.Ok)
        val planning = (result as OmniResult.Ok).value
        assertEquals(1, planning.viable.size)
        assertTrue(planning.rejections.none { it.candidateId == "fallback" })
    }

    @Test
    fun fallbackSameRevisionOnly_allowsBackendChange_notRevision() = runBlocking {
        val rev = revision('1')
        val primary = candidate(id = "cpu", rev = rev, backend = "cpu", primary = true)
        val gpuSame = candidate(
            id = "gpu",
            rev = rev,
            backend = "gpu",
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440002",
        )
        val otherRev = candidate(
            id = "other",
            rev = revision('a'),
            backend = "cpu",
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440003",
        )
        val req = orchestrationRequest(
            revision = rev,
            candidates = listOf(primary, gpuSame, otherRev),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY),
        )
        val ordered = planner().orderByFallbackPolicy(req)
        assertEquals(2, ordered.size)
        assertTrue(ordered.all { it.modelRevisionId.hex == rev.hex })
        assertFalse(ordered.any { it.candidateId == "other" })
    }

    @Test
    fun fallbackAllowList_onlyExplicitRevisions() = runBlocking {
        val primaryRev = revision('1')
        val allowedRev = revision('2')
        val blockedRev = revision('3')
        val primary = candidate(id = "p", rev = primaryRev, primary = true)
        val allowed = candidate(
            id = "a",
            rev = allowedRev,
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440004",
        )
        val blocked = candidate(
            id = "b",
            rev = blockedRev,
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440005",
        )
        val req = orchestrationRequest(
            revision = primaryRev,
            candidates = listOf(primary, allowed, blocked),
            routing = RoutingPreference(
                fallbackPolicy = FallbackPolicy.ALLOW_LIST,
                revisionAllowlist = listOf(allowedRev),
            ),
        )
        val ordered = planner().orderByFallbackPolicy(req)
        assertEquals(setOf("p", "a"), ordered.map { it.candidateId }.toSet())
        assertFalse(ordered.any { it.candidateId == "b" })
    }

    @Test
    fun capabilityUnsupported_recordsStableReason() = runBlocking {
        val bad = candidate(id = "bad-cap")
        val req = orchestrationRequest(candidates = listOf(bad))
        val result = planner(caps = SelectiveCapabilities(unsupported = setOf("bad-cap"))).plan(req)
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun capabilityUnsupported_amongMany_keepsOtherViable() = runBlocking {
        val good = candidate(id = "good")
        val bad = candidate(
            id = "bad",
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440006",
            backend = "gpu",
        )
        val req = orchestrationRequest(
            candidates = listOf(good, bad),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY),
        )
        val result = planner(caps = SelectiveCapabilities(unsupported = setOf("bad"))).plan(req)
        assertTrue(result is OmniResult.Ok)
        val planning = (result as OmniResult.Ok).value
        assertEquals(1, planning.viable.size)
        assertEquals("good", planning.viable.single().candidate.candidateId)
        assertEquals(1, planning.rejections.size)
        assertEquals(CandidateRejectionCodes.CAPABILITY, planning.rejections.single().code)
    }

    @Test
    fun trustPlacementRequired_rejected() = runBlocking {
        val cand = candidate(
            id = "untrusted",
            placement = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
        )
        val req = orchestrationRequest(candidates = listOf(cand))
        val result = planner().plan(req)
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun healthUnhealthy_rejectedWithHealthCode() = runBlocking {
        val cand = candidate()
        val req = orchestrationRequest(candidates = listOf(cand))
        val result = planner(
            health = HealthLookup { HealthSnapshot(engineHealthy = false) },
        ).plan(req)
        assertTrue(result is OmniResult.Err)
    }

    @Test
    fun thermalBlock_recordsThermalReasonWhenOtherViable() = runBlocking {
        val healthy = candidate(id = "ok")
        val hot = candidate(
            id = "hot",
            primary = false,
            backend = "gpu",
            installation = "550e8400-e29b-41d4-a716-446655440007",
        )
        val req = orchestrationRequest(
            candidates = listOf(healthy, hot),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY),
        )
        val result = planner(
            health = HealthLookup { c ->
                if (c.candidateId == "hot") HealthSnapshot(thermalOk = false)
                else HealthSnapshot()
            },
        ).plan(req)
        assertTrue(result is OmniResult.Ok)
        val planning = (result as OmniResult.Ok).value
        assertEquals(1, planning.viable.size)
        assertEquals(CandidateRejectionCodes.THERMAL, planning.rejections.single().code)
    }
}
