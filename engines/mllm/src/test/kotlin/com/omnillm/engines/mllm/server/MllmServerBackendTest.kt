package com.omnillm.engines.mllm.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-runnable behavior tests for the real [MllmServerBackend] against an
 * in-process fake of the upstream Go server protocol (OpenAI-compatible
 * `/v1/chat/completions` + SSE). The GoMobile bridge itself is device-only and
 * is substituted with [FakeBridge]; no device/NDK required.
 */
class MllmServerBackendTest {

    private lateinit var httpServer: HttpServer
    private lateinit var backend: MllmServerBackend
    private lateinit var bridge: FakeBridge

    /** Captures the last non-probe request body for protocol assertions. */
    private val lastBody = AtomicReference<String>("")
    private val lastCredentialHeader = AtomicReference<String?>(null)
    private val cancelFirstDelta = AtomicReference(false)

    /** D3 identity-probe tracking (requests with the probe model prefix). */
    private val probeRequestCount = AtomicInteger(0)
    private val lastProbeModel = AtomicReference<String>("")
    private val lastProbeCredential = AtomicReference<String?>(null)

    private val config = PrivateChannelConfig(
        kind = ChannelKind.LOCALHOST_TCP,
        bindHost = "127.0.0.1",
        port = 8080,
        runtimeCredential = "test-credential-abc123",
        runtimeEpoch = 1L,
        allowLocalhostTcp = true,
    )

