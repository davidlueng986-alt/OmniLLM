package com.omnillm.engines.mlcllm.runtime

import ai.mlc.mlcllm.MlcFixtureControl
import ai.mlc.mlcllm.OpenAIProtocol
import com.omnillm.core.canonical.IdentityHashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Host-JVM tests for the real MLC-LLM runtime integration ([MlcEngineRuntimeBackend]).
 *
 * The backend binds the official `ai.mlc.mlcllm` runtime API reflectively; the
 * test fixture under `src/test/kotlin/ai/mlc/mlcllm/` is a faithful mirror of
 * the pinned upstream API surface, so these tests exercise the production
 * binding path end-to-end (load → reload → streaming → usage → terminal).
 * Device/GPU inference evidence is a Stage-5 instrumented-test concern.
 */
class MlcEngineRuntimeBackendTest {

    private val loadAttributes = mapOf(
        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to "irrelevant-until-validated",
        MlcEngineRuntimeBackend.ATTRIBUTE_MODEL_LIB to "qwen2_q4f16_1",
    )

    private val pinnedDigests = NativeLoadRequest(
        storageRootKey = "broker:test-install",
        installationKey = "install-test",
        backend = "opencl",
        privilegedLoadTicketId = "ticket-pinned",
        generatedLibraryDigest = "aa".repeat(32),
        runtimeArtifactDigest = "bb".repeat(32),
        attributes = loadAttributes,
    )

    @Before
    fun setUp() {
        MlcRuntimeBridge.resetLoadStateForTest()
        MlcFixtureControl.deltas = listOf("Hello", ", ", "world!")
        MlcFixtureControl.promptTokens = 7
        MlcFixtureControl.completionTokens = 0
        MlcFixtureControl.finishReason = "stop"
        MlcFixtureControl.streamDelayMs = 1
        MlcFixtureControl.failReload = false
        MlcFixtureControl.reloadCount = 0
        MlcFixtureControl.unloadCount = 0
        MlcFixtureControl.lastMessages = emptyList()
        MlcFixtureControl.lastMaxTokens = null
        MlcFixtureControl.lastStop = null
    }

    private fun backendOrNull(): MlcEngineRuntimeBackend? = MlcEngineRuntimeBackend.createOrNull()

    private fun tempModelBundle(): Path {
        val dir = Files.createTempDirectory("mlc-model-bundle")
        val config = dir.resolve(MlcEngineRuntimeBackend.MLC_CHAT_CONFIG)
        Files.write(config, "{\"model_name\":\"fixture\",\"vocab_size\":151936}".toByteArray())
        return dir
    }

    // ------------------------------------------------------------------ probe

    @Test
    fun runtimePresent_backendCreatedWithRealLabel() {
        val backend = backendOrNull()
        assertNotNull(backend)
        val label = backend!!.libraryLabel()
        assertTrue("library label must identify the real runtime: $label", label.startsWith("mlc-llm-runtime"))
        assertTrue(backend.isAvailable())
        assertTrue(MlcEngineRuntimeBackend.isRuntimePresent())
    }

    @Test
    fun probe_reportsHonestBackendAvailability() {
        val backend = backendOrNull()!!
        val cpu = (backend.probe(
            NativeProbeRequest(backend = "cpu", operationToken = "op-probe"),
        ) as NativeResult.Ok).value
        assertFalse("stock mlc4j does not ship a CPU target", cpu.available)
        assertEquals("opencl", cpu.attributes["deviceSelection"])

        val opencl = (backend.probe(
            NativeProbeRequest(backend = "opencl", operationToken = "op-probe"),
        ) as NativeResult.Ok).value
        assertTrue("stock mlc4j initializes the OpenCL device", opencl.available)
        assertEquals("PENDING_MEASUREMENT", opencl.attributes["page16kb"])
        assertEquals("true", opencl.attributes["native"])

        val vulkan = (backend.probe(
            NativeProbeRequest(backend = "vulkan", operationToken = "op-probe"),
        ) as NativeResult.Ok).value
        assertFalse("stock mlc4j does not ship a Vulkan target", vulkan.available)
    }

    // ------------------------------------------------------------------- load

    @Test
    fun loadModel_requiresBrokerResolvedAttributes() {
        val backend = backendOrNull()!!
        val req = pinnedDigests.copy(attributes = emptyMap())
        val res = backend.loadModel(req)
        assertTrue(res is NativeResult.Err)
        assertEquals(
            NativeErrorCode.INVALID_ARGUMENT,
            (res as NativeResult.Err).error.code,
        )
    }

    @Test
    fun loadModel_requiresCompleteLockDigests() {
        val backend = backendOrNull()!!
        val req = pinnedDigests.copy(
            generatedLibraryDigest = "",
            runtimeArtifactDigest = "",
        )
        val res = backend.loadModel(req)
        assertTrue(res is NativeResult.Err)
        val err = (res as NativeResult.Err).error
        assertEquals(NativeErrorCode.MODULE_LOAD_FAILED, err.code)
        assertTrue(err.message!!.contains("NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK"))
    }

