package com.omnillm.features.tools.domain

/**
 * Entrance limits for JSON Schema subset and tool definitions (FEAT-TOOLS §1/§5).
 *
 * Bounds align with OpenAPI `ToolDefinition` / `JsonSchemaResponseFormat`
 * (`specs/openapi/omnillm.openapi.yaml`) where specified; remaining caps are
 * fixed bomb-protection defaults so validation never reaches engine compile
 * on pathological inputs (acceptance: schema bomb).
 */
data class SchemaLimits(
    /** OpenAPI tools.maxItems = 128. */
    val maxToolCount: Int = 128,
    /** OpenAPI schema maxProperties = 4096. */
    val maxProperties: Int = 4096,
    /** Total UTF-8 bytes of schema / tools canonical JSON. */
    val maxSchemaBytes: Int = 256 * 1024,
    val maxDepth: Int = 32,
    val maxNodes: Int = 4096,
    val maxEnumValues: Int = 1024,
    val maxEnumValueBytes: Int = 1024,
    val maxRegexLength: Int = 512,
    val maxReferenceExpansions: Int = 64,
    val maxArgumentBytes: Int = 64 * 1024,
    val maxOutputBytes: Int = 256 * 1024,
    val maxDescriptionBytes: Int = 2048,
    val maxNameLength: Int = 128,
    /** Soft wall-clock budget for schema walk (ms). */
    val maxValidateWallMs: Long = 200,
) {
    init {
        require(maxToolCount > 0) { "maxToolCount must be positive" }
        require(maxProperties > 0) { "maxProperties must be positive" }
        require(maxSchemaBytes > 0) { "maxSchemaBytes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxNodes > 0) { "maxNodes must be positive" }
        require(maxEnumValues > 0) { "maxEnumValues must be positive" }
        require(maxArgumentBytes > 0) { "maxArgumentBytes must be positive" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be positive" }
        require(maxValidateWallMs > 0L) { "maxValidateWallMs must be positive" }
    }

    companion object {
        val DEFAULT: SchemaLimits = SchemaLimits()
    }
}

/**
 * Caller policy for structured / tool modes (FEAT-TOOLS §2).
 *
 * [allowPostValidate] / [allowRepairRetry] gate non-native modes.
 * System must not silently fall back from NATIVE_CONSTRAINED to free text.
 */
data class StructuredCallerPolicy(
    val allowPostValidate: Boolean = false,
    val allowRepairRetry: Boolean = false,
    val maxValidationAttempts: Int = 1,
    val maxRepairAttempts: Int = 0,
    val maxTokenBudget: Int? = null,
    /**
     * If true, caller accepts only [StructuredMode.NATIVE_CONSTRAINED].
     * Conflicts with allowPostValidate/allowRepairRetry ⇒ deny at admission.
     */
    val requireNativeConstrained: Boolean = false,
) {
    init {
        require(maxValidationAttempts >= 1) { "maxValidationAttempts must be >= 1" }
        require(maxRepairAttempts >= 0) { "maxRepairAttempts must be >= 0" }
        maxTokenBudget?.let { require(it > 0) { "maxTokenBudget must be positive" } }
        if (requireNativeConstrained) {
            require(!allowPostValidate && !allowRepairRetry) {
                "requireNativeConstrained conflicts with non-native allow flags"
            }
        }
    }

    companion object {
        /** Strict default: native only, single attempt, no silent demotion. */
        val NATIVE_ONLY: StructuredCallerPolicy = StructuredCallerPolicy(
            allowPostValidate = false,
            allowRepairRetry = false,
            maxValidationAttempts = 1,
            maxRepairAttempts = 0,
            requireNativeConstrained = true,
        )

        val POST_VALIDATE_ALLOWED: StructuredCallerPolicy = StructuredCallerPolicy(
            allowPostValidate = true,
            allowRepairRetry = false,
            maxValidationAttempts = 3,
            maxRepairAttempts = 0,
            requireNativeConstrained = false,
        )

        val REPAIR_ALLOWED: StructuredCallerPolicy = StructuredCallerPolicy(
            allowPostValidate = true,
            allowRepairRetry = true,
            maxValidationAttempts = 3,
            maxRepairAttempts = 2,
            requireNativeConstrained = false,
        )
    }
}
