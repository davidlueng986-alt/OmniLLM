package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
import com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.ExploratoryInferenceSource
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.engines.llamacpp.native.NativeBackend
import com.omnillm.engines.llamacpp.native.NativeEmbedOutcome
import com.omnillm.engines.llamacpp.native.NativeEmbedRequest
import com.omnillm.engines.llamacpp.native.NativeGenerateOutcome
import com.omnillm.engines.llamacpp.native.NativeGenerateRequest
import com.omnillm.engines.llamacpp.native.NativeLoadRequest
import com.omnillm.engines.llamacpp.native.NativeModelToken
import com.omnillm.engines.llamacpp.native.NativeProbeOutcome
import com.omnillm.engines.llamacpp.native.NativeProbeRequest
import com.omnillm.engines.llamacpp.native.NativeResult
import com.omnillm.engines.llamacpp.native.NativeSessionRequest
import com.omnillm.engines.llamacpp.native.NativeSessionToken
import com.omnillm.engines.llamacpp.native.NativeStreamEvent
import com.omnillm.engines.llamacpp.native.NativeStreamKind
import com.omnillm.engines.llamacpp.native.StubNativeBackend
import com.omnillm.interfaces.http.ChatMessageDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.OpenAIChatRequestDto
import com.omnillm.interfaces.http.SseEvent
import com.omnillm.interfaces.http.SseHandlerResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.memory.InMemoryInstallationRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-02: engine token deltas must surface as VISIBLE assistant text on the
 * control-plane HTTP chat surface (sync `content` + SSE `delta.content`).
 *
 * The fake [TextTokenBackend] emits deterministic TOKEN_DELTA events carrying
 * plaintext `text` attributes (the catalog stub only carries opaque digests),
 * mirroring what a real native decode produces. The engine event model already
 * allows bounded opaque attributes on [com.omnillm.engines.api.EngineEvent].
 */
class ControlPlaneVisibleTextTest {

    private val principal = HttpPrincipal(
        principalId = "visible-text-principal",
        tokenId = "tok-visible",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    /** Expected concatenation of the fake backend's token deltas. */
    private val expectedText = "Hello from the visible engine!"

    private fun harness(deltas: List<String>): VisibleTextHarness {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            llamaBackend = TextTokenBackend(deltas),
        )
        val binding = EngineExecuteBinding(
            buildMode = ProductBuildMode(developmentShipMode = true),
            exploratoryEnabled = { true },
        )
        binding.applyAttachment(pack)
        val harness = OrchestratorModule.createInMemoryHarness(
            capacity = ResourceVector(
                cpuAnonBytes = 512L * 1024L * 1024L,
                nativeThreads = 64L,
                fileDescriptors = 256L,
            ),
            safetyMargin = ResourceVector(
                cpuAnonBytes = 1_000L,
                nativeThreads = 1L,
                fileDescriptors = 4L,
            ),
            engine = binding.inferenceEngine,
            capabilities = binding.capabilityLookup,
        )
        val installs = InMemoryInstallationRepository()
        val rev = ModelRevisionId.parse(IdentityHashing.sha256Hex("model|fixture"))
        runBlocking {
            installs.save(
                InstallationSnapshot.discovered(
                    installationId = InstallationId("550e8400-e29b-41d4-a716-446655440000"),
                    modelRevisionId = rev,
                    artifactPackageId = ArtifactPackageId.parse("d".repeat(64)),
                ),
            )
        }
        val handler = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = harness.registry,
            commandLedger = RequestRegistryModule.createInMemoryWithCommits().commandLedger,
            tokenService = LoopbackTokenService(),
            orchestrator = harness.orchestrator,
            exploratorySource = {
                ExploratoryInferenceSource(
                    binding = binding,
                    modelManager = ModelManagerModule.createInMemoryControlPlane(
                        installations = installs,
                    ),
                )
            },
        )
        return VisibleTextHarness(handler, binding)
    }

    private data class VisibleTextHarness(
        val handler: ControlPlaneHttpHandler,
        val binding: EngineExecuteBinding,
    )

    @Test
    fun syncChat_returnsConcatenatedDeltaTextWithStop() = runBlocking {
        val h = harness(
            listOf("Hello ", "from the ", "visible engine!"),
        ).handler
        val r = h.createChatCompletion(
            principal,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
            ),
            requestIdHeader = "11111111-1111-1111-1111-111111111111",
            idempotencyKeyHeader = "visible-sync-1-idem",
        )
        assertTrue("sync chat must succeed with visible text: $r", r is HttpHandlerResult.Ok)
        val body = (r as HttpHandlerResult.Ok).body
        assertEquals(expectedText, body.choices[0].message.content)
        assertEquals("stop", body.choices[0].finishReason)
    }

    @Test
    fun sseStream_emitsContentDeltasAndTerminates() = runBlocking {
        val h = harness(
            listOf("Hello ", "from the ", "visible engine!"),
        ).handler
        val stream = h.createChatCompletionStream(
            principal,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                stream = true,
            ),
            requestIdHeader = "22222222-2222-2222-2222-222222222222",
            idempotencyKeyHeader = "visible-sse-1-idem",
        )
        assertTrue("SSE chat must stream: $stream", stream is SseHandlerResult.Stream)
        val events: List<SseEvent> = (stream as SseHandlerResult.Stream).events.toList()
        assertTrue("stream must not be empty", events.isNotEmpty())
        // At least one chunk carries the aggregated delta text as choices[0].delta.content.
        val contentChunks = events.filter {
            !it.isTerminal && it.id != "0" && it.data.contains(expectedText)
        }
        assertTrue(
            "delta.content must carry the aggregated engine text; got ${events.map { it.data }}",
            contentChunks.isNotEmpty(),
        )
        // Terminal semantics: a "stop" finish chunk then [DONE].
        assertTrue(
            "stream must end with a stop finish chunk",
            events.any { !it.isTerminal && it.data.contains("\"finish_reason\":\"stop\"") },
        )
        val last = events.last()
        assertTrue("stream must end terminal", last.isTerminal)
        assertTrue(last.data.contains("[DONE]"))
    }
}

