package com.omnillm.engines.litertlm.sdk

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * Host unit tests against the **real** LiteRT-LM SDK classes
 * (`com.google.ai.edge.litertlm:litertlm-jvm:0.15.0` on the test classpath).
 *
 * Only pure data classes are constructed (EngineConfig / Backend / Contents / Message);
 * [Engine] and [Conversation] are never instantiated on the host (native load + model
 * file required — that is instrumented-test territory, Stage 5).
 */
class OfficialLitertLmSdkBridgeTest {

    // --- LitertLmApiMapping: backend mapping (real Backend classes) ---

    @Test
    fun toBackend_mapsCpuGpuNpu_toOfficialBackends() {
        val cpu = LitertLmApiMapping.toBackend("cpu")
        assertTrue(cpu is Backend.CPU)
        assertEquals("CPU", cpu?.name)

        val gpu = LitertLmApiMapping.toBackend("GPU")
        assertTrue(gpu is Backend.GPU)
        assertEquals("GPU", gpu?.name)

        val npu = LitertLmApiMapping.toBackend("npu", mapOf("npuNativeLibraryDir" to "/libs"))
        assertTrue(npu is Backend.NPU)
        assertEquals("/libs", (npu as Backend.NPU).nativeLibraryDir)
    }

    @Test
    fun toBackend_unknownBackend_failsClosedNull() {
        assertNull(LitertLmApiMapping.toBackend("tpu"))
        assertNull(LitertLmApiMapping.toBackend(""))
    }

    // --- LitertLmApiMapping: EngineConfig construction (real data class) ---

    @Test
    fun toEngineConfig_buildsOfficialConfigWithDefaults() {
        val config = LitertLmApiMapping.toEngineConfig(
            resolvedModelPath = "/broker/resolved/model.litertlm",
            backend = Backend.CPU(),
        )
        assertEquals("/broker/resolved/model.litertlm", config.modelPath)
        assertTrue(config.backend is Backend.CPU)
        assertNull(config.cacheDir)
        assertNull(config.maxNumTokens)
    }

    @Test
    fun toEngineConfig_passesCacheDirAttribute() {
        val config = LitertLmApiMapping.toEngineConfig(
            resolvedModelPath = "/broker/resolved/model.litertlm",
            backend = Backend.GPU(),
            attributes = mapOf("cacheDir" to "/data/cache"),
        )
        assertEquals("/data/cache", config.cacheDir)
    }

    // --- LitertLmApiMapping: text extraction + delta on real Message/Contents ---

    @Test
    fun extractText_joinsTextContents_only() {
        val contents = Contents.of(
            listOf(
                Content.Text("Hello"),
                Content.Text(", world"),
            ),
        )
        assertEquals("Hello, world", LitertLmApiMapping.extractText(contents))

        val message = Message.model(contents)
        assertEquals("Hello, world", LitertLmApiMapping.extractText(message))
        assertEquals("model", LitertLmApiMapping.roleLabel(message))
    }

    @Test
    fun extractText_emptyWhenNoTextContent() {
        assertEquals("", LitertLmApiMapping.extractText(Message.model("")))
        assertEquals("", LitertLmApiMapping.extractText(Contents.of("")))
    }

    @Test
    fun deltaText_suffixOfCumulativeEmission() {
        // LiteRT-LM streams the cumulative response on each emission.
        assertEquals("", LitertLmApiMapping.deltaText("Hello", "Hello"))
        assertEquals(" world", LitertLmApiMapping.deltaText("Hello", "Hello world"))
        // Non-prefix (tool interleave) → fall back to full text, never negative.
        assertEquals("XYZ", LitertLmApiMapping.deltaText("Hello", "XYZ"))
    }

    // --- LitertLmApiMapping: error mapping (real exception types) ---

    @Test
    fun mapThrowable_cancelMapsToCancelled() {
        val err = LitertLmApiMapping.mapThrowable(CancellationException("user"))
        assertEquals(SdkErrorCode.CANCELLED, err.code)
    }

    @Test
    fun mapThrowable_jniExceptionMapsToGenerateFailed() {
        val err = LitertLmApiMapping.mapThrowable(LiteRtLmJniException("native boom"))
        assertEquals(SdkErrorCode.GENERATE_FAILED, err.code)
        assertEquals("native boom", err.message)
    }

    @Test
    fun mapThrowable_missingClassesFailClosedNotAvailable() {
        val err = LitertLmApiMapping.mapThrowable(NoClassDefFoundError("com/google/ai/edge/litertlm/Engine"))
        assertEquals(SdkErrorCode.NOT_AVAILABLE, err.code)
        val link = LitertLmApiMapping.mapThrowable(UnsatisfiedLinkError("no lib"))
        assertEquals(SdkErrorCode.NOT_AVAILABLE, link.code)
    }

    @Test
    fun mapThrowable_illegalStateMapsToGenerateFailed_invalidArgToInvalidRequest() {
        assertEquals(
            SdkErrorCode.GENERATE_FAILED,
            LitertLmApiMapping.mapThrowable(IllegalStateException("not alive")).code,
        )
        assertEquals(
            SdkErrorCode.INVALID_ARGUMENT,
            LitertLmApiMapping.mapThrowable(IllegalArgumentException("bad")).code,
        )
    }

