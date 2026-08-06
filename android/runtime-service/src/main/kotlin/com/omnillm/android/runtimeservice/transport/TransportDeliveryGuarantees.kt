package com.omnillm.android.runtimeservice.transport

import com.omnillm.core.errors.TransportDeliveryGuarantee

/**
 * Process-local alias of [TransportDeliveryGuarantee] (INV-013 / ADR-011 / INV-006).
 *
 * Canonical catalog lives in `:core:errors` so HTTP and AIDL error projections
 * share one authority. This enum remains for runtime-service call sites and
 * mirrors the core labels 1:1.
 */
enum class TransportDeliveryGuarantees(
    /** Stable wire/docs label. */
    val label: String,
    /**
     * When true, transport may advance client-delivered checkpoints
     * (AIDL application ACK only). HTTP SSE is always false.
     */
    val establishesClientDeliveredCheckpoint: Boolean,
    /** On reply loss, client must query durable state (not blind replay). */
    val supportsQueryAfterReplyLoss: Boolean,
    val notes: String,
) {
    HTTP_SSE(
        label = TransportDeliveryGuarantee.HTTP_SSE.label,
        establishesClientDeliveredCheckpoint =
            TransportDeliveryGuarantee.HTTP_SSE.establishesClientDeliveredCheckpoint,
        supportsQueryAfterReplyLoss = TransportDeliveryGuarantee.HTTP_SSE.supportsQueryAfterReplyLoss,
        notes = TransportDeliveryGuarantee.HTTP_SSE.notes,
    ),
    HTTP_JSON(
        label = TransportDeliveryGuarantee.HTTP_JSON.label,
        establishesClientDeliveredCheckpoint =
            TransportDeliveryGuarantee.HTTP_JSON.establishesClientDeliveredCheckpoint,
        supportsQueryAfterReplyLoss = TransportDeliveryGuarantee.HTTP_JSON.supportsQueryAfterReplyLoss,
        notes = TransportDeliveryGuarantee.HTTP_JSON.notes,
    ),
    AIDL_STREAM(
        label = TransportDeliveryGuarantee.AIDL_STREAM.label,
        establishesClientDeliveredCheckpoint =
            TransportDeliveryGuarantee.AIDL_STREAM.establishesClientDeliveredCheckpoint,
        supportsQueryAfterReplyLoss = TransportDeliveryGuarantee.AIDL_STREAM.supportsQueryAfterReplyLoss,
        notes = TransportDeliveryGuarantee.AIDL_STREAM.notes,
    ),
    AIDL_UNARY(
        label = TransportDeliveryGuarantee.AIDL_UNARY.label,
        establishesClientDeliveredCheckpoint =
            TransportDeliveryGuarantee.AIDL_UNARY.establishesClientDeliveredCheckpoint,
        supportsQueryAfterReplyLoss = TransportDeliveryGuarantee.AIDL_UNARY.supportsQueryAfterReplyLoss,
        notes = TransportDeliveryGuarantee.AIDL_UNARY.notes,
    ),
    ADMIN_BINDER(
        label = TransportDeliveryGuarantee.ADMIN_BINDER.label,
        establishesClientDeliveredCheckpoint =
            TransportDeliveryGuarantee.ADMIN_BINDER.establishesClientDeliveredCheckpoint,
        supportsQueryAfterReplyLoss = TransportDeliveryGuarantee.ADMIN_BINDER.supportsQueryAfterReplyLoss,
        notes = TransportDeliveryGuarantee.ADMIN_BINDER.notes,
    ),
    ;

    fun toCore(): TransportDeliveryGuarantee = TransportDeliveryGuarantee.forLabel(label)

    companion object {
        private val BY_LABEL: Map<String, TransportDeliveryGuarantees> =
            entries.associateBy { it.label }

        /**
         * Resolve a known transport guarantee label. Unknown labels fail closed
         * (INV-018 spirit for transport identity).
         */
        fun forTransport(label: String): TransportDeliveryGuarantees =
            BY_LABEL[label]
                ?: throw IllegalArgumentException(
                    "unknown transport delivery guarantee '$label' (fail closed; INV-013)",
                )

        fun fromCore(core: TransportDeliveryGuarantee): TransportDeliveryGuarantees =
            forTransport(core.label)
    }
}
