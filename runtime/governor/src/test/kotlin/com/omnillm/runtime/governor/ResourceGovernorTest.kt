package com.omnillm.runtime.governor

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.OperatingConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resource Governor conservation, overflow rejection, and eviction barrier
 * (CORE-RESOURCE, ADR-003, ARCH resource conservation).
 */
class ResourceGovernorTest {

    private fun capacity(anon: Long = 1_000L, threads: Long = 16L) =
        ResourceVector(cpuAnonBytes = anon, nativeThreads = threads)

    private fun governor(
        cap: ResourceVector = capacity(),
        safety: ResourceVector = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 2L),
        clock: () -> Long = { 1_000L },
    ): ResourceGovernor =
        ResourceGovernor(
            capacity = cap,
            safetyMargin = safety,
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            clockMonotonic = clock,
            idSource = { java.util.UUID.randomUUID().toString() },
        )

    // ------------------------------------------------------------------
    // Reserve / conservation
    // ------------------------------------------------------------------

    @Test
    fun reserve_chargesReservedAndPreservesCapacityIdentity() {
        val g = governor()
        val peak = ResourceVector(cpuAnonBytes = 200L, nativeThreads = 4L)
        val result = g.reserve(peak, owner = "principal-a", deadlineMonotonic = 10_000L)
        assertTrue(result is OmniResult.Ok)

        val snap = g.snapshot()
        assertEquals(200L, snap.reserved.cpuAnonBytes)
        assertEquals(4L, snap.reserved.nativeThreads)
        assertEquals(0L, snap.allocated.cpuAnonBytes)
        // free = capacity - reserved
        assertEquals(800L, snap.free.cpuAnonBytes)
        assertEquals(12L, snap.free.nativeThreads)

        val inv = snap.checkInvariants()
        assertTrue(inv is OmniResult.Ok)
    }

    @Test
    fun reserve_rejectsWhenInsufficientHeadroomAfterSafetyMargin() {
        // capacity 1000, safety 100 → admittable 900; request 901 fails
        val g = governor(
            cap = ResourceVector(cpuAnonBytes = 1_000L),
            safety = ResourceVector(cpuAnonBytes = 100L),
        )
        val ok = g.reserve(
            ResourceVector(cpuAnonBytes = 900L),
            owner = "p",
            deadlineMonotonic = 10_000L,
        )
        assertTrue(ok is OmniResult.Ok)

        val g2 = governor(
            cap = ResourceVector(cpuAnonBytes = 1_000L),
            safety = ResourceVector(cpuAnonBytes = 100L),
        )
        val rejected = g2.reserve(
            ResourceVector(cpuAnonBytes = 901L),
            owner = "p",
            deadlineMonotonic = 10_000L,
        )
        assertTrue(rejected is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (rejected as OmniResult.Err).error.code)
        // Ledger unchanged on reject.
        assertEquals(1_000L, g2.snapshot().free.cpuAnonBytes)
        assertEquals(0L, g2.snapshot().reserved.cpuAnonBytes)
    }

    @Test
    fun reserve_overflowOnCharge_failsClosed() {
        val g = governor(
            cap = ResourceVector(cpuAnonBytes = Long.MAX_VALUE),
            safety = ResourceVector.ZERO,
        )
        // First reservation of MAX_VALUE succeeds (free becomes 0).
        val first = g.reserve(
            ResourceVector(cpuAnonBytes = Long.MAX_VALUE),
            owner = "p",
            deadlineMonotonic = 10_000L,
        )
        assertTrue(first is OmniResult.Ok)

        // Second would need free headroom 1 — ADMISSION_REJECTED (no wraparound).
        val second = g.reserve(
            ResourceVector(cpuAnonBytes = 1L),
            owner = "p2",
            deadlineMonotonic = 10_000L,
        )
        assertTrue(second is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (second as OmniResult.Err).error.code)
    }

    @Test
    fun reserve_rejectsPastDeadline() {
        val g = governor(clock = { 5_000L })
        val result = g.reserve(
            ResourceVector(cpuAnonBytes = 10L),
            owner = "p",
            deadlineMonotonic = 1_000L,
            nowMonotonic = 5_000L,
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.DEADLINE_EXCEEDED, (result as OmniResult.Err).error.code)
    }

    @Test
    fun operatingConstraint_notChargedIntoVectorLedger() {
        val g = governor(
            cap = ResourceVector(cpuAnonBytes = 1_000L),
            safety = ResourceVector.ZERO,
        )
        val constraint =
            OperatingConstraint(
                maxContinuousComputeMs = 5_000L,
                thermalClass = "SEVERE",
                requiresForeground = true,
            )
        val result =
            g.reserve(
                ResourceVector(cpuAnonBytes = 50L),
                owner = "p",
                deadlineMonotonic = 10_000L,
                operatingConstraint = constraint,
            )
        assertTrue(result is OmniResult.Ok)
        val r = (result as OmniResult.Ok).value
        assertEquals("SEVERE", r.operatingConstraint.thermalClass)
        // Only the ResourceVector peak is charged — thermal/power not dimensions.
        assertEquals(50L, g.snapshot().reserved.cpuAnonBytes)
        assertTrue(ResourceVector.DIMENSION_NAMES.none { it == "thermalClass" })
        assertTrue(ResourceVector.DIMENSION_NAMES.none { it == "maxContinuousComputeMs" })
    }

    // ------------------------------------------------------------------
    // Convert reservation → allocation
    // ------------------------------------------------------------------

    @Test
    fun convert_conservesReservedEqualsAllocationPlusRemainder() {
        val g = governor(safety = ResourceVector.ZERO)
        val peak = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 4L)
        val steady = ResourceVector(cpuAnonBytes = 60L, nativeThreads = 2L)
        val envelope = ResourceEnvelope(steady = steady, peak = peak)

        val rsv = g.reserve(
            peak,
            owner = "p",
            deadlineMonotonic = 10_000L,
            envelope = envelope,
        )
        assertTrue(rsv is OmniResult.Ok)
        val reservation = (rsv as OmniResult.Ok).value

        val freeAfterReserve = g.snapshot().free.cpuAnonBytes
        assertEquals(900L, freeAfterReserve) // 1000 - 100

        val conv = g.convert(reservation.reservationId, convertedAllocation = steady)
        assertTrue(conv is OmniResult.Ok)
        val handle = (conv as OmniResult.Ok).value
        assertEquals(60L, handle.resident.cpuAnonBytes)
        assertEquals(2L, handle.resident.nativeThreads)
        assertEquals(reservation.reservationId, handle.sourceReservationId)

        val snap = g.snapshot()
        // reserved fully converted; remainder 40 released to free; allocated = 60
        assertEquals(0L, snap.reserved.cpuAnonBytes)
        assertEquals(60L, snap.allocated.cpuAnonBytes)
        assertEquals(940L, snap.free.cpuAnonBytes) // 900 + 40 remainder
        assertEquals(0L, snap.reserved.nativeThreads)
        assertEquals(2L, snap.allocated.nativeThreads)
        assertEquals(14L, snap.free.nativeThreads)

        assertTrue(snap.checkInvariants() is OmniResult.Ok)
        assertEquals(ReservationFsm.COMMITTED, g.reservationState(reservation.reservationId))
        assertEquals(AllocationFsm.ACTIVE, g.allocationState(handle.allocationHandleId))
    }

    @Test
    fun convert_idempotentByReservationId() {
        val g = governor(safety = ResourceVector.ZERO)
        val peak = ResourceVector(cpuAnonBytes = 50L)
        val rsv = (g.reserve(peak, "p", 10_000L) as OmniResult.Ok).value
        val first = g.convert(rsv.reservationId, ResourceVector(cpuAnonBytes = 50L))
        val second = g.convert(rsv.reservationId, ResourceVector(cpuAnonBytes = 50L))
        assertTrue(first is OmniResult.Ok)
        assertTrue(second is OmniResult.Ok)
        assertEquals(
            (first as OmniResult.Ok).value.allocationHandleId,
            (second as OmniResult.Ok).value.allocationHandleId,
        )
        // No double charge.
        assertEquals(50L, g.snapshot().allocated.cpuAnonBytes)
        assertEquals(0L, g.snapshot().reserved.cpuAnonBytes)
    }

    @Test
    fun convert_exceedsReserved_admissionRejected() {
        val g = governor(safety = ResourceVector.ZERO)
        val rsv =
            (g.reserve(ResourceVector(cpuAnonBytes = 10L), "p", 10_000L) as OmniResult.Ok).value
        val result = g.convert(rsv.reservationId, ResourceVector(cpuAnonBytes = 20L))
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (result as OmniResult.Err).error.code)
        // Ledger unchanged.
        assertEquals(10L, g.snapshot().reserved.cpuAnonBytes)
        assertEquals(0L, g.snapshot().allocated.cpuAnonBytes)
    }

    // ------------------------------------------------------------------
    // Release reservation
    // ------------------------------------------------------------------

    @Test
    fun releaseReservation_returnsChargeToFree() {
        val g = governor(safety = ResourceVector.ZERO)
        val rsv =
            (g.reserve(ResourceVector(cpuAnonBytes = 300L), "p", 10_000L) as OmniResult.Ok).value
        assertEquals(700L, g.snapshot().free.cpuAnonBytes)

        val rel = g.releaseReservation(rsv.reservationId)
        assertTrue(rel is OmniResult.Ok)
        assertEquals(0L, g.snapshot().reserved.cpuAnonBytes)
        assertEquals(1_000L, g.snapshot().free.cpuAnonBytes)
        assertEquals(ReservationFsm.RELEASED, g.reservationState(rsv.reservationId))

        // Idempotent.
        assertTrue(g.releaseReservation(rsv.reservationId) is OmniResult.Ok)
        assertEquals(1_000L, g.snapshot().free.cpuAnonBytes)
    }

    @Test
    fun releaseReservation_doesNotReleaseResidentAllocation() {
        val g = governor(safety = ResourceVector.ZERO)
        val rsv =
            (g.reserve(ResourceVector(cpuAnonBytes = 80L), "p", 10_000L) as OmniResult.Ok).value
        val handle =
            (g.convert(rsv.reservationId, ResourceVector(cpuAnonBytes = 80L)) as OmniResult.Ok).value

        // Terminal release of reservation after commit is no-op for free.
        assertTrue(g.releaseReservation(rsv.reservationId) is OmniResult.Ok)
        assertEquals(80L, g.snapshot().allocated.cpuAnonBytes)
        assertEquals(920L, g.snapshot().free.cpuAnonBytes)
        assertEquals(AllocationFsm.ACTIVE, g.allocationState(handle.allocationHandleId))
    }

    // ------------------------------------------------------------------
    // Eviction barrier — never credit before observed release
    // ------------------------------------------------------------------

    @Test
    fun plannedEviction_doesNotCreditFreeUntilReleaseBarrier() {
        val g = governor(safety = ResourceVector.ZERO)
        val rsv =
            (g.reserve(ResourceVector(cpuAnonBytes = 200L), "p", 10_000L) as OmniResult.Ok).value
        val handle =
            (g.convert(rsv.reservationId, ResourceVector(cpuAnonBytes = 200L)) as OmniResult.Ok)
                .value

        val freeAfterAlloc = g.snapshot().free.cpuAnonBytes
        assertEquals(800L, freeAfterAlloc)
        assertEquals(200L, g.snapshot().allocated.cpuAnonBytes)

        // Drain: free must stay the same.
        assertTrue(g.requestDrain(handle.allocationHandleId) is OmniResult.Ok)
        assertEquals(AllocationFsm.DRAINING, g.allocationState(handle.allocationHandleId))
        assertEquals(freeAfterAlloc, g.snapshot().free.cpuAnonBytes)
        assertEquals(200L, g.snapshot().allocated.cpuAnonBytes)

        // Owner quiescent → RELEASING: still no credit.
        assertTrue(g.markOwnerQuiescent(handle.allocationHandleId) is OmniResult.Ok)
        assertEquals(AllocationFsm.RELEASING, g.allocationState(handle.allocationHandleId))
        assertEquals(freeAfterAlloc, g.snapshot().free.cpuAnonBytes)
        assertEquals(200L, g.snapshot().allocated.cpuAnonBytes)

        // Barrier without observation: fail closed, no credit.
        val blocked =
            g.completeReleaseBarrier(handle.allocationHandleId, nativeBarrierObserved = false)
        assertTrue(blocked is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (blocked as OmniResult.Err).error.code)
        assertEquals(freeAfterAlloc, g.snapshot().free.cpuAnonBytes)
        assertEquals(200L, g.snapshot().allocated.cpuAnonBytes)
        assertEquals(AllocationFsm.RELEASING, g.allocationState(handle.allocationHandleId))

        // Observed barrier: credit free.
        val done =
            g.completeReleaseBarrier(handle.allocationHandleId, nativeBarrierObserved = true)
        assertTrue(done is OmniResult.Ok)
        assertEquals(AllocationFsm.RELEASED, g.allocationState(handle.allocationHandleId))
        assertEquals(0L, g.snapshot().allocated.cpuAnonBytes)
        assertEquals(1_000L, g.snapshot().free.cpuAnonBytes)
        assertTrue(g.snapshot().checkInvariants() is OmniResult.Ok)

        // Idempotent barrier after RELEASED.
        assertTrue(
            g.completeReleaseBarrier(handle.allocationHandleId, nativeBarrierObserved = true)
                is OmniResult.Ok,
        )
        assertEquals(1_000L, g.snapshot().free.cpuAnonBytes)
    }

    @Test
    fun cannotReserveUsingPlannedEvictionAsHeadroom() {
        val g = governor(
            cap = ResourceVector(cpuAnonBytes = 100L),
            safety = ResourceVector.ZERO,
        )
        val rsv =
            (g.reserve(ResourceVector(cpuAnonBytes = 100L), "p", 10_000L) as OmniResult.Ok).value
        val handle =
            (g.convert(rsv.reservationId, ResourceVector(cpuAnonBytes = 100L)) as OmniResult.Ok)
                .value

        // Start planned eviction — free still 0.
        g.requestDrain(handle.allocationHandleId)
        g.markOwnerQuiescent(handle.allocationHandleId)
        assertEquals(0L, g.snapshot().free.cpuAnonBytes)

        val competing =
            g.reserve(ResourceVector(cpuAnonBytes = 1L), owner = "other", deadlineMonotonic = 10_000L)
        assertTrue(competing is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (competing as OmniResult.Err).error.code)

        // After real barrier, admit succeeds.
        g.completeReleaseBarrier(handle.allocationHandleId, nativeBarrierObserved = true)
        val after =
            g.reserve(ResourceVector(cpuAnonBytes = 1L), owner = "other", deadlineMonotonic = 10_000L)
        assertTrue(after is OmniResult.Ok)
    }

    // ------------------------------------------------------------------
    // Ledger pure helpers / overflow
    // ------------------------------------------------------------------

    @Test
    fun ledger_overflowOnPlus_failsClosed() {
        val base =
            ResourceLedger(
                capacity = ResourceVector(cpuAnonBytes = Long.MAX_VALUE),
                reserved = ResourceVector(cpuAnonBytes = Long.MAX_VALUE),
                allocated = ResourceVector.ZERO,
                free = ResourceVector.ZERO,
                safetyMargin = ResourceVector.ZERO,
            )
        // Trying to charge more fails admission (free is 0).
        val charged = base.chargeReservation(ResourceVector(cpuAnonBytes = 1L))
        assertTrue(charged is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (charged as OmniResult.Err).error.code)
    }

    @Test
    fun ledger_convertMismatch_failsClosed() {
        val base =
            (ResourceLedger.empty(ResourceVector(cpuAnonBytes = 100L)) as OmniResult.Ok).value
                .let { (it.chargeReservation(ResourceVector(cpuAnonBytes = 50L)) as OmniResult.Ok).value }

        // 30 + 10 != 50 → conservation fail
        val bad =
            base.convertReservation(
                beforeReserved = ResourceVector(cpuAnonBytes = 50L),
                convertedAllocation = ResourceVector(cpuAnonBytes = 30L),
                releasedRemainder = ResourceVector(cpuAnonBytes = 10L),
            )
        assertTrue(bad is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (bad as OmniResult.Err).error.code)
    }

    @Test
    fun fullLifecycle_capacityIdentityHolds() {
        val g = governor(
            cap = ResourceVector(cpuAnonBytes = 500L, nativeThreads = 8L),
            safety = ResourceVector(cpuAnonBytes = 50L, nativeThreads = 1L),
        )
        assertTrue(g.snapshot().checkInvariants() is OmniResult.Ok)

        val r1 =
            (g.reserve(ResourceVector(cpuAnonBytes = 100L, nativeThreads = 2L), "a", 9_999L)
                as OmniResult.Ok).value
        val r2 =
            (g.reserve(ResourceVector(cpuAnonBytes = 80L, nativeThreads = 1L), "b", 9_999L)
                as OmniResult.Ok).value
        assertTrue(g.snapshot().checkInvariants() is OmniResult.Ok)

        val h1 =
            (g.convert(r1.reservationId, ResourceVector(cpuAnonBytes = 70L, nativeThreads = 1L))
                as OmniResult.Ok).value
        assertTrue(g.snapshot().checkInvariants() is OmniResult.Ok)

        assertTrue(g.releaseReservation(r2.reservationId) is OmniResult.Ok)
        assertTrue(g.snapshot().checkInvariants() is OmniResult.Ok)

        g.requestDrain(h1.allocationHandleId)
        g.markOwnerQuiescent(h1.allocationHandleId)
        // Still conserved while draining/releasing.
        assertTrue(g.snapshot().checkInvariants() is OmniResult.Ok)
        assertEquals(70L, g.snapshot().allocated.cpuAnonBytes)

        g.completeReleaseBarrier(h1.allocationHandleId, nativeBarrierObserved = true)
        val final = g.snapshot()
        assertTrue(final.checkInvariants() is OmniResult.Ok)
        assertEquals(0L, final.reserved.cpuAnonBytes)
        assertEquals(0L, final.allocated.cpuAnonBytes)
        assertEquals(500L, final.free.cpuAnonBytes)
        assertNotNull(h1)
        assertFalse(final.safetyMargin == ResourceVector.ZERO)
    }
}
