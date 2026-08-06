package com.omnillm.engines.llamacpp.mapping

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.llamacpp.native.NativeError
import com.omnillm.engines.llamacpp.native.NativeErrorCode

/**
 * Maps native return codes / logs to catalog [OmniError] (ENGINE-LLAMACPP §9).
 *
 * - No raw native pointers or filesystem paths in [OmniError.details]
 * - Unsupported parameters → [OmniError.CAPABILITY_UNSUPPORTED] or INVALID_REQUEST
 * - Worker crash → [OmniError.WORKER_DIED]
 */
object ErrorMapper {

    fun toOmniError(error: NativeError): OmniError {
        val details = sanitize(error.attributes) + buildMap {
            put("nativeCode", error.code.name)
            put("engineId", "llama.cpp")
        }
        val msg = error.message
        return when (error.code) {
            NativeErrorCode.NOT_AVAILABLE ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            NativeErrorCode.INVALID_ARGUMENT ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            NativeErrorCode.UNSUPPORTED_PARAMETER ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported parameter",
                    details = details,
                )
            NativeErrorCode.UNSUPPORTED_OPERATION ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported operation",
                    details = details,
                )
            NativeErrorCode.MODEL_OPEN_FAILED ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            NativeErrorCode.CONTEXT_CREATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            NativeErrorCode.TOKENIZE_FAILED ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            NativeErrorCode.GENERATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            NativeErrorCode.CANCELLED ->
                OmniError.CANCELLED(message = msg, details = details)
            NativeErrorCode.DEADLINE_EXCEEDED ->
                OmniError.DEADLINE_EXCEEDED(message = msg, details = details)
            NativeErrorCode.RESOURCE_EXHAUSTED ->
                OmniError.ADMISSION_REJECTED(message = msg, details = details)
            NativeErrorCode.WORKER_CRASH ->
                OmniError.WORKER_DIED(message = msg, details = details)
            NativeErrorCode.INTERNAL ->
                OmniError.INTERNAL(message = msg, details = details)
        }
    }

    /**
     * Fail-closed response for unsupported / unknown request parameters.
     * Never silently ignore (ENGINE-STANDARD §3, failClosedRules).
     */
    fun unsupportedParameter(name: String, reason: String = "not supported by this engine build"): OmniError =
        OmniError.CAPABILITY_UNSUPPORTED(
            message = "unsupported parameter: $name",
            details = mapOf(
                "parameter" to name,
                "reason" to reason,
                "engineId" to "llama.cpp",
            ),
        )

    fun unknownParameter(name: String): OmniError =
        OmniError.INVALID_REQUEST(
            message = "unknown parameter: $name",
            details = mapOf(
                "parameter" to name,
                "engineId" to "llama.cpp",
            ),
        )

    fun capabilityUnknown(capability: String, reason: String): OmniError =
        OmniError.CAPABILITY_UNKNOWN(
            message = reason,
            details = mapOf(
                "capability" to capability,
                "engineId" to "llama.cpp",
            ),
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
