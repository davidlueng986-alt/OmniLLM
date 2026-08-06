package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.OrchestratorModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Orchestrator-level fallback policy (CORE-ORCHESTRATOR / FEAT-ROUTING).
 * Complements [CandidatePlannerTest] with end-to-end submit → pump paths.
 */
class FallbackPolicyOrchestratorTest {

    private fun harness(
        engine: FakeInferenceEngine = FakeInferenceEngine(),
        capacity: ResourceVector = ResourceVector(cpuAnonBytes = 200_000L, nativeThreads = 32L),
        health: HealthLookup = HealthLookup { HealthSnapshot() },
    ): Orchestrator {
        val base = OrchestratorModule.createInMemoryHarness(
            capacity = capacity,
            safetyMargin = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 1L),
            engine = engine,
            capabilities = AllSupportedCapabilities(),
            clockMonotonic = { 1_000L },
        )
        return OrchestratorModule.create(
            registry = base.registry,
            governor = base.governor,
            engine = engine,
            capabilities = AllSupportedCapabilities(),
            health = health,
            issuerBootId = "boot-test",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
        )
    }

    @Test
    fun sameRevisionOnly_backendFallback_reportsUsedFallback() = runBlocking {
        val rev = revision('1')
        val primary = candidate(id = "cpu", rev = rev, backend = "cpu", primary = true)
        val gpu = candidate(
            id = "gpu",
            rev = rev,
            backend = "gpu",
            primary = false,
            installation = "550e8400-e29b-41d4-a716-4466554400ee",
        )
        val orch = harness(
            health = HealthLookup { c ->
                if (c.candidateId == "cpu") HealthSnapshot(engineHealthy = false)
                else HealthSnapshot()
            },
        )
        val req = orchestrationRequest(
            revision = rev,
            candidates = listOf(primary, gpu),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY),
            deadline = 10_000L,
        )
        val submitted = orch.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val planning = (submitted as OmniResult.Ok).value.planning!!
        assertEquals(1, planning.viable.size)
        assertEquals("gpu", planning.viable.single().candidate.candidateId)

        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        val result = (terminal as OmniResult.Ok).value
        assertEquals("COMPLETED", result.state)
        assertTrue(result.actualRouting.usedFallback)
        assertEquals("gpu", result.actualRouting.backend)
        assertEquals(rev.hex, result.actualRouting.modelRevisionId.hex)
        assertEquals(FallbackPolicy.SAME_REVISION_ONLY, result.actualRouting.fallbackPolicy)
    }

    @Test
    fun sameRevisionOnly_rejectsCrossRevision_evenWhenHealthy() = runBlocking {
        val primaryRev = revision('1')
        val otherRev = revision('2')
        val primary = candidate(id = "primary", rev = primaryRev, primary = true)
        val cross = candidate(
            id = "cross",
            rev = otherRev,
            primary = false,
            installation = "550e8400-e29b-41d4-a716-4466554400ff",
        )
        val orch = harness(
            health = HealthLookup { c ->
                if (c.candidateId == "primary") HealthSnapshot(modelHealthy = false)
                else HealthSnapshot()
            },
        )
        val req = orchestrationRequest(
            revision = primaryRev,
            candidates = listOf(primary, cross),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY),
            deadline = 10_000L,
        )
        val submitted = orch.submit(req)
        assertTrue(submitted is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (submitted as OmniResult.Err).error.code)
    }

    @Test
    fun allowList_emptyAllowlist_rejectedAtPreferenceBuild() {
        // RoutingPreference ALLOW_LIST requires non-empty allowlist (catalog).
        var threw = false
        try {
            RoutingPreference(
                fallbackPolicy = FallbackPolicy.ALLOW_LIST,
                revisionAllowlist = emptyList(),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun none_primaryHealthy_noFallbackFlag() = runBlocking {
        val rev = revision('1')
        val primary = candidate(id = "primary", rev = rev, primary = true)
        val alt = candidate(
            id = "alt",
            rev = rev,
            primary = false,
            backend = "gpu",
            installation = "550e8400-e29b-41d4-a716-446655440011",
        )
        val orch = harness()
        // NONE keeps same-revision candidates (primary first); cross-revision is forbidden.
        // Execution prefers primary → usedFallback remains false.
        val req = orchestrationRequest(
            revision = rev,
            candidates = listOf(primary, alt),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE),
            deadline = 10_000L,
        )
        val submitted = orch.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val planning = (submitted as OmniResult.Ok).value.planning!!
        assertTrue(planning.viable.isNotEmpty())
        assertEquals("primary", planning.viable.first().candidate.candidateId)
        assertTrue(planning.viable.all { it.candidate.modelRevisionId.hex == rev.hex })

        val terminal = orch.pumpOnce()
        val result = (terminal as OmniResult.Ok).value
        assertEquals("COMPLETED", result.state)
        assertFalse(result.actualRouting.usedFallback)
        assertEquals("cpu", result.actualRouting.backend)
        assertEquals(rev.hex, result.actualRouting.modelRevisionId.hex)
        assertEquals(FallbackPolicy.NONE, result.actualRouting.fallbackPolicy)
    }
}
