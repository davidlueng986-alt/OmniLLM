package com.omnillm.engines.llamacpp.native

import java.util.concurrent.atomic.AtomicInteger

/**
 * Production [NativeBackend] backed by packaged `libomnillm_llama.so` (ENGINE-LLAMACPP).
 *
 * - Loads only the verified library name [JniNativeMapping.LIBRARY_NAME]
 * - Returns opaque tokens; never exposes raw pointers on wire
 * - Fail closed when the library is missing or ABI mismatches
 * - Supports path/FD GGUF load when upstream is linked, and EXPERIMENTAL_FIXTURE
 *
 * Prefer [createOrNull] on the control plane: production must not silently fall
 * back to [StubNativeBackend] (INV-018). Use [StubNativeBackend] only for host unit tests.
 */
class JniNativeBackend private constructor(
    private val libraryLabelCached: String,
    private val abiVersion: Int,
    private val upstreamLinked: Boolean,
) : NativeBackend {

    override fun libraryLabel(): String = libraryLabelCached

    override fun isAvailable(): Boolean = true

    /** Whether this process's .so was built against vendored llama.cpp. */
    fun isUpstreamLinked(): Boolean = upstreamLinked

    override fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome> {
        val outAttrs = arrayOfNulls<String>(1)
        val status = JniNativeBridge.nativeProbe(
            request.backend,
            request.operationToken,
            outAttrs,
        )
        val attrs = JniNativeMapping.parseAttributesKv(outAttrs[0])
        if (!JniNativeMapping.isOk(status)) {
            return JniNativeMapping.statusToErr(status)
        }
        val available = attrs["available"]?.equals("true", ignoreCase = true) ?: true
        return NativeResult.ok(
            NativeProbeOutcome(
                backend = request.backend,
                available = available,
                attributes = attrs + mapOf(
                    "library" to libraryLabelCached,
                    "abiVersion" to abiVersion.toString(),
                    "native" to "true",
                    "upstreamLinked" to upstreamLinked.toString(),
                ),
            ),
        )
    }

    override fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken> {
        val out = arrayOfNulls<String>(1)
        val status = JniNativeBridge.nativeLoadModel(
            request.storageRootKey,
            request.installationKey,
            request.backend,
            request.nCtx,
            request.nThreads,
            request.privilegedLoadTicketId,
            request.resolvedModelPath,
            request.modelFd,
            out,
        )
        if (!JniNativeMapping.isOk(status)) {
            return JniNativeMapping.statusToErr(status)
        }
        val token = out[0]
        if (token.isNullOrEmpty()) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INTERNAL,
                    message = "native load returned empty model token",
                ),
            )
        }
        return NativeResult.ok(NativeModelToken(token))
    }

    override fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken> {
        val out = arrayOfNulls<String>(1)
        val seed = request.seed ?: 0L
        val status = JniNativeBridge.nativeCreateSession(
            model.value,
            request.nCtx,
            seed,
            request.seed != null,
            out,
        )
        if (!JniNativeMapping.isOk(status)) {
            return JniNativeMapping.statusToErr(status)
        }
        val token = out[0]
        if (token.isNullOrEmpty()) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.CONTEXT_CREATE_FAILED,
                    message = "native createSession returned empty token",
                ),
            )
        }
        return NativeResult.ok(NativeSessionToken(token))
    }

    override fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome> {
        val cancelAtomic = AtomicInteger(0)
        if (cancelFlag()) {
            cancelAtomic.set(1)
            JniNativeBridge.nativeRequestCancel(request.operationToken)
        }

        val callback = object : NativeEventCallback {
            override fun onNativeEvent(
                kind: Int,
                payloadDigestHex: String?,
                attributesKv: String?,
            ) {
                // Cooperative cancel: re-check Kotlin flag on each native event.
                if (cancelFlag()) {
                    cancelAtomic.set(1)
                    JniNativeBridge.nativeRequestCancel(request.operationToken)
                }
                val mappedKind = JniNativeMapping.streamKindFromWire(kind) ?: return
                onEvent(
                    NativeStreamEvent(
                        kind = mappedKind,
                        payloadDigestHex = payloadDigestHex?.takeIf { it.isNotEmpty() },
                        attributes = JniNativeMapping.parseAttributesKv(attributesKv),
                    ),
                )
            }
        }

        val outCounts = IntArray(2)
        val outStop = arrayOfNulls<String>(1)
        val status = JniNativeBridge.nativeGenerate(
            session.value,
            request.operationToken,
            request.promptDigestHex,
            request.promptUtf8,
            request.maxTokens,
            request.temperature ?: 0f,
            request.temperature != null,
            request.topP ?: 0f,
            request.topP != null,
            request.topK ?: 0,
            request.topK != null,
            request.stopSequenceCount,
            cancelAtomic,
            callback,
            outCounts,
            outStop,
        )
        if (!JniNativeMapping.isOk(status)) {
            return JniNativeMapping.statusToErr(
                status,
                message = if (status == JniNativeMapping.Status.CANCELLED) {
                    "generate cancelled"
                } else {
                    null
                },
            )
        }
        return NativeResult.ok(
            NativeGenerateOutcome(
                promptTokens = outCounts[0],
                completionTokens = outCounts[1],
                stopReason = outStop[0] ?: "COMPLETED",
            ),
        )
    }

    override fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome> {
        val status = JniNativeBridge.nativeEmbed(
            model.value,
            request.operationToken,
            request.inputDigestHex,
        )
        // Expected fail-closed until pooling qualified.
        return JniNativeMapping.statusToErr(status, message = "embedding pooling not qualified")
    }

    override fun closeSession(session: NativeSessionToken): NativeResult<Unit> =
        JniNativeMapping.statusToResultUnit(
            JniNativeBridge.nativeCloseSession(session.value),
        )

    override fun unloadModel(model: NativeModelToken): NativeResult<Unit> =
        JniNativeMapping.statusToResultUnit(
            JniNativeBridge.nativeUnloadModel(model.value),
        )

    override fun requestCancel(operationToken: String): NativeResult<Unit> =
        JniNativeMapping.statusToResultUnit(
            JniNativeBridge.nativeRequestCancel(operationToken),
        )

    companion object {
        /**
         * Load library and construct backend, or null if missing / ABI mismatch.
         * Production control plane must fail closed when this returns null.
         */
        fun createOrNull(): JniNativeBackend? {
            if (!JniNativeBridge.tryLoadLibrary()) {
                return null
            }
            return try {
                val label = JniNativeBridge.nativeLibraryLabel()
                val abi = JniNativeBridge.nativeAbiVersion()
                if (abi != JniNativeMapping.ABI_VERSION_EXPECTED) {
                    return null
                }
                val upstream = try {
                    JniNativeBridge.nativeUpstreamLinked()
                } catch (_: UnsatisfiedLinkError) {
                    false
                }
                JniNativeBackend(
                    libraryLabelCached = label,
                    abiVersion = abi,
                    upstreamLinked = upstream,
                )
            } catch (_: UnsatisfiedLinkError) {
                null
            }
        }

        /** True when the packaged native library can be linked in this process. */
        fun isNativePresent(): Boolean = JniNativeBridge.tryLoadLibrary()
    }
}
