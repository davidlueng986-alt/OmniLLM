package com.omnillm.engines.mllm.mapping

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.mllm.server.ServerError
import com.omnillm.engines.mllm.server.ServerErrorCode

/**
 * Maps embedded-server / AAR outcomes to catalog [OmniError] (ENGINE-MLLM §8).
 *
 * - No raw paths, ports credentials, or native pointers in [OmniError.details]
 * - Unsupported parameters → [OmniError.CAPABILITY_UNSUPPORTED] or INVALID_REQUEST
 * - Unproven / unmapped capability → [OmniError.CAPABILITY_UNKNOWN]
 * - Server crash → [OmniError.WORKER_DIED] (sessions poisoned)
 * - Channel policy violations → fail closed (never silent LAN bind)
 */
object ErrorMapper {

    const val ENGINE_ID: String = "mllm"
    const val REDACTED: String = "<redacted>"

    fun toOmniError(error: ServerError): OmniError {
        val details = sanitize(error.attributes) + buildMap {
            put("serverCode", error.code.name)
            put("engineId", ENGINE_ID)
        }
        val msg = error.message
        return when (error.code) {
            ServerErrorCode.NOT_AVAILABLE ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            ServerErrorCode.NOT_LOCKED ->
                capabilityUnknown(
                    capability = error.attributes["capability"] ?: "upstream.lock",
                    reason = msg ?: "mllm UPSTREAM.lock incomplete; operations not executable",
                )
            ServerErrorCode.INVALID_ARGUMENT ->
                OmniError.INVALID_REQUEST(message = msg, details = details)
            ServerErrorCode.UNSUPPORTED_PARAMETER ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported parameter",
                    details = details,
                )
            ServerErrorCode.UNSUPPORTED_OPERATION ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "unsupported operation",
                    details = details,
                )
            ServerErrorCode.CAPABILITY_UNKNOWN ->
                capabilityUnknown(
                    capability = error.attributes["capability"] ?: "mllm.operation",
                    reason = msg ?: "capability unknown until qualified",
                )
            ServerErrorCode.SERVER_UNREACHABLE ->
                OmniError.INTERNAL(message = msg, details = details)
            ServerErrorCode.AUTH_FAILED ->
                OmniError.UNAUTHORIZED(message = msg, details = details)
            ServerErrorCode.CHANNEL_POLICY ->
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = msg ?: "private channel policy refused",
                    details = details,
                )
            ServerErrorCode.MODEL_OPEN_FAILED ->
                OmniError.ASSET_NOT_READY(message = msg, details = details)
            ServerErrorCode.SESSION_CREATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            ServerErrorCode.GENERATE_FAILED ->
                OmniError.INTERNAL(message = msg, details = details)
            ServerErrorCode.CANCELLED ->
                OmniError.CANCELLED(message = msg, details = details)
            ServerErrorCode.DEADLINE_EXCEEDED ->
                OmniError.DEADLINE_EXCEEDED(message = msg, details = details)
            ServerErrorCode.RESOURCE_EXHAUSTED ->
                OmniError.ADMISSION_REJECTED(message = msg, details = details)
            ServerErrorCode.SERVER_CRASH ->
                OmniError.WORKER_DIED(message = msg, details = details)
            ServerErrorCode.SERVER_IMPERSONATED ->
                OmniError.WORKER_DIED(message = msg, details = details)
            ServerErrorCode.INTERNAL ->
                OmniError.INTERNAL(message = msg, details = details)
        }
    }

    fun unsupportedParameter(name: String, reason: String = "not supported by this engine build"): OmniError =
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

    /**
     * Fail-closed for any execute-path operation while design is BASELINE
     * but lock/cells remain unproven.
     */
    fun unprovenOperation(operation: String): OmniError =
        capabilityUnknown(
            capability = "mllm.$operation",
            reason = "mllm operation $operation is UNKNOWN until UPSTREAM.lock " +
                "and qualification cells have PASS evidence (ENGINE-MLLM)",
        )

    /** Strip path-like, credential, or pointer-like values from attribute maps. */
    fun sanitize(attributes: Map<String, String>): Map<String, String> {
        if (attributes.isEmpty()) return emptyMap()
        return attributes.mapValues { (k, v) ->
            when {
                k.contains("path", ignoreCase = true) -> REDACTED
                k.contains("pointer", ignoreCase = true) -> REDACTED
                k.contains("credential", ignoreCase = true) -> REDACTED
                k.contains("token", ignoreCase = true) &&
                    k != "operationToken" &&
                    !k.equals("stopReason", ignoreCase = true) -> REDACTED
                k.contains("fd", ignoreCase = true) && v.all { it.isDigit() || it == '-' } -> REDACTED
                k.contains("port", ignoreCase = true) && v.all { it.isDigit() } -> REDACTED
                v.startsWith("/") || v.contains(":\\") -> REDACTED
                v.startsWith("0x") -> REDACTED
                else -> v
            }
        }
    }
}
