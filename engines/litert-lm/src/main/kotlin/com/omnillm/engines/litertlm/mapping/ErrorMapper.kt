package com.omnillm.engines.litertlm.mapping

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.litertlm.sdk.SdkError
import com.omnillm.engines.litertlm.sdk.SdkErrorCode

/**
 * Maps SDK return codes / logs to catalog [OmniError] (ENGINE-LITERT §9).
 *
 * - No raw SDK pointers or filesystem paths in [OmniError.details]
 * - Unsupported parameters → [OmniError.CAPABILITY_UNSUPPORTED] or INVALID_REQUEST
 * - Unproven operations → [OmniError.CAPABILITY_UNKNOWN]
 * - Worker crash → [OmniError.WORKER_DIED]
 */
object ErrorMapper {

    private const val ENGINE_ID: String = "LiteRT-LM"

    fun toOmniError(error: SdkError): OmniError {
        val details = sanitize(error.attributes) + buildMap {
            put("sdkCode", error.code.name)
            put("engineId", ENGINE_ID)
        }
        val msg = error.message
        return when (error.code) {
            SdkErrorCode.NOT_AVAILABLE ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            SdkErrorCode.INVALID_ARGUMENT ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            SdkErrorCode.UNSUPPORTED_PARAMETER ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported parameter",
                    details = details,
                )
            SdkErrorCode.UNSUPPORTED_OPERATION ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported operation",
                    details = details,
                )
            SdkErrorCode.CAPABILITY_UNKNOWN ->
                OmniError.CAPABILITY_UNKNOWN(
                    message = msg ?: "capability unknown for this LiteRT-LM build",
                    details = details,
                )
            SdkErrorCode.MODEL_OPEN_FAILED ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            SdkErrorCode.COMPILE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            SdkErrorCode.CONVERSATION_CREATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            SdkErrorCode.TOKENIZE_FAILED ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            SdkErrorCode.GENERATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            SdkErrorCode.CANCELLED ->
                OmniError.CANCELLED(message = msg, details = details)
            SdkErrorCode.DEADLINE_EXCEEDED ->
                OmniError.DEADLINE_EXCEEDED(message = msg, details = details)
            SdkErrorCode.RESOURCE_EXHAUSTED ->
                OmniError.ADMISSION_REJECTED(message = msg, details = details)
            SdkErrorCode.WORKER_CRASH ->
                OmniError.WORKER_DIED(message = msg, details = details)
            SdkErrorCode.INTERNAL ->
                OmniError.INTERNAL(message = msg, details = details)
        }
    }

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

    fun capabilityUnknown(capability: String, reason: String): OmniError =
        OmniError.CAPABILITY_UNKNOWN(
            message = reason,
            details = mapOf(
                "capability" to capability,
                "engineId" to ENGINE_ID,
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
