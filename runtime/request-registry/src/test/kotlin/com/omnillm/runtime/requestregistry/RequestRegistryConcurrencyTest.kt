package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.RequestRegistryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TST-05(b): multi-threaded claim / idempotency / terminal races against
 * [RequestRegistry] — exactly-one-New claim, conflict discipline, and the
 * exactly-one-durable-terminal invariant. Real threads + start barrier.
 */
class RequestRegistryConcurrencyTest {

    private val digestA = Sha256Digest.parse("a".repeat(64))
    private val clock = { "2026-08-08T12:00:00Z" }

    private fun newRegistry() = RequestRegistryModule.createInMemory(clock).first

    private fun <T> runConcurrently(threads: Int, block: () -> T): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        val futures = (0 until threads).map {
            executor.submit<T> {
                start.await()
                block()
            }
        }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        return results
    }

    @Test
    fun concurrentIdenticalClaims_exactlyOneNew_restExisting() {
        val registry = newRegistry()
        val threads = 8
        val requestId = RequestId.parse("11111111-1111-1111-1111-111111111111")
        val key = IdempotencyKey.parse("idem-race-1")

        val results = runConcurrently(threads) {
            registry.claim(
                principal = PrincipalId.parse("principal-race"),
                operationKind = "CHAT",
                idempotencyKey = key,
                canonicalHash = digestA,
                requestId = requestId,
            )
        }

        assertEquals(1, results.count { it is ClaimOutcome.New })
        assertEquals(threads - 1, results.count { it is ClaimOutcome.Existing })
        assertEquals(0, results.count { it is ClaimOutcome.Conflict })
        assertNotNull(registry.queryRequest(requestId))
        assertEquals("RECEIVED", registry.queryRequest(requestId)!!.state)
    }

    @Test
    fun concurrentSameKeyDifferentRequestIds_exactlyOneNew_restConflict() {
        val registry = newRegistry()
        val threads = 8
        val key = IdempotencyKey.parse("idem-race-2")

        val results = runConcurrently(threads) {
            val requestId = RequestId.parse(java.util.UUID.randomUUID().toString())
            registry.claim(
                principal = PrincipalId.parse("principal-race"),
                operationKind = "CHAT",
                idempotencyKey = key,
                canonicalHash = digestA,
                requestId = requestId,
            )
        }

        assertEquals(1, results.count { it is ClaimOutcome.New })
        assertEquals(0, results.count { it is ClaimOutcome.Existing })
        assertEquals(threads - 1, results.count { it is ClaimOutcome.Conflict })
        val conflicts = results.filterIsInstance<ClaimOutcome.Conflict>()
        assertTrue(
            conflicts.all { it.error.code == OmniErrorCode.IDEMPOTENCY_CONFLICT },
        )
    }

    @Test
    fun concurrentTerminalWrites_exactlyOneDurableTerminal() {
        val registry = newRegistry()
        val threads = 8
        val requestId = RequestId.parse("22222222-2222-2222-2222-222222222222")
        registry.claim(
            principal = PrincipalId.parse("principal-race"),
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("idem-race-3"),
            canonicalHash = digestA,
            requestId = requestId,
        )

        // Each thread races a different terminal payload: exactly one may win.
        val results = runConcurrently(threads) {
            val state = when ((Thread.currentThread().id % 3)) {
                0L -> "COMPLETED"
                1L -> "FAILED"
                else -> "CANCELLED"
            }
            registry.recordTerminal(
                requestId = requestId,
                terminalState = state,
                terminalSeq = 1L,
                errorCode = null,
            )
        }

        val okCount = results.count { it is com.omnillm.core.canonical.generated.OmniResult.Ok }
        val conflictCount = results.count {
            it is com.omnillm.core.canonical.generated.OmniResult.Err &&
                (it as com.omnillm.core.canonical.generated.OmniResult.Err).error.code ==
                OmniErrorCode.STATE_CONFLICT
        }
        assertTrue("expected at least one success", okCount >= 1)
        assertEquals(threads, okCount + conflictCount)

        // Exactly one durable terminal; state/terminal aligned.
        val row = registry.queryRequest(requestId)
        val terminal = registry.queryRequestTerminal(requestId)
        assertNotNull(row)
        assertNotNull(terminal)
        assertEquals(terminal!!.terminalState, row!!.state)
    }
}
