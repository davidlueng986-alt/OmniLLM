package com.omnillm.features.tools.domain

/**
 * Structured-output execution modes (FEAT-TOOLS §2).
 *
 * Feature-level capability descriptor — not a catalog state machine enum.
 * Response / plan / trace **must** disclose the actual mode; silent
 * demotion from constrained decoding to free text is forbidden.
 */
enum class StructuredMode {
    /** Engine enforces schema via native constrained decoding / grammar. */
    NATIVE_CONSTRAINED,

    /** Free generation + post-hoc schema validation. */
    POST_VALIDATE,

    /** Post-validation with limited repair retries (attempt-capped). */
    REPAIR_RETRY,

    /** Cell / engine cannot satisfy structured output. */
    UNSUPPORTED,
    ;

    companion object {
        fun fromWire(name: String): StructuredMode? =
            entries.firstOrNull { it.name == name }

        fun requireFromWire(name: String): StructuredMode =
            fromWire(name) ?: error("Unknown StructuredMode (fail closed): $name")
    }
}

/**
 * Validation outcome codes for structured results (FEAT-TOOLS §6).
 * Wire-stable short codes — not catalog error codes.
 */
enum class ValidationStatusCode {
    OK,
    SCHEMA_REJECTED,
    SCHEMA_BOMB,
    MODE_UNSUPPORTED,
    MODE_POLICY_DENIED,
    ATTEMPT_EXHAUSTED,
    OUTPUT_INVALID,
    CAPABILITY_BLOCKED,
    ;

    companion object {
        fun fromWire(name: String): ValidationStatusCode? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * Lifecycle of a host-bound tool proposal (FEAT-TOOLS §3–§4).
 * Distinct from REQUEST FSM; feature-scoped only.
 */
enum class ToolProposalState {
    /** Model emitted proposal; host has not yet committed a result. */
    PROPOSED,

    /** Host claimed execution intent (optional intermediate). */
    HOST_CLAIMED,

    /** Host submitted a terminal ToolResult successfully. */
    RESULT_COMMITTED,

    /** Same claim key / different payload (IDEMPOTENCY_CONFLICT). */
    CONFLICT,

    /**
     * Host outcome is unprovable after reply loss / crash.
     * Session must not invent opposite KV state (FEAT-TOOLS §4/§7.5).
     */
    UNCERTAIN,

    /** Explicit cancel before host result. */
    CANCELLED,
    ;

    companion object {
        fun fromWire(name: String): ToolProposalState? =
            entries.firstOrNull { it.name == name }
    }
}

/** Who is allowed to execute tools — always HOST, never OmniLLM runtime. */
enum class ToolExecutionOwner {
    /** Only the authorized host principal may submit ToolResult. */
    HOST,

    /** OmniLLM platform never executes tools (FEAT-TOOLS §3 non-execution). */
    PLATFORM_FORBIDDEN,
}
