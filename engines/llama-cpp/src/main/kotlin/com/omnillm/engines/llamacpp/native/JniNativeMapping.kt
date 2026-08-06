package com.omnillm.engines.llamacpp.native

/**
 * Pure host-testable mapping between native status/event wire values and Kotlin types.
 * Kept free of [System.loadLibrary] so unit tests run on JVM hosts without NDK.
 *
 * Wire status integers must stay aligned with `omnillm_llama.h` OmnillmLlamaStatus.
 */
object JniNativeMapping {

    const val LIBRARY_NAME: String = "omnillm_llama"
    /** Must stay aligned with OMNILLM_LLAMA_ABI_VERSION in omnillm_llama.h. */
    const val ABI_VERSION_EXPECTED: Int = 2

    /**
     * Installation / path token that selects the EXPERIMENTAL_FIXTURE C++ path.
     * Must be passed **explicitly** (or via `fixture:` prefix). Broker-only
     * keys without this marker fail closed — never silent fixture.
     */
    const val EXPERIMENTAL_FIXTURE: String = "EXPERIMENTAL_FIXTURE"

    /** Wire status codes (omnillm_llama.h). */
    object Status {
        const val OK: Int = 0
        const val NOT_AVAILABLE: Int = 1
        const val INVALID_ARGUMENT: Int = 2
        const val UNSUPPORTED_PARAMETER: Int = 3
        const val UNSUPPORTED_OPERATION: Int = 4
        const val MODEL_OPEN_FAILED: Int = 5
        const val CONTEXT_CREATE_FAILED: Int = 6
        const val TOKENIZE_FAILED: Int = 7
        const val GENERATE_FAILED: Int = 8
        const val CANCELLED: Int = 9
        const val DEADLINE_EXCEEDED: Int = 10
        const val RESOURCE_EXHAUSTED: Int = 11
        const val INTERNAL: Int = 12
        const val WORKER_CRASH: Int = 13
    }

    /** Wire stream kinds (omnillm_llama.h OmnillmLlamaStreamKind). */
    object StreamKindWire {
        const val METADATA: Int = 0
        const val TOKEN_DELTA: Int = 1
        const val USAGE: Int = 2
        const val DIAGNOSTIC: Int = 3
        const val WARNING: Int = 4
        const val STOP: Int = 5
    }

    fun statusToErrorCode(status: Int): NativeErrorCode =
        when (status) {
            Status.OK -> NativeErrorCode.INTERNAL // caller should not map OK as error
            Status.NOT_AVAILABLE -> NativeErrorCode.NOT_AVAILABLE
            Status.INVALID_ARGUMENT -> NativeErrorCode.INVALID_ARGUMENT
            Status.UNSUPPORTED_PARAMETER -> NativeErrorCode.UNSUPPORTED_PARAMETER
            Status.UNSUPPORTED_OPERATION -> NativeErrorCode.UNSUPPORTED_OPERATION
            Status.MODEL_OPEN_FAILED -> NativeErrorCode.MODEL_OPEN_FAILED
            Status.CONTEXT_CREATE_FAILED -> NativeErrorCode.CONTEXT_CREATE_FAILED
            Status.TOKENIZE_FAILED -> NativeErrorCode.TOKENIZE_FAILED
            Status.GENERATE_FAILED -> NativeErrorCode.GENERATE_FAILED
            Status.CANCELLED -> NativeErrorCode.CANCELLED
            Status.DEADLINE_EXCEEDED -> NativeErrorCode.DEADLINE_EXCEEDED
            Status.RESOURCE_EXHAUSTED -> NativeErrorCode.RESOURCE_EXHAUSTED
            Status.WORKER_CRASH -> NativeErrorCode.WORKER_CRASH
            Status.INTERNAL -> NativeErrorCode.INTERNAL
            else -> NativeErrorCode.INTERNAL
        }

    fun statusToResultUnit(status: Int, message: String? = null): NativeResult<Unit> {
        if (status == Status.OK) return NativeResult.ok(Unit)
        return NativeResult.err(
            NativeError(
                code = statusToErrorCode(status),
                message = message ?: defaultMessage(status),
                attributes = mapOf("nativeStatus" to status.toString()),
            ),
        )
    }

    fun <T> statusToErr(status: Int, message: String? = null): NativeResult<T> =
        NativeResult.err(
            NativeError(
                code = statusToErrorCode(status),
                message = message ?: defaultMessage(status),
                attributes = mapOf("nativeStatus" to status.toString()),
            ),
        )

    fun streamKindFromWire(kind: Int): NativeStreamKind? =
        when (kind) {
            StreamKindWire.METADATA -> NativeStreamKind.METADATA
            StreamKindWire.TOKEN_DELTA -> NativeStreamKind.TOKEN_DELTA
            StreamKindWire.USAGE -> NativeStreamKind.USAGE
            StreamKindWire.DIAGNOSTIC -> NativeStreamKind.DIAGNOSTIC
            StreamKindWire.WARNING -> NativeStreamKind.WARNING
            StreamKindWire.STOP -> NativeStreamKind.STOP
            else -> null
        }

    /**
     * Parse compact `k=v;k=v` attribute bags from native (no JSON dependency).
     * Empty keys/values are dropped. Values may not contain `;`.
     */
    fun parseAttributesKv(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (part in raw.split(';')) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            if (eq <= 0) continue
            val k = piece.substring(0, eq).trim()
            val v = piece.substring(eq + 1).trim()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    fun encodeAttributesKv(attrs: Map<String, String>): String =
        attrs.entries.joinToString(";") { (k, v) -> "$k=$v" }

    fun defaultMessage(status: Int): String =
        when (status) {
            Status.NOT_AVAILABLE -> "native library not available"
            Status.INVALID_ARGUMENT -> "invalid native argument"
            Status.UNSUPPORTED_PARAMETER -> "unsupported parameter"
            Status.UNSUPPORTED_OPERATION -> "unsupported operation"
            Status.MODEL_OPEN_FAILED -> "model open failed"
            Status.CONTEXT_CREATE_FAILED -> "context create failed"
            Status.TOKENIZE_FAILED -> "tokenize failed"
            Status.GENERATE_FAILED -> "generate failed"
            Status.CANCELLED -> "cancelled"
            Status.DEADLINE_EXCEEDED -> "deadline exceeded"
            Status.RESOURCE_EXHAUSTED -> "resource exhausted"
            Status.WORKER_CRASH -> "worker crash"
            else -> "native internal error"
        }

    fun isOk(status: Int): Boolean = status == Status.OK
}