    @Before
    fun setUp() {
        bridge = FakeBridge()
        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            lastCredentialHeader.set(exchange.requestHeaders.getFirst("X-Omni-Mllm-Credential"))
            val model = extractModel(body)
            if (MllmOpenAiProtocol.isIdentityProbeModel(model)) {
                // Upstream shape (mllm-cli/pkg/server/handlers.go, verified):
                // unknown model ⇒ HTTP 404 + "Model '<model>' is not available
                // on this server." — the probe nonce is reflected in the text.
                probeRequestCount.incrementAndGet()
                lastProbeModel.set(model)
                lastProbeCredential.set(
                    exchange.requestHeaders.getFirst("X-Omni-Mllm-Credential"),
                )
                exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
                exchange.sendResponseHeaders(404, 0)
                exchange.responseBody.use {
                    it.write("Model '$model' is not available on this server.\n".toByteArray())
                }
                return@createContext
            }
            lastBody.set(body)
            if (cancelFirstDelta.get()) {
                sendChunks(exchange, listOf("""{"id":"1","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}]}"""))
                return@createContext
            }
            sendChunks(
                exchange,
                listOf(
                    """{"id":"1","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""",
                    """{"id":"1","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}""",
                    """{"id":"1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                ),
            )
        }
        httpServer.start()

        backend = MllmServerBackend(
            bridge = bridge,
            transport = OkHttpMllmTransport(OkHttpClient()),
            serverHost = "127.0.0.1",
            serverPort = httpServer.address.port,
        )
        backend.ensureReady(config)
    }

    private fun sendChunks(exchange: HttpExchange, chunks: List<String>) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        for (chunk in chunks) {
            out.write("data: $chunk\n\n".toByteArray())
            out.flush()
        }
        out.write("data: [DONE]\n\n".toByteArray())
        out.flush()
        out.close()
    }

    private fun extractModel(body: String): String {
        val regex = Regex("\"model\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(body)?.groupValues?.get(1) ?: ""
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
    }

    private fun loadModel(path: String = "/sdcard/Download/model/qwen3"): ServerResult<ServerModelToken> =
        backend.loadModel(
            ServerLoadRequest(
                storageRootKey = "broker:install-1",
                installationKey = "install-1",
                backend = "cpu",
                privilegedLoadTicketId = "ticket-1",
                resolvedModelPath = path,
            ),
        )

    private fun loadedToken(path: String = "/sdcard/Download/model/qwen3"): ServerModelToken =
        (loadModel(path) as ServerResult.Ok).value

    private fun session(model: ServerModelToken): ServerSessionToken {
        val created = backend.createSession(
            model,
            ServerSessionRequest(ownerKey = "principal-1", runtimeEpoch = 1L),
        )
        assertTrue(created.isOk)
        return (created as ServerResult.Ok).value
    }

    // --- lifecycle / auth ---

    @Test
    fun ensureReady_refusesPolicyViolation() {
        val bad = PrivateChannelConfig(
            kind = ChannelKind.LOCALHOST_TCP,
            bindHost = "0.0.0.0",
            runtimeCredential = "x",
            allowLocalhostTcp = true,
        )
        val result = backend.ensureReady(bad)
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.CHANNEL_POLICY, (result as ServerResult.Err).error.code)
    }

    @Test
    fun ensureReady_refusesMissingCredential() {
        val noCred = config.copy(runtimeCredential = null)
        val result = backend.ensureReady(noCred)
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.CHANNEL_POLICY, (result as ServerResult.Err).error.code)
    }

    @Test
    fun authenticate_acceptsMatchingCredential() {
        val outcome = backend.authenticate(
            ChannelAuthRequest(
                runtimeCredential = "test-credential-abc123",
                runtimeEpoch = 1L,
                operationToken = "op-auth",
            ),
        )
        assertTrue(outcome.isOk)
        val value = (outcome as ServerResult.Ok).value
        assertTrue(value.accepted)
        assertEquals("adapter-enforced", value.attributes["authModel"])
        assertEquals("false", value.attributes["serverAuthenticates"])
    }

    @Test
    fun authenticate_rejectsMismatchedCredential() {
        val outcome = backend.authenticate(
            ChannelAuthRequest(
                runtimeCredential = "wrong",
                runtimeEpoch = 1L,
                operationToken = "op-auth",
            ),
        )
        assertTrue(outcome.isErr)
        assertEquals(ServerErrorCode.AUTH_FAILED, (outcome as ServerResult.Err).error.code)
    }

    // --- load ---

    @Test
    fun loadModel_failsClosedWithoutResolvedPath() {
        val result = backend.loadModel(
            ServerLoadRequest(
                storageRootKey = "broker:install-1",
                installationKey = "install-1",
                backend = "cpu",
                privilegedLoadTicketId = "ticket-1",
                resolvedModelPath = null,
            ),
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.MODEL_OPEN_FAILED, (result as ServerResult.Err).error.code)
    }

    @Test
    fun loadModel_startsServerWithModelPathAndDerivesName() {
        val result = loadModel()
        assertTrue(result.isOk)
        assertEquals(1, bridge.started.size)
        val startedPath = bridge.started.peek()
        assertTrue(startedPath != null && startedPath.contains("/sdcard/Download/model/qwen3"))
    }

    @Test
    fun loadModel_secondDifferentModelFailsSingleSlot() {
        loadModel()
        val second = loadModel(path = "/sdcard/Download/model/qwen3-other")
        assertTrue(second.isErr)
        assertEquals(ServerErrorCode.RESOURCE_EXHAUSTED, (second as ServerResult.Err).error.code)
        assertEquals(1, bridge.started.size)
    }

    @Test
    fun loadModel_samePathIsIdempotent() {
        val first = loadModel()
        val second = loadModel()
        assertTrue(first.isOk)
        assertTrue(second.isOk)
        assertEquals(first.getOrNull(), second.getOrNull())
        assertEquals(1, bridge.started.size)
    }

    // --- sessions ---

    @Test
    fun createSession_unknownModelRejected() {
        val result = backend.createSession(
            ServerModelToken("mllm-model-999"),
            ServerSessionRequest(),
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.INVALID_ARGUMENT, (result as ServerResult.Err).error.code)
    }

    // --- generate ---

    @Test
    fun generate_streamsDeltasAndTerminal() {
        loadModel()
        val session = session(loadedToken())
        val events = ConcurrentLinkedQueue<ServerStreamEvent>()
        val outcome = backend.generate(
            session,
            ServerGenerateRequest(
                operationToken = "op-gen-1",
                canonicalInputDigest = "aa".repeat(32),
                maxTokens = 16,
                promptUtf8 = "Hello there",
            ),
            cancelFlag = { false },
            onEvent = { events.add(it) },
        )
        assertTrue(outcome.isOk)
        val kinds = events.map { it::class.simpleName }
        assertTrue("metadata expected", kinds.contains("Metadata"))
        assertTrue("deltas expected", events.filterIsInstance<ServerStreamEvent.Delta>().size >= 2)
        val stops = events.filterIsInstance<ServerStreamEvent.Stop>()
        assertEquals(1, stops.size)
        assertEquals(MllmOpenAiProtocol.STOP_REASON_COMPLETED, stops.first().stopReason)

        // Protocol assertions on the wire body.
        val body = lastBody.get()
        assertTrue(body.contains("\"model\":\"qwen3\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("Hello there"))
        assertTrue(body.contains("\"id\":\"op-gen-1\""))
        assertEquals("test-credential-abc123", lastCredentialHeader.get())
    }

    @Test
    fun generate_failsClosedWithoutPrompt() {
        loadModel()
        val session = session(loadedToken())
        val result = backend.generate(
            session,
            ServerGenerateRequest(
                operationToken = "op-gen-2",
                canonicalInputDigest = "aa".repeat(32),
                maxTokens = 16,
                promptUtf8 = null,
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.INVALID_ARGUMENT, (result as ServerResult.Err).error.code)
    }

    @Test
    fun generate_unknownSessionRejected() {
        loadModel()
        val result = backend.generate(
            ServerSessionToken("mllm-session-unknown"),
            ServerGenerateRequest(
                operationToken = "op-gen-3",
                canonicalInputDigest = "aa".repeat(32),
                maxTokens = 16,
                promptUtf8 = "x",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.INVALID_ARGUMENT, (result as ServerResult.Err).error.code)
    }

    @Test
    fun generate_cancelledBeforeStart() {
        loadModel()
        val session = session(loadedToken())
        backend.requestCancel("op-gen-cancel")
        val result = backend.generate(
            session,
            ServerGenerateRequest(
                operationToken = "op-gen-cancel",
                canonicalInputDigest = "aa".repeat(32),
                maxTokens = 16,
                promptUtf8 = "x",
            ),
            cancelFlag = { true },
            onEvent = {},
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.CANCELLED, (result as ServerResult.Err).error.code)
    }

    @Test
    fun generate_cancelledMidStream() {
        cancelFirstDelta.set(true)
        try {
            loadModel()
            val session = session(loadedToken())
            val events = ConcurrentLinkedQueue<ServerStreamEvent>()
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val result = backend.generate(
                session,
                ServerGenerateRequest(
                    operationToken = "op-gen-mid",
                    canonicalInputDigest = "aa".repeat(32),
                    maxTokens = 16,
                    promptUtf8 = "Hello",
                ),
                cancelFlag = { cancelled.get() },
                onEvent = { event ->
                    events.add(event)
                    if (event is ServerStreamEvent.Delta) cancelled.set(true)
                },
            )
            assertTrue(result.isErr)
            assertEquals(ServerErrorCode.CANCELLED, (result as ServerResult.Err).error.code)
            val stops = events.filterIsInstance<ServerStreamEvent.Stop>()
            assertEquals(1, stops.size)
            assertEquals(MllmOpenAiProtocol.STOP_REASON_CANCELLED, stops.first().stopReason)
        } finally {
            cancelFirstDelta.set(false)
        }
    }

    @Test
    fun generate_deadServerMapsToServerCrash() {
        // Point a fresh backend at an unbound port: server start cannot be
        // verified → SERVER_CRASH (never load-as-success).
        val dead = MllmServerBackend(
            bridge = bridge,
            transport = OkHttpMllmTransport(OkHttpClient()),
            serverHost = "127.0.0.1",
            serverPort = httpServer.address.port + 2000,
        )
        dead.ensureReady(config)
        val loaded = dead.loadModel(
            ServerLoadRequest(
                storageRootKey = "k",
                installationKey = "i",
                backend = "cpu",
                privilegedLoadTicketId = "t",
                resolvedModelPath = "/sdcard/Download/model/qwen3",
            ),
        )
        assertTrue(loaded.isErr)
        assertEquals(ServerErrorCode.SERVER_CRASH, (loaded as ServerResult.Err).error.code)
    }

    // --- D3: port-squat / server identity (post-start identity probe) ---

    @Test
    fun loadModel_rejectsPreBoundPort_whenProbeShapeMismatch() {
        // Attacker pre-binds 127.0.0.1:8080 and serves ANY HTTP (e.g. 200
        // "hello") while our Gomllm.startServer silently fails to bind (the
        // upstream mobile StartServer drops the goroutine bind error and
        // returns "Success"). The identity probe must reject the impostor.
        val attacker = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        attacker.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("hello\n".toByteArray()) }
        }
        attacker.start()
        try {
            val squat = MllmServerBackend(
                bridge = FakeBridge(status = "Success"),
                transport = OkHttpMllmTransport(OkHttpClient()),
                serverHost = "127.0.0.1",
                serverPort = attacker.address.port,
            )
            squat.ensureReady(config)
            val result = squat.loadModel(
                ServerLoadRequest(
                    storageRootKey = "k",
                    installationKey = "i",
                    backend = "cpu",
                    privilegedLoadTicketId = "t",
                    resolvedModelPath = "/sdcard/Download/model/qwen3",
                ),
            )
            assertTrue("pre-bound port must fail closed", result.isErr)
            assertEquals(
                ServerErrorCode.SERVER_IMPERSONATED,
                (result as ServerResult.Err).error.code,
            )
        } finally {
            attacker.stop(0)
        }
    }

    @Test
    fun loadModel_rejectsGenericOpenAiErrorShape() {
        // An OpenAI-compatible impostor answers 404 but with a different error
        // shape — the upstream template + per-session nonce reflection are
        // missing, so identity is NOT verified.
        val attacker = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        attacker.createContext("/") { exchange ->
            val body = """{"error":{"message":"model not found","type":"invalid_request_error"}}"""
            exchange.sendResponseHeaders(404, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        attacker.start()
        try {
            val squat = MllmServerBackend(
                bridge = FakeBridge(status = "Success"),
                transport = OkHttpMllmTransport(OkHttpClient()),
                serverHost = "127.0.0.1",
                serverPort = attacker.address.port,
            )
            squat.ensureReady(config)
            val result = squat.loadModel(
                ServerLoadRequest(
                    storageRootKey = "k",
                    installationKey = "i",
                    backend = "cpu",
                    privilegedLoadTicketId = "t",
                    resolvedModelPath = "/sdcard/Download/model/qwen3",
                ),
            )
            assertTrue(result.isErr)
            assertEquals(
                ServerErrorCode.SERVER_IMPERSONATED,
                (result as ServerResult.Err).error.code,
            )
        } finally {
            attacker.stop(0)
        }
    }

    @Test
    fun loadModel_propagatesStartServerFailureStatus() {
        // Gomllm.startServer reports failure (e.g. bind error) — the status
        // must propagate as SERVER_CRASH, never swallowed into load success.
        val failed = MllmServerBackend(
            bridge = FakeBridge(
                status = "error: listen tcp 127.0.0.1:8080: bind: address already in use",
            ),
            transport = OkHttpMllmTransport(OkHttpClient()),
            serverHost = "127.0.0.1",
            serverPort = httpServer.address.port,
        )
        failed.ensureReady(config)
        val result = failed.loadModel(
            ServerLoadRequest(
                storageRootKey = "k",
                installationKey = "i",
                backend = "cpu",
                privilegedLoadTicketId = "t",
                resolvedModelPath = "/sdcard/Download/model/qwen3",
            ),
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.SERVER_CRASH, (result as ServerResult.Err).error.code)
    }

    @Test
    fun loadModel_identityProbeDisabled_failsClosed() {
        // Escape hatch: environments where probing is impossible must fail
        // closed — there is NO load path without identity verification.
        val noProbe = MllmServerBackend(
            bridge = bridge,
            transport = OkHttpMllmTransport(OkHttpClient()),
            serverHost = "127.0.0.1",
            serverPort = httpServer.address.port,
            identityProbeEnabled = false,
        )
        noProbe.ensureReady(config)
        val result = noProbe.loadModel(
            ServerLoadRequest(
                storageRootKey = "k",
                installationKey = "i",
                backend = "cpu",
                privilegedLoadTicketId = "t",
                resolvedModelPath = "/sdcard/Download/model/qwen3",
            ),
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.SERVER_IMPERSONATED, (result as ServerResult.Err).error.code)
    }

    @Test
    fun loadModel_happyPath_identityProbeVerified() {
        val result = loadModel()
        assertTrue(result.isOk)
        assertEquals(1, probeRequestCount.get())
        assertTrue(MllmOpenAiProtocol.isIdentityProbeModel(lastProbeModel.get()))
        assertEquals("test-credential-abc123", lastProbeCredential.get())
    }

    // --- D19: bounded cancel bookkeeping ---

    @Test
    fun cancelRegistry_boundedByCap_evictsOldest() {
        for (i in 0 until 1100) {
            backend.requestCancel("op-cancel-$i")
        }
        assertTrue(
            "cancel registry must be bounded by cap",
            backend.cancelRegistry.size <= BoundedCancelRegistry.MAX_CANCEL_TOKENS,
        )
        assertFalse(
            "oldest cancel intent must be evicted",
            backend.cancelRegistry.contains("op-cancel-0"),
        )
        assertTrue(
            "newest cancel intent must survive",
            backend.cancelRegistry.contains("op-cancel-1099"),
        )
    }

    @Test
    fun generate_consumesCancelTokenOnCompletion() {
        loadModel()
        val session = session(loadedToken())
        backend.requestCancel("op-consume")
        val first = backend.generate(
            session,
            ServerGenerateRequest(
                operationToken = "op-consume",
                canonicalInputDigest = "aa".repeat(32),
                maxTokens = 16,
                promptUtf8 = "hi",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(first.isErr)
        assertEquals(ServerErrorCode.CANCELLED, (first as ServerResult.Err).error.code)
        assertFalse(
            "completed operation must purge its cancel token",
            backend.cancelRegistry.contains("op-consume"),
        )
        val second = backend.generate(
            session,
            ServerGenerateRequest(
                operationToken = "op-consume",
                canonicalInputDigest = "aa".repeat(32),
                maxTokens = 16,
                promptUtf8 = "hi",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue("purged cancel intent ⇒ re-invocation runs normally", second.isOk)
    }

    // --- unsupported-by-default ---

    @Test
    fun embed_isUnsupportedOperation() {
        val result = backend.embed(
            ServerModelToken("mllm-model-1"),
            ServerEmbedRequest(operationToken = "op-emb", canonicalInputDigest = "aa".repeat(32)),
        )
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.UNSUPPORTED_OPERATION, (result as ServerResult.Err).error.code)
    }

    @Test
    fun unload_isUnsupportedOperation() {
        val token = loadedToken()
        val result = backend.unloadModel(token)
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.UNSUPPORTED_OPERATION, (result as ServerResult.Err).error.code)
    }

    @Test
    fun shutdown_reportsCapabilityUnknownHonestly() {
        val result = backend.shutdown()
        assertTrue(result.isErr)
        assertEquals(ServerErrorCode.CAPABILITY_UNKNOWN, (result as ServerResult.Err).error.code)
    }

    @Test
    fun probe_reportsStartedAndIdentityVerified() {
        loadModel()
        val outcome = backend.probe(
            ServerProbeRequest(backend = "cpu", operationToken = "op-probe"),
        )
        assertTrue(outcome.isOk)
        assertEquals("true", (outcome as ServerResult.Ok).value.attributes["started"])
        assertEquals("true", outcome.value.attributes["reachable"])
        assertEquals("true", outcome.value.attributes["identityVerified"])
    }

    @Test
    fun libraryLabel_identifiesGoServerBridge() {
        assertEquals("mllm-server-gomllm", backend.libraryLabel())
        assertTrue(backend.isAvailable())
    }
}

/** Records upstream bridge calls; never touches gomllm classes. */
class FakeBridge(
    /** Status string returned by the upstream bridge (default nominal). */
    private val status: String = "upstream-fake-status",
) : MllmServerBridge {
    val started = ConcurrentLinkedQueue<String>()

    override fun startServer(
        modelPath: String,
        ocrPath: String,
        tmpDir: String,
        enableProbing: Boolean,
    ): String {
        started.add(modelPath)
        return status
    }

    override fun stopServer(): Boolean = false
}
