package com.omnillm.engines.litertlm.sdk

/**
 * Optional binding to the official LiteRT-LM Kotlin surface
 * (`com.google.ai.edge.litertlm.*` — Engine / Conversation / EngineConfig).
 *
 * The `:engines:litert-lm` JVM module **does not** hard-depend on the AAR so
 * host unit tests and CI stay green without Google Maven artifacts. Production
 * processes that ship the AAR inject a bridge (or use [ReflectiveLitertLmSdkBridge]
 * when classes are on the runtime classpath).
 *
 * Never returns live SDK objects to callers outside this package — only
 * opaque string tokens. Never writes OmniLLM DB / model store.
 *
 * Official coordinates (pin in UPSTREAM.lock; never use "latest" for qualification):
 * - Android: `com.google.ai.edge.litertlm:litertlm-android:<version>`
 * - JVM: `com.google.ai.edge.litertlm:litertlm-jvm:<version>`
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
                message = "official LiteRT-LM AAR not on classpath; " +
                    "cannot execute $operation (see engines/litert-lm/README.md)",
                attributes = mapOf(
                    "operation" to operation,
                    "engineId" to "LiteRT-LM",
                    "mavenCoordinate" to ReflectiveLitertLmSdkBridge.DEFAULT_MAVEN_HINT,
                ),
            ),
        )
}

/**
 * Reflection probe + optional Engine/Conversation invocation for the official
 * Kotlin API without a compile-time AAR dependency.
 *
 * When classes are missing → [isPresent] false (delegates behavior to
 * [AbsentLitertLmSdkBridge] semantics for load/generate).
 *
 * When classes are present, this bridge still requires a privileged
 * `resolvedModelPath` attribute for openEngine (path broker). Without it,
 * fails closed as [SdkErrorCode.CAPABILITY_UNKNOWN] — never invents paths.
 *
 * **Note:** Full stream callback wiring may need an adapter process that
 * compiles against the pinned AAR; reflection covers presence + basic lifecycle.
 * Integration steps are in README.
 */
class ReflectiveLitertLmSdkBridge : LitertLmSdkBridge {

    private val engineClass: Class<*>? = loadClass(ENGINE_CLASS)
    private val present: Boolean = engineClass != null

    private val engines = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val conversations = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val engineSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val conversationSeq = java.util.concurrent.atomic.AtomicInteger(0)
    private val cancelTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun isPresent(): Boolean = present

    override fun libraryLabel(): String =
        if (present) "litert-lm-sdk-reflective" else "litert-lm-sdk-absent"

    override fun openEngine(
        modelPathBrokerKey: String,
        backend: String,
        attributes: Map<String, String>,
    ): SdkResult<SdkEngineToken> {
        if (!present) {
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

        return try {
            val configClass = Class.forName(ENGINE_CONFIG_CLASS)
            val backendObj = resolveBackend(backend, attributes)
            val config = constructEngineConfig(configClass, resolvedPath, backendObj, attributes)
            val engineCtor = engineClass!!.getConstructor(configClass)
            val engineInstance = engineCtor.newInstance(config)
            // engine.initialize() — may be long-running
            val init = engineClass.getMethod("initialize")
            init.invoke(engineInstance)
            val id = "litert-engine-${engineSeq.incrementAndGet()}"
            engines[id] = engineInstance
            SdkResult.ok(SdkEngineToken(id))
        } catch (e: ClassNotFoundException) {
            SdkResult.err(
                SdkError(
                    code = SdkErrorCode.NOT_AVAILABLE,
                    message = "LiteRT-LM class missing during openEngine: ${e.message}",
                ),
            )
        } catch (e: ReflectiveOperationException) {
            SdkResult.err(
                SdkError(
                    code = SdkErrorCode.MODEL_OPEN_FAILED,
                    message = "LiteRT-LM Engine open/initialize failed via reflection",
                    attributes = mapOf(
                        "exception" to (e.javaClass.simpleName),
                        "cause" to (e.cause?.javaClass?.simpleName ?: ""),
                    ),
                ),
            )
        } catch (e: RuntimeException) {
            SdkResult.err(
                SdkError(
                    code = SdkErrorCode.MODEL_OPEN_FAILED,
                    message = "LiteRT-LM Engine open failed",
                    attributes = mapOf("exception" to e.javaClass.simpleName),
                ),
            )
        }
    }

    override fun createConversation(
        engine: SdkEngineToken,
        attributes: Map<String, String>,
    ): SdkResult<SdkConversationToken> {
        if (!present) {
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
            val method = engineInstance.javaClass.getMethod("createConversation")
            val conversation = method.invoke(engineInstance)
                ?: return SdkResult.err(
                    SdkError(
                        code = SdkErrorCode.CONVERSATION_CREATE_FAILED,
                        message = "createConversation returned null",
                    ),
                )
            val id = "litert-conversation-${conversationSeq.incrementAndGet()}"
            conversations[id] = conversation
            SdkResult.ok(SdkConversationToken(id))
        } catch (e: ReflectiveOperationException) {
            SdkResult.err(
                SdkError(
                    code = SdkErrorCode.CONVERSATION_CREATE_FAILED,
                    message = "LiteRT-LM createConversation failed",
                    attributes = mapOf("exception" to e.javaClass.simpleName),
                ),
            )
        }
    }

    override fun generate(
        conversation: SdkConversationToken,
        request: SdkGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (SdkStreamEvent) -> Unit,
    ): SdkResult<SdkGenerateOutcome> {
        if (!present) {
            return AbsentLitertLmSdkBridge().generate(conversation, request, cancelFlag, onEvent)
        }
        val conv = conversations[conversation.value]
            ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.INVALID_ARGUMENT,
                    message = "unknown conversation token",
                ),
            )
        if (cancelFlag() || cancelTokens.contains(request.operationToken)) {
            return SdkResult.err(
                SdkError(code = SdkErrorCode.CANCELLED, message = "cancelled before generate"),
            )
        }

