package com.omnillm.core.resource

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniError

/**
 * Checked ResourceVector arithmetic that fail-closes to catalog errors
 * (CORE-RESOURCE §2: overflow / underflow → ADMISSION_REJECTED or INVALID_REQUEST;
 * never wraparound).
 */
object ResourceArithmetic {

    fun tryPlus(a: ResourceVector, b: ResourceVector): OmniResult<ResourceVector> =
        try {
            OmniResult.ok(a.plus(b))
        } catch (_: ArithmeticException) {
            OmniResult.err(
                OmniError.ADMISSION_REJECTED(
                    message = "ResourceVector addition overflow",
                    details = mapOf("op" to "plus"),
                ),
            )
        } catch (e: IllegalArgumentException) {
            OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = e.message ?: "ResourceVector plus produced invalid vector",
                    details = mapOf("op" to "plus"),
                ),
            )
        }

    fun tryMinus(a: ResourceVector, b: ResourceVector): OmniResult<ResourceVector> =
        try {
            OmniResult.ok(a.minus(b))
        } catch (_: ArithmeticException) {
            // Underflow of non-negative dimensions surfaces as ArithmeticException
            // (subtractExact) or IllegalArgumentException (negative result in init).
            OmniResult.err(
                OmniError.ADMISSION_REJECTED(
                    message = "ResourceVector subtraction overflow/underflow",
                    details = mapOf("op" to "minus"),
                ),
            )
        } catch (e: IllegalArgumentException) {
            OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = e.message ?: "ResourceVector minus produced negative dimension",
                    details = mapOf("op" to "minus"),
                ),
            )
        }

    /**
     * Scale every dimension by [factor] with checked multiply.
     * Rejects negative factors (not a valid charge scale).
     */
    fun tryScale(v: ResourceVector, factor: Long): OmniResult<ResourceVector> {
        if (factor < 0L) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "ResourceVector scale factor must be non-negative",
                    details = mapOf("factor" to factor.toString()),
                ),
            )
        }
        return try {
            OmniResult.ok(
                ResourceVector(
                    cpuAnonBytes = Math.multiplyExact(v.cpuAnonBytes, factor),
                    cpuFileBytes = Math.multiplyExact(v.cpuFileBytes, factor),
                    sharedMemoryChargeBytes = Math.multiplyExact(v.sharedMemoryChargeBytes, factor),
                    gpuDedicatedBytes = Math.multiplyExact(v.gpuDedicatedBytes, factor),
                    gpuSharedBytes = Math.multiplyExact(v.gpuSharedBytes, factor),
                    npuBytes = Math.multiplyExact(v.npuBytes, factor),
                    nativeThreads = Math.multiplyExact(v.nativeThreads, factor),
                    fileDescriptors = Math.multiplyExact(v.fileDescriptors, factor),
                    temporaryDiskBytes = Math.multiplyExact(v.temporaryDiskBytes, factor),
                    networkBytesInFlight = Math.multiplyExact(v.networkBytesInFlight, factor),
                ),
            )
        } catch (_: ArithmeticException) {
            OmniResult.err(
                OmniError.ADMISSION_REJECTED(
                    message = "ResourceVector scale overflow",
                    details = mapOf("op" to "scale", "factor" to factor.toString()),
                ),
            )
        }
    }

    /** Build envelope with fail-closed peak>=steady check mapped to INVALID_REQUEST. */
    fun tryEnvelope(steady: ResourceVector, peak: ResourceVector): OmniResult<ResourceEnvelope> =
        try {
            OmniResult.ok(ResourceEnvelope(steady, peak))
        } catch (e: IllegalArgumentException) {
            OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = e.message ?: "ResourceEnvelope.peak must dominate steady",
                    details = mapOf("op" to "envelope"),
                ),
            )
        }
}
