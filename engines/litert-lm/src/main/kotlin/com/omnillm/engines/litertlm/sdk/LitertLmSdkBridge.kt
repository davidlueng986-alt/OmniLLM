package com.omnillm.engines.litertlm.sdk

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional binding to the official LiteRT-LM Kotlin surface
 * (`com.google.ai.edge.litertlm.*` — Engine / Conversation / EngineConfig), pinned to
 * 0.15.0 (UPSTREAM.lock).
 *
 * The `:engines:litert-lm` JVM module compiles against `litertlm-jvm` (compileOnly) and
 * never packages it for Android — the Android runtime process packages the `litertlm-android`
 * AAR (same API surface). When the SDK classes are absent at runtime, [detect] falls back to
 * [AbsentLitertLmSdkBridge] and every operation fails closed
 * ([SdkErrorCode.NOT_AVAILABLE] / [SdkErrorCode.CAPABILITY_UNKNOWN]).
 *
 * Never returns live SDK objects to callers outside this package — only opaque string
 * tokens. Never writes OmniLLM DB / model store.
 *
 * Official coordinates (pinned in UPSTREAM.lock; never use "latest" for qualification):
 * - Android: `com.google.ai.edge.litertlm:litertlm-android:0.15.0`
 * - JVM: `com.google.ai.edge.litertlm:litertlm-jvm:0.15.0`
 * Docs: https://developers.google.com/edge/litert-lm/android
 */
interface LitertLmSdkBridge {
    /** True when the official Engine entry class is loadable in this process. */
    fun isPresent(): Boolean

    /** Diagnostics label (not a trust elevation). */
    fun libraryLabel(): String

    /**
     * Create + initialize Engine for [modelPathBrokerKey] (must already be a
     * privileged path/FD broker resolution — never a raw client absolute path
     * passed across process). Returns opaque engine token.
     */
    fun openEngine(
        modelPathBrokerKey: String,
        backend: String,
        attributes: Map<String, String>,
    ): SdkResult<SdkEngineToken>

    fun createConversation(
        engine: SdkEngineToken,
        attributes: Map<String, String>,
    ): SdkResult<SdkConversationToken>

    fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome>

    fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit>

    fun closeEngine(engine: SdkEngineToken): SdkResult<Unit>

    fun requestCancel(operationToken: String): SdkResult<Unit>

    companion object {
        /** True when the official `com.google.ai.edge.litertlm.Engine` class is loadable. */
        fun isOfficialSdkLoadable(): Boolean =
            try {
                Class.forName(
                    "com.google.ai.edge.litertlm.Engine",
                    false,
                    LitertLmSdkBridge::class.java.classLoader,
                )
                true
            } catch (_: ClassNotFoundException) {
                false
            } catch (_: LinkageError) {
                false
            }

        const val DEFAULT_MAVEN_HINT: String =
            "com.google.ai.edge.litertlm:litertlm-android:0.15.0 (or litertlm-jvm for host)"

        /**
         * Select the production bridge: typed [OfficialLitertLmSdkBridge] when the SDK
         * classes are loadable, otherwise fail-closed [AbsentLitertLmSdkBridge].
         */
        fun detect(): LitertLmSdkBridge =
            if (isOfficialSdkLoadable()) OfficialLitertLmSdkBridge() else AbsentLitertLmSdkBridge()
    }
}

/**
 * Absent / not-on-classpath bridge. All mutating ops fail closed with
 * [SdkErrorCode.NOT_AVAILABLE] or [SdkErrorCode.CAPABILITY_UNKNOWN].
 * Loading missing AAR is **never** reported as success.
 */
class AbsentLitertLmSdkBridge : LitertLmSdkBridge {
    override fun isPresent(): Boolean = false

    override fun libraryLabel(): String = "litert-lm-sdk-absent"

    override fun openEngine(
        modelPathBrokerKey: String,
        backend: String,
        attributes: Map<String, String>,
    ): SdkResult<SdkEngineToken> = notAvailable("LOAD")

    override fun createConversation(
        engine: SdkEngineToken,
        attributes: Map<String, String>,
    ): SdkResult<SdkConversationToken> = notAvailable("CREATE_CONVERSATION")

    override fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome> = notAvailable("GENERATE")

    override fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit> =
        SdkResult.ok(Unit)

