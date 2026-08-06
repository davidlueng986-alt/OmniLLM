package com.omnillm.engines.ortgenai.mapping

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.ortgenai.session.GenAiError
import com.omnillm.engines.ortgenai.session.GenAiErrorCode

/**
 * Catalog error helpers for the ORT GenAI adapter (ENGINE-ORTGENAI §8).
 *
 * - No raw native pointers or filesystem paths in [OmniError.details]
 * - Unproven operations → [OmniError.CAPABILITY_UNKNOWN]
 * - Explicitly unsupported parameters → [OmniError.CAPABILITY_UNSUPPORTED]
 * - Provider / worker crash → [OmniError.WORKER_DIED]
 * - Never silently ignore unknown parameters (ENGINE-STANDARD §3)
 */
object ErrorMapper {

    private const val ENGINE_ID: String = "ONNX-Runtime-GenAI"

    fun toOmniError(error: GenAiError): OmniError {
        val details = sanitize(error.attributes) + buildMap {
            put("genAiCode", error.code.name)
            put("engineId", ENGINE_ID)
        }
        val msg = error.message
        return when (error.code) {
            GenAiErrorCode.NOT_AVAILABLE ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            GenAiErrorCode.INVALID_ARGUMENT ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            GenAiErrorCode.UNSUPPORTED_PARAMETER ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported parameter",
                    details = details,
                )
            GenAiErrorCode.UNSUPPORTED_OPERATION ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported operation",
                    details = details,
                )
            GenAiErrorCode.UNKNOWN_CAPABILITY ->
                OmniError.CAPABILITY_UNKNOWN(
                    message = msg ?: "capability unknown until qualification",
                    details = details,
                )
            GenAiErrorCode.MODEL_OPEN_FAILED ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            GenAiErrorCode.PROVIDER_INIT_FAILED ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            GenAiErrorCode.GRAPH_OPT_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            GenAiErrorCode.COMPILE_CACHE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            GenAiErrorCode.CONFIG_PARSE_FAILED ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            GenAiErrorCode.SESSION_CREATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            GenAiErrorCode.TOKENIZE_FAILED ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            GenAiErrorCode.GENERATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            GenAiErrorCode.CANCELLED ->
                OmniError.CANCELLED(message = msg, details = details)
            GenAiErrorCode.DEADLINE_EXCEEDED ->
                OmniError.DEADLINE_EXCEEDED(message = msg, details = details)
            GenAiErrorCode.RESOURCE_EXHAUSTED ->
                OmniError.ADMISSION_REJECTED(message = msg, details = details)
            GenAiErrorCode.WORKER_CRASH ->
                OmniError.WORKER_DIED(message = msg, details = details)
            GenAiErrorCode.PROVIDER_CRASH ->
                OmniError.WORKER_DIED(message = msg, details = details)
            GenAiErrorCode.INTERNAL ->
                OmniError.INTERNAL(message = msg, details = details)
        }
    }

    /**
     * Unproven / unqualified capability — Registry exposure remains UNKNOWN.
     * Do **not** project as SUPPORTED.
     */
    fun capabilityUnknown(capability: String, reason: String): OmniError =
        OmniError.CAPABILITY_UNKNOWN(
            message = reason,
            details = mapOf(
                "capability" to capability,
                "engineId" to ENGINE_ID,
                "qualificationStatus" to "UNQUALIFIED",
            ),
        )

    fun unsupportedParameter(
        name: String,
        reason: String = "not supported by this engine build",
    ): OmniError =
        OmniError.CAPABILITY_UNSUPPORTED(
            message = "unsupported parameter: $name",
            details = mapOf(
                "parameter" to name,
                "reason" to reason,
                "engineId" to ENGINE_ID,
            ),
        )

    fun unknownParameter(name: String): OmniError =
        OmniError.INVALID_REQUEST(
            message = "unknown parameter: $name",
            details = mapOf(
                "parameter" to name,
                "engineId" to ENGINE_ID,
            ),
        )

    /**
     * Operation not yet backed by a locked native GenAI/ORT build.
     * Scaffold default for commit/execute paths.
     */
    fun operationUnqualified(operation: String): OmniError =
        capabilityUnknown(
            capability = operation,
            reason = "ONNX Runtime GenAI $operation is design-complete but UNQUALIFIED; " +
                "no EngineBuildId evidence cell projects SUPPORTED",
        )

    /** Strip path-like or pointer-like values from attribute maps. */
    fun sanitize(attributes: Map<String, String>): Map<String, String> {
        if (attributes.isEmpty()) return emptyMap()
        return attributes.mapValues { (k, v) ->
            when {
                k.contains("path", ignoreCase = true) -> REDACTED
                k.contains("pointer", ignoreCase = true) -> REDACTED
                k.contains("fd", ignoreCase = true) && v.all { it.isDigit() || it == '-' } -> REDACTED
                v.startsWith("/") || v.contains(":\\") -> REDACTED
                v.startsWith("0x") -> REDACTED
                else -> v
            }
        }
    }

    const val REDACTED: String = "<redacted>"
}
