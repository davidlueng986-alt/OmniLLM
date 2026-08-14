package com.omnillm.runtime.policy.acl

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * C-11: principal-scoped admission rate / concurrency enforcement
 * (SEC-AUTH-NET; denial surfaces as OmniError.RATE_LIMITED, 429, retryable —
 * error-catalog.yaml).
 *
 * Dimensions (independent, each disabled by a limit of 0 = fail-open,
 * documented):
 * - Rate: token bucket per principal (capacity = limit, refill = limit/sec).
 *   [tryAcquireRate] consumes a token without holding state.
 * - Concurrency: bounded in-flight leases per principal. [Lease.release]
 *   MUST be called when the request completes (the HTTP gateway releases on
 *   response completion); leases also auto-expire after [leaseTtlMs] as a
 *   safety net for abandoned / stalled operations, and expired leases are
 *   reclaimed under pressure.
 *
 * Bounded memory: buckets idle beyond the lease window are evicted on
 * pressure ([maxEntries]) — a principal set can never grow the map without
 * bound. All per-principal state is serialized under a single lock.
 */
class PrincipalRateLimiter(
    private val rpsLimit: () -> Int,
    private val concurrencyLimit: () -> Int,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val leaseTtlMs: Long = LEASE_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /** Concurrency slot. Auto-expires after the lease window if not released. */
    inner class Lease internal constructor(
        private val owner: PrincipalRateLimiter,
        internal val principalId: String,
        internal val leaseId: Long,
    ) {
        @Volatile
        private var released = false

        /** Idempotent: releases exactly once (no-op afterwards). */
        fun release() {
            if (!released) {
                released = true
                owner.onLeaseReleased(principalId, leaseId)
            }
        }
    }

    private class LeaseEntry(val leaseId: Long, val expiresAtMs: Long)

    private class Bucket {
        var tokens: Double = 0.0
        var lastRefillMs: Long = 0L
        var lastActivityMs: Long = 0L
        val leases: ArrayDeque<LeaseEntry> = ArrayDeque()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val lock = Any()
    private var nextLeaseId = 0L

    /**
     * Token-bucket admission only (no lease). True when admitted; always true
     * when the rate limit is disabled (0). Stateless — callers never release.
     */
    fun tryAcquireRate(principalId: String): Boolean {
        val now = clockMs()
        val rps = rpsLimit()
        if (rps <= 0) return true
        synchronized(lock) {
            evictIfOversized(now)
            val bucket = buckets.computeIfAbsent(principalId) {
                Bucket().apply {
                    // Seed a full bucket: a fresh principal may burst up to the
                    // limit immediately, then refills at limit/sec.
                    tokens = rps.toDouble()
                    lastRefillMs = now
                    lastActivityMs = now
                }
            }
            refill(bucket, rps, now)
            if (bucket.tokens < 1.0) return false
            bucket.tokens -= 1.0
            bucket.lastActivityMs = now
            return true
        }
    }

    /**
     * Concurrency-slot admission only (no token consumed). Null when the
     * principal's in-flight leases are at the limit (expired leases are
     * reclaimed first); always granted when the limit is disabled (0).
     */
    fun tryAcquireConcurrency(principalId: String): Lease? {
        val now = clockMs()
        val conc = concurrencyLimit()
        if (conc <= 0) return Lease(this, principalId, 0L)
        synchronized(lock) {
            evictIfOversized(now)
            val bucket = buckets.computeIfAbsent(principalId) {
                Bucket().apply {
                    lastRefillMs = now
                    lastActivityMs = now
                }
            }
            reclaimExpired(bucket, now)
            if (bucket.leases.size >= conc) return null
            val lease = Lease(this, principalId, nextLeaseId++)
            bucket.leases.addLast(LeaseEntry(lease.leaseId, now + leaseTtlMs))
            bucket.lastActivityMs = now
            return lease
        }
    }

    /**
     * Full admission: rate token + concurrency lease. Null when EITHER
     * dimension rejects. NOTE: when the concurrency dimension rejects, the
     * rate token is already consumed (the request was admitted-and-rejected —
     * callers should prefer the single-dimension entry points when composing).
     */
    fun tryAcquire(principalId: String): Lease? {
        if (!tryAcquireRate(principalId)) return null
        return tryAcquireConcurrency(principalId)
    }

    /** Current non-expired in-flight leases for [principalId] (tests/telemetry). */
    fun currentInFlight(principalId: String): Int = synchronized(lock) {
        val bucket = buckets[principalId] ?: return 0
        reclaimExpired(bucket, clockMs())
        bucket.leases.size
    }

    /** Number of tracked principal buckets (bounded by [maxEntries]). */
    fun bucketCount(): Int = synchronized(lock) { buckets.size }

    private fun onLeaseReleased(principalId: String, leaseId: Long) {
        synchronized(lock) {
            val bucket = buckets[principalId] ?: return
            bucket.leases.removeFirstOrNull { it.leaseId == leaseId }
            bucket.lastActivityMs = clockMs()
        }
    }

    private fun refill(bucket: Bucket, limit: Int, now: Long) {
        val elapsedMs = (now - bucket.lastRefillMs).coerceAtLeast(0L)
        bucket.tokens = minOf(limit.toDouble(), bucket.tokens + elapsedMs / 1000.0 * limit)
        bucket.lastRefillMs = now
    }

    /** Drop leases whose window expired (they never released). */
    private fun reclaimExpired(bucket: Bucket, now: Long) {
        while (bucket.leases.isNotEmpty() && bucket.leases.first().expiresAtMs <= now) {
            bucket.leases.removeFirst()
        }
    }

    /**
     * Bounded map: under pressure, evict the least-recently-active bucket
     * that has no in-flight leases (idle buckets are always evictable; a
     * bucket holding active leases is never evicted mid-flight — the map can
     * only exceed [maxEntries] by the number of concurrently-active
     * principals, which is inherently bounded by the quota × concurrency).
     */
    private fun evictIfOversized(now: Long) {
        if (buckets.size < maxEntries) return
        val idle = buckets.entries.filter { it.value.leases.isEmpty() }
        val lru = idle.minByOrNull { it.value.lastActivityMs } ?: return
        buckets.remove(lru.key)
    }

    companion object {
        /** Default lease window: an abandoned request frees its slot after 60s. */
        const val LEASE_TTL_MS: Long = 60_000L

        /** Upper bound on tracked principal buckets. */
        const val MAX_ENTRIES: Int = 1024
    }
}

private fun <T> ArrayDeque<T>.removeFirstOrNull(predicate: (T) -> Boolean): T? {
    val it = iterator()
    while (it.hasNext()) {
        val next = it.next()
        if (predicate(next)) {
            it.remove()
            return next
        }
    }
    return null
}
