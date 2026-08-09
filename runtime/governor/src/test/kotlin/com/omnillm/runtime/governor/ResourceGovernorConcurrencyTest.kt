package com.omnillm.runtime.governor

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.AllocationHandle
import com.omnillm.core.resource.Reservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TST-05(a): multi-threaded race tests for [ResourceGovernor] — atomic claims
 * (double-spend prevention), concurrent reserve/release conservation and
 * concurrent convert conservation. Real threads + start barrier.
 */
class ResourceGovernorConcurrencyTest {

    private fun governor(cap: ResourceVector, safety: ResourceVector = ResourceVector.ZERO) =
        ResourceGovernor(
            capacity = cap,
            safetyMargin = safety,
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
            idSource = { java.util.UUID.randomUUID().toString() },
        )

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
    fun concurrentReserve_capacityNeverOverbooked() {
        val g = governor(ResourceVector(cpuAnonBytes = 100_000L, nativeThreads = 32L))
        val threads = 8
        val peak = ResourceVector(cpuAnonBytes = 60_000L, nativeThreads = 8L)

        val results = runConcurrently(threads) {
            g.reserve(peak, owner = "owner-${Thread.currentThread().id}", deadlineMonotonic = 10_000_000L)
        }

        val ok = results.filterIsInstance<OmniResult.Ok<Reservation>>()
        val rejected = results.filterIsInstance<OmniResult.Err>()
        // Only one of 8 can hold 60k of a 100k capacity.
        assertEquals(1, ok.size)
        assertEquals(threads - 1, rejected.size)
        assertTrue(rejected.all { it.error.code == OmniErrorCode.ADMISSION_REJECTED })

        val snap = g.snapshot()
        assertEquals(60_000L, snap.reserved.cpuAnonBytes)
        assertEquals(40_000L, snap.free.cpuAnonBytes)
        assertTrue(snap.checkInvariants() is OmniResult.Ok)
    }

    @Test
    fun concurrentReserveRelease_returnsToBaseline() {
        val g = governor(ResourceVector(cpuAnonBytes = 1_000_000L, nativeThreads = 128L))
        val threads = 8
        val iterations = 40
        val peak = ResourceVector(cpuAnonBytes = 10_000L, nativeThreads = 2L)

        runConcurrently(threads) {
            repeat(iterations) {
                val r = g.reserve(peak, owner = "o", deadlineMonotonic = 10_000_000L)
                assertTrue(r is OmniResult.Ok)
                g.releaseReservation((r as OmniResult.Ok).value.reservationId)
            }
        }

        val snap = g.snapshot()
        assertEquals(0L, snap.reserved.cpuAnonBytes)
        assertEquals(1_000_000L, snap.free.cpuAnonBytes)
        assertTrue(snap.checkInvariants() is OmniResult.Ok)
    }

    @Test
    fun concurrentConvert_conservationPerDimensionHolds() {
        val g = governor(ResourceVector(cpuAnonBytes = 1_000_000L, nativeThreads = 128L))
        val threads = 8
        val peak = ResourceVector(cpuAnonBytes = 50_000L, nativeThreads = 8L)
        val steady = ResourceVector(cpuAnonBytes = 10_000L, nativeThreads = 2L)

        val results = runConcurrently(threads) {
            val r = g.reserve(peak, owner = "o", deadlineMonotonic = 10_000_000L) as OmniResult.Ok
            g.convert(r.value.reservationId, steady)
        }

        assertEquals(threads, results.filterIsInstance<OmniResult.Ok<AllocationHandle>>().size)
        val snap = g.snapshot()
        assertEquals(0L, snap.reserved.cpuAnonBytes)
        assertEquals(10_000L * threads, snap.allocated.cpuAnonBytes)
        assertEquals(1_000_000L - 10_000L * threads, snap.free.cpuAnonBytes)
        assertTrue(snap.checkInvariants() is OmniResult.Ok)
    }

    @Test
    fun concurrentReleaseAndReReserve_noLeak() {
        val g = governor(ResourceVector(cpuAnonBytes = 500_000L, nativeThreads = 64L))
        val threads = 6
        val peak = ResourceVector(cpuAnonBytes = 50_000L, nativeThreads = 4L)
        val failures = AtomicInteger(0)

        runConcurrently(threads) {
            repeat(25) {
                val r = g.reserve(peak, owner = "o", deadlineMonotonic = 10_000_000L)
                when (r) {
                    is OmniResult.Err -> failures.incrementAndGet()
                    is OmniResult.Ok -> g.releaseReservation(r.value.reservationId)
                }
            }
        }

        // Transient contention may reject; capacity must never leak.
        val snap = g.snapshot()
        assertEquals(0L, snap.reserved.cpuAnonBytes)
        assertEquals(500_000L, snap.free.cpuAnonBytes)
        assertTrue(snap.checkInvariants() is OmniResult.Ok)
    }
}
