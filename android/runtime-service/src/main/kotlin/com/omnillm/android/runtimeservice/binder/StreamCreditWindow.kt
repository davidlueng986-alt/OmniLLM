package com.omnillm.android.runtimeservice.binder

/**
 * Application-level stream credit window (ANDROID-BINDER §4 / ADR-006).
 *
 * - [ownerId] scopes credit to one stream (requestId / subscriptionId)
 * - [streamEpoch] partitions credit; epoch bump resets outstanding accounting
 * - Grants are **monotonic increments** within an epoch, never above [hardCapEvents]
 * - ACK advances half-open `[0, seqToExclusive)` consumption and frees outstanding events
 * - Binder oneway return ≠ delivery; only [ack] / [grantCredit] affect the window
 *
 * Sequence ranges are half-open `[seqFrom, seqTo)` per StreamBatch catalog type.
 */
class StreamCreditWindow(
    val ownerId: String,
    hardCapEvents: Long = DEFAULT_HARD_CAP_EVENTS,
    hardCapBytes: Long = DEFAULT_HARD_CAP_BYTES,
) {
    init {
        require(ownerId.isNotEmpty()) { "ownerId must be non-empty" }
        require(hardCapEvents > 0L) { "hardCapEvents must be positive" }
        require(hardCapBytes > 0L) { "hardCapBytes must be positive" }
    }

    val hardCapEvents: Long = hardCapEvents
    val hardCapBytes: Long = hardCapBytes

    private val lock = Any()

    private var streamEpoch: Long = 0L
    /** Cumulative events granted for the current epoch (monotonic). */
    private var grantedEvents: Long = 0L
    private var grantedBytes: Long = 0L
    /** Highest exclusive seq end that has been accepted for delivery (sent). */
    private var sentSeqToExclusive: Long = 0L
    /** Highest exclusive seq end acknowledged by the client (ACK). */
    private var ackedSeqToExclusive: Long = 0L
    /** Bytes of events currently outstanding (sent but not acked). */
    private var outstandingBytes: Long = 0L
    /** Per-seq byte sizes for outstanding range (for accurate ACK free). */
    private val outstandingByteBySeq = linkedMapOf<Long, Long>()

    fun snapshot(): StreamCreditSnapshot = synchronized(lock) {
        StreamCreditSnapshot(
            ownerId = ownerId,
            streamEpoch = streamEpoch,
            grantedEvents = grantedEvents,
            grantedBytes = grantedBytes,
            sentSeqToExclusive = sentSeqToExclusive,
            ackedSeqToExclusive = ackedSeqToExclusive,
            availableEvents = availableEventsUnlocked(),
            availableBytes = availableBytesUnlocked(),
            hardCapEvents = hardCapEvents,
            hardCapBytes = hardCapBytes,
        )
    }

    fun currentEpoch(): Long = synchronized(lock) { streamEpoch }

    fun sentToExclusive(): Long = synchronized(lock) { sentSeqToExclusive }

    fun ackedToExclusive(): Long = synchronized(lock) { ackedSeqToExclusive }

    /**
     * Grant additional credit (monotonic). Returns actual granted delta after cap clamp.
     * Bumping [epoch] above current resets outstanding accounting for the new epoch.
     */
    fun grantCredit(eventCredit: Long, byteCredit: Long = 0L, epoch: Long? = null): GrantResult {
        require(eventCredit >= 0L) { "eventCredit must be non-negative" }
        require(byteCredit >= 0L) { "byteCredit must be non-negative" }
        return synchronized(lock) {
            if (epoch != null) {
                if (epoch < streamEpoch) {
                    return@synchronized GrantResult(
                        accepted = false,
                        streamEpoch = streamEpoch,
                        grantedEventsDelta = 0L,
                        grantedBytesDelta = 0L,
                        reason = "stale streamEpoch",
                    )
                }
                if (epoch > streamEpoch) {
                    // New epoch: reset windows (ANDROID-BINDER: credit bound to stream epoch).
                    streamEpoch = epoch
                    grantedEvents = 0L
                    grantedBytes = 0L
                    sentSeqToExclusive = 0L
                    ackedSeqToExclusive = 0L
                    outstandingBytes = 0L
                    outstandingByteBySeq.clear()
                }
            }
            val roomEvents = (hardCapEvents - grantedEvents).coerceAtLeast(0L)
            val roomBytes = (hardCapBytes - grantedBytes).coerceAtLeast(0L)
            val takeEvents = minOf(eventCredit, roomEvents)
            val takeBytes = minOf(byteCredit, roomBytes)
            grantedEvents += takeEvents
            grantedBytes += takeBytes
            GrantResult(
                accepted = true,
                streamEpoch = streamEpoch,
                grantedEventsDelta = takeEvents,
                grantedBytesDelta = takeBytes,
                reason = if (takeEvents < eventCredit || takeBytes < byteCredit) "clamped_to_hard_cap" else null,
            )
        }
    }

    /**
     * Reserve capacity for a batch about to be delivered.
     * [seqFrom] must equal current [sentSeqToExclusive] (contiguous half-open).
     */
    fun tryConsumeForSend(seqFrom: Long, eventCount: Int, approxBytes: Long): ConsumeResult {
        require(eventCount >= 0) { "eventCount must be non-negative" }
        require(approxBytes >= 0L) { "approxBytes must be non-negative" }
        return synchronized(lock) {
            if (eventCount == 0) {
                return@synchronized ConsumeResult(accepted = true, streamEpoch = streamEpoch, seqToExclusive = seqFrom)
            }
            if (seqFrom != sentSeqToExclusive) {
                return@synchronized ConsumeResult(
                    accepted = false,
                    streamEpoch = streamEpoch,
                    seqToExclusive = sentSeqToExclusive,
                    reason = "non_contiguous_seqFrom",
                )
            }
            if (eventCount.toLong() > availableEventsUnlocked()) {
                return@synchronized ConsumeResult(
                    accepted = false,
                    streamEpoch = streamEpoch,
                    seqToExclusive = sentSeqToExclusive,
                    reason = "insufficient_event_credit",
                )
            }
            if (approxBytes > availableBytesUnlocked() && grantedBytes > 0L) {
                // Byte credit enforced only when any byte credit has been granted.
                return@synchronized ConsumeResult(
                    accepted = false,
                    streamEpoch = streamEpoch,
                    seqToExclusive = sentSeqToExclusive,
                    reason = "insufficient_byte_credit",
                )
            }
            val seqTo = seqFrom + eventCount
            val perEvent = if (eventCount > 0) approxBytes / eventCount else 0L
            var remainder = if (eventCount > 0) approxBytes % eventCount else 0L
            for (seq in seqFrom until seqTo) {
                val b = perEvent + if (remainder > 0) {
                    remainder--
                    1L
                } else {
                    0L
                }
                outstandingByteBySeq[seq] = b
                outstandingBytes += b
            }
            sentSeqToExclusive = seqTo
            ConsumeResult(accepted = true, streamEpoch = streamEpoch, seqToExclusive = seqTo)
        }
    }

    /**
     * Application ACK (half-open exclusive end). Idempotent when not advancing;
     * cannot exceed [sentSeqToExclusive]; cannot go backward; stale epoch rejected.
     */
    fun ack(epoch: Long, seqToExclusive: Long): AckResult {
        require(seqToExclusive >= 0L) { "seqToExclusive must be non-negative" }
        return synchronized(lock) {
            if (epoch != streamEpoch) {
                return@synchronized AckResult(
                    accepted = false,
                    streamEpoch = streamEpoch,
                    ackedSeqToExclusive = ackedSeqToExclusive,
                    advanced = false,
                    reason = if (epoch < streamEpoch) "stale streamEpoch" else "future streamEpoch",
                )
            }
            if (seqToExclusive < ackedSeqToExclusive) {
                // Backward ACK ignored as no-op (idempotent / fail soft for race).
                return@synchronized AckResult(
                    accepted = true,
                    streamEpoch = streamEpoch,
                    ackedSeqToExclusive = ackedSeqToExclusive,
                    advanced = false,
                    reason = "non_monotonic_ack_ignored",
                )
            }
            if (seqToExclusive > sentSeqToExclusive) {
                return@synchronized AckResult(
                    accepted = false,
                    streamEpoch = streamEpoch,
                    ackedSeqToExclusive = ackedSeqToExclusive,
                    advanced = false,
                    reason = "ack_beyond_sent",
                )
            }
            if (seqToExclusive == ackedSeqToExclusive) {
                return@synchronized AckResult(
                    accepted = true,
                    streamEpoch = streamEpoch,
                    ackedSeqToExclusive = ackedSeqToExclusive,
                    advanced = false,
                    reason = null,
                )
            }
            // Free outstanding for [acked, seqToExclusive)
            var freed = 0L
            val iter = outstandingByteBySeq.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.key >= seqToExclusive) break
                if (e.key >= ackedSeqToExclusive) {
                    freed += e.value
                    outstandingBytes -= e.value
                    iter.remove()
                } else {
                    iter.remove()
                }
            }
            ackedSeqToExclusive = seqToExclusive
            AckResult(
                accepted = true,
                streamEpoch = streamEpoch,
                ackedSeqToExclusive = ackedSeqToExclusive,
                advanced = true,
                bytesFreed = freed,
            )
        }
    }

    private fun availableEventsUnlocked(): Long {
        val outstanding = (sentSeqToExclusive - ackedSeqToExclusive).coerceAtLeast(0L)
        return (grantedEvents - outstanding).coerceAtLeast(0L)
    }

    private fun availableBytesUnlocked(): Long {
        if (grantedBytes <= 0L) return Long.MAX_VALUE / 4
        return (grantedBytes - outstandingBytes).coerceAtLeast(0L)
    }

    companion object {
        /** Conservative default: keep binder batches small (ANDROID-BINDER budget). */
        const val DEFAULT_HARD_CAP_EVENTS: Long = 256L
        const val DEFAULT_HARD_CAP_BYTES: Long = 256L * 1024L
        const val DEFAULT_INITIAL_EVENT_CREDIT: Long = 32L
        const val DEFAULT_INITIAL_BYTE_CREDIT: Long = 64L * 1024L
    }
}

data class StreamCreditSnapshot(
    val ownerId: String,
    val streamEpoch: Long,
    val grantedEvents: Long,
    val grantedBytes: Long,
    val sentSeqToExclusive: Long,
    val ackedSeqToExclusive: Long,
    val availableEvents: Long,
    val availableBytes: Long,
    val hardCapEvents: Long,
    val hardCapBytes: Long,
)

data class GrantResult(
    val accepted: Boolean,
    val streamEpoch: Long,
    val grantedEventsDelta: Long,
    val grantedBytesDelta: Long,
    val reason: String? = null,
)

data class ConsumeResult(
    val accepted: Boolean,
    val streamEpoch: Long,
    val seqToExclusive: Long,
    val reason: String? = null,
)

data class AckResult(
    val accepted: Boolean,
    val streamEpoch: Long,
    val ackedSeqToExclusive: Long,
    val advanced: Boolean,
    val bytesFreed: Long = 0L,
    val reason: String? = null,
)
