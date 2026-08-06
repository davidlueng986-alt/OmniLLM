// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.errors.generated

/** Error catalog from specs/error-catalog.yaml. */

enum class ErrorCategory {
    SEMANTIC,
    TRANSPORT,
}

enum class OmniErrorCode(
    val code: String,
    val httpStatus: Int,
    val category: ErrorCategory,
    val retryable: Boolean,
    val requiredClientAction: String,
) {
    INVALID_REQUEST("INVALID_REQUEST", 400, ErrorCategory.SEMANTIC, false, "correct-request"),
    UNAUTHORIZED("UNAUTHORIZED", 401, ErrorCategory.SEMANTIC, false, "reauthenticate"),
    FORBIDDEN("FORBIDDEN", 403, ErrorCategory.SEMANTIC, false, "request-scope-or-complete-local-approval"),
    NOT_FOUND("NOT_FOUND", 404, ErrorCategory.SEMANTIC, false, "refresh-resource"),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", 409, ErrorCategory.SEMANTIC, false, "query-existing-or-use-a-new-key"),
    STATE_CONFLICT("STATE_CONFLICT", 409, ErrorCategory.SEMANTIC, false, "refresh-state-and-retry-only-if-safe"),
    CONTEXT_LIMIT_EXCEEDED("CONTEXT_LIMIT_EXCEEDED", 413, ErrorCategory.SEMANTIC, false, "reduce-context-or-output"),
    TRANSPORT_TOO_LARGE("TRANSPORT_TOO_LARGE", 413, ErrorCategory.TRANSPORT, false, "use-handle-chunk-or-client-preflight"),
    RATE_LIMITED("RATE_LIMITED", 429, ErrorCategory.SEMANTIC, true, "respect-retry-after"),
    CAPABILITY_UNSUPPORTED("CAPABILITY_UNSUPPORTED", 422, ErrorCategory.SEMANTIC, false, "select-a-supported-operation"),
    CAPABILITY_UNKNOWN("CAPABILITY_UNKNOWN", 503, ErrorCategory.SEMANTIC, true, "qualify-the-cell-or-select-a-known-safe-option"),
    ADMISSION_REJECTED("ADMISSION_REJECTED", 503, ErrorCategory.SEMANTIC, true, "reduce-resource-requirement-close-resident-work-or-wait"),
    MODEL_REVOKED("MODEL_REVOKED", 410, ErrorCategory.SEMANTIC, false, "select-a-trusted-revision"),
    TRUST_PLACEMENT_REQUIRED("TRUST_PLACEMENT_REQUIRED", 412, ErrorCategory.SEMANTIC, false, "use-the-safe-placement-or-complete-the-explicit-risk-flow"),
    PAIRING_REQUIRED("PAIRING_REQUIRED", 428, ErrorCategory.SEMANTIC, false, "complete-local-pairing"),
    CURSOR_GONE("CURSOR_GONE", 410, ErrorCategory.SEMANTIC, false, "load-a-current-snapshot-and-resubscribe"),
    ASSET_NOT_READY("ASSET_NOT_READY", 409, ErrorCategory.SEMANTIC, true, "wait-for-ready-or-query-asset"),
    ASSET_EXPIRED("ASSET_EXPIRED", 410, ErrorCategory.SEMANTIC, false, "create-a-new-asset"),
    DEADLINE_EXCEEDED("DEADLINE_EXCEEDED", 504, ErrorCategory.SEMANTIC, true, "query-status-before-retry"),
    CANCELLED("CANCELLED", 499, ErrorCategory.SEMANTIC, false, "none"),
    WORKER_DIED("WORKER_DIED", 503, ErrorCategory.SEMANTIC, true, "query-and-reconcile"),
    ABORTED_UNCERTAIN("ABORTED_UNCERTAIN", 500, ErrorCategory.SEMANTIC, false, "query-diagnostics-and-never-reuse-the-affected-session"),
    STREAM_INTERRUPTED("STREAM_INTERRUPTED", 503, ErrorCategory.SEMANTIC, true, "resume-only-with-an-explicit-protocol-checkpoint-otherwise-start-a-new-session"),
    CONTENT_REPORT_UNAVAILABLE("CONTENT_REPORT_UNAVAILABLE", 503, ErrorCategory.SEMANTIC, true, "keep-the-local-encrypted-draft-and-retry-with-user-consent"),
    INTERNAL("INTERNAL", 500, ErrorCategory.SEMANTIC, false, "collect-redacted-diagnostics")
    ;

    companion object {
        fun fromCode(code: String): OmniErrorCode? =
            entries.firstOrNull { it.code == code }

        fun requireFromCode(code: String): OmniErrorCode =
            fromCode(code) ?: error("Unknown error code (fail closed): $code")
    }
}
