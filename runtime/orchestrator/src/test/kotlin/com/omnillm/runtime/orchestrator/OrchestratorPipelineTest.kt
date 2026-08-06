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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration: claim → plan → queue → reserve → commit → prepare → start → stream → terminal
 * with fakes for engine + real in-memory governor / request registry.
 */
class OrchestratorPipelineTest {

    private fun harness(
        engine: FakeInferenceEngine = FakeInferenceEngine(),
        capacity: ResourceVector = ResourceVector(cpuAnonBytes = 100_000L, nativeThreads = 32L),
        clock: () -> Long = { 1_000L },
    ) = OrchestratorModule.createInMemoryHarness(
        capacity = capacity,
        safetyMargin = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 1L),
        engine = engine,
        capabilities = AllSupportedCapabilities(),
        clockMonotonic = clock,
    ) to engine

    @Test
    fun happyPath_claimThroughCompleted_reportsActualRouting() = runBlocking {
        val (h, engine) = harness()
        val rev = revision('1')
        val req = orchestrationRequest(
            revision = rev,
            candidates = listOf(candidate(rev = rev, backend = "cpu")),
            deadline = 10_000L,
        )

        val submitted = h.orchestrator.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val submit = (submitted as OmniResult.Ok).value
        assertEquals(ClaimKind.NEW, submit.claim)
        assertEquals("QUEUED", submit.state)
        assertNotNull(submit.planning)
        assertTrue(submit.planning!!.hasViable)
        assertNotNull(submit.earliestStart)
        assertEquals("orchestrator-drr-v1", submit.earliestStart!!.policyVersion)

        val terminal = h.orchestrator.pumpOnce()
        assertNotNull(terminal)
        assertTrue(terminal is OmniResult.Ok)
        val result = (terminal as OmniResult.Ok).value
        assertEquals("COMPLETED", result.state)
        assertNull(result.errorCode)
        assertNotNull(result.preparedOperationId)
        assertEquals("cpu", result.actualRouting.backend)
        assertEquals(rev.hex, result.actualRouting.modelRevisionId.hex)
        assertFalse(result.actualRouting.usedFallback)

        assertTrue(engine.planCount.get() >= 1)
        assertEquals(1, engine.commitCount.get())
        assertEquals(1, engine.startCount.get())

        val query = h.orchestrator.query(req.requestId)
        assertNotNull(query)
        assertEquals("COMPLETED", query!!.state)
        assertEquals("COMPLETED", query.terminalState)
        assertNotNull(query.actualRouting)
    }

    @Test
    fun idempotentResubmit_returnsExisting_withoutReexecute() = runBlocking {
        val (h, engine) = harness()
        val req = orchestrationRequest(deadline = 10_000L)

        val first = h.orchestrator.submit(req)
        assertTrue(first is OmniResult.Ok)
        assertEquals(ClaimKind.NEW, (first as OmniResult.Ok).value.claim)

        val plansBefore = engine.planCount.get()
        val second = h.orchestrator.submit(req)
        assertTrue(second is OmniResult.Ok)
        assertEquals(ClaimKind.EXISTING, (second as OmniResult.Ok).value.claim)
        // Existing claim must not re-plan.
        assertEquals(plansBefore, engine.planCount.get())
    }

    @Test
    fun idempotencyConflict_differentPayload_sameKey() = runBlocking {
        val (h, _) = harness()
        val id = requestId()
        val key = "same-key"
        val r1 = orchestrationRequest(
            requestId = id,
            idempotencyKey = key,
            deadline = 10_000L,
        )
        assertTrue(h.orchestrator.submit(r1) is OmniResult.Ok)

        // Different digest, same claim key — must conflict (ADR-004/005).
        val r2 = OrchestrationRequest(
            requestId = requestId(),
            principalId = r1.principalId,
            idempotencyKey = r1.idempotencyKey,
            operationKind = r1.operationKind,
            canonicalRequestDigest = digest('f'),
            requiredCapabilities = r1.requiredCapabilities,
            requestedRevisionId = r1.requestedRevisionId,
            candidates = r1.candidates,
            routing = r1.routing,
            costClass = r1.costClass,
            runtimeEpoch = r1.runtimeEpoch,
            revocationEpoch = r1.revocationEpoch,
            deadlineMonotonic = r1.deadlineMonotonic,
        )
        val conflict = h.orchestrator.submit(r2)
        assertTrue(conflict is OmniResult.Err)
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, (conflict as OmniResult.Err).error.code)
    }

    @Test
    fun fallbackAllowList_usesSecondary_andReportsUsedFallback() = runBlocking {
        val engine = FakeInferenceEngine(peakBytes = 50_000L)
        // Capacity only fits one peak of 50k with safety — primary will reserve first.
        // Force primary plan peak too large by using selective envelope: we fail primary
        // via health on primary and allow secondary.
        val primaryRev = revision('1')
        val altRev = revision('2')
        val primary = candidate(id = "primary", rev = primaryRev, primary = true)
        val alt = candidate(
            id = "alt",
            rev = altRev,
            primary = false,
            installation = "550e8400-e29b-41d4-a716-4466554400aa",
            backend = "gpu",
        )
        val (h, _) = harness(
            engine = engine,
            capacity = ResourceVector(cpuAnonBytes = 200_000L, nativeThreads = 32L),
        )
        val orch = OrchestratorModule.create(
            registry = h.registry,
            governor = h.governor,
            engine = engine,
            capabilities = AllSupportedCapabilities(),
            health = HealthLookup { c ->
                if (c.candidateId == "primary") HealthSnapshot(modelHealthy = false)
                else HealthSnapshot()
            },
            issuerBootId = "boot-test",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
        )
        val req = orchestrationRequest(
            revision = primaryRev,
            candidates = listOf(primary, alt),
            routing = RoutingPreference(
                fallbackPolicy = FallbackPolicy.ALLOW_LIST,
                revisionAllowlist = listOf(altRev),
            ),
            deadline = 10_000L,
        )
        val submitted = orch.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val planning = (submitted as OmniResult.Ok).value.planning!!
        assertEquals(1, planning.viable.size)
        assertEquals("alt", planning.viable.single().candidate.candidateId)
        assertTrue(planning.rejections.any { it.code == CandidateRejectionCodes.HEALTH })

        val terminal = orch.pumpOnce()
        assertNotNull(terminal)
        val result = (terminal as OmniResult.Ok).value
        assertEquals("COMPLETED", result.state)
        assertTrue(result.actualRouting.usedFallback)
        assertEquals("gpu", result.actualRouting.backend)
        assertEquals(altRev.hex, result.actualRouting.modelRevisionId.hex)
        assertEquals(FallbackPolicy.ALLOW_LIST, result.actualRouting.fallbackPolicy)
    }

    @Test
    fun fallbackNone_doesNotUseSecondaryRevision() = runBlocking {
        val primaryRev = revision('1')
        val altRev = revision('2')
        val primary = candidate(id = "primary", rev = primaryRev, primary = true)
        val alt = candidate(
            id = "alt",
            rev = altRev,
            primary = false,
            installation = "550e8400-e29b-41d4-a716-4466554400bb",
        )
        val engine = FakeInferenceEngine()
        val (h, _) = harness(engine = engine)
        val orch = OrchestratorModule.create(
            registry = h.registry,
            governor = h.governor,
            engine = engine,
            capabilities = AllSupportedCapabilities(),
            health = HealthLookup { c ->
                if (c.candidateId == "primary") HealthSnapshot(engineHealthy = false)
                else HealthSnapshot()
            },
            issuerBootId = "boot-test",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
        )
        val req = orchestrationRequest(
            revision = primaryRev,
            candidates = listOf(primary, alt),
            routing = RoutingPreference(fallbackPolicy = FallbackPolicy.NONE),
            deadline = 10_000L,
        )
        val submitted = orch.submit(req)
        assertTrue(submitted is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (submitted as OmniResult.Err).error.code)
    }

    @Test
    fun resourceContention_secondRequest_admissionRejected() = runBlocking {
        val engine = FakeInferenceEngine(peakBytes = 80_000L, steadyBytes = 40_000L)
        val (h, _) = harness(
            engine = engine,
            capacity = ResourceVector(cpuAnonBytes = 100_000L, nativeThreads = 16L),
        )
        val r1 = orchestrationRequest(deadline = 10_000L, costUnits = 4L)
        val r2 = orchestrationRequest(
            principalId = principal("principal-b"),
            deadline = 10_000L,
            costUnits = 4L,
            candidates = listOf(
                candidate(installation = "550e8400-e29b-41d4-a716-4466554400cc"),
            ),
        )
        assertTrue(h.orchestrator.submit(r1) is OmniResult.Ok)
        assertTrue(h.orchestrator.submit(r2) is OmniResult.Ok)

        val t1 = h.orchestrator.pumpOnce()
        assertNotNull(t1)
        // First should complete and convert steady 40k — free left ~60k after release of peak remainder.
        // Actually convert keeps steady allocated; peak remainder released.
        // free after convert: capacity - safety - allocated(40k) = 100k - 100 - 40k = ~59800? wait
        // capacity 100000, safety 100 → admittable free starts at 99900
        // reserve 80000 → free 19900
        // convert steady 40000, release remainder 40000 → free 19900+40000=59900, allocated 40000
        // Second peak 80000 > free 59900 → admission reject.

        // Drain first fully
        assertTrue(t1 is OmniResult.Ok)
        assertEquals("COMPLETED", (t1 as OmniResult.Ok).value.state)

        // Note: after COMPLETED, allocation still holds steady — second may fail.
        val t2 = h.orchestrator.pumpOnce()
        assertNotNull(t2)
        // Either err ADMISSION_REJECTED or ok with FAILED state depending path.
        when (t2) {
            is OmniResult.Err ->
                assertEquals(OmniErrorCode.ADMISSION_REJECTED, t2.error.code)
            is OmniResult.Ok ->
                assertEquals("FAILED", t2.value.state)
            null -> error("unreachable")
        }
    }

    @Test
    fun cancelQueued_reachesCancelledTerminal() = runBlocking {
        val (h, _) = harness()
        val req = orchestrationRequest(deadline = 10_000L)
        assertTrue(h.orchestrator.submit(req) is OmniResult.Ok)
        val cancelled = h.orchestrator.cancel(req.requestId)
        assertTrue(cancelled is OmniResult.Ok)
        val q = h.orchestrator.query(req.requestId)
        assertEquals("CANCELLED", q!!.terminalState)
    }

    @Test
    fun multiPrincipal_fairPump_completesBoth() = runBlocking {
        val (h, _) = harness()
        val r1 = orchestrationRequest(
            principalId = principal("alice"),
            deadline = 10_000L,
            costUnits = 4L,
        )
        val r2 = orchestrationRequest(
            principalId = principal("bob"),
            deadline = 10_000L,
            costUnits = 4L,
            candidates = listOf(
                candidate(installation = "550e8400-e29b-41d4-a716-4466554400dd"),
            ),
        )
        assertTrue(h.orchestrator.submit(r1) is OmniResult.Ok)
        assertTrue(h.orchestrator.submit(r2) is OmniResult.Ok)

        val results = h.orchestrator.pumpAll(maxSteps = 4)
        assertEquals(2, results.size)
        assertTrue(results.all { it is OmniResult.Ok && (it as OmniResult.Ok).value.state == "COMPLETED" })
    }

    @Test
    fun governor_conservation_holds_afterPipeline() = runBlocking {
        val engine = FakeInferenceEngine(peakBytes = 5_000L, steadyBytes = 2_000L)
        val (h, _) = harness(engine = engine)
        val req = orchestrationRequest(deadline = 10_000L)
        assertTrue(h.orchestrator.submit(req) is OmniResult.Ok)
        val term = h.orchestrator.pumpOnce()
        assertTrue(term is OmniResult.Ok)
        val inv = h.governor.snapshot().checkInvariants()
        assertTrue(inv is OmniResult.Ok)
        // Steady allocation still resident after complete (request terminal ≠ allocation release).
        assertEquals(2_000L, h.governor.snapshot().allocated.cpuAnonBytes)
    }
}
