package com.omnillm.features.tools.domain

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.FallbackPolicy

/**
 * Stable tool definition (FEAT-TOOLS §3).
 *
 * Model only produces proposals; OmniLLM never runs shell/URL/file/host commands.
 */
data class ToolDefinition(
    /** Stable tool ID (function name / registry key). */
    val toolId: String,
    val description: String = "",
    /**
     * Schema as nested maps/lists/primitives (JSON-compatible tree).
     * Digest computed over a deterministic canonical walk (not full RFC 8785).
     */
    val parametersSchema: Map<String, Any?>,
    /** Optional data classification label for UI disclosure (opaque string). */
    val dataClassification: String = "unspecified",
    /** Host permission string the host must hold to execute this tool. */
    val requiredHostPermission: String = "",
    val maxArgumentBytes: Int = SchemaLimits.DEFAULT.maxArgumentBytes,
) {
    val schemaDigest: String by lazy { digestSchemaTree(parametersSchema) }

    init {
        require(toolId.isNotBlank()) { "toolId must be non-blank" }
        require(toolId.length <= SchemaLimits.DEFAULT.maxNameLength) {
            "toolId exceeds max name length"
        }
        require(description.toByteArray(Charsets.UTF_8).size <= SchemaLimits.DEFAULT.maxDescriptionBytes) {
            "description exceeds max bytes"
        }
        require(maxArgumentBytes > 0) { "maxArgumentBytes must be positive" }
    }
}

/**
 * Model-emitted tool call proposal (FEAT-TOOLS §3).
 * OmniLLM records this only — never auto-executes.
 */
data class ToolProposal(
    val proposalId: String,
    val requestId: String,
    val toolId: String,
    val schemaDigest: String,
    /** Arguments as opaque JSON string (bounded; may be redacted in traces). */
    val argumentsJson: String,
    val attempt: Int = 1,
    val state: ToolProposalState = ToolProposalState.PROPOSED,
    val createdAtEpochMs: Long,
) {
    init {
        require(proposalId.isNotBlank()) { "proposalId must be non-blank" }
        require(requestId.isNotBlank()) { "requestId must be non-blank" }
        require(toolId.isNotBlank()) { "toolId must be non-blank" }
        require(schemaDigest.isNotBlank()) { "schemaDigest must be non-blank" }
        require(attempt >= 1) { "attempt must be >= 1" }
        require(createdAtEpochMs >= 0L) { "createdAtEpochMs must be non-negative" }
    }

    val argumentsByteLength: Int
        get() = argumentsJson.toByteArray(Charsets.UTF_8).size
}

/**
 * Host-submitted tool result bound to proposal + idempotency (FEAT-TOOLS §3–§4).
 */
data class ToolResult(
    val requestId: String,
    val proposalId: String,
    val attempt: Int,
    val idempotencyKey: String,
    /** Canonical payload digest of the result body (not the body itself). */
    val resultPayloadDigest: String,
    /** Optional bounded result body for host session commit; never auto-traced. */
    val resultBodyJson: String? = null,
    val isError: Boolean = false,
    val submittedAtEpochMs: Long,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must be non-blank" }
        require(proposalId.isNotBlank()) { "proposalId must be non-blank" }
        require(attempt >= 1) { "attempt must be >= 1" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= 128) {
            "idempotencyKey exceeds 128 bytes"
        }
        require(resultPayloadDigest.isNotBlank()) { "resultPayloadDigest must be non-blank" }
        require(submittedAtEpochMs >= 0L) { "submittedAtEpochMs must be non-negative" }
    }
}

