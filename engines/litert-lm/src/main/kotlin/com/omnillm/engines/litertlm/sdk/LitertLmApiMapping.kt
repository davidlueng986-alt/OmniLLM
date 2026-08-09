package com.omnillm.engines.litertlm.sdk

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import java.util.concurrent.CancellationException

/**
 * Pure mapping between OmniLLM control-plane values and the official LiteRT-LM Kotlin
 * API types (`com.google.ai.edge.litertlm.*`, pinned 0.15.0 in UPSTREAM.lock).
 *
 * These functions only touch pure data classes (EngineConfig / Backend / Contents /
 * Message) — never Engine / Conversation — so host unit tests can exercise them against
 * the real SDK types without loading native libraries.
 *
 * Security rules (ENGINE-LITERT §8/§9): no raw paths in error attributes, opaque digests
 * on the wire, unproven backends fail closed.
 */
object LitertLmApiMapping {

    /**
     * OmniLLM backend label → official [Backend].
     * Returns null for unknown / unproven backends (fail closed; never invent SUPPORTED).
     */
    fun toBackend(
        backend: String,
        attributes: Map<String, String> = emptyMap(),
    ): Backend? = when (backend.lowercase()) {
        "cpu" -> Backend.CPU()
        "gpu" -> Backend.GPU()
        "npu" -> Backend.NPU(attributes["npuNativeLibraryDir"].orEmpty())
        else -> null
    }

    /**
     * Build the official [EngineConfig] for a privileged-resolved model path.
     * [resolvedModelPath] must come from the privileged path/FD broker — never a raw
     * client-supplied absolute path (ENGINE-LITERT §8).
     */
    fun toEngineConfig(
        resolvedModelPath: String,
        backend: Backend,
        attributes: Map<String, String> = emptyMap(),
    ): EngineConfig =
        EngineConfig(
            modelPath = resolvedModelPath,
            backend = backend,
            cacheDir = attributes["cacheDir"]?.takeIf { it.isNotBlank() },
        )

    /**
     * Extract all text content from a LiteRT-LM [Message]. Streaming emissions carry the
     * cumulative response text (each emission replaces the previous), so callers combine
     * with [deltaText] for deltas.
     */
    fun extractText(message: Message): String {
        if (message.role == Role.TOOL) return ""
        return message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }
    }

    /** Convenience: text contents from a [Contents] object (pure data). */
    fun extractText(contents: Contents): String =
        contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString(separator = "") { it.text }

    /**
     * Delta between consecutive streaming emissions. LiteRT-LM emits cumulative text, so
     * the new chunk is the suffix after the previously seen text; when the emission is not
     * a strict prefix extension (tool interleave), fall back to the full text; an identical
     * emission contributes no delta.
     */
    fun deltaText(previous: String, current: String): String =
        when {
            current.isEmpty() -> ""
            current == previous -> ""
            current.startsWith(previous) -> current.substring(previous.length)
            else -> current
        }

    /** Role label from an official [Message] (never a raw enum on the wire). */
    fun roleLabel(message: Message): String = message.role.name.lowercase()

    /**
     * Map official SDK throwables to the engine error contract. LiteRtLmJniException is
     * the documented native error; CancellationException is the documented cancel signal
     * (statusCode kCancelled → callback.onError(CancellationException)).
     */
    fun mapThrowable(throwable: Throwable): SdkError =
        when (throwable) {
            is CancellationException -> SdkError(
                code = SdkErrorCode.CANCELLED,
                message = "LiteRT-LM generation cancelled",
                attributes = mapOf("exception" to "CancellationException"),
            )
            is LiteRtLmJniException -> SdkError(
                code = SdkErrorCode.GENERATE_FAILED,
                message = throwable.message,
                attributes = mapOf("exception" to "LiteRtLmJniException"),
            )
            is NoClassDefFoundError -> SdkError(
                code = SdkErrorCode.NOT_AVAILABLE,
                message = "LiteRT-LM SDK classes not on classpath",
                attributes = mapOf("exception" to "NoClassDefFoundError"),
            )
            is UnsatisfiedLinkError -> SdkError(
                code = SdkErrorCode.NOT_AVAILABLE,
                message = "LiteRT-LM native library failed to load",
                attributes = mapOf("exception" to "UnsatisfiedLinkError"),
            )
            is IllegalArgumentException -> SdkError(
                code = SdkErrorCode.INVALID_ARGUMENT,
                message = throwable.message,
                attributes = mapOf("exception" to "IllegalArgumentException"),
            )
            is IllegalStateException -> SdkError(
                code = SdkErrorCode.GENERATE_FAILED,
                message = throwable.message,
                attributes = mapOf("exception" to "IllegalStateException"),
            )
            else -> SdkError(
                code = SdkErrorCode.INTERNAL,
                message = throwable.message,
                attributes = mapOf("exception" to throwable.javaClass.simpleName),
            )
        }

    /** Map official SDK throwable in the LOAD phase (native open/initialize). */
    fun mapLoadThrowable(throwable: Throwable): SdkError =
        when (throwable) {
            is LiteRtLmJniException -> SdkError(
                code = SdkErrorCode.MODEL_OPEN_FAILED,
                message = throwable.message,
                attributes = mapOf("exception" to "LiteRtLmJniException"),
            )
            else -> mapThrowable(throwable)
        }
}
