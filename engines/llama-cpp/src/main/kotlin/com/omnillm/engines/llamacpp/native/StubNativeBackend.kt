package com.omnillm.engines.llamacpp.native

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process stub for unit tests and hosts without NDK / packaged `.so`.
 *
 * **Not** a production backend. Does not load GGUF, does not map real weights.
 * Dry "load" never elevates model trust (INV-008).
 *
 * Production control plane uses [JniNativeBackend.createOrNull] and **fail-closes**
 * when the native library is missing — do not substitute this stub silently.
 */
class StubNativeBackend(
    /** Number of synthetic token deltas emitted per [generate]. */
    var deltaCount: Int = 2,
    var failLoad: Boolean = false,
    var failGenerate: Boolean = false,
    /** When true, [embed] returns a synthetic outcome; default unsupported. */
    var embeddingsEnabled: Boolean = false,
) : NativeBackend {

    private val modelSeq = AtomicInteger(0)
    private val sessionSeq = AtomicInteger(0)
    private val models = ConcurrentHashMap<String, NativeLoadRequest>()
    private val sessions = ConcurrentHashMap<String, String>() // session → model
    private val cancelTokens = ConcurrentHashMap.newKeySet<String>()

    override fun libraryLabel(): String = "stub-llama-cpp"

    override fun isAvailable(): Boolean = true

    override fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome> {
        if (request.backend !in SUPPORTED_STUB_BACKENDS) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.UNSUPPORTED_OPERATION,
                    message = "stub backend not available for probe",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        return NativeResult.ok(
            NativeProbeOutcome(
                backend = request.backend,
                available = true,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "native" to "false",
                ),
            ),
        )
    }

    override fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken> {
        if (failLoad) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.MODEL_OPEN_FAILED,
                    message = "stub load failed",
                ),
            )
        }
        if (request.backend !in SUPPORTED_STUB_BACKENDS) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.UNSUPPORTED_OPERATION,
                    message = "stub does not implement backend",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        val id = "stub-model-${modelSeq.incrementAndGet()}"
        models[id] = request
        return NativeResult.ok(NativeModelToken(id))
    }

    override fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken> {
        if (!models.containsKey(model.value)) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        }
        val id = "stub-session-${sessionSeq.incrementAndGet()}"
        sessions[id] = model.value
        return NativeResult.ok(NativeSessionToken(id))
    }

    override fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome> {
        if (!sessions.containsKey(session.value)) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown session token",
                ),
            )
        }
        if (failGenerate) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.GENERATE_FAILED,
                    message = "stub generate failed",
                ),
            )
        }

        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.METADATA,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                ),
            ),
        )

        var completed = 0
        for (i in 0 until deltaCount) {
            if (cancelFlag() || cancelTokens.contains(request.operationToken)) {
                onEvent(
                    NativeStreamEvent(
                        kind = NativeStreamKind.STOP,
                        attributes = mapOf("stopReason" to "CANCELLED"),
                    ),
                )
                return NativeResult.err(
                    NativeError(
                        code = NativeErrorCode.CANCELLED,
                        message = "stub generate cancelled",
                        attributes = mapOf("completionTokens" to completed.toString()),
                    ),
                )
            }
            onEvent(
                NativeStreamEvent(
                    kind = NativeStreamKind.TOKEN_DELTA,
                    payloadDigestHex = STUB_DELTA_DIGEST,
                    attributes = mapOf("index" to i.toString()),
                ),
            )
            completed++
        }

        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to "1",
                    "completionTokens" to completed.toString(),
                ),
            ),
        )
        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.STOP,
                attributes = mapOf("stopReason" to "COMPLETED"),
            ),
        )
        return NativeResult.ok(
            NativeGenerateOutcome(
                promptTokens = 1,
                completionTokens = completed,
                stopReason = "COMPLETED",
            ),
        )
    }

    override fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome> {
        if (!embeddingsEnabled) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.UNSUPPORTED_OPERATION,
                    message = "embedding pooling not qualified for this stub build",
                    attributes = mapOf("operation" to "EMBED"),
                ),
            )
        }
        if (!models.containsKey(model.value)) {
            return NativeResult.err(
                NativeError(
                    code = NativeErrorCode.INVALID_ARGUMENT,
                    message = "unknown model token",
                ),
            )
        }
        return NativeResult.ok(
            NativeEmbedOutcome(
                dimensions = 8,
                normalized = true,
                vectorDigestHex = STUB_EMBED_DIGEST,
            ),
        )
    }

    override fun closeSession(session: NativeSessionToken): NativeResult<Unit> {
        sessions.remove(session.value)
        return NativeResult.ok(Unit)
    }

    override fun unloadModel(model: NativeModelToken): NativeResult<Unit> {
        val sessionsToClose = sessions.filterValues { it == model.value }.keys
        sessionsToClose.forEach { sessions.remove(it) }
        models.remove(model.value)
        return NativeResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): NativeResult<Unit> {
        cancelTokens.add(operationToken)
        return NativeResult.ok(Unit)
    }

    companion object {
        val SUPPORTED_STUB_BACKENDS: Set<String> = setOf("cpu")

        /** Fixed digests for deterministic tests (valid hex SHA-256 shape). */
        const val STUB_DELTA_DIGEST: String =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val STUB_EMBED_DIGEST: String =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
