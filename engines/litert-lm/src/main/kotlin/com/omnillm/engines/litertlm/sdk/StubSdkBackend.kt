package com.omnillm.engines.litertlm.sdk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process stub for **host unit tests** and architecture wiring without the real AAR.
 *
 * **Not** a production backend. Does not load `.litertlm`, does not call LiteRT-LM.
 * Dry "load" never elevates model trust (INV-008).
 *
 * Production must use [RealSdkBackend] via [SdkBackendFactory.forProduction] and
 * must **not** silently fall back to this class when the AAR is missing (INV-018).
 *
 * When [exploratoryDryRun] is false (default for fail-closed probes), every
 * unproven operation returns [SdkErrorCode.CAPABILITY_UNKNOWN].
 */
class StubSdkBackend(
    /** Number of synthetic token deltas emitted per [generate] when dry-run is on. */
    var deltaCount: Int = 2,
    var failLoad: Boolean = false,
    var failGenerate: Boolean = false,
    /** When true, [embed] returns a synthetic outcome; default unknown/unsupported. */
    var embeddingsEnabled: Boolean = false,
    /**
     * When true, allows synthetic probe/load/generate for architecture tests only.
     * Default true for backward-compatible host unit tests; set false to assert
     * fail-closed CAPABILITY_UNKNOWN behavior.
     */
    var exploratoryDryRun: Boolean = true,
) : SdkBackend {

    private val engineSeq = AtomicInteger(0)
    private val conversationSeq = AtomicInteger(0)
    private val engines = ConcurrentHashMap<String, SdkLoadRequest>()
    private val conversations = ConcurrentHashMap<String, String>() // conversation → engine
    private val cancelTokens = ConcurrentHashMap.newKeySet<String>()

    override fun libraryLabel(): String = "stub-litert-lm"

    override fun isAvailable(): Boolean = exploratoryDryRun

    override fun probe(request: SdkProbeRequest): SdkResult<SdkProbeOutcome> {
        if (!exploratoryDryRun) {
            return unknown("PROBE", request.backend)
        }
        if (request.backend !in SUPPORTED_STUB_BACKENDS) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "stub backend not available for probe; capability unknown",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        return SdkResult.ok(
            SdkProbeOutcome(
                backend = request.backend,
                available = true,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "sdkBound" to "false",
                    "exploratory" to "true",
                ),
            ),
        )
    }

    override fun loadEngine(request: SdkLoadRequest): SdkResult<SdkEngineToken> {
        if (!exploratoryDryRun) {
            return unknown("LOAD", request.backend)
        }
        if (failLoad) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.MODEL_OPEN_FAILED,
                    message = "stub load failed",
                ),
            )
        }
        if (request.backend !in SUPPORTED_STUB_BACKENDS) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "stub does not implement backend; capability unknown",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        val id = "stub-engine-${engineSeq.incrementAndGet()}"
        engines[id] = request
        return SdkResult.ok(SdkEngineToken(id))
    }

    override fun createConversation(
        engine: SdkEngineToken,
        request: SdkConversationRequest,
    ): SdkResult<SdkConversationToken> {
        if (!exploratoryDryRun) {
            return unknown("CREATE_CONVERSATION", "n/a")
        }
        if (!engines.containsKey(engine.value)) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown engine token",
                ),
            )
        }
        val id = "stub-conversation-${conversationSeq.incrementAndGet()}"
        conversations[id] = engine.value
        return SdkResult.ok(SdkConversationToken(id))
    }

    override fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome> {
        if (!exploratoryDryRun) {
            return unknown("GENERATE", "n/a")
        }
        if (!conversations.containsKey(conversation.value)) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown conversation token",
                ),
            )
        }
        if (failGenerate) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.GENERATE_FAILED,
                    message = "stub generate failed",
                ),
            )
        }

        onEvent(
            SdkStreamEvent(
                kind = SdkStreamKind.METADATA,
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
                    SdkStreamEvent(
                        kind = SdkStreamKind.STOP,
                        attributes = mapOf("stopReason" to "CANCELLED"),
                    ),
                )
                return SdkResult.err(
                    SdkError(
                        code = SdkErrorCode.CANCELLED,
                        message = "stub generate cancelled",
                        attributes = mapOf("completionTokens" to completed.toString()),
                    ),
                )
            }
            onEvent(
                SdkStreamEvent(
                    kind = SdkStreamKind.TOKEN_DELTA,
                    payloadDigestHex = STUB_DELTA_DIGEST,
                    attributes = mapOf("index" to i.toString()),
                ),
            )
            completed++
        }

        onEvent(
            SdkStreamEvent(
                kind = SdkStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to "1",
                    "completionTokens" to completed.toString(),
                ),
            ),
        )
        onEvent(
            SdkStreamEvent(
                kind = SdkStreamKind.STOP,
                attributes = mapOf("stopReason" to "COMPLETED"),
            ),
        )
        return SdkResult.ok(
            SdkGenerateOutcome(
                promptTokens = 1,
                completionTokens = completed,
                stopReason = "COMPLETED",
            ),
        )
    }

    override fun embed(
        engine: SdkEngineToken,
        request: SdkEmbedRequest,
    ): SdkResult<SdkEmbedOutcome> {
        if (!embeddingsEnabled) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "embedding not qualified for this LiteRT-LM build (ENGINE-LITERT §10)",
                    attributes = mapOf("operation" to "EMBED"),
                ),
            )
        }
        if (!engines.containsKey(engine.value)) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown engine token",
                ),
            )
        }
        return SdkResult.ok(
            SdkEmbedOutcome(
                dimensions = 8,
                normalized = true,
                vectorDigestHex = STUB_EMBED_DIGEST,
            ),
        )
    }

    override fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit> {
        conversations.remove(conversation.value)
        return SdkResult.ok(Unit)
    }

    override fun unloadEngine(engine: SdkEngineToken): SdkResult<Unit> {
        val toClose = conversations.filterValues { it == engine.value }.keys
        toClose.forEach { conversations.remove(it) }
        engines.remove(engine.value)
        return SdkResult.ok(Unit)
    }

    override fun requestCancel(operationToken: String): SdkResult<Unit> {
        cancelTokens.add(operationToken)
        return SdkResult.ok(Unit)
    }

    private fun unknown(operation: String, backend: String): SdkResult<Nothing> =
        SdkResult.err(
            SdkError(
                code = SdkErrorCode.CAPABILITY_UNKNOWN,
                message = "LiteRT-LM $operation not proven for this build " +
                    "(UNQUALIFIED; stub exploratoryDryRun=false)",
                attributes = mapOf(
                    "operation" to operation,
                    "backend" to backend,
                    "engineId" to "LiteRT-LM",
                ),
            ),
        )

    companion object {
        /** Stub only exercises CPU; gpu/npu stay CAPABILITY_UNKNOWN. */
        val SUPPORTED_STUB_BACKENDS: Set<String> = setOf("cpu")

        const val STUB_DELTA_DIGEST: String =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val STUB_EMBED_DIGEST: String =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