        // Official API: sendMessage(String) synchronous path via reflection.
        // Streaming Flow/callback variants require AAR-compile-time adapters;
        // map the blocking path and emit synthetic metadata + stop for fencing.
        return try {
            onEvent(
                SdkStreamEvent(
                    kind = SdkStreamKind.METADATA,
                    attributes = mapOf(
                        "library" to libraryLabel(),
                        "operationToken" to request.operationToken,
                        "api" to "sendMessage",
                    ),
                ),
            )
            val promptHint = request.attributes["promptText"]
                ?: return SdkResult.err(
                    SdkError(
                        code = SdkErrorCode.CAPABILITY_UNKNOWN,
                        message = "promptText not supplied to reflective generate; " +
                            "control plane must inject prompt after privileged re-verify " +
                            "(opaque digest alone is insufficient for SDK sendMessage)",
                        attributes = mapOf("operationToken" to request.operationToken),
                    ),
                )
            if (cancelFlag() || cancelTokens.contains(request.operationToken)) {
                onEvent(
                    SdkStreamEvent(
                        kind = SdkStreamKind.STOP,
                        attributes = mapOf("stopReason" to "CANCELLED"),
                    ),
                )
                return SdkResult.err(
                    SdkError(code = SdkErrorCode.CANCELLED, message = "cancelled during generate"),
                )
            }
            val send = conv.javaClass.methods.firstOrNull { m ->
                m.name == "sendMessage" && m.parameterTypes.size == 1
            } ?: return SdkResult.err(
                SdkError(
                    code = SdkErrorCode.UNSUPPORTED_OPERATION,
                    message = "sendMessage not found on Conversation",
                ),
            )
            val response = send.invoke(conv, promptHint)
            val text = response?.toString().orEmpty()
            val digest = request.attributes["responseDigestHex"]
            onEvent(
                SdkStreamEvent(
                    kind = SdkStreamKind.TOKEN_DELTA,
                    payloadDigestHex = digest,
                    attributes = mapOf("chars" to text.length.toString()),
                ),
            )
            onEvent(
                SdkStreamEvent(
                    kind = SdkStreamKind.USAGE,
                    attributes = mapOf(
                        "promptTokens" to "0",
                        "completionTokens" to "0",
                        "maxTokens" to request.maxTokens.toString(),
                    ),
                ),
            )
            onEvent(
                SdkStreamEvent(
                    kind = SdkStreamKind.STOP,
                    attributes = mapOf("stopReason" to "COMPLETED"),
                ),
            )
            SdkResult.ok(
                SdkGenerateOutcome(
                    promptTokens = 0,
                    completionTokens = 0,
                    stopReason = "COMPLETED",
                    attributes = mapOf("reflective" to "true"),
                ),
            )
        } catch (e: ReflectiveOperationException) {
            SdkResult.err(
                SdkError(
                    code = SdkErrorCode.GENERATE_FAILED,
                    message = "LiteRT-LM generate failed",
                    attributes = mapOf("exception" to e.javaClass.simpleName),
                ),
            )
        } catch (e: RuntimeException) {
            SdkResult.err(
                SdkError(
                    code = SdkErrorCode.GENERATE_FAILED,
                    message = "LiteRT-LM generate failed",
                    attributes = mapOf("exception" to e.javaClass.simpleName),
                ),
            )
        }
    }

    override fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit> {
        val conv = conversations.remove(conversation.value) ?: return SdkResult.ok(Unit)
        return invokeClose(conv)
    }

    override fun closeEngine(engine: SdkEngineToken): SdkResult<Unit> {
        val eng = engines.remove(engine.value) ?: return SdkResult.ok(Unit)
        // Close remaining conversations for this engine is caller's duty;
        // best-effort close engine.
        return invokeClose(eng)
    }

    override fun requestCancel(operationToken: String): SdkResult<Unit> {
        cancelTokens.add(operationToken)
        // Official Kotlin API cancellation observability is best-effort;
        // stopping future output ≠ native execution stopped (ENGINE-LITERT §6).
        return SdkResult.ok(Unit)
    }

    private fun invokeClose(target: Any): SdkResult<Unit> =
        try {
            val close = target.javaClass.methods.firstOrNull {
                it.name == "close" && it.parameterTypes.isEmpty()
            }
            close?.invoke(target)
            SdkResult.ok(Unit)
        } catch (_: ReflectiveOperationException) {
            SdkResult.ok(Unit)
        }

    private fun resolveBackend(backend: String, attributes: Map<String, String>): Any? {
        // Best-effort Backend.CPU() / Backend.GPU() / Backend.NPU(...) via reflection.
        return try {
            val backendClass = Class.forName(BACKEND_CLASS)
            when (backend.lowercase()) {
                "cpu" -> backendClass.getMethod("CPU").invoke(null)
                "gpu" -> backendClass.getMethod("GPU").invoke(null)
                "npu" -> {
                    val npu = backendClass.methods.firstOrNull {
                        it.name == "NPU" && it.parameterTypes.size <= 1
                    }
                    val dir = attributes["npuNativeLibraryDir"]
                    when {
                        npu == null -> null
                        npu.parameterTypes.isEmpty() -> npu.invoke(null)
                        dir != null -> npu.invoke(null, dir)
                        else -> npu.invoke(null, "")
                    }
                }
                else -> null
            }
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun constructEngineConfig(
        configClass: Class<*>,
        modelPath: String,
        backendObj: Any?,
        attributes: Map<String, String>,
    ): Any {
        // Try EngineConfig(modelPath = ..., backend = ...) style constructors.
        val ctors = configClass.constructors.sortedByDescending { it.parameterCount }
        for (ctor in ctors) {
            try {
                val args = Array<Any?>(ctor.parameterCount) { null }
                val types = ctor.parameterTypes
                for (i in types.indices) {
                    val t = types[i]
                    args[i] = when {
                        t == String::class.java && i == 0 -> modelPath
                        t == String::class.java &&
                            attributes["cacheDir"] != null &&
                            i > 0 -> attributes["cacheDir"]
                        backendObj != null && t.isInstance(backendObj) -> backendObj
                        t == String::class.java -> modelPath
                        else -> null
                    }
                }
                // Ensure first String is model path
                if (types.isNotEmpty() && types[0] == String::class.java) {
                    args[0] = modelPath
                }
                return ctor.newInstance(*args)
            } catch (_: ReflectiveOperationException) {
                // try next
            } catch (_: IllegalArgumentException) {
                // try next
            }
        }
        // Fallback: no-arg + setters if present
        val instance = configClass.getDeclaredConstructor().newInstance()
        configClass.methods.firstOrNull { it.name == "setModelPath" }?.invoke(instance, modelPath)
        return instance
    }

    companion object {
        const val ENGINE_CLASS: String = "com.google.ai.edge.litertlm.Engine"
        const val ENGINE_CONFIG_CLASS: String = "com.google.ai.edge.litertlm.EngineConfig"
        const val BACKEND_CLASS: String = "com.google.ai.edge.litertlm.Backend"
        const val DEFAULT_MAVEN_HINT: String =
            "com.google.ai.edge.litertlm:litertlm-android:<pin-in-UPSTREAM.lock>"

        fun detect(): LitertLmSdkBridge {
            val reflective = ReflectiveLitertLmSdkBridge()
            return if (reflective.isPresent()) reflective else AbsentLitertLmSdkBridge()
        }

        private fun loadClass(name: String): Class<*>? =
            try {
                Class.forName(name)
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: LinkageError) {
                null
            }
    }
}