    override fun closeEngine(engine: SdkEngineToken): SdkResult<Unit> =
        SdkResult.ok(Unit)

    override fun requestCancel(operationToken: String): SdkResult<Unit> =
        SdkResult.ok(Unit)

    private fun notAvailable(operation: String): SdkResult<Nothing> =
        SdkResult.err(
            SdkError(
                code = SdkErrorCode.NOT_AVAILABLE,
                message = "official LiteRT-LM SDK not on classpath; " +
                    "cannot execute $operation (see engines/litert-lm/README.md)",
                attributes = mapOf(
                    "operation" to operation,
                    "engineId" to "LiteRT-LM",
                    "mavenCoordinate" to LitertLmSdkBridge.DEFAULT_MAVEN_HINT,
                ),
            ),
        )
}

/**
 * Typed production bridge calling the official LiteRT-LM Kotlin API directly
 * (Engine / Conversation / MessageCallback — no reflection).
 *
 * - [openEngine] constructs [EngineConfig] from the privileged `resolvedModelPath`
 *   attribute (never raw client paths), then [Engine.initialize].
 * - [generate] streams via `conversation.sendMessageAsync(text, MessageCallback)`
 *   (the documented non-suspend streaming API). Each emission is the cumulative
 *   response; [LitertLmApiMapping.deltaText] computes the new chunk.
 * - Cooperative cancel: [requestCancel] / [cancelFlag] trigger `conversation.cancelProcess()`
 *   (best-effort — upstream tracks state-rollback as b/450903294; stopping future output
 *   ≠ native execution stopped, ENGINE-LITERT §6 → killable worker still required).
 * - Error mapping via [LitertLmApiMapping] (LiteRtLmJniException, CancellationException,
 *   NoClassDefFoundError → NOT_AVAILABLE, …).
 *
 * When classes are missing → [isPresent] false; every mutating op fails closed.
 */
class OfficialLitertLmSdkBridge : LitertLmSdkBridge {

    private val engines = ConcurrentHashMap<String, Engine>()
    private val conversations = ConcurrentHashMap<String, Conversation>()
    /** operationToken → conversation token for best-effort cancelProcess(). */
    private val operationToConversation = ConcurrentHashMap<String, String>()

    /**
     * Bounded cooperative-cancel registry (D19): cap + FIFO eviction +
     * consume-on-completion — never unbounded (mirrors the native 1024 cap).
     */
    internal val cancelRequestedRegistry: BoundedCancelRegistry = BoundedCancelRegistry()
    private val engineSeq = AtomicInteger(0)
    private val conversationSeq = AtomicInteger(0)

    override fun isPresent(): Boolean = LitertLmSdkBridge.isOfficialSdkLoadable()

    override fun libraryLabel(): String =
        if (isPresent()) "litert-lm-0.15.0-official" else "litert-lm-sdk-absent"

