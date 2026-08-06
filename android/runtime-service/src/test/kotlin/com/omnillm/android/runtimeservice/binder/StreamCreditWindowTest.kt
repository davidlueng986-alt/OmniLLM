package com.omnillm.android.runtimeservice.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANDROID-BINDER §4 / ADR-006 credit + half-open ACK semantics.
 */
class StreamCreditWindowTest {

    @Test
    fun grantIsMonotonicAndClampedToHardCap() {
        val w = StreamCreditWindow(ownerId = "req-1", hardCapEvents = 10L, hardCapBytes = 1000L)
        val g1 = w.grantCredit(eventCredit = 8L, byteCredit = 100L, epoch = 0L)
        assertTrue(g1.accepted)
        assertEquals(8L, g1.grantedEventsDelta)

        val g2 = w.grantCredit(eventCredit = 5L, byteCredit = 0L)
        assertTrue(g2.accepted)
        assertEquals(2L, g2.grantedEventsDelta) // 10 - 8
        assertEquals("clamped_to_hard_cap", g2.reason)

        val snap = w.snapshot()
        assertEquals(10L, snap.grantedEvents)
        assertEquals(10L, snap.availableEvents)
    }

    @Test
    fun sendConsumesCreditAndAckFreesWindow() {
        val w = StreamCreditWindow(ownerId = "req-2", hardCapEvents = 32L, hardCapBytes = 64_000L)
        w.grantCredit(eventCredit = 4L, byteCredit = 400L, epoch = 0L)

        val c1 = w.tryConsumeForSend(seqFrom = 0L, eventCount = 2, approxBytes = 100L)
        assertTrue(c1.accepted)
        assertEquals(2L, c1.seqToExclusive)
        assertEquals(2L, w.snapshot().availableEvents)

        // Cannot send beyond credit.
        val blocked = w.tryConsumeForSend(seqFrom = 2L, eventCount = 3, approxBytes = 10L)
        assertFalse(blocked.accepted)
        assertEquals("insufficient_event_credit", blocked.reason)

        // ACK free 2 → available 4 again? granted 4, outstanding 0 after full ack.
        val ack = w.ack(epoch = 0L, seqToExclusive = 2L)
        assertTrue(ack.accepted)
        assertTrue(ack.advanced)
        assertEquals(4L, w.snapshot().availableEvents)

        val c2 = w.tryConsumeForSend(seqFrom = 2L, eventCount = 2, approxBytes = 50L)
        assertTrue(c2.accepted)
        assertEquals(4L, c2.seqToExclusive)
    }

    @Test
    fun ackBeyondSentRejected_duplicateAckIdempotent() {
        val w = StreamCreditWindow(ownerId = "req-3")
        w.grantCredit(eventCredit = 10L, epoch = 0L)
        w.tryConsumeForSend(0L, 3, 30L)

        val over = w.ack(0L, 99L)
        assertFalse(over.accepted)
        assertEquals("ack_beyond_sent", over.reason)

        val a1 = w.ack(0L, 3L)
        assertTrue(a1.accepted)
        assertTrue(a1.advanced)
        val a2 = w.ack(0L, 3L)
        assertTrue(a2.accepted)
        assertFalse(a2.advanced)
    }

    @Test
    fun staleEpochRejected_newEpochResets() {
        val w = StreamCreditWindow(ownerId = "req-4", hardCapEvents = 20L)
        w.grantCredit(eventCredit = 5L, epoch = 0L)
        w.tryConsumeForSend(0L, 2, 10L)

        val stale = w.grantCredit(eventCredit = 1L, epoch = -1L)
        // epoch -1 < 0
        assertFalse(stale.accepted)

        val bump = w.grantCredit(eventCredit = 3L, epoch = 1L)
        assertTrue(bump.accepted)
        assertEquals(1L, bump.streamEpoch)
        assertEquals(3L, bump.grantedEventsDelta)
        assertEquals(0L, w.sentToExclusive())
        assertEquals(0L, w.ackedToExclusive())
    }

    @Test
    fun nonContiguousSendRejected() {
        val w = StreamCreditWindow(ownerId = "req-5")
        w.grantCredit(eventCredit = 10L, epoch = 0L)
        w.tryConsumeForSend(0L, 1, 8L)
        val bad = w.tryConsumeForSend(seqFrom = 5L, eventCount = 1, approxBytes = 8L)
        assertFalse(bad.accepted)
        assertEquals("non_contiguous_seqFrom", bad.reason)
    }
}
