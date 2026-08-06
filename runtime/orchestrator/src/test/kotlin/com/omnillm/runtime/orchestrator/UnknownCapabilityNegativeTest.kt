package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unknown capability fail-closed (INV-018, SEC-THREAT, CORE-ORCHESTRATOR).
 *
 * Catalog-unknown IDs throw at request construction; catalog-known but
 * evidence-UNKNOWN candidates are rejected without execution.
 *
 * Quality scenarios: capability fail-closed underpins **Q-010** (input/envelope)
 * and engine envelope non-reuse **Q-016**.
 */
class UnknownCapabilityNegativeTest {

    private val engine = FakeInferenceEngine()

    private fun planner(caps: CapabilityLookup = AllSupportedCapabilities()) =
        CandidatePlanner(capabilities = caps, health = HealthLookup { HealthSnapshot() }, engine = engine)

    @Test
    fun catalogUnknownCapabilityId_failClosed() {
        try {
            CapabilityId.requireFromId("not.a.capability")
            assertFalse("expected fail-closed on unknown capability id", true)
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("fail closed") || e.message!!.contains("Unknown"))
        }
        assertEquals(null, CapabilityId.fromId("totally.unknown.capability"))
        // Known catalog id still resolves.
        assertEquals(CapabilityId.TEXT_GENERATION, CapabilityId.requireFromId(CapabilityId.TEXT_GENERATION.id))
    }

    @Test
    fun evidenceUnknown_capabilityRejected_noViableCandidate() = runBlocking {
        val cand = candidate(id = "unknown-cap-cand")
        val req = orchestrationRequest(candidates = listOf(cand))
        val caps = object : CapabilityLookup {
            override fun state(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState =
                CapabilityState.UNKNOWN
        }
        val result = planner(caps).plan(req)
        assertTrue(result is OmniResult.Err)
        // Plan must not have committed engine work for the rejected candidate.
        // Fake plan is only invoked for candidates that pass capability filter.
        assertEquals(0, engine.planCount.get())
    }

    @Test
    fun unknownAmongCandidates_rejectsOnlyUnknown_keepsSupported() = runBlocking {
        val good = candidate(id = "supported")
        val bad = candidate(
            id = "unknown",
            primary = false,
            installation = "550e8400-e29b-41d4-a716-446655440099",
            backend = "gpu",
        )
        val req = orchestrationRequest(
            candidates = listOf(good, bad),
            routing = RoutingPreference(
                fallbackPolicy = com.omnillm.core.canonical.generated.FallbackPolicy.SAME_REVISION_ONLY,
            ),
        )
        val caps = object : CapabilityLookup {
            override fun state(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState =
                if (candidate.candidateId == "unknown") {
                    CapabilityState.UNKNOWN
                } else {
                    CapabilityState.SUPPORTED
                }
        }
        val result = planner(caps).plan(req)
        assertTrue(result is OmniResult.Ok)
        val planning = (result as OmniResult.Ok).value
        assertEquals(1, planning.viable.size)
        assertEquals("supported", planning.viable.single().candidate.candidateId)
        assertEquals(1, planning.rejections.size)
        assertEquals(CandidateRejectionCodes.CAPABILITY, planning.rejections.single().code)
        assertTrue(planning.rejections.single().message.contains("unknown", ignoreCase = true))
    }
}
