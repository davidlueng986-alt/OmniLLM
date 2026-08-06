package com.omnillm.runtime.governor

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.AllocationHandle
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Conservation
import com.omnillm.core.resource.OperatingConstraint
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.core.resource.ResourceArithmetic
import java.util.concurrent.atomic.AtomicLong

/**
 * Multi-dimensional Resource Governor (CORE-RESOURCE, ADR-003, ARCH conservation).
 *
 * API surface:
 * - [reserve] — charge peak [ResourceVector] for owner until deadline
 * - [convert] — reservation → [AllocationHandle] with per-dimension conservation
 * - [releaseReservation] — release temporary reservation remainder
 * - eviction barrier — [requestDrain] → [markOwnerQuiescent] → [completeReleaseBarrier]
 *
 * Hard rules:
 * - Never credit capacity before observed release barrier (planned eviction not pre-credited)
 * - [OperatingConstraint] is never summed into [ResourceVector]
 * - Checked arithmetic only; overflow / underflow fail closed
 * - Plan has no domain mutation; this component mutates the resource ledger only
 *   (runtime control plane; ADR-010)
 *
 * In-memory ledger for unit tests and control-plane wiring. Persistence of handles
 * remains a single-writer DB concern outside this type.
 */
class ResourceGovernor(
    capacity: ResourceVector,
    safetyMargin: ResourceVector = ResourceVector.ZERO,
    private val issuerBootId: String,
    private val runtimeEpoch: Long,
    private val clockMonotonic: () -> Long = { System.nanoTime() },
    private val idSource: () -> String = { nextId("id") },
) {
    private val lock = Any()

    private var ledger: ResourceLedger =
        when (val empty = ResourceLedger.empty(capacity, safetyMargin)) {
            is OmniResult.Ok -> empty.value
            is OmniResult.Err ->
                throw IllegalArgumentException(
                    empty.error.message ?: "invalid capacity/safetyMargin for ResourceGovernor",
                )
        }

    private val reservations = linkedMapOf<String, TrackedReservation>()
    private val allocations = linkedMapOf<String, TrackedAllocation>()

    init {
        require(issuerBootId.isNotEmpty()) { "issuerBootId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }

    /** Immutable ledger snapshot (capacity / reserved / allocated / free / safetyMargin). */
    fun snapshot(): ResourceLedger = synchronized(lock) { ledger }

    /**
     * Reserve [vector] peak (or envelope peak) for [owner] until [deadlineMonotonic].
     *
     * Admission fails closed when any dimension lacks free headroom after safety margin,
     * when the deadline is already past, or when arithmetic overflows.
     *
     * [operatingConstraint] is recorded for callers but **never** added into the vector ledger.
     */
    fun reserve(
        vector: ResourceVector,
        owner: String,
        deadlineMonotonic: Long,
        operatingConstraint: OperatingConstraint = OperatingConstraint.NONE,
        envelope: ResourceEnvelope? = null,
        nonce: String? = null,
        nowMonotonic: Long = clockMonotonic(),
    ): OmniResult<Reservation> = synchronized(lock) {
        if (owner.isEmpty()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "reservation owner (principalId) must be non-empty",
                    details = mapOf("op" to "reserve"),
                ),
            )
        }
        if (deadlineMonotonic < 0L) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "deadlineMonotonic must be non-negative",
                    details = mapOf("op" to "reserve"),
                ),
            )
        }
        if (nowMonotonic > deadlineMonotonic) {
            return OmniResult.err(
                OmniError.DEADLINE_EXCEEDED(
                    message = "reservation deadline already passed",
                    details = mapOf("op" to "reserve"),
                ),
            )
        }

        val peak = envelope?.peak ?: vector
        val steady = envelope?.steady ?: vector
        val effectiveEnvelope = when (envelope) {
            null -> {
                val env = ResourceArithmetic.tryEnvelope(steady, peak)
                when (env) {
                    is OmniResult.Err -> return env
                    is OmniResult.Ok -> env.value
                }
            }
            else -> envelope
        }

        // OperatingConstraint is intentionally not part of peak charge.
        val charged = ledger.chargeReservation(peak)
        when (charged) {
            is OmniResult.Err -> return charged
            is OmniResult.Ok -> ledger = charged.value
        }

        val reservationId = ReservationId.parse(idSource())
        val reservation =
            Reservation(
                reservationId = reservationId,
                principalId = owner,
                issuerBootId = issuerBootId,
                runtimeEpoch = runtimeEpoch,
                nonce = nonce ?: idSource(),
                deadlineMonotonic = deadlineMonotonic,
                envelope = effectiveEnvelope,
                operatingConstraint = operatingConstraint,
            )
        reservations[reservationId.value] =
            TrackedReservation(
                reservation = reservation,
                state = ReservationFsm.HELD,
                remainingCharge = peak,
                convertedAllocationId = null,
            )
        return OmniResult.ok(reservation)
    }

    /**
     * Convert a held reservation into a resident [AllocationHandle].
     *
     * [convertedAllocation] must be dominated by the reserved peak
     * (typically envelope.steady). Remainder is released to free immediately
     * (RESERVATION FSM `releaseRemainder`); conversion is idempotent by reservation id.
     */
    fun convert(
        reservationId: ReservationId,
        convertedAllocation: ResourceVector,
        nowMonotonic: Long = clockMonotonic(),
        allocationId: AllocationHandleId? = null,
    ): OmniResult<AllocationHandle> = synchronized(lock) {
        val tracked = reservations[reservationId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "reservation not found",
                    details = mapOf("reservationId" to reservationId.value),
                ),
            )

        // Idempotent: already converted for this reservation.
        tracked.convertedAllocationId?.let { existingId ->
            val existing = allocations[existingId.value]
            if (existing != null && tracked.state == ReservationFsm.COMMITTED) {
                return OmniResult.ok(existing.handle)
            }
        }

        if (tracked.state != ReservationFsm.HELD) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "reservation not in HELD state for convert",
                    details = mapOf(
                        "reservationId" to reservationId.value,
                        "state" to tracked.state,
                    ),
                ),
            )
        }

        if (tracked.reservation.runtimeEpoch != runtimeEpoch ||
            tracked.reservation.issuerBootId != issuerBootId
        ) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "reservation issuer boot/runtime epoch mismatch",
                    details = mapOf("guard" to "issuerEpochValid"),
                ),
            )
        }

        if (nowMonotonic > tracked.reservation.deadlineMonotonic) {
            // Expire and release charge (RES-005).
            expireHeldLocked(tracked)
            return OmniResult.err(
                OmniError.DEADLINE_EXCEEDED(
                    message = "reservation deadline passed before convert",
                    details = mapOf("reservationId" to reservationId.value),
                ),
            )
        }

        val before = tracked.remainingCharge
        val split = Conservation.splitConversion(before, convertedAllocation)
        val proof = when (split) {
            is OmniResult.Err -> return split
            is OmniResult.Ok -> split.value
        }

        val nextLedger =
            ledger.convertReservation(
                beforeReserved = proof.beforeReserved,
                convertedAllocation = proof.convertedAllocation,
                releasedRemainder = proof.releasedRemainder,
            )
        when (nextLedger) {
            is OmniResult.Err -> return nextLedger
            is OmniResult.Ok -> ledger = nextLedger.value
        }

        val handleId = allocationId ?: AllocationHandleId.parse(idSource())
        val handle =
            AllocationHandle(
                allocationHandleId = handleId,
                principalId = tracked.reservation.principalId,
                runtimeEpoch = runtimeEpoch,
                resident = proof.convertedAllocation,
                sourceReservationId = reservationId,
                operatingConstraint = tracked.reservation.operatingConstraint,
            )
        allocations[handleId.value] =
            TrackedAllocation(
                handle = handle,
                state = AllocationFsm.ACTIVE,
                ownerFenced = false,
                nativeBarrierObserved = false,
            )
        reservations[reservationId.value] =
            tracked.copy(
                state = ReservationFsm.COMMITTED,
                remainingCharge = ResourceVector.ZERO,
                convertedAllocationId = handleId,
            )
        return OmniResult.ok(handle)
    }

    /**
     * Release a held (or converting) temporary reservation remainder.
     * Resident [AllocationHandle] charges are unaffected (INV-005 / ADR-003).
     *
     * Idempotent for terminal RELEASED / COMMITTED / EXPIRED.
     */
    fun releaseReservation(
        reservationId: ReservationId,
        nowMonotonic: Long = clockMonotonic(),
    ): OmniResult<Unit> = synchronized(lock) {
        val tracked = reservations[reservationId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "reservation not found",
                    details = mapOf("reservationId" to reservationId.value),
                ),
            )

        when (tracked.state) {
            ReservationFsm.RELEASED,
            ReservationFsm.COMMITTED,
            ReservationFsm.EXPIRED,
            -> return OmniResult.ok(Unit)

            ReservationFsm.HELD,
            ReservationFsm.RELEASING,
            -> {
                if (tracked.reservation.runtimeEpoch != runtimeEpoch ||
                    tracked.reservation.issuerBootId != issuerBootId
                ) {
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "reservation issuer boot/runtime epoch mismatch",
                            details = mapOf("guard" to "issuerEpochValid"),
                        ),
                    )
                }
                // Optional: treat past deadline as EXPIRED path (same free credit).
                val terminalState =
                    if (nowMonotonic > tracked.reservation.deadlineMonotonic) {
                        ReservationFsm.EXPIRED
                    } else {
                        ReservationFsm.RELEASED
                    }
                val charge = tracked.remainingCharge
                if (charge != ResourceVector.ZERO) {
                    val released = ledger.releaseReservationCharge(charge)
                    when (released) {
                        is OmniResult.Err -> return released
                        is OmniResult.Ok -> ledger = released.value
                    }
                }
                reservations[reservationId.value] =
                    tracked.copy(
                        state = terminalState,
                        remainingCharge = ResourceVector.ZERO,
                    )
                return OmniResult.ok(Unit)
            }

            ReservationFsm.CONVERTING ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "reservation convert in progress; complete conversion first",
                        details = mapOf("reservationId" to reservationId.value),
                    ),
                )

            else ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "unknown reservation state",
                        details = mapOf("state" to tracked.state),
                    ),
                )
        }
    }

    /**
     * Expire all held reservations past deadline and credit free for their remainder.
     * Returns reservation ids that transitioned to EXPIRED.
     */
    fun expireDueReservations(nowMonotonic: Long = clockMonotonic()): List<ReservationId> =
        synchronized(lock) {
            val expired = mutableListOf<ReservationId>()
            // Snapshot keys to allow map mutation.
            val ids = reservations.keys.toList()
            for (id in ids) {
                val tracked = reservations[id] ?: continue
                if (tracked.state == ReservationFsm.HELD &&
                    nowMonotonic > tracked.reservation.deadlineMonotonic
                ) {
                    expireHeldLocked(tracked)
                    expired.add(tracked.reservation.reservationId)
                }
            }
            expired
        }

    // -----------------------------------------------------------------------
    // Eviction / allocation release barrier (ALLOCATION FSM)
    // -----------------------------------------------------------------------

    /**
     * Begin eviction: ACTIVE → DRAINING. Blocks new use; **does not** credit free.
     */
    fun requestDrain(allocationHandleId: AllocationHandleId): OmniResult<Unit> =
        synchronized(lock) {
            val tracked = allocations[allocationHandleId.value]
                ?: return OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "allocation not found",
                        details = mapOf("allocationHandleId" to allocationHandleId.value),
                    ),
                )
            when (tracked.state) {
                AllocationFsm.DRAINING,
                AllocationFsm.RELEASING,
                -> return OmniResult.ok(Unit) // idempotent fence
                AllocationFsm.RELEASED ->
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "allocation already RELEASED",
                            details = mapOf("allocationHandleId" to allocationHandleId.value),
                        ),
                    )
                AllocationFsm.ACTIVE -> {
                    allocations[allocationHandleId.value] =
                        tracked.copy(state = AllocationFsm.DRAINING, ownerFenced = true)
                    return OmniResult.ok(Unit)
                }
                else ->
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "illegal drain from state ${tracked.state}",
                            details = mapOf("state" to tracked.state),
                        ),
                    )
            }
        }

    /**
     * DRAINING → RELEASING once owner is quiescent (no active operations).
     * Still **does not** credit free capacity.
     */
    fun markOwnerQuiescent(allocationHandleId: AllocationHandleId): OmniResult<Unit> =
        synchronized(lock) {
            val tracked = allocations[allocationHandleId.value]
                ?: return OmniResult.err(
                    OmniError.NOT_FOUND(
                        message = "allocation not found",
                        details = mapOf("allocationHandleId" to allocationHandleId.value),
                    ),
                )
            when (tracked.state) {
                AllocationFsm.RELEASING -> return OmniResult.ok(Unit)
                AllocationFsm.RELEASED ->
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "allocation already RELEASED",
                            details = mapOf("allocationHandleId" to allocationHandleId.value),
                        ),
                    )
                AllocationFsm.DRAINING -> {
                    if (!tracked.ownerFenced) {
                        return OmniResult.err(
                            OmniError.STATE_CONFLICT(
                                message = "ownerFenced guard failed",
                                details = mapOf("guard" to "ownerFenced"),
                            ),
                        )
                    }
                    allocations[allocationHandleId.value] =
                        tracked.copy(state = AllocationFsm.RELEASING)
                    return OmniResult.ok(Unit)
                }
                else ->
                    return OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "OWNER_QUIESCENT only valid from DRAINING",
                            details = mapOf("state" to tracked.state),
                        ),
                    )
            }
        }

    /**
     * Complete release barrier (RELEASING → RELEASED) and credit free **only when**
     * [nativeBarrierObserved] is true. Planned eviction without barrier observation
     * fails closed and does not increase free capacity.
     */
    fun completeReleaseBarrier(
        allocationHandleId: AllocationHandleId,
        nativeBarrierObserved: Boolean,
    ): OmniResult<Unit> = synchronized(lock) {
        val tracked = allocations[allocationHandleId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "allocation not found",
                    details = mapOf("allocationHandleId" to allocationHandleId.value),
                ),
            )

        if (tracked.state == AllocationFsm.RELEASED) {
            // Idempotent release.
            return OmniResult.ok(Unit)
        }

        if (tracked.state != AllocationFsm.RELEASING) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "RELEASE_BARRIER only valid from RELEASING",
                    details = mapOf("state" to tracked.state),
                ),
            )
        }

        if (!nativeBarrierObserved) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message =
                        "native/process release barrier not observed; " +
                            "capacity must not be credited (planned eviction not pre-credited)",
                    details = mapOf("guard" to "nativeBarrierObserved"),
                ),
            )
        }

        val credited = ledger.creditAllocationRelease(tracked.handle.resident)
        when (credited) {
            is OmniResult.Err -> return credited
            is OmniResult.Ok -> ledger = credited.value
        }
        allocations[allocationHandleId.value] =
            tracked.copy(
                state = AllocationFsm.RELEASED,
                nativeBarrierObserved = true,
            )
        return OmniResult.ok(Unit)
    }

    /**
     * Look up allocation tracking state (for tests / control-plane projection).
     */
    fun allocationState(allocationHandleId: AllocationHandleId): String? =
        synchronized(lock) { allocations[allocationHandleId.value]?.state }

    /**
     * Look up reservation tracking state.
     */
    fun reservationState(reservationId: ReservationId): String? =
        synchronized(lock) { reservations[reservationId.value]?.state }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private fun expireHeldLocked(tracked: TrackedReservation) {
        if (tracked.state != ReservationFsm.HELD) return
        val charge = tracked.remainingCharge
        if (charge != ResourceVector.ZERO) {
            when (val released = ledger.releaseReservationCharge(charge)) {
                is OmniResult.Ok -> ledger = released.value
                is OmniResult.Err ->
                    // Fail closed: leave charge in place rather than corrupt free.
                    // Callers observe via subsequent invariant checks.
                    return
            }
        }
        reservations[tracked.reservation.reservationId.value] =
            tracked.copy(
                state = ReservationFsm.EXPIRED,
                remainingCharge = ResourceVector.ZERO,
            )
    }

    private data class TrackedReservation(
        val reservation: Reservation,
        val state: String,
        /** Peak still charged against the reserved ledger. */
        val remainingCharge: ResourceVector,
        val convertedAllocationId: AllocationHandleId?,
    )

    private data class TrackedAllocation(
        val handle: AllocationHandle,
        val state: String,
        val ownerFenced: Boolean,
        val nativeBarrierObserved: Boolean,
    )

    companion object {
        private val SEQ = AtomicLong(0L)

        private fun nextId(prefix: String): String =
            "$prefix-${SEQ.incrementAndGet()}-${System.nanoTime()}"

        /**
         * Create a governor from a pre-validated ledger (tests / recovery).
         */
        fun fromLedger(
            ledger: ResourceLedger,
            issuerBootId: String,
            runtimeEpoch: Long,
            clockMonotonic: () -> Long = { System.nanoTime() },
            idSource: () -> String = { nextId("id") },
        ): OmniResult<ResourceGovernor> {
            val check = ledger.checkInvariants()
            return when (check) {
                is OmniResult.Err -> check
                is OmniResult.Ok ->
                    OmniResult.ok(
                        ResourceGovernor(
                            capacity = ledger.capacity,
                            safetyMargin = ledger.safetyMargin,
                            issuerBootId = issuerBootId,
                            runtimeEpoch = runtimeEpoch,
                            clockMonotonic = clockMonotonic,
                            idSource = idSource,
                        ).also { g ->
                            // Replace empty ledger with provided snapshot (zero reservations).
                            synchronized(g.lock) {
                                g.ledger = ledger
                            }
                        },
                    )
            }
        }
    }
}
