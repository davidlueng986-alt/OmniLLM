package com.omnillm.interfaces.admin

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.job.JobRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Job observer subscription registry (FEAT-ADMIN §5, CORE-INTERFACE §6).
 *
 * - Returns opaque [subscriptionId] handles
 * - Credit / window limits unacked deliveries
 * - ACK advances exclusive cursor with [streamEpoch]
 * - Retention gap ⇒ [OmniError.CURSOR_GONE]; client rebuilds from AdminSnapshot
 * - Death cleanup is invoked by the transport adapter via [close]
 */
class JobSubscriptionRegistry(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val eventSeq = AtomicLong(0L)
    private val retained = ArrayDeque<AdminJobEvent>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val lock = Any()

    /** Highest retained event id (0 when empty). */
    fun highWatermark(): Long = synchronized(lock) {
        retained.lastOrNull()?.eventId ?: 0L
    }

    /**
     * Lowest retained event id, or 0 when empty.
     * Used to detect cursor gaps after retention eviction.
     */
    fun lowWatermark(): Long = synchronized(lock) {
        retained.firstOrNull()?.eventId ?: 0L
    }

    /**
     * Append job lifecycle events to the global stream and push to subscribers.
     * Call after durable job mutation (control-plane single writer).
     */
    fun publishJobRecord(record: JobRecord) {
        val events = synchronized(lock) {
            val jobId = record.jobId.value
            val attempt = record.currentAttemptNo ?: 0
            val progress = record.progress.ratioOrNull() ?: 0.0
            val newest = record.events.lastOrNull()
            val kind = newest?.eventKind ?: "SNAPSHOT"
            val event = AdminJobEvent(
                eventId = eventSeq.incrementAndGet(),
                jobId = jobId,
                attemptNo = attempt,
                kind = kind,
                state = record.state,
                progress = progress,
                occurredAtEpochMs = newest?.occurredAtEpochMs ?: record.updatedAtEpochMs,
                error = record.error,
            )
            retained.addLast(event)
            trimRetentionLocked()
            event
        }
        fanOut(listOf(events))
    }

    /**
     * Subscribe from [cursor] (exclusive event id decimal string, or null for live tail).
     * [credit] is the max unacked events that may be in-flight.
     */
    fun subscribe(
        principalId: PrincipalId,
        cursor: String?,
        credit: Int,
        sink: AdminJobEventSink,
    ): OmniResult<String> {
        if (credit < 0) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "credit must be non-negative",
                    details = mapOf("credit" to credit.toString()),
                ),
            )
        }
        val startExclusive = when {
            cursor.isNullOrBlank() -> highWatermark()
            else -> {
                val parsed = cursor.toLongOrNull()
                    ?: return OmniResult.err(
                        OmniError.INVALID_REQUEST(
                            message = "cursor must be decimal event id",
                            details = mapOf("cursor" to cursor),
                        ),
                    )
                if (parsed < 0L) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(message = "cursor must be >= 0"),
                    )
                }
                // Gap: cursor behind retained window.
                val low = lowWatermark()
                val high = highWatermark()
                if (low > 0L && parsed + 1 < low && parsed < high) {
                    return OmniResult.err(
                        OmniError.CURSOR_GONE(
                            message = "event retention gap; rebuild from AdminSnapshot",
                            details = mapOf(
                                "cursor" to parsed.toString(),
                                "lowWatermark" to low.toString(),
                                "highWatermark" to high.toString(),
                            ),
                        ),
                    )
                }
                parsed
            }
        }

        val id = "sub-" + UUID.randomUUID().toString()
        val sub = Subscription(
            subscriptionId = id,
            principalId = principalId.value,
            streamEpoch = 1L,
            ackedExclusive = startExclusive,
            credit = credit.coerceAtMost(MAX_CREDIT),
            sink = sink,
            createdAtEpochMs = clockMs(),
        )
        subscriptions[id] = sub
        // Deliver backlog if any.
        deliver(sub)
        return OmniResult.ok(id)
    }

    fun ack(subscriptionId: String, streamEpoch: Long, eventToExclusive: Long): OmniResult<Unit> {
        val sub = subscriptions[subscriptionId]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "subscription not found",
                    details = mapOf("subscriptionId" to subscriptionId),
                ),
            )
        if (streamEpoch != sub.streamEpoch) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "streamEpoch mismatch",
                    details = mapOf(
                        "expected" to sub.streamEpoch.toString(),
                        "got" to streamEpoch.toString(),
                    ),
                ),
            )
        }
        if (eventToExclusive < sub.ackedExclusive) {
            // Idempotent ACK of older range — no-op success.
            return OmniResult.ok(Unit)
        }
        // eventToExclusive is half-open end: after event N the exclusive cursor is N+1.
        val maxExclusive = highWatermark() + 1
        if (eventToExclusive > maxExclusive) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "ack beyond high watermark",
                    details = mapOf(
                        "eventToExclusive" to eventToExclusive.toString(),
                        "highWatermark" to highWatermark().toString(),
                        "maxExclusive" to maxExclusive.toString(),
                    ),
                ),
            )
        }
        sub.ackedExclusive = eventToExclusive
        sub.inFlight = 0
        deliver(sub)
        return OmniResult.ok(Unit)
    }

    fun close(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
    }

    fun closeAll() {
        subscriptions.clear()
    }

    fun subscriptionCount(): Int = subscriptions.size

    private fun fanOut(newEvents: List<AdminJobEvent>) {
        if (newEvents.isEmpty()) return
        for (sub in subscriptions.values) {
            deliver(sub)
        }
    }

    private fun deliver(sub: Subscription) {
        if (sub.credit <= 0) return
        val batchEvents: List<AdminJobEvent>
        val from: Long
        val to: Long
        synchronized(lock) {
            val pending = retained.filter { it.eventId > sub.ackedExclusive }
            if (pending.isEmpty()) return
            val remainingCredit = (sub.credit - sub.inFlight).coerceAtLeast(0)
            if (remainingCredit <= 0) return
            val slice = pending.take(remainingCredit)
            batchEvents = slice
            from = slice.first().eventId
            to = slice.last().eventId + 1 // exclusive
            sub.inFlight += slice.size
        }
        try {
            sub.sink.onEvents(
                AdminJobEventBatch(
                    subscriptionId = sub.subscriptionId,
                    streamEpoch = sub.streamEpoch,
                    eventFrom = from,
                    eventTo = to,
                    events = batchEvents,
                ),
            )
        } catch (_: Exception) {
            // Death / remote exception: drop subscription (transport should also close).
            subscriptions.remove(sub.subscriptionId)
        }
    }

    private fun trimRetentionLocked() {
        while (retained.size > MAX_RETAINED_EVENTS) {
            retained.removeFirst()
        }
    }

    private class Subscription(
        val subscriptionId: String,
        val principalId: String,
        val streamEpoch: Long,
        var ackedExclusive: Long,
        val credit: Int,
        val sink: AdminJobEventSink,
        val createdAtEpochMs: Long,
        var inFlight: Int = 0,
    )

    companion object {
        const val MAX_CREDIT: Int = 256
        const val MAX_RETAINED_EVENTS: Int = 10_000
    }
}
