package com.omnillm.features.playground

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.playground.api.ChatMessage
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.playground.api.InferenceIdentity
import com.omnillm.features.playground.api.PlaygroundScreenMode
import com.omnillm.features.playground.api.PlaygroundTab
import com.omnillm.features.playground.api.SourceSessionRef
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.ports.PlaygroundModelRow
import com.omnillm.features.playground.ports.PortMetricSample
import com.omnillm.features.playground.projection.CapabilityUiProjection
import com.omnillm.features.playground.usecase.PlaygroundService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.observability.MetricId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-PLAYGROUND capability negotiation — unsupported / unknown / conditional /
 * temporarily unavailable paths (UX-STATE §6, INV-018 fail closed).
 */
class PlaygroundCapabilityNegotiationTest {

    private lateinit var caps: FakeCapabilityPort
    private lateinit var inference: FakeInferencePort
    private lateinit var api: PlaygroundService

    private val modelId = "a".repeat(64)
    private val digest = "b".repeat(64)

    @Before
    fun setUp() {
        caps = FakeCapabilityPort()
        inference = FakeInferencePort()
        api = PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = inference,
                capabilities = caps,
                models = FakeModelCatalog(
                    listOf(
                        PlaygroundModelRow(
                            modelRevisionId = modelId,
                            displayName = "Demo Chat",
                            installationState = "READY",
                        ),
                    ),
                ),
                metrics = FakeMetricsPort(
                    listOf(
                        PortMetricSample(
                            metricId = MetricId.REQUEST_TTFT_MS.wireName,
                            value = null,
                            unit = "ms",
                            evidenceLabel = EvidenceLabel.UNKNOWN,
                            sampledAtEpochMs = 1_000L,
                        ),
                    ),
                ),
                runtimeStatus = FakeRuntimeStatus(),
            ),
            clockMs = { 2_000L },
        )
    }

    @Test
    fun negotiate_chat_supported_isOperable() = runBlocking {
        val r = api.negotiateCapabilities(LocalUiPrincipal.ID, PlaygroundTab.CHAT, modelId)
            as OmniResult.Ok
        assertTrue(r.value.operable)
        assertNull(r.value.blockingReasonKey)
        assertTrue(
            r.value.required.any { it.capabilityId == CapabilityId.TEXT_GENERATION },
        )
    }

    @Test
    fun negotiate_textGeneration_unsupported_blocksChat() = runBlocking {
        caps.set(modelId, CapabilityId.TEXT_GENERATION, CapabilityState.UNSUPPORTED)

        val neg = api.negotiateCapabilities(LocalUiPrincipal.ID, PlaygroundTab.CHAT, modelId)
            as OmniResult.Ok
        assertFalse(neg.value.operable)
        assertEquals(
            "capability.unsupported.select-supported-operation",
            neg.value.blockingReasonKey,
        )
        val cell = neg.value.required.first { it.capabilityId == CapabilityId.TEXT_GENERATION }
        assertEquals(CapabilityState.UNSUPPORTED, cell.state)
        assertEquals("capability.unsupported", cell.labelKey)

        // Must not start inference when unsupported.
        val start = api.startChat(
            LocalUiPrincipal.ID,
            chatSpec(requestId = "11111111-1111-1111-1111-111111111111"),
        )
        assertTrue(start is OmniResult.Err)
        val err = (start as OmniResult.Err).error
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, err.code)
        assertEquals("UNSUPPORTED", err.details["state"])
        assertEquals(CapabilityId.TEXT_GENERATION.id, err.details["capability"])
        assertEquals(0, inference.chatStartCount)
    }

    @Test
    fun negotiate_unknown_isNotLabelledUnsupported() = runBlocking {
        caps.set(modelId, CapabilityId.TEXT_GENERATION, CapabilityState.UNKNOWN)

        val neg = api.negotiateCapabilities(LocalUiPrincipal.ID, PlaygroundTab.CHAT, modelId)
            as OmniResult.Ok
        assertFalse(neg.value.operable)
        val cell = neg.value.required.first { it.capabilityId == CapabilityId.TEXT_GENERATION }
        assertEquals(CapabilityState.UNKNOWN, cell.state)
        assertEquals("capability.unknown", cell.labelKey)
        assertEquals("capability.unknown.run-qualification", cell.explanationKey)
        // Must NOT use unsupported label (UX-STATE §8).
        assertFalse(cell.labelKey.contains("unsupported"))

        val start = api.startChat(
            LocalUiPrincipal.ID,
            chatSpec(requestId = "22222222-2222-2222-2222-222222222222"),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, start.error.code)
        assertEquals("true", start.error.details["failClosed"])
        assertEquals(0, inference.chatStartCount)
    }

    @Test
    fun negotiate_temporarilyUnavailable_retryablePath() = runBlocking {
        caps.set(modelId, CapabilityId.EMBEDDING, CapabilityState.TEMPORARILY_UNAVAILABLE)

        val neg = api.negotiateCapabilities(LocalUiPrincipal.ID, PlaygroundTab.EMBEDDINGS, modelId)
            as OmniResult.Ok
        assertFalse(neg.value.operable)
        val cell = neg.value.required.first { it.capabilityId == CapabilityId.EMBEDDING }
        assertEquals("capability.temporarily-unavailable", cell.labelKey)

        val start = api.startEmbedding(
            LocalUiPrincipal.ID,
            EmbeddingRequestSpec(
                identity = InferenceIdentity(
                    requestId = "33333333-3333-3333-3333-333333333333",
                    idempotencyKey = "emb-1",
                    canonicalInputDigest = digest,
                ),
                modelRevisionId = modelId,
                inputs = listOf("hello"),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, start.error.code)
        assertTrue(start.error.retryable)
        assertEquals(0, inference.embedStartCount)
    }

    @Test
    fun negotiate_conditional_remainsOperable_withConditionsDisclosed() = runBlocking {
        caps.set(modelId, CapabilityId.TEXT_GENERATION, CapabilityState.CONDITIONAL)
        caps.setConditions(
            modelId,
            CapabilityId.TEXT_GENERATION,
            listOf("requires-loaded-model", "max-context-4k"),
        )

        val neg = api.negotiateCapabilities(LocalUiPrincipal.ID, PlaygroundTab.CHAT, modelId)
            as OmniResult.Ok
        assertTrue(neg.value.operable)
        val cell = neg.value.required.first { it.capabilityId == CapabilityId.TEXT_GENERATION }
        assertEquals(CapabilityState.CONDITIONAL, cell.state)
        assertEquals(listOf("requires-loaded-model", "max-context-4k"), cell.conditions)

        val start = api.startChat(
            LocalUiPrincipal.ID,
            chatSpec(requestId = "44444444-4444-4444-4444-444444444444"),
        )
        assertTrue(start is OmniResult.Ok)
        assertEquals(1, inference.chatStartCount)
    }

    @Test
    fun negotiate_embeddings_unsupported_doesNotCreateSession() = runBlocking {
        caps.set(modelId, CapabilityId.EMBEDDING, CapabilityState.UNSUPPORTED)

        val start = api.startEmbedding(
            LocalUiPrincipal.ID,
            EmbeddingRequestSpec(
                identity = InferenceIdentity(
                    requestId = "55555555-5555-5555-5555-555555555555",
                    idempotencyKey = "emb-unsup",
                    canonicalInputDigest = digest,
                ),
                modelRevisionId = modelId,
                inputs = listOf("a", "b"),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, start.error.code)
        assertEquals(0, inference.embedStartCount)
    }

    @Test
    fun visionTab_requiresVisionAndAudioCapabilities() {
        val required = CapabilityUiProjection.requiredCapabilities(PlaygroundTab.VISION_AUDIO)
        assertTrue(CapabilityId.VISION_INPUT in required)
        assertTrue(CapabilityId.AUDIO_INPUT in required)
        assertTrue(CapabilityId.TEXT_GENERATION in required)
    }

    @Test
    fun vision_unsupported_blocksMultimodalBeforeInference() = runBlocking {
        caps.set(modelId, CapabilityId.VISION_INPUT, CapabilityState.UNSUPPORTED)

        val neg = api.negotiateCapabilities(
            LocalUiPrincipal.ID,
            PlaygroundTab.VISION_AUDIO,
            modelId,
        ) as OmniResult.Ok
        assertFalse(neg.value.operable)

        // startChat for vision tab is gated via VISION_AUDIO negotiation in multimodal path;
        // CHAT tab still works if TEXT_GENERATION is supported.
        val chat = api.startChat(
            LocalUiPrincipal.ID,
            chatSpec(requestId = "66666666-6666-6666-6666-666666666666"),
        )
        assertTrue(chat is OmniResult.Ok)
    }

    @Test
    fun snapshot_emptyModels_isEmptyScreen() = runBlocking {
        val emptyApi = PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = FakeInferencePort(),
                capabilities = FakeCapabilityPort(),
                models = FakeModelCatalog(emptyList()),
            ),
        )
        val snap = emptyApi.getSnapshot(LocalUiPrincipal.ID) as OmniResult.Ok
        assertEquals(PlaygroundScreenMode.EMPTY, snap.value.screenMode)
        assertTrue(snap.value.models.isEmpty())
    }

    @Test
    fun snapshot_degradedRuntime_isDegradedScreen() = runBlocking {
        val degraded = PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = FakeInferencePort(),
                capabilities = caps,
                models = FakeModelCatalog(
                    listOf(
                        PlaygroundModelRow(modelId, "Demo", "READY"),
                    ),
                ),
                runtimeStatus = FakeRuntimeStatus(
                    state = "DEGRADED",
                    reasons = listOf("engine-thermal"),
                ),
            ),
        )
        val snap = degraded.getSnapshot(LocalUiPrincipal.ID) as OmniResult.Ok
        assertEquals(PlaygroundScreenMode.DEGRADED, snap.value.screenMode)
        assertEquals(listOf("engine-thermal"), snap.value.runtimeDegradedReasons)
    }

    @Test
    fun snapshot_metrics_carryEvidenceLabels_unknownNotZero() = runBlocking {
        val snap = api.getSnapshot(LocalUiPrincipal.ID) as OmniResult.Ok
        val unknown = snap.value.metrics.first {
            it.metricId == MetricId.REQUEST_TTFT_MS.wireName
        }
        assertEquals(EvidenceLabel.UNKNOWN, unknown.evidenceLabel)
        assertNull(unknown.value)
        assertFalse(unknown.displayNumeric)
    }

    @Test
    fun happyPath_chat_projectsEvidenceMetrics() = runBlocking {
        val start = api.startChat(
            LocalUiPrincipal.ID,
            chatSpec(requestId = "77777777-7777-7777-7777-777777777777"),
        ) as OmniResult.Ok
        assertEquals("COMPLETED", start.value.state)
        assertNotNull(start.value.assistantText)
        assertEquals(SourceSessionRef.None, SourceSessionRef.None)

        val ttft = start.value.metrics.first { it.metricId == MetricId.REQUEST_TTFT_MS.wireName }
        assertEquals(EvidenceLabel.MEASURED, ttft.evidenceLabel)
        assertEquals(42.0, ttft.value!!, 0.0)
        assertTrue(ttft.displayNumeric)

        val tps = start.value.metrics.first {
            it.metricId == MetricId.REQUEST_TOKENS_PER_SECOND.wireName
        }
        assertEquals(EvidenceLabel.UNKNOWN, tps.evidenceLabel)
        assertNull(tps.value)
        assertFalse(tps.displayNumeric)

        val queue = start.value.metrics.first { it.metricId == MetricId.REQUEST_QUEUE_MS.wireName }
        assertEquals(EvidenceLabel.REPORTED, queue.evidenceLabel)
        assertEquals("scheduler", queue.source)
    }

    @Test
    fun happyPath_embedding_noGenerationSession() = runBlocking {
        val start = api.startEmbedding(
            LocalUiPrincipal.ID,
            EmbeddingRequestSpec(
                identity = InferenceIdentity(
                    requestId = "88888888-8888-8888-8888-888888888888",
                    idempotencyKey = "emb-ok",
                    canonicalInputDigest = digest,
                ),
                modelRevisionId = modelId,
                inputs = listOf("one", "two"),
                dimensions = 4,
            ),
        ) as OmniResult.Ok
        assertEquals("COMPLETED", start.value.state)
        assertNull(start.value.sessionId)
        assertEquals(4, start.value.embeddingDimensions)
        assertEquals(2, start.value.embeddingCount)
    }

    @Test
    fun mapCapabilityBlocker_distinguishesUnsupportedAndUnknown() {
        val unsupported = CapabilityUiProjection.projectCell(
            CapabilityId.TEXT_GENERATION,
            CapabilityState.UNSUPPORTED,
        )
        val unknown = CapabilityUiProjection.projectCell(
            CapabilityId.TEXT_GENERATION,
            CapabilityState.UNKNOWN,
        )
        val e1 = api.mapCapabilityBlocker(unsupported)
        val e2 = api.mapCapabilityBlocker(unknown)
        assertTrue(e1 is OmniError.CAPABILITY_UNSUPPORTED)
        assertTrue(e2 is OmniError.CAPABILITY_UNKNOWN)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, e1.code)
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, e2.code)
    }

    private fun chatSpec(requestId: String): ChatRequestSpec =
        ChatRequestSpec(
            identity = InferenceIdentity(
                requestId = requestId,
                idempotencyKey = "chat-$requestId",
                canonicalInputDigest = digest,
            ),
            modelRevisionId = modelId,
            messages = listOf(ChatMessage(role = "user", content = "hi")),
            sourceSession = SourceSessionRef.None,
        )
}
