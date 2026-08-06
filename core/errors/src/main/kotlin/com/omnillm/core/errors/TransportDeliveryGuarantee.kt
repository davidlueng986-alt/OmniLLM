package com.omnillm.core.errors

/**
 * Transport **delivery** guarantees (INV-013 / ADR-011 / INV-006).
 *
 * Canonical request / event / **error codes** are shared across HTTP, AIDL, and
 * Admin. Delivery differs by transport and must never be implied from a
 * successful wire write or a non-error response alone.
 *
 * Authority notes:
 * - `specs/error-catalog.yaml` `streamRule` — semantic stream terminal rules
 * - `specs/aidl/omnillm-aidl.yaml` `transportConstraints.streamDelivery`
 * - CORE-INTERFACE §4 — SSE pre-stream HTTP errors vs post-stream terminal events
 *
 * Clients recovering from reply loss must **query** durable state
 * (`getRequest` / `getCommand` / `queryRequest` / `queryCommand`) — never blind replay.
 */
enum class TransportDeliveryGuarantee(
    /** Stable wire / docs label (use in error details when relevant). */
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
        label = "HTTP_SSE",
        establishesClientDeliveredCheckpoint = false,
        supportsQueryAfterReplyLoss = true,
        notes = "Socket write ≠ app delivery; Session STATELESS by default; " +
            "pre-stream errors are HTTP OmniError; post-stream failures are terminal events only",
    ),
    HTTP_JSON(
        label = "HTTP_JSON",
        establishesClientDeliveredCheckpoint = false,
        supportsQueryAfterReplyLoss = true,
        notes = "Sync/async JSON uses claim-or-return; reply loss → query getRequest/getCommand only",
    ),
    AIDL_STREAM(
        label = "AIDL_STREAM",
        establishesClientDeliveredCheckpoint = true,
        supportsQueryAfterReplyLoss = true,
        notes = "Application ACK with streamEpoch + half-open seq ranges only; " +
            "oneway callback ≠ consumption; credit window gates send",
    ),
    AIDL_UNARY(
        label = "AIDL_UNARY",
        establishesClientDeliveredCheckpoint = false,
        supportsQueryAfterReplyLoss = true,
        notes = "Non-stream Binder ops still use client requestId + queryRequest/cancelRequest",
    ),
    ADMIN_BINDER(
        label = "ADMIN_BINDER",
        establishesClientDeliveredCheckpoint = false,
        supportsQueryAfterReplyLoss = true,
        notes = "LOCAL_UI Admin commands: durable CommandResult; never void success; " +
            "job stream uses application ACK (ackJobEvents) separately from domain mutations",
    ),
    ;

    companion object {
        /** Error detail key for transport delivery label (INV-013). */
        const val DETAIL_KEY: String = "transport_delivery"

        /** Error detail key for human-readable delivery notes. */
        const val DETAIL_NOTES_KEY: String = "transport_delivery_notes"

        /**
         * Shared stream rule (mirrors [com.omnillm.core.errors.generated.OmniError.STREAM_RULE]
         * and specs/error-catalog.yaml streamRule). Documented here so error consumers
         * do not invent transport-local semantics.
         */
        const val STREAM_RULE: String =
            "Before the first stream event use a normal response/onError. " +
                "After stream commit emit exactly one terminal error event. " +
                "A transport failure that prevents the request reaching the service " +
                "is not a semantic error response."

        private val BY_LABEL: Map<String, TransportDeliveryGuarantee> =
            entries.associateBy { it.label }

        /**
         * Resolve a known transport guarantee label. Unknown labels fail closed
         * (INV-018 spirit for transport identity).
         */
        fun forLabel(label: String): TransportDeliveryGuarantee =
            BY_LABEL[label]
                ?: throw IllegalArgumentException(
                    "unknown transport delivery guarantee '$label' (fail closed; INV-013)",
                )

        fun knownLabels(): Set<String> = BY_LABEL.keys
    }
}
