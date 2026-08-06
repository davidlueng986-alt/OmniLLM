package com.omnillm.core.resource

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniError

/**
 * Resource ledger conservation helpers (CORE-RESOURCE §4, RESERVATION FSM invariants).
 *
 * Conversion identity:
 *   beforeReserved = convertedAllocation + releasedRemainder
 *
 * Capacity identity (per dimension):
 *   reserved + allocated + free = capacity
 *
 * All arithmetic is checked; overflow / mismatch fail closed.
 */
object Conservation {

    /**
     * Proof that a reservation conversion conserved charge:
     * [beforeReserved] == [convertedAllocation] + [releasedRemainder]
     * (element-wise, checked).
     */
    data class ConversionProof(
        val beforeReserved: ResourceVector,
        val convertedAllocation: ResourceVector,
        val releasedRemainder: ResourceVector,
    )

    /**
     * Verify conversion conservation. On success returns a [ConversionProof];
     * on mismatch / overflow returns a catalog error (fail closed).
     */
    fun checkConversion(
        beforeReserved: ResourceVector,
        convertedAllocation: ResourceVector,
        releasedRemainder: ResourceVector,
    ): OmniResult<ConversionProof> {
        val sum = ResourceArithmetic.tryPlus(convertedAllocation, releasedRemainder)
        when (sum) {
            is OmniResult.Err -> return sum
            is OmniResult.Ok -> {
                if (sum.value != beforeReserved) {
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message =
                                "reservation conversion violated conservation: " +
                                    "converted + remainder != beforeReserved",
                            details = mapOf(
                                "guard" to "vectorWithinReservation",
                            ),
                        ),
                    )
                }
                return OmniResult.ok(
                    ConversionProof(
                        beforeReserved = beforeReserved,
                        convertedAllocation = convertedAllocation,
                        releasedRemainder = releasedRemainder,
                    ),
                )
            }
        }
    }

    /**
     * Split [beforeReserved] into [convertedAllocation] and remainder.
     * Remainder = beforeReserved - convertedAllocation (checked).
     */
    fun splitConversion(
        beforeReserved: ResourceVector,
        convertedAllocation: ResourceVector,
    ): OmniResult<ConversionProof> {
        if (!beforeReserved.dominates(convertedAllocation)) {
            return OmniResult.err(
                OmniError.ADMISSION_REJECTED(
                    message = "converted allocation exceeds reserved vector",
                    details = mapOf("guard" to "vectorWithinReservation"),
                ),
            )
        }
        val remainder = ResourceArithmetic.tryMinus(beforeReserved, convertedAllocation)
        return when (remainder) {
            is OmniResult.Err -> remainder
            is OmniResult.Ok ->
                checkConversion(beforeReserved, convertedAllocation, remainder.value)
        }
    }

    /**
     * Capacity conservation: reserved + allocated + free == capacity (element-wise).
     */
    fun checkCapacity(
        reserved: ResourceVector,
        allocated: ResourceVector,
        free: ResourceVector,
        capacity: ResourceVector,
    ): OmniResult<ResourceVector> {
        val reservedPlusAllocated = ResourceArithmetic.tryPlus(reserved, allocated)
        val total = when (reservedPlusAllocated) {
            is OmniResult.Err -> return reservedPlusAllocated
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(reservedPlusAllocated.value, free)
        }
        return when (total) {
            is OmniResult.Err -> total
            is OmniResult.Ok -> {
                if (total.value != capacity) {
                    OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message =
                                "capacity conservation violated: " +
                                    "reserved+allocated+free != capacity",
                            details = mapOf("invariant" to "capacityLedger"),
                        ),
                    )
                } else {
                    OmniResult.ok(capacity)
                }
            }
        }
    }
}
