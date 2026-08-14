// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.errors.generated

/**
 * Sealed error hierarchy derived from specs/error-catalog.yaml.
 * Unknown codes must not be constructed at the boundary (fail closed).
 */
sealed class OmniError {
    abstract val code: OmniErrorCode
    abstract val message: String?
    abstract val details: Map<String, String>

    val httpStatus: Int get() = code.httpStatus
    val retryable: Boolean get() = code.retryable
    val category: ErrorCategory get() = code.category
    val requiredClientAction: String get() = code.requiredClientAction

    data class INVALID_REQUEST(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.INVALID_REQUEST
    }

    data class MODEL_FORMAT_INVALID(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.MODEL_FORMAT_INVALID
    }

    data class UNAUTHORIZED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.UNAUTHORIZED
    }

    data class FORBIDDEN(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.FORBIDDEN
    }

    data class NOT_FOUND(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.NOT_FOUND
    }

    data class IDEMPOTENCY_CONFLICT(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.IDEMPOTENCY_CONFLICT
    }

    data class STATE_CONFLICT(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.STATE_CONFLICT
    }

    data class CONTEXT_LIMIT_EXCEEDED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.CONTEXT_LIMIT_EXCEEDED
    }

    data class TRANSPORT_TOO_LARGE(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.TRANSPORT_TOO_LARGE
    }

    data class RATE_LIMITED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.RATE_LIMITED
    }

    data class CAPABILITY_UNSUPPORTED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.CAPABILITY_UNSUPPORTED
    }

    data class CAPABILITY_UNKNOWN(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.CAPABILITY_UNKNOWN
    }

    data class ADMISSION_REJECTED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.ADMISSION_REJECTED
    }

    data class MODEL_REVOKED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.MODEL_REVOKED
    }

    data class TRUST_PLACEMENT_REQUIRED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.TRUST_PLACEMENT_REQUIRED
    }

    data class PAIRING_REQUIRED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.PAIRING_REQUIRED
    }

    data class CURSOR_GONE(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.CURSOR_GONE
    }

    data class ASSET_NOT_READY(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.ASSET_NOT_READY
    }

    data class ASSET_EXPIRED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.ASSET_EXPIRED
    }

    data class DEADLINE_EXCEEDED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.DEADLINE_EXCEEDED
    }

    data class CANCELLED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.CANCELLED
    }

    data class WORKER_DIED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.WORKER_DIED
    }

    data class ABORTED_UNCERTAIN(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.ABORTED_UNCERTAIN
    }

    data class STREAM_INTERRUPTED(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.STREAM_INTERRUPTED
    }

    data class CONTENT_REPORT_UNAVAILABLE(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.CONTENT_REPORT_UNAVAILABLE
    }

    data class INTERNAL(
        override val message: String? = null,
        override val details: Map<String, String> = emptyMap(),
    ) : OmniError() {
        override val code: OmniErrorCode = OmniErrorCode.INTERNAL
    }

    companion object {
        val STREAM_RULE: String = "Before the first stream event use a normal response/onError. After stream commit emit exactly one terminal error event. A transport failure that prevents the request reaching the service is not a semantic error response."

        fun of(
            code: OmniErrorCode,
            message: String? = null,
            details: Map<String, String> = emptyMap(),
        ): OmniError = when (code) {
            OmniErrorCode.INVALID_REQUEST -> INVALID_REQUEST(message, details)
            OmniErrorCode.MODEL_FORMAT_INVALID -> MODEL_FORMAT_INVALID(message, details)
            OmniErrorCode.UNAUTHORIZED -> UNAUTHORIZED(message, details)
            OmniErrorCode.FORBIDDEN -> FORBIDDEN(message, details)
            OmniErrorCode.NOT_FOUND -> NOT_FOUND(message, details)
            OmniErrorCode.IDEMPOTENCY_CONFLICT -> IDEMPOTENCY_CONFLICT(message, details)
            OmniErrorCode.STATE_CONFLICT -> STATE_CONFLICT(message, details)
            OmniErrorCode.CONTEXT_LIMIT_EXCEEDED -> CONTEXT_LIMIT_EXCEEDED(message, details)
            OmniErrorCode.TRANSPORT_TOO_LARGE -> TRANSPORT_TOO_LARGE(message, details)
            OmniErrorCode.RATE_LIMITED -> RATE_LIMITED(message, details)
            OmniErrorCode.CAPABILITY_UNSUPPORTED -> CAPABILITY_UNSUPPORTED(message, details)
            OmniErrorCode.CAPABILITY_UNKNOWN -> CAPABILITY_UNKNOWN(message, details)
            OmniErrorCode.ADMISSION_REJECTED -> ADMISSION_REJECTED(message, details)
            OmniErrorCode.MODEL_REVOKED -> MODEL_REVOKED(message, details)
            OmniErrorCode.TRUST_PLACEMENT_REQUIRED -> TRUST_PLACEMENT_REQUIRED(message, details)
            OmniErrorCode.PAIRING_REQUIRED -> PAIRING_REQUIRED(message, details)
            OmniErrorCode.CURSOR_GONE -> CURSOR_GONE(message, details)
            OmniErrorCode.ASSET_NOT_READY -> ASSET_NOT_READY(message, details)
            OmniErrorCode.ASSET_EXPIRED -> ASSET_EXPIRED(message, details)
            OmniErrorCode.DEADLINE_EXCEEDED -> DEADLINE_EXCEEDED(message, details)
            OmniErrorCode.CANCELLED -> CANCELLED(message, details)
            OmniErrorCode.WORKER_DIED -> WORKER_DIED(message, details)
            OmniErrorCode.ABORTED_UNCERTAIN -> ABORTED_UNCERTAIN(message, details)
            OmniErrorCode.STREAM_INTERRUPTED -> STREAM_INTERRUPTED(message, details)
            OmniErrorCode.CONTENT_REPORT_UNAVAILABLE -> CONTENT_REPORT_UNAVAILABLE(message, details)
            OmniErrorCode.INTERNAL -> INTERNAL(message, details)
        }

        fun ofCode(
            code: String,
            message: String? = null,
            details: Map<String, String> = emptyMap(),
        ): OmniError = of(OmniErrorCode.requireFromCode(code), message, details)
    }
}
