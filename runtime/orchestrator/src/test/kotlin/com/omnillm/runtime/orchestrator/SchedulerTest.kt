package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-principal FIFO + global deficit round-robin (CORE-ORCHESTRATOR §3, FEAT-ROUTING §5).
 * Deterministic ordering under identical policy/input.
 */
class SchedulerTest {

    private fun planned(
        costClass: String = CostClassLabels.GENERATION,
        costUnits: Long = 4L,
        candId: String = "cand-primary",
    ): PlannedCandidate {
        val cand = candidate(id = candId)
        return PlannedCandidate(
            candidate = cand,
            resourceEnvelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 100L),
                peak = ResourceVector(cpuAnonBytes = 200L),
            ),
            planInputDigest = digest('e'),
            costClass = costClass,
            costUnits = costUnits,
        )
    }

    private fun enqueue(
        scheduler: DeficitRoundRobinScheduler,
        principalName: String,
        costUnits: Long,
        costClass: String = CostClassLabels.GENERATION,
    ): ScheduledWork {
        val req = orchestrationRequest(
            principalId = principal(principalName),
            costClass = costClass,
            costUnits = costUnits,
            candidates = listOf(candidate()),
        )
        return scheduler.enqueue(
            requestId = req.requestId,
            principalId = req.principalId,
            planned = planned(costClass = costClass, costUnits = costUnits),
            request = req,
        )
    }

    @Test
    fun perPrincipal_fifo_orderPreserved() {
        val s = DeficitRoundRobinScheduler(policyVersion = "v1", clockMonotonic = { 100L })
        val a1 = enqueue(s, "p-a", costUnits = 1L)
        val a2 = enqueue(s, "p-a", costUnits = 1L)
        val a3 = enqueue(s, "p-a", costUnits = 1L)

        // Single principal: DRR still serves FIFO after quantum refill.
        val first = s.selectNext()
        val second = s.selectNext()
        val third = s.selectNext()
        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)
        assertEquals(a1.requestId, first!!.requestId)
        assertEquals(a2.requestId, second!!.requestId)
        assertEquals(a3.requestId, third!!.requestId)
        assertTrue(a1.enqueueSeq < a2.enqueueSeq && a2.enqueueSeq < a3.enqueueSeq)
    }

    @Test
    fun global_deficitRoundRobin_interleavesPrincipals() {
        val s = DeficitRoundRobinScheduler(policyVersion = "v1", clockMonotonic = { 100L })
        // Two principals, equal cost — should not starve either permanently.
        val a1 = enqueue(s, "p-a", costUnits = 4L)
        val b1 = enqueue(s, "p-b", costUnits = 4L)
        val a2 = enqueue(s, "p-a", costUnits = 4L)
        val b2 = enqueue(s, "p-b", costUnits = 4L)

        val order = mutableListOf<String>()
        repeat(4) {
            val w = s.selectNext()
            assertNotNull(w)
            order += w!!.principalId.value
        }
        // Both principals appear (no permanent starvation).
        assertTrue(order.contains("p-a"))
        assertTrue(order.contains("p-b"))
        // Deterministic: first pick among equal deficit prefers lower enqueueSeq head.
        // After refill, heads are a1 (seq0) and b1 (seq1) → a1 first.
        assertEquals("p-a", order[0])
        assertEquals(a1.principalId.value, "p-a")
        assertEquals(b1.principalId.value, "p-b")
        assertEquals(a2.principalId.value, "p-a")
        assertEquals(b2.principalId.value, "p-b")
        assertNull(s.selectNext())
    }

    @Test
    fun largeLoad_doesNotStarveBehindTinyRequests_withQuantum() {
        val s = DeficitRoundRobinScheduler(
            policyVersion = "v1",
            defaultQuantum = 8L,
            clockMonotonic = { 100L },
        )
        // Principal A floods small generation; B has one expensive LOAD.
        repeat(5) { enqueue(s, "flood", costUnits = 4L, costClass = CostClassLabels.GENERATION) }
        val load = enqueue(s, "loader", costUnits = 16L, costClass = CostClassLabels.LOAD)

        val seen = mutableListOf<String>()
        var steps = 0
        while (!s.isEmpty() && steps < 20) {
            val w = s.selectNext() ?: break
            seen += w.principalId.value
            steps++
        }
        assertTrue("LOAD principal must eventually run", seen.contains("loader"))
        assertEquals(load.principalId.value, "loader")
    }

    @Test
    fun canAdmit_false_keepsHeadQueued_withoutReorderingFifo() {
        val s = DeficitRoundRobinScheduler(policyVersion = "v1", clockMonotonic = { 100L })
        val a1 = enqueue(s, "p-a", costUnits = 1L)
        val a2 = enqueue(s, "p-a", costUnits = 1L)
        // Block first head.
        var blocked = true
        val selected = s.selectNext { w ->
            if (blocked && w.requestId == a1.requestId) false else true
        }
        // Nothing selectable for p-a while head blocked (FIFO).
        assertNull(selected)
        blocked = false
        val first = s.selectNext()
        assertEquals(a1.requestId, first!!.requestId)
        val second = s.selectNext()
        assertEquals(a2.requestId, second!!.requestId)
    }

    @Test
    fun estimateStart_includesPolicyVersion_notSla() {
        val s = DeficitRoundRobinScheduler(policyVersion = "policy-42", clockMonotonic = { 1_000L })
        val w = enqueue(s, "p-a", costUnits = 4L)
        val est = s.estimateStart(w.requestId)
        assertNotNull(est)
        assertEquals("policy-42", est!!.policyVersion)
        assertEquals("ESTIMATED", est.confidence)
        assertEquals(w.enqueueSeq, est.enqueueSeq)
    }

    @Test
    fun remove_cancelsQueuedWork() {
        val s = DeficitRoundRobinScheduler(policyVersion = "v1", clockMonotonic = { 100L })
        val w = enqueue(s, "p-a", costUnits = 1L)
        assertEquals(1, s.size())
        assertNotNull(s.remove(w.requestId))
        assertEquals(0, s.size())
        assertNull(s.selectNext())
    }
}
