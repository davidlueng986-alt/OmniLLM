package com.omnillm.runtime.policy.acl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * C-11: principal rate / concurrency admission mechanics
 * (token bucket, in-flight leases, TTL safety net, bounded map, disabled).
 */
class PrincipalRateLimiterTest {

    private val clock = AtomicLong(1_000_000L)

    private fun limiter(
        rps: Int = 60,
        concurrent: Int = 8,
        leaseTtlMs: Long = 60_000L,
        maxEntries: Int = 1024,
    ): PrincipalRateLimiter = PrincipalRateLimiter(
        rpsLimit = { rps },
        concurrencyLimit = { concurrent },
        clockMs = { clock.get() },
        leaseTtlMs = leaseTtlMs,
        maxEntries = maxEntries,
    )

    @Test
    fun rate_burstOverLimit_isRejected_thenRefills() {
        val l = limiter(rps = 3, concurrent = 0)
        assertTrue(l.tryAcquireRate("p1"))
        assertTrue(l.tryAcquireRate("p1"))
        assertTrue(l.tryAcquireRate("p1"))
        assertFalse("4th request in the same window must be RATE_LIMITED", l.tryAcquireRate("p1"))
        // Refill: 1s later a token is available again.
        clock.addAndGet(1_000L)
        assertTrue(l.tryAcquireRate("p1"))
    }

    @Test
    fun rate_disabled_zeroLimit_failsOpen() {
        val l = limiter(rps = 0, concurrent = 0)
        repeat(10_000) {
            assertTrue("0 limit = disabled = fail-open", l.tryAcquireRate("p1"))
        }
        assertNotNull(l.tryAcquire("p1"))
    }

    @Test
    fun rate_perPrincipalIsIndependent() {
        val l = limiter(rps = 1, concurrent = 0)
        assertTrue(l.tryAcquireRate("p1"))
        assertFalse(l.tryAcquireRate("p1"))
        assertTrue("other principal has its own bucket", l.tryAcquireRate("p2"))
    }

    @Test
    fun concurrency_overQuota_isRejected_untilRelease() {
        val l = limiter(rps = 0, concurrent = 3)
        val leases = (1..3).map { l.tryAcquireConcurrency("p1") }
        assertTrue(leases.all { it != null })
        assertEquals(3, l.currentInFlight("p1"))
        assertNull("4th concurrent request must be RATE_LIMITED", l.tryAcquireConcurrency("p1"))

        leases[0]!!.release()
        assertEquals(2, l.currentInFlight("p1"))
        assertNotNull("slot freed after release", l.tryAcquireConcurrency("p1"))
    }

    @Test
    fun concurrency_expiredLease_isReclaimedUnderPressure() {
        val l = limiter(rps = 0, concurrent = 1, leaseTtlMs = 1_000L)
        val lease = l.tryAcquireConcurrency("p1")
        assertNotNull(lease)
        assertNull(l.tryAcquireConcurrency("p1"))
        // Lease window expires without release (abandoned request).
        clock.addAndGet(1_001L)
        assertNotNull("expired lease must be reclaimed", l.tryAcquireConcurrency("p1"))
        assertEquals(1, l.currentInFlight("p1"))
    }

    @Test
    fun concurrency_releaseIsIdempotent() {
        val l = limiter(rps = 0, concurrent = 1)
        val lease = l.tryAcquireConcurrency("p1")!!
        lease.release()
        lease.release() // second release is a no-op
        assertEquals(0, l.currentInFlight("p1"))
        assertNotNull(l.tryAcquireConcurrency("p1"))
    }

    @Test
    fun concurrency_disabled_failsOpen() {
        val l = limiter(rps = 60, concurrent = 0)
        repeat(100) {
            assertNotNull("0 concurrent = disabled = fail-open", l.tryAcquireConcurrency("p1"))
        }
    }

    @Test
    fun fullAdmission_requiresBothDimensions() {
        val l = limiter(rps = 1, concurrent = 1)
        assertNotNull(l.tryAcquire("p1"))
        clock.addAndGet(1_000L)
        assertNull("concurrency full -> full admission rejected", l.tryAcquire("p1"))
    }

    @Test
    fun mapStaysBounded_evictsIdleBuckets() {
        val l = limiter(rps = 1, concurrent = 0, maxEntries = 2)
        assertTrue(l.tryAcquireRate("p-a"))
        assertTrue(l.tryAcquireRate("p-b"))
        // Third principal under pressure: p-a/p-b are idle -> evicted.
        assertTrue(l.tryAcquireRate("p-c"))
        assertTrue("bucket count stays bounded", l.bucketCount() <= 2)
    }
}
