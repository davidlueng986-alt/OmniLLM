package com.omnillm.engines.ortgenai.session

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.engines.ortgenai.OrtGenaiModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Real backend tests (ENGINE-ORTGENAI §2–§6):
 * - Host JVM: the real native boundary is exercised — without Android .so the
 *   backend must fail closed (NOT_AVAILABLE), never fake availability.
 * - Orchestration (token loop, stop conditions, cancellation, events, cleanup)
 *   is tested against a fake [GenAiRuntime] so host CI stays deterministic.
 * - [provisionedRealInference_smoke] is the REAL-inference test, gated on
 *   provisioned natives + model (device/emulator or upstream JVM build, Stage 5).
 */
class RealGenAiBackendTest {

    private fun loadRequest(modelDir: String? = null, backend: String = "cpu"): GenAiLoadRequest {
        val attrs = if (modelDir != null) mapOf("modelDir" to modelDir) else emptyMap()
        return GenAiLoadRequest(
            storageRootKey = "broker:test-install",
            installationKey = "test-install",
            backend = backend,
            privilegedLoadTicketId = "ticket-1",
            attributes = attrs,
        )
    }

    private fun generateRequest(
        maxTokens: Int = 4,
        promptUtf8: String? = "hello world",
        temperature: Float? = null,
    ): GenAiGenerateRequest = GenAiGenerateRequest(
        operationToken = "op-1",
        promptDigestHex = IdentityHashing.sha256Hex("hello world"),
        maxTokens = maxTokens,
        temperature = temperature,
        promptUtf8 = promptUtf8,
    )

    private fun tempGenAiFolder(): File {
        val dir = Files.createTempDirectory("ort-genai-test-model").toFile()
        File(dir, RealGenAiBackend.GENAI_CONFIG_FILE).writeText("{\"model\":{}}")
        return dir
    }

    // ------------------------------------------------------------------
    // Real native boundary on host JVM (no Android .so ⇒ fail closed)
    // ------------------------------------------------------------------

    @Test
    fun hostJvm_nativeUnavailable_failsClosedEverywhere() {
        val backend = RealGenAiBackend()
        // GenAI.init() cannot load Android natives on the host JVM.
        assertFalse("host JVM must not report natives available", backend.isAvailable())
        assertEquals("onnxruntime-genai-android-0.14.0", backend.libraryLabel())

        val probe = backend.probe(
            GenAiProbeRequest(backend = "cpu", operationToken = "op-probe"),
        )
        assertTrue(probe is GenAiResult.Err)
        assertEquals(GenAiErrorCode.NOT_AVAILABLE, (probe as GenAiResult.Err).error.code)

        val loaded = backend.loadModel(loadRequest(modelDir = "C:\\fake"))
        assertTrue(loaded is GenAiResult.Err)
        assertEquals(GenAiErrorCode.NOT_AVAILABLE, (loaded as GenAiResult.Err).error.code)
    }

    @Test
    fun moduleDefaultEngine_usesRealBackend() {
        val engine = OrtGenaiModule.createEngine()
        assertTrue(engine.backend is RealGenAiBackend)
        assertTrue(engine.backend.libraryLabel().contains("onnxruntime-genai"))
        // Fail-closed default: unproven execute stays CAPABILITY_UNKNOWN.
        assertFalse(engine.allowUnprovenExecution)
    }

    // ------------------------------------------------------------------
    // Load path (fake runtime)
    // ------------------------------------------------------------------

