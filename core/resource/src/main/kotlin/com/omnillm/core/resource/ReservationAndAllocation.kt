package com.omnillm.core.resource

import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector

/**
 * Opaque reservation identity (catalog `ReservationId`, kind: opaque-handle).
 * Bound to issuerBootId, runtimeEpoch, principalId, resourceEnvelope, deadline.
 */
@JvmInline
value class ReservationId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val MAX_BYTES: Int = 256

        fun parse(raw: String): ReservationId {
            require(raw.isNotEmpty()) { "ReservationId must be non-empty" }
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "ReservationId exceeds $MAX_BYTES bytes"
            }
            return ReservationId(raw)
        }

        fun ofValidated(value: String): ReservationId = ReservationId(value)
    }
}

/**
 * Opaque allocation handle identity (catalog `AllocationHandleId`).
 * Resident resource ownership produced by reservation conversion (ADR-003).
 */
@JvmInline
value class AllocationHandleId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val MAX_BYTES: Int = 256

        fun parse(raw: String): AllocationHandleId {
            require(raw.isNotEmpty()) { "AllocationHandleId must be non-empty" }
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "AllocationHandleId exceeds $MAX_BYTES bytes"
            }
            return AllocationHandleId(raw)
        }

        fun ofValidated(value: String): AllocationHandleId = AllocationHandleId(value)
    }
}

/**
 * Short-lived resource reservation issued by the Governor before side effects
 * (CORE-RESOURCE §4, ADR-003).
 *
 * Does **not** represent resident model/KV ownership — that is [AllocationHandle].
 * Request terminal releases only the temporary reservation remainder.
 */
data class Reservation(
    val reservationId: ReservationId,
    val principalId: String,
    val issuerBootId: String,
    val runtimeEpoch: Long,
    val nonce: String,
    /** Monotonic deadline (nanoseconds or boot-relative unit as declared by issuer). */
    val deadlineMonotonic: Long,
    /** Peak/steady envelope held for the bounded operation. */
    val envelope: ResourceEnvelope,
    val operatingConstraint: OperatingConstraint = OperatingConstraint.NONE,
) {
    init {
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(issuerBootId.isNotEmpty()) { "issuerBootId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(nonce.isNotEmpty()) { "nonce must be non-empty" }
        require(deadlineMonotonic >= 0L) { "deadlineMonotonic must be non-negative" }
    }

    /** Peak vector currently reserved (admission charge). */
    val reservedVector: ResourceVector get() = envelope.peak
}

/**
 * Resident resource ownership after commit conversion (ADR-003 / INV-005).
 *
 * Survives request terminal; capacity is credited only after release barrier.
 * State labels come from the ALLOCATION FSM in specs/state-machines.yaml —
 * this type carries the ledger vector, not the full state machine engine.
 */
data class AllocationHandle(
    val allocationHandleId: AllocationHandleId,
    val principalId: String,
    val runtimeEpoch: Long,
    /** Steady resident charge transferred from a reservation. */
    val resident: ResourceVector,
    /** Optional source reservation that produced this handle (audit). */
    val sourceReservationId: ReservationId? = null,
    val operatingConstraint: OperatingConstraint = OperatingConstraint.NONE,
) {
    init {
        require(principalId.isNotEmpty()) { "principalId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}