    override fun openEngine(
        modelPathBrokerKey: String,
        backend: String,
        attributes: Map<String, String>,
    ): SdkResult<SdkEngineToken> {
        if (!isPresent()) {
            return AbsentLitertLmSdkBridge().openEngine(modelPathBrokerKey, backend, attributes)
        }
        // Prefer explicit privileged resolution; never treat broker keys as filesystem paths.
        val resolvedPath = attributes["resolvedModelPath"]?.takeIf { it.isNotBlank() }
            ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "resolvedModelPath not provided by privileged path broker; " +
                        "LiteRT-LM openEngine refuses raw client paths (ENGINE-LITERT §8)",
                    attributes = mapOf(
                        "storageRootKeyPresent" to modelPathBrokerKey.isNotEmpty().toString(),
                        "backend" to backend,
                    ),
                ),
            )

        val backendObj = LitertLmApiMapping.toBackend(backend, attributes)
            ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "backend '$backend' not proven for this LiteRT-LM build",
                    attributes = mapOf("backend" to backend),
                ),
            )

        return try {
            val config: EngineConfig =
                LitertLmApiMapping.toEngineConfig(resolvedPath, backendObj, attributes)
            val engineInstance = Engine(config)
            engineInstance.initialize()
            val id = "litert-engine-${engineSeq.incrementAndGet()}"
            engines[id] = engineInstance
            SdkResult.ok(SdkEngineToken(id))
        } catch (e: Throwable) {
            val mapped = LitertLmApiMapping.mapLoadThrowable(e)
            SdkResult.err(mapped)
        }
    }

    override fun createConversation(
        engine: SdkEngineToken,
        attributes: Map<String, String>,
    ): SdkResult<SdkConversationToken> {
        if (!isPresent()) {
            return AbsentLitertLmSdkBridge().createConversation(engine, attributes)
        }
        val engineInstance = engines[engine.value]
            ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown engine token",
                ),
            )
        return try {
            val conversation = engineInstance.createConversation(ConversationConfig())
            val id = "litert-conversation-${conversationSeq.incrementAndGet()}"
            conversations[id] = conversation
            SdkResult.ok(SdkConversationToken(id))
        } catch (e: Throwable) {
            SdkResult.err(LitertLmApiMapping.mapLoadThrowable(e))
        }
    }

    override fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome> {
        if (!isPresent()) {
            return AbsentLitertLmSdkBridge().generate(conversation, request, cancelFlag, onEvent)
        }
        // Validate inputs before touching state (fail closed, never invents prompts).
        val promptText = request.attributes["promptText"]?.takeIf { it.isNotBlank() }
            ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CAPABILITY_UNKNOWN,
                    message = "promptText not supplied to official generate; control plane " +
                        "must inject prompt after privileged re-verify (opaque digest alone is " +
                        "insufficient for SDK sendMessageAsync)",
                    attributes = mapOf("operationToken" to request.operationToken),
                ),
            )
        val conv = conversations[conversation.value]
            ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown conversation token",
                ),
            )
        if (cancelFlag() || cancelRequestedRegistry.contains(request.operationToken)) {
            cancelRequestedRegistry.consume(request.operationToken)
            return SdkResult.err(
                SdkError(code = SdkErrorCode.CANCELLED, message = "cancelled before generate"),
            )
        }

        operationToConversation[request.operationToken] = conversation.value
        onEvent(
            SdkStreamEvent(
                kind = SdkStreamKind.METADATA,
                attributes = mapOf(
                    "library" to libraryLabel(),
                    "operationToken" to request.operationToken,
                    "api" to "sendMessageAsync(callback)",
                ),
            ),
        )

        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val outcome = java.util.concurrent.atomic.AtomicReference<SdkGenerateOutcome?>()
        val failure = java.util.concurrent.atomic.AtomicReference<SdkError?>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val promptTokensBefore = runCatching { conv.getTokenCount() }.getOrDefault(0)
        var previousText = ""
        val lastActivity = java.util.concurrent.atomic.AtomicLong(System.nanoTime())

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                lastActivity.set(System.nanoTime())
                if (cancelled.get()) return
                if (cancelFlag() || cancelRequestedRegistry.contains(request.operationToken)) {
                    cancelled.set(true)
                    runCatching { conv.cancelProcess() }
                    return
                }
                val cumulative = LitertLmApiMapping.extractText(message)
                val delta = LitertLmApiMapping.deltaText(previousText, cumulative)
                previousText = cumulative
                if (delta.isNotEmpty()) {
                    onEvent(
                        SdkStreamEvent(
                            kind = SdkStreamKind.TOKEN_DELTA,
                            payloadDigestHex = deltaDigest(delta),
                            attributes = mapOf(
                                "chars" to delta.length.toString(),
                                "cumulativeChars" to cumulative.length.toString(),
                            ),
                        ),
                    )
                }
            }

            override fun onDone() {
                lastActivity.set(System.nanoTime())
                outcome.set(
                    SdkGenerateOutcome(
                        promptTokens = promptTokensBefore,
                        completionTokens = usageDelta(promptTokensBefore, conv.getTokenCount()),
                        stopReason = if (cancelled.get() ||
                            cancelFlag() ||
                            cancelRequestedRegistry.contains(request.operationToken)
                        ) {
                            "CANCELLED"
                        } else {
                            "COMPLETED"
                        },
                        attributes = mapOf(
                            "usageKind" to "kv-cache-approximate",
                            "operationToken" to request.operationToken,
                        ),
                    ),
                )
                done.set(true)
            }

            override fun onError(throwable: Throwable) {
                lastActivity.set(System.nanoTime())
                failure.set(LitertLmApiMapping.mapThrowable(throwable))
                done.set(true)
            }
        }

        try {
            conv.sendMessageAsync(promptText, callback)
        } catch (e: Throwable) {
            failure.set(LitertLmApiMapping.mapThrowable(e))
            done.set(true)
        }

        // Blocking wait (bridge contract is synchronous). Cooperative cancel is polled so
        // requestCancel / cancelFlag reach cancelProcess() promptly.
        while (!done.get()) {
            if (cancelled.get() || cancelFlag() || cancelRequestedRegistry.contains(request.operationToken)) {
                cancelled.set(true)
                runCatching { conv.cancelProcess() }
            }
            val idleNanos = System.nanoTime() - lastActivity.get()
            if (idleNanos > IDLE_TIMEOUT_NANOS) {
                failure.set(
                    SdkError(
                        code = SdkErrorCode.DEADLINE_EXCEEDED,
                        message = "LiteRT-LM stream idle (no callback) beyond timeout; " +
                            "worker kill required (ENGINE-LITERT §6)",
                        attributes = mapOf("operationToken" to request.operationToken),
                    ),
                )
                done.set(true)
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        operationToConversation.remove(request.operationToken)
        // D19: a completed operation consumes its cancel token — the registry
        // never grows without bound.
        cancelRequestedRegistry.consume(request.operationToken)

        failure.get()?.let { err ->
            if (err.code == SdkErrorCode.CANCELLED) {
                onEvent(
                    SdkStreamEvent(
                        kind = SdkStreamKind.STOP,
                        attributes = mapOf("stopReason" to "CANCELLED"),
                    ),
                )
            }
            return SdkResult.err(err)
        }

        val finalOutcome = outcome.get()
            ?: SdkGenerateOutcome(
                promptTokens = promptTokensBefore,
                completionTokens = 0,
                stopReason = "COMPLETED",
            )
        onEvent(
            SdkStreamEvent(
                kind = SdkStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to finalOutcome.promptTokens.toString(),
                    "completionTokens" to finalOutcome.completionTokens.toString(),
                    "usageKind" to "kv-cache-approximate",
                ),
            ),
        )
        onEvent(
            SdkStreamEvent(
                kind = SdkStreamKind.STOP,
                attributes = mapOf("stopReason" to finalOutcome.stopReason),
            ),
        )
        if (finalOutcome.stopReason == "CANCELLED") {
            return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CANCELLED,
                    message = "LiteRT-LM generation cancelled",
                    attributes = mapOf(
                        "completionTokens" to finalOutcome.completionTokens.toString(),
                    ),
                ),
            )
        }
        return SdkResult.ok(finalOutcome)
    }

    override fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit> {
        val conv = conversations.remove(conversation.value) ?: return SdkResult.ok(Unit)
        return runCatching { conv.close() }.fold(
            onSuccess = { SdkResult.ok(Unit) },
            onFailure = { SdkResult.err(LitertLmApiMapping.mapThrowable(it)) },
        )
    }

    override fun closeEngine(engine: SdkEngineToken): SdkResult<Unit> {
        val eng = engines.remove(engine.value) ?: return SdkResult.ok(Unit)
        return runCatching { eng.close() }.fold(
            onSuccess = { SdkResult.ok(Unit) },
            onFailure = { SdkResult.err(LitertLmApiMapping.mapThrowable(it)) },
        )
    }

    override fun requestCancel(operationToken: String): SdkResult<Unit> {
        cancelRequestedRegistry.add(operationToken)
        // Best-effort native cancel: find the in-flight conversation and ask the SDK to stop.
        // Stopping future output ≠ native execution stopped (ENGINE-LITERT §6 / b/450903294).
        val conversationToken = operationToConversation[operationToken]
        if (conversationToken != null) {
            conversations[conversationToken]?.let { conv ->
                runCatching { conv.cancelProcess() }
            }
        }
        return SdkResult.ok(Unit)
    }

    companion object {
        const val DEFAULT_MAVEN_HINT: String = LitertLmSdkBridge.DEFAULT_MAVEN_HINT
        /** Idle-wait guard: no callback for this long ⇒ DEADLINE_EXCEEDED + worker kill. */
        val IDLE_TIMEOUT_NANOS: Long = 15 * 60L * 1000L * 1000L * 1000L
        private const val POLL_INTERVAL_MS: Long = 25L

        private fun deltaDigest(text: String): String =
            com.omnillm.core.canonical.IdentityHashing.sha256Hex(text)

        private fun usageDelta(before: Int, after: Int): Int = (after - before).coerceAtLeast(0)
    }
}