    @Test
    fun loadModel_missingModelDir_failsClosed() {
        val runtime = FakeGenAiRuntime()
        val backend = RealGenAiBackend(
            runtime = runtime,
            modelDirProvider = { null },
            allowExploratoryExecute = true,
        )
        val result = backend.loadModel(loadRequest())
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.MODEL_OPEN_FAILED, (result as GenAiResult.Err).error.code)
        assertTrue(runtime.openedDirs.isEmpty())
    }

    @Test
    fun loadModel_missingGenAiConfig_failsClosed() {
        val dir = Files.createTempDirectory("ort-genai-no-config").toFile()
        val runtime = FakeGenAiRuntime()
        val backend = RealGenAiBackend(runtime = runtime, allowExploratoryExecute = true)
        val result = backend.loadModel(loadRequest(modelDir = dir.absolutePath))
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.MODEL_OPEN_FAILED, (result as GenAiResult.Err).error.code)
        assertTrue(runtime.openedDirs.isEmpty())
    }

    @Test
    fun loadModel_unprovenExecute_unknownCapability() {
        // Natives "available" via fake but exploratory execute is off:
        // AAR presence must not fake qualification.
        val dir = tempGenAiFolder()
        val backend = RealGenAiBackend(
            runtime = FakeGenAiRuntime(),
            allowExploratoryExecute = false,
        )
        val result = backend.loadModel(loadRequest(modelDir = dir.absolutePath))
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.UNKNOWN_CAPABILITY, (result as GenAiResult.Err).error.code)
    }

    @Test
    fun loadModel_success_andUnload_releasesNative() {
        val dir = tempGenAiFolder()
        val runtime = FakeGenAiRuntime()
        val backend = RealGenAiBackend(runtime = runtime, allowExploratoryExecute = true)
        val loaded = backend.loadModel(loadRequest(modelDir = dir.absolutePath))
        assertTrue(loaded is GenAiResult.Ok)
        assertEquals(listOf(dir.absolutePath), runtime.openedDirs)

        val unloaded = backend.unloadModel((loaded as GenAiResult.Ok).value)
        assertTrue(unloaded is GenAiResult.Ok)
        assertTrue(runtime.closedModels.isNotEmpty())
    }

    @Test
    fun createSession_unknownModel_invalidArgument() {
        val backend = RealGenAiBackend(
            runtime = FakeGenAiRuntime(),
            allowExploratoryExecute = true,
        )
        val session = backend.createSession(
            GenAiModelToken("missing-model"),
            GenAiSessionRequest(),
        )
        assertTrue(session is GenAiResult.Err)
        assertEquals(GenAiErrorCode.INVALID_ARGUMENT, (session as GenAiResult.Err).error.code)
    }

    // ------------------------------------------------------------------
    // Generate path (fake runtime orchestration)
    // ------------------------------------------------------------------

    private fun openedPipeline(runtime: FakeGenAiRuntime): Triple<RealGenAiBackend, GenAiModelToken, GenAiSessionToken> {
        val dir = tempGenAiFolder()
        val backend = RealGenAiBackend(runtime = runtime, allowExploratoryExecute = true)
        val loaded = (backend.loadModel(loadRequest(modelDir = dir.absolutePath)) as GenAiResult.Ok).value
        val session = (backend.createSession(loaded, GenAiSessionRequest()) as GenAiResult.Ok).value
        return Triple(backend, loaded, session)
    }

    @Test
    fun generate_missingPrompt_failsClosed() {
        val (backend, _, session) = openedPipeline(FakeGenAiRuntime())
        val result = backend.generate(
            session = session,
            request = generateRequest(promptUtf8 = null),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.INVALID_ARGUMENT, (result as GenAiResult.Err).error.code)
    }

    @Test
    fun generate_streamsUntilMaxTokens() {
        val events = mutableListOf<GenAiStreamEvent>()
        val (backend, _, session) = openedPipeline(FakeGenAiRuntime())
        val result = backend.generate(
            session = session,
            request = generateRequest(maxTokens = 3),
            cancelFlag = { false },
            onEvent = { events.add(it) },
        )
        assertTrue(result is GenAiResult.Ok)
        val outcome = (result as GenAiResult.Ok).value
        assertEquals(3, outcome.completionTokens)
        assertEquals(RealGenAiBackend.MAX_TOKENS, outcome.stopReason)
        assertEquals(2, outcome.promptTokens) // "hello world" → 2 words

        val kinds = events.map { it.kind }
        assertEquals(
            listOf(
                GenAiStreamKind.METADATA,
                GenAiStreamKind.TOKEN_DELTA,
                GenAiStreamKind.TOKEN_DELTA,
                GenAiStreamKind.TOKEN_DELTA,
                GenAiStreamKind.USAGE,
                GenAiStreamKind.STOP,
            ),
            kinds,
        )
        val deltas = events.filter { it.kind == GenAiStreamKind.TOKEN_DELTA }
        deltas.forEach { event ->
            assertNotNull("token delta must carry a sha256 payload digest", event.payloadDigestHex)
            assertEquals(64, event.payloadDigestHex!!.length)
        }
        val stop = events.last()
        assertEquals("MAX_TOKENS", stop.attributes["stopReason"])
        val usage = events[4]
        assertEquals("2", usage.attributes["promptTokens"])
        assertEquals("3", usage.attributes["completionTokens"])
    }

    @Test
    fun generate_stopsOnEos() {
        val events = mutableListOf<GenAiStreamEvent>()
        val runtime = FakeGenAiRuntime(eosAfterTokens = 2)
        val (backend, _, session) = openedPipeline(runtime)
        val result = backend.generate(
            session = session,
            request = generateRequest(maxTokens = 8),
            cancelFlag = { false },
            onEvent = { events.add(it) },
        )
        assertTrue(result is GenAiResult.Ok)
        val outcome = (result as GenAiResult.Ok).value
        assertEquals(RealGenAiBackend.EOS, outcome.stopReason)
        assertEquals(2, outcome.completionTokens)
        assertEquals("EOS", events.last().attributes["stopReason"])
    }

    @Test
    fun generate_cancelFlag_earlyStop() {
        val events = mutableListOf<GenAiStreamEvent>()
        var generateCalls = 0
        val (backend, _, session) = openedPipeline(FakeGenAiRuntime())
        val result = backend.generate(
            session = session,
            request = generateRequest(maxTokens = 8),
            cancelFlag = { generateCalls++ > 0 },
            onEvent = { events.add(it) },
        )
        assertTrue(result is GenAiResult.Err)
        val error = (result as GenAiResult.Err).error
        assertEquals(GenAiErrorCode.CANCELLED, error.code)
        assertEquals("1", error.attributes["completionTokens"])
        assertEquals("CANCELLED", events.last().attributes["stopReason"])
    }

    @Test
    fun requestCancel_registeredTokenCancels() {
        val (backend, _, session) = openedPipeline(FakeGenAiRuntime())
        val request = generateRequest(maxTokens = 8)
        backend.requestCancel(request.operationToken)
        val result = backend.generate(
            session = session,
            request = request,
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.CANCELLED, (result as GenAiResult.Err).error.code)
    }

    @Test
    fun stagePrompt_suppliesContentByDigest() {
        val runtime = FakeGenAiRuntime()
        val (backend, _, session) = openedPipeline(runtime)
        // Staged under the digest the request will carry; content differs from the
        // digest's nominal string to prove the registry (not the digest) is used.
        backend.stagePrompt(IdentityHashing.sha256Hex("hello world"), "staged text")
        val result = backend.generate(
            session = session,
            request = generateRequest(promptUtf8 = null),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result is GenAiResult.Ok)
        assertEquals("staged text", runtime.lastEncodedPrompt)
    }

    @Test
    fun generate_appliesSamplingOptions() {
        val runtime = FakeGenAiRuntime()
        val (backend, _, session) = openedPipeline(runtime)
        backend.generate(
            session = session,
            request = generateRequest(
                maxTokens = 2,
                temperature = 0.7f,
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(runtime.recordedSearchOptions.any { it.first == "max_length" && it.second > 2.0 })
        val temperature = runtime.recordedSearchOptions.first { it.first == "temperature" }.second
        assertTrue("temperature must be applied (~0.7), got $temperature", temperature in 0.69..0.71)
    }

    @Test
    fun embed_unknownCapability() {
        val (backend, model, _) = openedPipeline(FakeGenAiRuntime())
        val result = backend.embed(
            model,
            GenAiEmbedRequest(
                operationToken = "op-emb",
                inputDigestHex = "ab".repeat(32),
            ),
        )
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.UNKNOWN_CAPABILITY, (result as GenAiResult.Err).error.code)
    }

    @Test
    fun closeSession_unload_releasesAllNativeState() {
        val runtime = FakeGenAiRuntime()
        val (backend, model, session) = openedPipeline(runtime)
        backend.closeSession(session)
        assertEquals(1, runtime.paramsClosed)
        backend.unloadModel(model)
        assertTrue(runtime.closedModels.isNotEmpty())
    }

    @Test
    fun generate_samplingUnsupportedParameter_failsClosed() {
        // Simulate upstream rejecting a sampling option for this model:
        // the backend must surface UNSUPPORTED_PARAMETER, not ignore it.
        val runtime = FakeGenAiRuntime(rejectSearchOption = "temperature")
        val (backend, _, session) = openedPipeline(runtime)
        val result = backend.generate(
            session = session,
            request = generateRequest(maxTokens = 2, temperature = 0.7f),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result is GenAiResult.Err)
        assertEquals(GenAiErrorCode.UNSUPPORTED_PARAMETER, (result as GenAiResult.Err).error.code)
    }

    // ------------------------------------------------------------------
    // REAL inference smoke — provisioned only (Stage 5)
    // ------------------------------------------------------------------

    @Test
    fun provisionedRealInference_smoke() {
        val runtime = OrtGenAiRuntime()
        assumeTrue(
            "GenAI natives not loadable on this runtime; provision natives + model " +
                "(device/emulator with AARs, or upstream JVM build) and set " +
                "-Pomnillm.ortgenai.smokeModelDir=<ONNX GenAI folder>",
            runtime.isAvailable(),
        )
        val modelDir = System.getProperty("omnillm.ortgenai.smokeModelDir")
        assumeTrue("smokeModelDir system property required", !modelDir.isNullOrBlank())

        val backend = RealGenAiBackend(
            runtime = runtime,
            allowExploratoryExecute = true,
        )
        val probe = backend.probe(GenAiProbeRequest("cpu", "op-smoke"))
        assertTrue(probe is GenAiResult.Ok)
        val loaded = backend.loadModel(loadRequest(modelDir = modelDir))
        assertTrue("model must load from real folder: $loaded", loaded is GenAiResult.Ok)
        val model = (loaded as GenAiResult.Ok).value
        val session = (backend.createSession(model, GenAiSessionRequest()) as GenAiResult.Ok).value

        val deltas = mutableListOf<String>()
        val result = backend.generate(
            session = session,
            request = GenAiGenerateRequest(
                operationToken = "op-smoke",
                promptDigestHex = IdentityHashing.sha256Hex("The capital of France is"),
                maxTokens = 8,
                promptUtf8 = "The capital of France is",
            ),
            cancelFlag = { false },
            onEvent = { event ->
                if (event.kind == GenAiStreamKind.TOKEN_DELTA) {
                    event.payloadDigestHex?.let { deltas.add(it) }
                }
            },
        )
        assertTrue("real generation must succeed: $result", result is GenAiResult.Ok)
        val outcome = (result as GenAiResult.Ok).value
        assertTrue("at least one completion token expected", outcome.completionTokens >= 1)
        assertTrue("at least one delta event expected", deltas.isNotEmpty())
        assertTrue("prompt tokens must be counted", outcome.promptTokens >= 1)

        backend.closeSession(session)
        backend.unloadModel(model)
    }

    // ------------------------------------------------------------------

    private class FakeGenAiRuntime(
        var available: Boolean = true,
        var eosAfterTokens: Int = Int.MAX_VALUE,
        var rejectSearchOption: String? = null,
    ) : GenAiRuntime {
        val openedDirs = mutableListOf<String>()
        val closedModels = mutableListOf<String>()
        val recordedSearchOptions = mutableListOf<Pair<String, Double>>()
        var paramsClosed = 0
        var lastEncodedPrompt: String? = null

        override fun isAvailable(): Boolean = available

        override fun libraryLabel(): String = "fake-ort-genai-0.14.0"

        override fun openModel(modelDir: String): GenAiNativeModel {
            openedDirs += modelDir
            return FakeNativeModel(this)
        }
    }

    private class FakeNativeModel(
        private val runtime: FakeGenAiRuntime,
    ) : GenAiNativeModel {
        private var closed = false

        override fun label(): String = "fake-model"

        override fun encode(prompt: String): IntArray {
            runtime.lastEncodedPrompt = prompt
            return prompt.trim().split(" ").mapIndexed { i, _ -> i + 1 }.toIntArray()
        }

        override fun createParams(): GenAiNativeParams = FakeNativeParams(runtime)

        override fun close() {
            closed = true
            runtime.closedModels.add("fake-model")
        }
    }

    private class FakeNativeParams(
        private val runtime: FakeGenAiRuntime,
    ) : GenAiNativeParams {
        private var closed = false

        override fun setSearchOption(name: String, value: Double) {
            if (name == runtime.rejectSearchOption) {
                throw GenAiRuntimeException(GenAiErrorCode.UNSUPPORTED_PARAMETER, "no such option: $name")
            }
            runtime.recordedSearchOptions.add(name to value)
        }

        override fun setSearchOption(name: String, value: Boolean) {
            runtime.recordedSearchOptions.add(name to if (value) 1.0 else 0.0)
        }

        override fun createGenerator(): GenAiNativeGenerator = FakeNativeGenerator(runtime)

        override fun close() {
            if (!closed) {
                closed = true
                runtime.paramsClosed++
            }
        }
    }

    private class FakeNativeGenerator(
        private val runtime: FakeGenAiRuntime,
    ) : GenAiNativeGenerator {
        private var generated = 0
        private var closed = false

        override fun isDone(): Boolean = generated >= runtime.eosAfterTokens

        override fun generateNextToken() {
            generated++
        }

        override fun decodeLastToken(): String = PIECES[(generated - 1) % PIECES.size]

        override fun tokenCount(): Long = PROMPT_TOKENS + generated.toLong()

        override fun appendTokens(tokens: IntArray) {
            // prompt tokens already counted via encode
        }

        override fun close() {
            closed = true
        }

        companion object {
            const val PROMPT_TOKENS: Long = 2L
            val PIECES = listOf("A", "B", "C", "D", "E")
        }
    }
}
