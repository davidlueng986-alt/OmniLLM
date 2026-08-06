package com.omnillm.core.resource

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Conservation, overflow fail-closed, OperatingConstraint separation (CORE-RESOURCE / ADR-003).
 */
class ResourceConservationTest {

    @Test
    fun conversionConservation_holds() {
        val before = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 4L)
        val converted = ResourceVector(cpuAnonBytes = 60L, nativeThreads = 2L)
        val remainder = ResourceVector(cpuAnonBytes = 40L, nativeThreads = 2L)

        val proof = Conservation.checkConversion(before, converted, remainder)
        assertTrue(proof is OmniResult.Ok)
        assertEquals(before, (proof as OmniResult.Ok).value.beforeReserved)
    }

    @Test
    fun splitConversion_producesConservedRemainder() {
        val before = ResourceVector(cpuAnonBytes = 100L, gpuDedicatedBytes = 10L)
        val converted = ResourceVector(cpuAnonBytes = 30L, gpuDedicatedBytes = 10L)
        val proof = Conservation.splitConversion(before, converted)
        assertTrue(proof is OmniResult.Ok)
        val p = (proof as OmniResult.Ok).value
        assertEquals(70L, p.releasedRemainder.cpuAnonBytes)
        assertEquals(0L, p.releasedRemainder.gpuDedicatedBytes)
    }

    @Test
    fun conversionMismatch_failsClosedStateConflict() {
        val before = ResourceVector(cpuAnonBytes = 100L)
        val converted = ResourceVector(cpuAnonBytes = 60L)
        val remainder = ResourceVector(cpuAnonBytes = 30L) // 60+30 != 100
        val result = Conservation.checkConversion(before, converted, remainder)
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (result as OmniResult.Err).error.code)
    }

    @Test
    fun convertedExceedsReserved_admissionRejected() {
        val before = ResourceVector(cpuAnonBytes = 10L)
        val converted = ResourceVector(cpuAnonBytes = 20L)
        val result = Conservation.splitConversion(before, converted)
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (result as OmniResult.Err).error.code)
    }

    @Test
    fun capacityConservation_holds() {
        val reserved = ResourceVector(cpuAnonBytes = 20L)
        val allocated = ResourceVector(cpuAnonBytes = 30L)
        val free = ResourceVector(cpuAnonBytes = 50L)
        val capacity = ResourceVector(cpuAnonBytes = 100L)
        val result = Conservation.checkCapacity(reserved, allocated, free, capacity)
        assertTrue(result is OmniResult.Ok)
    }

    @Test
    fun capacityMismatch_failsClosed() {
        val result = Conservation.checkCapacity(
            reserved = ResourceVector(cpuAnonBytes = 20L),
            allocated = ResourceVector(cpuAnonBytes = 30L),
            free = ResourceVector(cpuAnonBytes = 40L),
            capacity = ResourceVector(cpuAnonBytes = 100L),
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (result as OmniResult.Err).error.code)
    }

    @Test
    fun overflowOnPlus_failsClosedAdmissionRejected() {
        val a = ResourceVector(cpuAnonBytes = Long.MAX_VALUE)
        val b = ResourceVector(cpuAnonBytes = 1L)
        val result = ResourceArithmetic.tryPlus(a, b)
        assertTrue(result is OmniResult.Err)
        val err = (result as OmniResult.Err).error
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, err.code)
        assertTrue(err.retryable)
    }

    @Test
    fun underflowOnMinus_failsClosed() {
        val a = ResourceVector(cpuAnonBytes = 1L)
        val b = ResourceVector(cpuAnonBytes = 2L)
        val result = ResourceArithmetic.tryMinus(a, b)
        assertTrue(result is OmniResult.Err)
        // Negative dimension → INVALID_REQUEST (vector invariant) or ADMISSION_REJECTED.
        val code = (result as OmniResult.Err).error.code
        assertTrue(
            code == OmniErrorCode.INVALID_REQUEST || code == OmniErrorCode.ADMISSION_REJECTED,
        )
    }

    @Test
    fun scaleOverflow_failsClosed() {
        val v = ResourceVector(cpuAnonBytes = Long.MAX_VALUE / 2 + 1)
        val result = ResourceArithmetic.tryScale(v, 3L)
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (result as OmniResult.Err).error.code)
    }

    @Test
    fun negativeScale_invalidRequest() {
        val result = ResourceArithmetic.tryScale(ResourceVector(cpuAnonBytes = 10L), -1L)
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (result as OmniResult.Err).error.code)
    }

    @Test
    fun operatingConstraint_notMixedIntoVector() {
        // Compile-time / structural separation: OperatingConstraint has no byte fields
        // that could be summed into ResourceVector.DIMENSION_NAMES.
        val constraint = OperatingConstraint(
            maxContinuousComputeMs = 5_000L,
            thermalClass = "SEVERE",
            requiresForeground = true,
        )
        val vector = ResourceVector(cpuAnonBytes = 1024L)
        // ResourceEnvelope only carries ResourceVector dimensions.
        val envelope = ResourceEnvelope(steady = vector, peak = vector)
        assertEquals(1024L, envelope.peak.cpuAnonBytes)
        assertEquals("SEVERE", constraint.thermalClass)
        assertTrue(ResourceVector.DIMENSION_NAMES.none { it == "thermalClass" })
        assertTrue(ResourceVector.DIMENSION_NAMES.none { it == "maxContinuousComputeMs" })
    }

    @Test
    fun reservationAndAllocation_areDistinctTypes() {
        val envelope = ResourceEnvelope(
            steady = ResourceVector(cpuAnonBytes = 50L),
            peak = ResourceVector(cpuAnonBytes = 100L),
        )
        val reservation = Reservation(
            reservationId = ReservationId.parse("rsv-1"),
            principalId = "principal-a",
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            nonce = "n1",
            deadlineMonotonic = 1_000_000L,
            envelope = envelope,
            operatingConstraint = OperatingConstraint(thermalClass = "NORMAL"),
        )
        val allocation = AllocationHandle(
            allocationHandleId = AllocationHandleId.parse("alloc-1"),
            principalId = "principal-a",
            runtimeEpoch = 1L,
            resident = envelope.steady,
            sourceReservationId = reservation.reservationId,
        )
        // ADR-003: terminal of request would release reservation remainder, not allocation.
        assertEquals(100L, reservation.reservedVector.cpuAnonBytes)
        assertEquals(50L, allocation.resident.cpuAnonBytes)
        assertEquals(reservation.reservationId, allocation.sourceReservationId)
    }

    @Test
    fun resourceVectorRejectsNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            ResourceVector(cpuAnonBytes = -1L)
        }
    }
}
