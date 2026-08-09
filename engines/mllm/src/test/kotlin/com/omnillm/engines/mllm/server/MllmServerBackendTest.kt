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

    /** Captures the last request body for protocol assertions. */
    private val lastBody = AtomicReference<String>("")
    private val lastCredentialHeader = AtomicReference<String?>(null)
    private val cancelFirstDelta = AtomicReference(false)

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
            lastBody.set(body)
            lastCredentialHeader.set(exchange.requestHeaders.getFirst("X-Omni-Mllm-Credential"))
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
    fun probe_reportsStartedAndReachable() {
        loadModel()
        val outcome = backend.probe(
            ServerProbeRequest(backend = "cpu", operationToken = "op-probe"),
        )
        assertTrue(outcome.isOk)
        assertEquals("true", (outcome as ServerResult.Ok).value.attributes["started"])
        assertEquals("true", outcome.value.attributes["reachable"])
    }

    @Test
    fun libraryLabel_identifiesGoServerBridge() {
        assertEquals("mllm-server-gomllm", backend.libraryLabel())
        assertTrue(backend.isAvailable())
    }
}

/** Records upstream bridge calls; never touches gomllm classes. */
class FakeBridge : MllmServerBridge {
    val started = ConcurrentLinkedQueue<String>()

    override fun startServer(
        modelPath: String,
        ocrPath: String,
        tmpDir: String,
        enableProbing: Boolean,
    ): String {
        started.add(modelPath)
        return "upstream-fake-status"
    }

    override fun stopServer(): Boolean = false
}