/**
 * Fake [NativeBackend] whose TOKEN_DELTA events carry deterministic plaintext
 * `text` attributes — the minimum fake needed to prove C-02 text aggregation.
 * All other surface is delegated to the catalog stub (test-only).
 */
private class TextTokenBackend(
    private val deltas: List<String>,
) : NativeBackend {

    private val delegate = StubNativeBackend(deltaCount = 0)

    override fun libraryLabel(): String = "text-token-fake"
    override fun isAvailable(): Boolean = true
    override fun probe(request: NativeProbeRequest): NativeResult<NativeProbeOutcome> =
        delegate.probe(request)

    override fun loadModel(request: NativeLoadRequest): NativeResult<NativeModelToken> =
        delegate.loadModel(request)

    override fun createSession(
        model: NativeModelToken,
        request: NativeSessionRequest,
    ): NativeResult<NativeSessionToken> = delegate.createSession(model, request)

    override fun generate(
        session: NativeSessionToken,
        request: NativeGenerateRequest,
        cancelFlag: () -> Boolean,
        onEvent: (NativeStreamEvent) -> Unit,
    ): NativeResult<NativeGenerateOutcome> {
        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.METADATA,
                attributes = mapOf("library" to libraryLabel()),
            ),
        )
        var completed = 0
        for ((index, text) in deltas.withIndex()) {
            if (cancelFlag()) {
                return NativeResult.err(
                    com.omnillm.engines.llamacpp.native.NativeError(
                        code = com.omnillm.engines.llamacpp.native.NativeErrorCode.CANCELLED,
                        message = "fake cancelled",
                    ),
                )
            }
            onEvent(
                NativeStreamEvent(
                    kind = NativeStreamKind.TOKEN_DELTA,
                    payloadDigestHex = StubNativeBackend.STUB_DELTA_DIGEST,
                    attributes = mapOf("index" to index.toString(), "text" to text),
                ),
            )
            completed++
        }
        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.USAGE,
                attributes = mapOf(
                    "promptTokens" to "1",
                    "completionTokens" to completed.toString(),
                ),
            ),
        )
        onEvent(
            NativeStreamEvent(
                kind = NativeStreamKind.STOP,
                attributes = mapOf("stopReason" to "COMPLETED"),
            ),
        )
        return NativeResult.ok(
            NativeGenerateOutcome(
                promptTokens = 1,
                completionTokens = completed,
                stopReason = "COMPLETED",
            ),
        )
    }

    override fun embed(
        model: NativeModelToken,
        request: NativeEmbedRequest,
    ): NativeResult<NativeEmbedOutcome> = delegate.embed(model, request)

    override fun closeSession(session: NativeSessionToken): NativeResult<Unit> =
        delegate.closeSession(session)

    override fun unloadModel(model: NativeModelToken): NativeResult<Unit> =
        delegate.unloadModel(model)

    override fun requestCancel(operationToken: String): NativeResult<Unit> =
        delegate.requestCancel(operationToken)
}
