package com.omnillm.android.runtimeservice.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AIDL stream ACK + credit semantics (ADR-006 / ANDROID-BINDER §4).
 *
 * Host unit tests exercise [StreamCreditWindow] without Android Binder stubs
 * (`IOmniStreamCallback.Stub` requires device/Robolectric). [StreamDeliveryEngine]
 * is the same credit gate + oneway flush; credit contract is the durable delivery rule.
 *
 * Delivery guarantee: [com.omnillm.core.errors.TransportDeliveryGuarantee.AIDL_STREAM]
 * — oneway callback ≠ application consumption; only ACK frees credit.
 */
class StreamDeliveryAckCreditTest {

    @Test
    fun creditGatesSend_ackFreesWindow_forMoreEvents() {
        val credit = StreamCreditWindow(
            ownerId = "req-ack-1",
            hardCapEvents = 64L,
            hardCapBytes = 256_000L,
        )
        credit.grantCredit(eventCredit = 2L, byteCredit = 10_000L, epoch = 0L)

        val c1 = credit.tryConsumeForSend(0L, 2, 100L)
        assertTrue(c1.accepted)
        assertEquals(2L, c1.seqToExclusive)
        assertEquals(0L, credit.snapshot().availableEvents)

        // Without ACK, cannot send more.
        val blocked = credit.tryConsumeForSend(2L, 1, 10L)
        assertFalse(blocked.accepted)

        val ack = credit.ack(0L, 2L)
        assertTrue(ack.accepted)
        assertTrue(ack.advanced)
        assertEquals(2L, credit.snapshot().availableEvents)

        val c2 = credit.tryConsumeForSend(2L, 2, 50L)
        assertTrue(c2.accepted)
        assertEquals(4L, c2.seqToExclusive)
    }

    @Test
    fun halfOpenAckRanges_areIdempotentAndRejectBeyondSent() {
        val credit = StreamCreditWindow(ownerId = "req-ack-2", hardCapEvents = 16L)
        credit.grantCredit(eventCredit = 8L, epoch = 0L)
        assertTrue(credit.tryConsumeForSend(0L, 4, 40L).accepted)

        val beyond = credit.ack(0L, 99L)
        assertFalse(beyond.accepted)
        assertEquals("ack_beyond_sent", beyond.reason)

        val a1 = credit.ack(0L, 4L)
        assertTrue(a1.accepted)
        assertTrue(a1.advanced)
        val a2 = credit.ack(0L, 4L)
        assertTrue(a2.accepted)
        assertFalse(a2.advanced)

        // After full ACK of sent, available returns to granted.
        assertEquals(8L, credit.snapshot().availableEvents)
    }

    @Test
    fun streamCreditWindow_establishesAidlStreamDeliveryContract() {
        val g = com.omnillm.core.errors.TransportDeliveryGuarantee.AIDL_STREAM
        assertTrue(g.establishesClientDeliveredCheckpoint)
        assertTrue(g.supportsQueryAfterReplyLoss)

        val http = com.omnillm.core.errors.TransportDeliveryGuarantee.HTTP_SSE
        assertFalse(http.establishesClientDeliveredCheckpoint)

        // Labels match runtime-service mirror.
        assertEquals(
            g.label,
            com.omnillm.android.runtimeservice.transport.TransportDeliveryGuarantees.AIDL_STREAM.label,
        )
    }
}
