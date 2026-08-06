package com.omnillm.core.resource

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-dimension conservation properties (Q-004 / Q-012 / CORE-RESOURCE).
 *
 * Q-004: request terminal preserves resident model/KV accounting —
 *        conversion keeps steady allocation; peak remainder releases.
 * Q-012: returned context/resource limits must remain admissible on every dimension.
 */
class ResourceConservationPropertyTest {

    private fun multi(
        anon: Long = 0L,
        file: Long = 0L,
        gpu: Long = 0L,
        threads: Long = 0L,
        fd: Long = 0L,
        disk: Long = 0L,
    ): ResourceVector =
        ResourceVector(
            cpuAnonBytes = anon,
            cpuFileBytes = file,
            gpuDedicatedBytes = gpu,
            nativeThreads = threads,
            fileDescriptors = fd,
            temporaryDiskBytes = disk,
        )

    @Test
    fun multiDimension_conversionConservation_holds() {
        val before = multi(anon = 1_000L, file = 200L, gpu = 500L, threads = 8L, fd = 16L, disk = 64L)
        val converted = multi(anon = 400L, file = 100L, gpu = 500L, threads = 4L, fd = 8L, disk = 0L)
        val proof = Conservation.splitConversion(before, converted)
        assertTrue(proof is OmniResult.Ok)
        val p = (proof as OmniResult.Ok).value
        assertEquals(600L, p.releasedRemainder.cpuAnonBytes)
        assertEquals(100L, p.releasedRemainder.cpuFileBytes)
        assertEquals(0L, p.releasedRemainder.gpuDedicatedBytes)
        assertEquals(4L, p.releasedRemainder.nativeThreads)
        assertEquals(8L, p.releasedRemainder.fileDescriptors)
        assertEquals(64L, p.releasedRemainder.temporaryDiskBytes)
        // Round-trip: converted + remainder == before
        val check = Conservation.checkConversion(before, converted, p.releasedRemainder)
        assertTrue(check is OmniResult.Ok)
    }

    @Test
    fun q004_requestTerminal_releasesPeakRemainder_keepsSteadyAllocation() {
        // Peak reserved for inference; convert to steady resident (model/KV).
        val peakReserved = multi(anon = 80_000L, threads = 8L)
        val steadyResident = multi(anon = 40_000L, threads = 4L)
        val split = Conservation.splitConversion(peakReserved, steadyResident)
        assertTrue(split is OmniResult.Ok)
        val remainder = (split as OmniResult.Ok).value.releasedRemainder
        assertEquals(40_000L, remainder.cpuAnonBytes)
        assertEquals(4L, remainder.nativeThreads)

        // After terminal of the request: allocation still holds steady; remainder free.
        // Capacity identity: reserved(0) + allocated(steady) + free = capacity.
        val capacity = multi(anon = 100_000L, threads = 16L)
        val free = multi(anon = 60_000L, threads = 12L) // capacity - allocated
        val cap = Conservation.checkCapacity(
            reserved = ResourceVector.ZERO,
            allocated = steadyResident,
            free = free,
            capacity = capacity,
        )
        assertTrue(cap is OmniResult.Ok)
    }

    @Test
    fun multiDimension_capacityMismatchOnAnyAxis_failsClosed() {
        val reserved = multi(anon = 10L, threads = 2L)
        val allocated = multi(anon = 20L, threads = 2L)
        val free = multi(anon = 70L, threads = 12L)
        // capacity threads wrong (should be 16, is 15)
        val capacity = multi(anon = 100L, threads = 15L)
        val result = Conservation.checkCapacity(reserved, allocated, free, capacity)
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (result as OmniResult.Err).error.code)
    }

    @Test
    fun q012_admissibleClamp_neverExceedsHardCapOnAnyDimension() {
        // Candidate request context/resource envelope clamped by hard cap (CFG-001 style).
        val requestPeak = multi(anon = 8_192L)
        val hardCap = multi(anon = 6_144L)
        // Admission: peak must be dominated by hard cap (else reject or clamp).
        assertTrue(!hardCap.dominates(requestPeak))
        val clamped = multi(anon = minOf(requestPeak.cpuAnonBytes, hardCap.cpuAnonBytes))
        assertTrue(hardCap.dominates(clamped))
        assertEquals(6_144L, clamped.cpuAnonBytes)
    }

    @Test
    fun arithmetic_plusMinusRoundTrip_multiDimension() {
        val a = multi(anon = 100L, file = 50L, gpu = 10L, threads = 2L)
        val b = multi(anon = 30L, file = 20L, gpu = 5L, threads = 1L)
        val sum = ResourceArithmetic.tryPlus(a, b)
        assertTrue(sum is OmniResult.Ok)
        val back = ResourceArithmetic.tryMinus((sum as OmniResult.Ok).value, b)
        assertTrue(back is OmniResult.Ok)
        assertEquals(a, (back as OmniResult.Ok).value)
    }

    @Test
    fun envelope_peakDominatesSteady() {
        val steady = multi(anon = 10L, threads = 1L)
        val peak = multi(anon = 20L, threads = 2L)
        val envelope = ResourceEnvelope(steady = steady, peak = peak)
        assertTrue(envelope.peak.dominates(envelope.steady))
        assertEquals(20L, envelope.peak.cpuAnonBytes)
    }

    @Test
    fun zeroVector_conservesTrivially() {
        val z = ResourceVector.ZERO
        val proof = Conservation.splitConversion(z, z)
        assertTrue(proof is OmniResult.Ok)
        assertEquals(z, (proof as OmniResult.Ok).value.releasedRemainder)
        val cap = Conservation.checkCapacity(z, z, multi(anon = 1L), multi(anon = 1L))
        assertTrue(cap is OmniResult.Ok)
    }

    @Test
    fun convertExceedsReservedOnSecondaryDimension_admissionRejected() {
        val before = multi(anon = 100L, threads = 2L)
        val converted = multi(anon = 50L, threads = 4L) // threads exceed
        val result = Conservation.splitConversion(before, converted)
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, (result as OmniResult.Err).error.code)
    }
}
