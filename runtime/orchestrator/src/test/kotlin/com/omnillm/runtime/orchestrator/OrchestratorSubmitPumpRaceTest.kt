package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.OrchestratorModule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * COR-11 regression: concurrent submit + pump must never fail a scheduled
 * request with INTERNAL "missing planning snapshot" (enqueue/snapshot race).
 *
 * Submitters and the pump run on real threads with a start barrier; the
 * invariant is asserted via the durable registry (every request reaches a
 * single terminal state) and via the terminal result stream (no INTERNAL
 * "missing planning snapshot" failures).
 */
class OrchestratorSubmitPumpRaceTest {

    private fun harness() = OrchestratorModule.createInMemoryHarness(
        capacity = ResourceVector(cpuAnonBytes = 10_000_000L, nativeThreads = 256L),
        safetyMargin = ResourceVector.ZERO,
        engine = FakeInferenceEngine(),
        capabilities = AllSupportedCapabilities(),
    )

    @Test
    fun concurrentSubmitAndPump_noMissingPlanningSnapshotFailures() {
        val h = harness()
        val threads = 8
        val perThread = 8
        val total = threads * perThread
        val requests = (0 until total).map { orchestrationRequest(deadline = 10_000_000L) }

        val start = CountDownLatch(1)
        val submitsDone = CountDownLatch(threads)
        val internalFailures = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threads + 1)
        val pumpDone = AtomicBoolean(false)

        fun record(result: OmniResult<TerminalResult>) {
            if (result is OmniResult.Err) {
                val msg = result.error.message ?: ""
                if (result.error.code == OmniErrorCode.INTERNAL &&
                    msg.contains("missing planning snapshot")
                ) {
                    internalFailures.incrementAndGet()
                }
            }
        }

        val pump = executor.submit {
            start.await()
            while (!pumpDone.get()) {
                val results = runBlocking { h.orchestrator.pumpAll(maxSteps = 512) }
                if (results.isEmpty()) {
                    Thread.yield()
                }
                results.forEach { record(it) }
            }
        }

        val workers = requests.chunked(perThread).map { chunk ->
            executor.submit {
                start.await()
                for (req in chunk) {
                    val out = runBlocking { h.orchestrator.submit(req) }
                    assertTrue("submit failed: $out", out is OmniResult.Ok)
                }
                submitsDone.countDown()
            }
        }

        start.countDown()
        assertTrue("submitters did not finish", submitsDone.await(120, TimeUnit.SECONDS))
        workers.forEach { it.get(120, TimeUnit.SECONDS) }

        // Drain any remaining queued work after all submitters are done.
        while (true) {
            val more = runBlocking { h.orchestrator.pumpAll(maxSteps = 512) }
            if (more.isEmpty()) break
            more.forEach { record(it) }
        }
        pumpDone.set(true)
        pump.get(120, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS))

        assertEquals("INTERNAL missing-planning-snapshot failures", 0, internalFailures.get())
        for (req in requests) {
            val q = h.orchestrator.query(req.requestId)
            assertNotNull("no query view for ${req.requestId.value}", q)
            assertNotNull(
                "non-terminal ${req.requestId.value} state=${q!!.state}",
                q.terminalState,
            )
            assertTrue(
                "unexpected terminal state ${q.terminalState} for ${req.requestId.value}",
                q.terminalState in setOf("COMPLETED", "FAILED", "CANCELLED", "ABORTED_UNCERTAIN"),
            )
        }
    }
}
