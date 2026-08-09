package com.omnillm.engines.litertlm.sdk

import java.util.concurrent.ConcurrentHashMap

/**
 * Production-oriented [SdkBackend] for the official LiteRT-LM SDK / AAR
 * (ENGINE-LITERT §2).
 *
 * - Optional dependency: does **not** require the AAR at compile time of this
 *   JVM module. Uses [LitertLmSdkBridge] (default [LitertLmSdkBridge.detect],
 *   binding [OfficialLitertLmSdkBridge] when the SDK is on the classpath).
 * - When the AAR is absent: [isAvailable] is false; probe/load/generate fail closed
 *   ([SdkErrorCode.NOT_AVAILABLE] / [SdkErrorCode.CAPABILITY_UNKNOWN]).
 *   **Missing natives / AAR are never reported as success.**
 * - When present: Engine / Conversation lifecycle via the bridge; opaque tokens only.
 * - Never writes OmniLLM DB / model store (ADR-010).
 * - Never elevates Registry cells to SUPPORTED (evidence is control-plane only).
 *
 * Prefer [createOrNull] / [create] on the control plane. Do **not** silently
 * substitute [StubSdkBackend] in production (INV-018).
 */
class RealSdkBackend(
    private val bridge: LitertLmSdkBridge = LitertLmSdkBridge.detect(),
    /**
     * Optional lock completeness gate. When false (default for incomplete lock),
     * load/generate remain CAPABILITY_UNKNOWN even if the AAR is on the classpath,
     * so exploratory AAR presence cannot fake qualification.
     */
    private val allowExploratoryExecute: Boolean = false,
) : SdkBackend {

    private val engineMeta = ConcurrentHashMap<String, EngineMeta>()
    private val conversationToEngine = ConcurrentHashMap<String, String>()

    override fun libraryLabel(): String = bridge.libraryLabel()

    override fun isAvailable(): Boolean = bridge.isPresent()

    override fun probe(request: SdkProbeRequest): SdkResult<SdkProbeOutcome> {
        if (!bridge.isPresent()) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.NOT_AVAILABLE,
                    message = "LiteRT-LM AAR not available for probe",
                    attributes = mapOf(
                        "backend" to request.backend,
                        "operationToken" to request.operationToken,
                    ),
                ),
            )
        }
        // Presence of the AAR is not device/backend qualification evidence.
        return SdkResult.ok(
            SdkProbeOutcome(
                backend = request.backend,
                available = true,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "sdkBound" to "true",
                    "qualification" to "UNKNOWN",
                    "allowExploratoryExecute" to allowExploratoryExecute.toString(),
                ),
            ),
        )
    }

    override fun loadEngine(request: SdkLoadRequest): SdkResult<SdkEngineToken> {
        if (!bridge.isPresent()) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.NOT_AVAILABLE,
                    message = "LiteRT-LM AAR not available; refuse load (fail closed)",
                    attributes = mapOf("backend" to request.backend),
                ),
            )
        }
        if (!allowExploratoryExecute) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "LiteRT-LM LOAD not allowed until UPSTREAM.lock complete " +
                        "and exploratory policy enables execute; AAR presence ≠ SUPPORTED",
                    attributes = mapOf(
                        "backend" to request.backend,
                        "installationKey" to request.installationKey,
                    ),
                ),
            )
        }
        val attrs = request.attributes + mapOf(
            "installationKey" to request.installationKey,
            "privilegedLoadTicketId" to request.privilegedLoadTicketId,
        )
        return when (
            val opened = bridge.openEngine(
                modelPathBrokerKey = request.storageRootKey,
                backend = request.backend,
                attributes = attrs,
            )
        ) {
            is SdkResult.Err -> opened
            is SdkResult.Ok -> {
                engineMeta[opened.value.value] = EngineMeta(
                    backend = request.backend,
                    installationKey = request.installationKey,
                )
                opened
            }
        }
    }

    override fun createConversation(
        engine: SdkEngineToken,
        request: SdkConversationRequest,
    ): SdkResult<SdkConversationToken> {
        if (!bridge.isPresent()) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.NOT_AVAILABLE,
                    message = "LiteRT-LM AAR not available",
                ),
            )
        }
        if (!engineMeta.containsKey(engine.value) && !allowExploratoryExecute) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown engine token",
                ),
            )
        }
        return when (
            val created = bridge.createConversation(engine, request.attributes)
        ) {
            is SdkResult.Err -> created
            is SdkResult.Ok -> {
                conversationToEngine[created.value.value] = engine.value
                created
            }
        }
    }

    override fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome> {
        if (!bridge.isPresent()) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.NOT_AVAILABLE,
                    message = "LiteRT-LM AAR not available",
                ),
            )
        }
        if (!allowExploratoryExecute) {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "LiteRT-LM GENERATE not allowed without exploratory policy " +
                        "and complete lock; capability remains UNKNOWN",
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )
        }
        return bridge.generate(conversation, request, cancelFlag, onEvent)
    }

    override fun embed(
        engine: SdkEngineToken,
        request: SdkEmbedRequest,
    ): SdkResult<SdkEmbedOutcome> {
        // Embedding unsupported-by-default until SDK version + model cell evidence
        // (ENGINE-LITERT §10). AAR presence does not flip this.
        return SdkResult.err(
            SdkError(
                code = SdkErrorCode.CAPABILITY_UNKNOWN,
                message = "embedding not qualified for this LiteRT-LM build (ENGINE-LITERT §10)",
                attributes = mapOf(
                    "operation" to "EMBED",
                    "operationToken" to request.operationToken,
                    "sdkPresent" to bridge.isPresent().toString(),
                ),
            ),
        )
    }

    override fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit> {
        conversationToEngine.remove(conversation.value)
        return bridge.closeConversation(conversation)
    }

    override fun unloadEngine(engine: SdkEngineToken): SdkResult<Unit> {
        val toClose = conversationToEngine.filterValues { it == engine.value }.keys
        toClose.forEach {
            conversationToEngine.remove(it)
            bridge.closeConversation(SdkConversationToken(it))
        }
        engineMeta.remove(engine.value)
        return bridge.closeEngine(engine)
    }

    override fun requestCancel(operationToken: String): SdkResult<Unit> =
        bridge.requestCancel(operationToken)

    fun isSdkPresent(): Boolean = bridge.isPresent()

    fun isExploratoryExecuteAllowed(): Boolean = allowExploratoryExecute

    private data class EngineMeta(
        val backend: String,
        val installationKey: String,
    )

    companion object {
        /**
         * Construct a real backend. Always returns an instance; when the AAR is
         * missing, [isAvailable] is false and operations fail closed.
         */
        fun create(
            allowExploratoryExecute: Boolean = false,
            bridge: LitertLmSdkBridge = LitertLmSdkBridge.detect(),
        ): RealSdkBackend =
            RealSdkBackend(
                bridge = bridge,
                allowExploratoryExecute = allowExploratoryExecute,
            )

        /**
         * Returns a backend only when the official AAR classes are present.
         * Production control plane should treat null as "do not select LiteRT-LM
         * for execute" — never silently fall back to [StubSdkBackend].
         */
        fun createOrNull(
            allowExploratoryExecute: Boolean = false,
            bridge: LitertLmSdkBridge = LitertLmSdkBridge.detect(),
        ): RealSdkBackend? {
            if (!bridge.isPresent()) return null
            return RealSdkBackend(
                bridge = bridge,
                allowExploratoryExecute = allowExploratoryExecute,
            )
        }
    }
}

