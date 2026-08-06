package com.omnillm.runtime.governor

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.Conservation
import com.omnillm.core.resource.ResourceArithmetic

/**
 * Multi-dimensional resource capacity ledger (CORE-RESOURCE, ARCH resource conservation).
 *
 * Per dimension `d`:
 * ```
 * reserved[d] + allocated[d] + free[d] == capacity[d]
 * capacity[d] >= reserved[d] + allocated[d] + safetyMargin[d]
 * ```
 *
 * [OperatingConstraint][com.omnillm.core.resource.OperatingConstraint] is **not** part of this
 * ledger — non-additive limits live outside [ResourceVector] sums (CORE-RESOURCE §1).
 *
 * All arithmetic is checked; overflow / underflow fail closed (never wraparound).
 * Planned eviction must not mutate free; credit only after observed release barrier.
 */
data class ResourceLedger(
    val capacity: ResourceVector,
    val reserved: ResourceVector = ResourceVector.ZERO,
    val allocated: ResourceVector = ResourceVector.ZERO,
    val free: ResourceVector,
    /** Non-admittable reserve held out of free (ARCH conservation). */
    val safetyMargin: ResourceVector = ResourceVector.ZERO,
) {
    init {
        require(capacity.dominates(safetyMargin)) {
            "capacity must dominate safetyMargin on every dimension"
        }
    }

    /**
     * Free capacity available for new reservations after holding [safetyMargin].
     * Fail closed on arithmetic overflow.
     */
    fun admittableFree(): OmniResult<ResourceVector> =
        ResourceArithmetic.tryMinus(free, safetyMargin)

    /**
     * Whether [request] peak can be reserved under current ledger + safety margin.
     */
    fun canAdmit(request: ResourceVector): OmniResult<Boolean> {
        val headroom = admittableFree()
        return when (headroom) {
            is OmniResult.Err -> headroom
            is OmniResult.Ok -> OmniResult.ok(headroom.value.dominates(request))
        }
    }

    /**
     * Verify ledger identities. Returns self on success.
     */
    fun checkInvariants(): OmniResult<ResourceLedger> {
        val capacityCheck =
            Conservation.checkCapacity(reserved, allocated, free, capacity)
        when (capacityCheck) {
            is OmniResult.Err -> return capacityCheck
            is OmniResult.Ok -> Unit
        }
        // cap >= reserved + allocated + safety_margin
        val reservedPlusAllocated = ResourceArithmetic.tryPlus(reserved, allocated)
        val charged = when (reservedPlusAllocated) {
            is OmniResult.Err -> return reservedPlusAllocated
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(reservedPlusAllocated.value, safetyMargin)
        }
        return when (charged) {
            is OmniResult.Err -> charged
            is OmniResult.Ok -> {
                if (!capacity.dominates(charged.value)) {
                    OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message =
                                "capacity conservation violated: " +
                                    "reserved+allocated+safetyMargin exceeds capacity",
                            details = mapOf("invariant" to "capGeReservedAllocatedSafety"),
                        ),
                    )
                } else {
                    OmniResult.ok(this)
                }
            }
        }
    }

    /**
     * Move [amount] from free → reserved (admission charge).
     */
    fun chargeReservation(amount: ResourceVector): OmniResult<ResourceLedger> {
        val admit = canAdmit(amount)
        when (admit) {
            is OmniResult.Err -> return admit
            is OmniResult.Ok -> {
                if (!admit.value) {
                    return OmniResult.err(
                        OmniError.ADMISSION_REJECTED(
                            message = "insufficient free capacity for reservation (including safety margin)",
                            details = mapOf("op" to "chargeReservation"),
                        ),
                    )
                }
            }
        }
        val newFree = ResourceArithmetic.tryMinus(free, amount)
        val newReserved = when (newFree) {
            is OmniResult.Err -> return newFree
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(reserved, amount)
        }
        return when (newReserved) {
            is OmniResult.Err -> newReserved
            is OmniResult.Ok ->
                copy(reserved = newReserved.value, free = (newFree as OmniResult.Ok).value)
                    .checkInvariants()
        }
    }

    /**
     * Release [amount] from reserved → free (temporary reservation remainder / full release).
     */
    fun releaseReservationCharge(amount: ResourceVector): OmniResult<ResourceLedger> {
        if (!reserved.dominates(amount)) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "release reservation charge exceeds reserved ledger",
                    details = mapOf("op" to "releaseReservationCharge"),
                ),
            )
        }
        val newReserved = ResourceArithmetic.tryMinus(reserved, amount)
        val newFree = when (newReserved) {
            is OmniResult.Err -> return newReserved
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(free, amount)
        }
        return when (newFree) {
            is OmniResult.Err -> newFree
            is OmniResult.Ok ->
                copy(reserved = (newReserved as OmniResult.Ok).value, free = newFree.value)
                    .checkInvariants()
        }
    }

    /**
     * Convert reserved peak into resident allocation + free remainder.
     *
     * Conservation: [beforeReserved] = [convertedAllocation] + [releasedRemainder]
     * Ledger: reserved -= beforeReserved; allocated += converted; free += remainder.
     */
    fun convertReservation(
        beforeReserved: ResourceVector,
        convertedAllocation: ResourceVector,
        releasedRemainder: ResourceVector,
    ): OmniResult<ResourceLedger> {
        val proof =
            Conservation.checkConversion(beforeReserved, convertedAllocation, releasedRemainder)
        when (proof) {
            is OmniResult.Err -> return proof
            is OmniResult.Ok -> Unit
        }
        if (!reserved.dominates(beforeReserved)) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "convert charge exceeds reserved ledger",
                    details = mapOf("op" to "convertReservation"),
                ),
            )
        }
        val afterReleaseReserved = ResourceArithmetic.tryMinus(reserved, beforeReserved)
        val afterAlloc = when (afterReleaseReserved) {
            is OmniResult.Err -> return afterReleaseReserved
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(allocated, convertedAllocation)
        }
        val afterFree = when (afterAlloc) {
            is OmniResult.Err -> return afterAlloc
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(free, releasedRemainder)
        }
        return when (afterFree) {
            is OmniResult.Err -> afterFree
            is OmniResult.Ok ->
                copy(
                    reserved = (afterReleaseReserved as OmniResult.Ok).value,
                    allocated = (afterAlloc as OmniResult.Ok).value,
                    free = afterFree.value,
                ).checkInvariants()
        }
    }

    /**
     * Credit free only after allocation release barrier (ADR-003 / ALLOCATION FSM).
     * Must not be called for planned eviction before [nativeBarrierObserved].
     */
    fun creditAllocationRelease(amount: ResourceVector): OmniResult<ResourceLedger> {
        if (!allocated.dominates(amount)) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "allocation release exceeds allocated ledger",
                    details = mapOf("op" to "creditAllocationRelease"),
                ),
            )
        }
        val newAllocated = ResourceArithmetic.tryMinus(allocated, amount)
        val newFree = when (newAllocated) {
            is OmniResult.Err -> return newAllocated
            is OmniResult.Ok -> ResourceArithmetic.tryPlus(free, amount)
        }
        return when (newFree) {
            is OmniResult.Err -> newFree
            is OmniResult.Ok ->
                copy(allocated = (newAllocated as OmniResult.Ok).value, free = newFree.value)
                    .checkInvariants()
        }
    }

    companion object {
        /**
         * Build an empty ledger where free == capacity and invariants hold.
         */
        fun empty(
            capacity: ResourceVector,
            safetyMargin: ResourceVector = ResourceVector.ZERO,
        ): OmniResult<ResourceLedger> {
            val ledger =
                ResourceLedger(
                    capacity = capacity,
                    reserved = ResourceVector.ZERO,
                    allocated = ResourceVector.ZERO,
                    free = capacity,
                    safetyMargin = safetyMargin,
                )
            return ledger.checkInvariants()
        }
    }
}