/** Structured / tools request identity (ADR-004/005). */
data class ToolsRequestIdentity(
    val requestId: String,
    val idempotencyKey: String,
    val canonicalInputDigest: String,
) {
    init {
        require(REQUEST_ID_UUID.matches(requestId.lowercase())) {
            "requestId must be a UUID string"
        }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(idempotencyKey.toByteArray(Charsets.UTF_8).size <= 128) {
            "idempotencyKey exceeds 128 bytes"
        }
        require(HEX64.matches(canonicalInputDigest)) {
            "canonicalInputDigest must be 64-char lower-case hex SHA-256"
        }
    }

    companion object {
        private val REQUEST_ID_UUID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Structured-output request envelope (response_format json_schema path).
 */
data class StructuredOutputSpec(
    val identity: ToolsRequestIdentity,
    val modelRevisionId: String,
    val schemaName: String,
    val schema: Map<String, Any?>,
    val strict: Boolean = true,
    val callerPolicy: StructuredCallerPolicy = StructuredCallerPolicy.NATIVE_ONLY,
    /**
     * Catalog [FallbackPolicy] for routing (FEAT-ROUTING).
     * Cross-revision only when ALLOW_LIST + explicit allowlist — never silent.
     */
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.NONE,
    val allowedRevisionIds: Set<String> = emptySet(),
    val deadlineElapsedRealtimeNanos: Long = Long.MAX_VALUE / 4,
) {
    val schemaDigest: String by lazy { digestSchemaTree(schema) }

    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
        require(schemaName.isNotBlank()) { "schemaName must be non-blank" }
        require(schemaName.length <= SchemaLimits.DEFAULT.maxNameLength) {
            "schemaName exceeds max name length"
        }
        if (fallbackPolicy == FallbackPolicy.ALLOW_LIST) {
            require(allowedRevisionIds.isNotEmpty()) {
                "ALLOW_LIST requires non-empty allowedRevisionIds"
            }
        }
    }
}

/**
 * Tool-calling chat request envelope.
 */
data class ToolCallingSpec(
    val identity: ToolsRequestIdentity,
    val modelRevisionId: String,
    val tools: List<ToolDefinition>,
    val toolChoice: ToolChoice = ToolChoice.Auto,
    val messages: List<ToolsChatMessage>,
    val callerPolicy: StructuredCallerPolicy = StructuredCallerPolicy.POST_VALIDATE_ALLOWED,
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.NONE,
    val allowedRevisionIds: Set<String> = emptySet(),
    /** Optional per-request tool allowlist (LAN/AIDL scope projection). */
    val toolAllowlist: Set<String>? = null,
    val deadlineElapsedRealtimeNanos: Long = Long.MAX_VALUE / 4,
) {
    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
        require(tools.isNotEmpty()) { "tools must be non-empty for tool-calling" }
        require(messages.isNotEmpty()) { "messages must be non-empty" }
        if (fallbackPolicy == FallbackPolicy.ALLOW_LIST) {
            require(allowedRevisionIds.isNotEmpty()) {
                "ALLOW_LIST requires non-empty allowedRevisionIds"
            }
        }
    }
}

sealed class ToolChoice {
    data object None : ToolChoice()
    data object Auto : ToolChoice()
    data object Required : ToolChoice()
    data class Named(val toolId: String) : ToolChoice() {
        init {
            require(toolId.isNotBlank()) { "toolId must be non-blank" }
        }
    }
}

data class ToolsChatMessage(
    val role: String,
    val content: String?,
) {
    init {
        require(role.isNotBlank()) { "role must be non-blank" }
    }
}

/**
 * Deterministic digest of a JSON-compatible tree for request hash / cache keys.
 * Domain-separated from identity hashes; not a catalog identity type.
 */
fun digestSchemaTree(tree: Map<String, Any?>): String {
    val canonical = canonicalizeTree(tree)
    return IdentityHashing.sha256Hex("OmniLLM.SchemaDigest.v1\n$canonical")
}

fun digestPayload(utf8: String): String =
    IdentityHashing.sha256Hex("OmniLLM.ToolPayloadDigest.v1\n$utf8")

/**
 * Minimal deterministic tree walk (sorted object keys, typed tags).
 * Sufficient for equality of feature-local schemas; not full RFC 8785.
 */
internal fun canonicalizeTree(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> if (value) "true" else "false"
    is Number -> value.toString()
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Map<*, *> -> {
        val entries = value.entries
            .map { (k, v) -> k.toString() to canonicalizeTree(v) }
            .sortedBy { it.first }
        entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":$v"
        }
    }
    is List<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
        canonicalizeTree(it)
    }
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") {
        canonicalizeTree(it)
    }
    else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
