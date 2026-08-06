package com.omnillm.runtime.session

/**
 * Transport delivery semantics for Session checkpoints (ADR-006 / INV-006).
 *
 * Types only — no Android Binder/SSE sockets. Interfaces and runtime wiring
 * attach these policies; this module never treats write completion as delivery.
 */
sealed class DeliverySemantics {
    /**
     * HTTP SSE: stateless-by-default (ADR-006).
     * Socket write completion does **not** establish a client-delivered
     * checkpoint; disconnect does not auto-resume the session.
     */
    data object SseStatelessByDefault : DeliverySemantics()

    /**
     * AIDL: application ACK with streamEpoch and half-open sequence ranges
     * (`specs/aidl/omnillm-aidl.yaml` transportConstraints.streamDelivery).
     * Only this path may advance [CommitCheckpoint.ASSISTANT_ACKNOWLEDGED].
     */
    data class AidlApplicationAck(
        val streamEpoch: Long,
        /** Exclusive end of the acknowledged half-open range. */
        val seqToExclusive: Long,
    ) : DeliverySemantics() {
        init {
            require(streamEpoch >= 0L) { "streamEpoch must be non-negative" }
            require(seqToExclusive >= 0L) { "seqToExclusive must be non-negative" }
        }
    }
}

/**
 * Whether a delivery signal may establish [CommitCheckpoint.ASSISTANT_ACKNOWLEDGED].
 * SSE write completion ⇒ false; AIDL application ACK ⇒ true.
 */
fun DeliverySemantics.establishesClientDeliveredCheckpoint(): Boolean =
    when (this) {
        is DeliverySemantics.SseStatelessByDefault -> false
        is DeliverySemantics.AidlApplicationAck -> true
    }