    @Test
    fun mapLoadThrowable_jniExceptionMapsToModelOpenFailed() {
        val err = LitertLmApiMapping.mapLoadThrowable(LiteRtLmJniException("open failed"))
        assertEquals(SdkErrorCode.MODEL_OPEN_FAILED, err.code)
    }

    // --- OfficialLitertLmSdkBridge: gating without instantiating Engine ---

    @Test
    fun bridge_detectsOfficialSdkOnTestClasspath() {
        assertTrue(LitertLmSdkBridge.isOfficialSdkLoadable())
        val bridge = LitertLmSdkBridge.detect()
        assertTrue(bridge.isPresent())
        assertEquals("litert-lm-0.15.0-official", bridge.libraryLabel())
        assertTrue(bridge is OfficialLitertLmSdkBridge)
    }

    @Test
    fun absentBridge_failsClosed() {
        val bridge = AbsentLitertLmSdkBridge()
        assertFalse(bridge.isPresent())
        val load = bridge.openEngine("broker:x", "cpu", emptyMap())
        assertTrue(load is SdkResult.Err)
        assertEquals(SdkErrorCode.NOT_AVAILABLE, (load as SdkResult.Err).error.code)
    }

    @Test
    fun officialBridge_openEngine_withoutResolvedPath_failsClosed() {
        val bridge = OfficialLitertLmSdkBridge()
        // No privileged path broker resolution → never touches the filesystem.
        val result = bridge.openEngine(
            modelPathBrokerKey = "broker:inst-1",
            backend = "cpu",
            attributes = emptyMap(),
        )
        assertTrue(result is SdkResult.Err)
        assertEquals(SdkErrorCode.CAPABILITY_UNKNOWN, (result as SdkResult.Err).error.code)
    }

    @Test
    fun officialBridge_openEngine_unknownBackend_failsClosed() {
        val bridge = OfficialLitertLmSdkBridge()
        val result = bridge.openEngine(
            modelPathBrokerKey = "broker:inst-1",
            backend = "tpu",
            attributes = mapOf("resolvedModelPath" to "/broker/model.litertlm"),
        )
        assertTrue(result is SdkResult.Err)
        assertEquals(SdkErrorCode.CAPABILITY_UNKNOWN, (result as SdkResult.Err).error.code)
    }

    @Test
    fun officialBridge_generate_unknownConversation_failsClosed() {
        val bridge = OfficialLitertLmSdkBridge()
        val result = bridge.generate(
            conversation = SdkConversationToken("nope"),
            request = SdkGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
                attributes = mapOf("promptText" to "hi"),
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result is SdkResult.Err)
        assertEquals(SdkErrorCode.INVALID_ARGUMENT, (result as SdkResult.Err).error.code)
    }

    @Test
    fun officialBridge_generate_withoutPromptText_failsClosed() {
        val bridge = OfficialLitertLmSdkBridge()
        val result = bridge.generate(
            conversation = SdkConversationToken("conv-1"),
            request = SdkGenerateRequest(
                operationToken = "op-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(result is SdkResult.Err)
        // Opaque digest alone is not enough — prompt must come from the control plane
        // after privileged re-verify (same contract as the previous bridge).
        assertEquals(SdkErrorCode.CAPABILITY_UNKNOWN, (result as SdkResult.Err).error.code)
    }

    @Test
    fun officialBridge_requestCancel_withoutInflight_isIdempotent() {
        val bridge = OfficialLitertLmSdkBridge()
        val result = bridge.requestCancel("op-not-running")
        assertTrue(result is SdkResult.Ok)
        assertNotNull((result as SdkResult.Ok).value)
    }

    @Test
    fun officialBridge_cancelRegistry_boundedByCap() {
        val bridge = OfficialLitertLmSdkBridge()
        for (i in 0 until 1100) {
            bridge.requestCancel("op-cancel-$i")
        }
        assertTrue(
            "cancel registry must be bounded by cap",
            bridge.cancelRequestedRegistry.size <= BoundedCancelRegistry.MAX_CANCEL_TOKENS,
        )
        assertFalse(
            "oldest cancel intent must be evicted",
            bridge.cancelRequestedRegistry.contains("op-cancel-0"),
        )
        assertTrue(
            "newest cancel intent must survive",
            bridge.cancelRequestedRegistry.contains("op-cancel-1099"),
        )
    }

    @Test
    fun officialBridge_closeUnknownTokens_isIdempotentOk() {
        val bridge = OfficialLitertLmSdkBridge()
        assertTrue(bridge.closeConversation(SdkConversationToken("nope")) is SdkResult.Ok)
        assertTrue(bridge.closeEngine(SdkEngineToken("nope")) is SdkResult.Ok)
    }

    // --- Factory wiring ---

    @Test
    fun factory_detectsOfficialSdk_andHostStubRemainsExplicit() {
        assertTrue(SdkBackendFactory.isOfficialSdkOnClasspath())
        val host = SdkBackendFactory.forHostUnitTests()
        assertEquals("StubSdkBackend", host.javaClass.simpleName)
        // Production factory now binds the official typed bridge (SDK present in tests).
        val prod = SdkBackendFactory.forProduction(com.omnillm.engines.litertlm.lock.UpstreamLock.template())
        assertEquals("RealSdkBackend", prod.javaClass.simpleName)
        assertFalse(prod.isExploratoryExecuteAllowed())
    }
}
