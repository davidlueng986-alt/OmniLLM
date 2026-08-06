package com.omnillm.core.errors

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode

/**
 * Boundary helpers that map catalog codes to typed [OmniError] with retryability.
 * Unknown codes fail closed (INV-018).
 *
 * Transport **delivery** is not an error code — it is annotated on errors via
 * [withTransportDelivery] so clients cannot confuse wire success with app delivery
 * (INV-013 / ADR-011). See [TransportDeliveryGuarantee].
 */
object ErrorMapping {
    /** Map a wire/catalog code string to a typed error; unknown → fail closed. */
    fun fromCode(
        code: String,
        message: String? = null,
        details: Map<String, String> = emptyMap(),
    ): OmniError = OmniError.ofCode(code, message, details)

    fun fromCode(
        code: OmniErrorCode,
        message: String? = null,
        details: Map<String, String> = emptyMap(),
    ): OmniError = OmniError.of(code, message, details)

    fun isRetryable(code: String): Boolean =
        OmniErrorCode.requireFromCode(code).retryable

    fun isRetryable(code: OmniErrorCode): Boolean = code.retryable

    fun httpStatus(code: String): Int =
        OmniErrorCode.requireFromCode(code).httpStatus

    fun requiredClientAction(code: String): String =
        OmniErrorCode.requireFromCode(code).requiredClientAction

    /** All catalog codes (stable order = enum declaration order). */
    fun allCodes(): List<OmniErrorCode> = OmniErrorCode.entries

    /**
     * Annotate [error] with explicit transport delivery labels in [OmniError.details].
     * Does not change the catalog [OmniError.code] (delivery is not a semantic code).
     */
    fun withTransportDelivery(
        error: OmniError,
        transport: TransportDeliveryGuarantee,
    ): OmniError {
        val merged = error.details.toMutableMap()
        merged[TransportDeliveryGuarantee.DETAIL_KEY] = transport.label
        merged[TransportDeliveryGuarantee.DETAIL_NOTES_KEY] = transport.notes
        return OmniError.of(error.code, error.message, merged)
    }

    /**
     * Whether this error already carries a known [TransportDeliveryGuarantee] label.
     */
    fun transportDeliveryOf(error: OmniError): TransportDeliveryGuarantee? {
        val label = error.details[TransportDeliveryGuarantee.DETAIL_KEY] ?: return null
        return runCatching { TransportDeliveryGuarantee.forLabel(label) }.getOrNull()
    }
}