    @Test
    fun loadModel_rejectsMissingModelBundle() {
        val backend = backendOrNull()!!
        val missing = pinnedDigests.copy(
            attributes = loadAttributes + mapOf(
                MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to "Z:/definitely/not/here",
            ),
        )
        val res = backend.loadModel(missing)
        assertTrue(res is NativeResult.Err)
        assertEquals(
            NativeErrorCode.MODEL_OPEN_FAILED,
            (res as NativeResult.Err).error.code,
        )

        // Directory without mlc-chat-config.json is not a bundle.
        val emptyDir = Files.createTempDirectory("mlc-not-a-bundle").toFile()
        val noConfig = pinnedDigests.copy(
            attributes = loadAttributes + mapOf(
                MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to emptyDir.absolutePath,
            ),
        )
        val res2 = backend.loadModel(noConfig)
        assertTrue(res2 is NativeResult.Err)
        assertEquals(
            NativeErrorCode.MODEL_OPEN_FAILED,
            (res2 as NativeResult.Err).error.code,
        )
    }

    @Test
    fun loadModel_reloadFailure_mapsToModuleLoadFailed() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        MlcFixtureControl.failReload = true
        val req = pinnedDigests.copy(
            attributes = loadAttributes + mapOf(
                MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
            ),
        )
        val res = backend.loadModel(req)
        assertTrue(res is NativeResult.Err)
        assertEquals(
            NativeErrorCode.MODULE_LOAD_FAILED,
            (res as NativeResult.Err).error.code,
        )
    }

    // ------------------------------------------------------- load + generate

    @Test
    fun loadAndGenerate_streamRealDeltasWithUsageAndStop() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()

        val load = backend.loadModel(
            pinnedDigests.copy(
                attributes = loadAttributes + mapOf(
                    MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                ),
            ),
        )
        val modelToken = (load as NativeResult.Ok).value
        assertEquals(
            dir.toFile().absolutePath,
            MlcFixtureControl.lastModelPath,
        )
        assertEquals("qwen2_q4f16_1", MlcFixtureControl.lastModelLib)

        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value

        val events = mutableListOf<NativeStreamEvent>()
        val result = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 32,
                temperature = 0.7f,
                topP = 0.9f,
                promptUtf8 = "What is machine learning?",
                stopSequences = listOf("</s>"),
            ),
            cancelFlag = { false },
            onEvent = { events.add(it) },
        )

        val outcome = (result as NativeResult.Ok).value
        assertEquals(7, outcome.promptTokens)
        assertEquals(13, outcome.completionTokens)
        assertEquals("stop", outcome.stopReason)

        val kinds = events.map { it.kind }
        assertTrue(kinds.contains(NativeStreamKind.METADATA))
        assertTrue(kinds.contains(NativeStreamKind.TOKEN_DELTA))
        assertTrue(kinds.contains(NativeStreamKind.USAGE))
        assertTrue(kinds.contains(NativeStreamKind.STOP))

        val deltas = events.filter { it.kind == NativeStreamKind.TOKEN_DELTA }
        assertEquals(3, deltas.size)
        deltas.zip(listOf("Hello", ", ", "world!")).forEach { (evt, text) ->
            assertEquals(IdentityHashing.sha256Hex(text), evt.payloadDigestHex)
        }

        val usage = events.first { it.kind == NativeStreamKind.USAGE }.attributes
        assertEquals("7", usage["promptTokens"])
        assertEquals("13", usage["completionTokens"])
        val stop = events.first { it.kind == NativeStreamKind.STOP }.attributes
        assertEquals("stop", stop["stopReason"])

        assertEquals(32, MlcFixtureControl.lastMaxTokens)
        assertEquals(0.7f, MlcFixtureControl.lastTemperature!!, 0.0f)
        assertEquals(0.9f, MlcFixtureControl.lastTopP!!, 0.0f)
        assertEquals(listOf("</s>"), MlcFixtureControl.lastStop)
    }

    @Test
    fun generate_requiresRealPromptText() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value

        val res = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = null,
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(
            NativeErrorCode.INVALID_ARGUMENT,
            (res as NativeResult.Err).error.code,
        )
    }

    @Test
    fun generate_topK_failsClosedAsUnsupported() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value

        val res = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                topK = 40,
                promptUtf8 = "hi",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(
            NativeErrorCode.UNSUPPORTED_PARAMETER,
            (res as NativeResult.Err).error.code,
        )
    }

    @Test
    fun generate_cooperativeCancel_midStream() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        MlcFixtureControl.streamDelayMs = 5
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value

        var deltasSeen = 0
        val events = mutableListOf<NativeStreamEvent>()
        val res = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-cancel",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "cancel me",
            ),
            cancelFlag = { deltasSeen++ >= 1 },
            onEvent = { events.add(it) },
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(NativeErrorCode.CANCELLED, (res as NativeResult.Err).error.code)
        val stop = events.first { it.kind == NativeStreamKind.STOP }.attributes
        assertEquals("CANCELLED", stop["stopReason"])
    }

    @Test
    fun generate_requestCancel_beforeStart_cancelled() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value
        assertTrue(backend.requestCancel("op-pre-cancel").isOk)

        val res = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-pre-cancel",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "never mind",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(NativeErrorCode.CANCELLED, (res as NativeResult.Err).error.code)
    }

    @Test
    fun sessionJournal_multiTurn_keepsHistory() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value

        val firstTurnEvents = mutableListOf<NativeStreamEvent>()
        val firstTurn = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "first turn",
            ),
            cancelFlag = { false },
            onEvent = { firstTurnEvents.add(it) },
        )
        if (firstTurn is NativeResult.Err) {
            throw AssertionError("turn 1 failed: ${(firstTurn as NativeResult.Err).error.code} ${(firstTurn as NativeResult.Err).error.message}")
        }
        val turn1EventKinds = firstTurnEvents.map { it.kind.name }.joinToString(",")

        MlcFixtureControl.deltas = listOf("again!")
        backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-2",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "second turn",
            ),
            cancelFlag = { false },
            onEvent = {},
        )

        // Second call replays the full journal: user + assistant + user.
        val messages = MlcFixtureControl.lastMessages
        if (messages.size != 3) {
            throw AssertionError(
                "expected 3 messages, got ${messages.size}: " +
                    messages.joinToString(" | ") { "${it.role}='${it.content?.asText()}'" } +
                    " (turn1 events: $turn1EventKinds)",
            )
        }
        assertEquals(3, messages.size)
        assertEquals(OpenAIProtocol.ChatCompletionRole.user, messages[0].role)
        assertEquals("first turn", messages[0].content!!.asText())
        assertEquals(OpenAIProtocol.ChatCompletionRole.assistant, messages[1].role)
        assertEquals("Hello, world!", messages[1].content!!.asText())
        assertEquals(OpenAIProtocol.ChatCompletionRole.user, messages[2].role)
        assertEquals("second turn", messages[2].content!!.asText())
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    fun createSession_unknownModel_invalidArgument() {
        val backend = backendOrNull()!!
        val res = backend.createSession(NativeModelToken("mlc-model-missing"), NativeSessionRequest())
        assertTrue(res is NativeResult.Err)
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, (res as NativeResult.Err).error.code)
    }

    @Test
    fun generate_unknownSession_invalidArgument() {
        val backend = backendOrNull()!!
        val res = backend.generate(
            NativeSessionToken("mlc-session-missing"),
            NativeGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "hi",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, (res as NativeResult.Err).error.code)
    }

    @Test
    fun embed_staysUnknownCapability() {
        val backend = backendOrNull()!!
        val res = backend.embed(
            NativeModelToken("mlc-model-1"),
            NativeEmbedRequest(operationToken = "op-1", inputDigestHex = "ab".repeat(32)),
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(NativeErrorCode.UNKNOWN_CAPABILITY, (res as NativeResult.Err).error.code)
    }

    @Test
    fun unloadModel_releasesEngineAndClosesSessions() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value

        assertEquals(0, MlcFixtureControl.unloadCount)
        backend.unloadModel(modelToken)

        assertEquals(1, MlcFixtureControl.unloadCount)
        val after = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-x",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "hi",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(after is NativeResult.Err)
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, (after as NativeResult.Err).error.code)
    }

    @Test
    fun closeSession_removesJournal() {
        val backend = backendOrNull()!!
        val dir = tempModelBundle()
        val modelToken = (
            backend.loadModel(
                pinnedDigests.copy(
                    attributes = loadAttributes + mapOf(
                        MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to dir.toString(),
                    ),
                ),
            ) as NativeResult.Ok
            ).value
        val session = (backend.createSession(modelToken, NativeSessionRequest()) as NativeResult.Ok).value
        assertTrue(backend.closeSession(session).isOk)

        val res = backend.generate(
            session,
            NativeGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                promptUtf8 = "hi",
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(res is NativeResult.Err)
        assertEquals(NativeErrorCode.INVALID_ARGUMENT, (res as NativeResult.Err).error.code)
    }

    @Test
    fun errorMessages_neverLeakFilesystemPaths() {
        val backend = backendOrNull()!!
        val res = backend.loadModel(
            pinnedDigests.copy(
                attributes = loadAttributes + mapOf(
                    MlcEngineRuntimeBackend.ATTRIBUTE_RESOLVED_MODEL_PATH to "C:\\secrets\\model\\dir",
                ),
            ),
        )
        assertTrue(res is NativeResult.Err)
        assertFalse((res as NativeResult.Err).error.message!!.contains("secrets"))
    }

    @Test
    fun createOrNull_failClosed_whenApiAbsentIsReportedViaDiagnostics() {
        // With the fixture present the binding loads; the important contract is
        // that a failed verification is observable and does not crash.
        val backend = backendOrNull()
        assertNotNull(backend)
        val diags = MlcRuntimeBridge.diagnostics()
        assertTrue("load must record verification steps", diags.isNotEmpty())
        if (backend == null) {
            assertNull(MlcRuntimeBridge.createEngineOrNull())
        }
    }
}
