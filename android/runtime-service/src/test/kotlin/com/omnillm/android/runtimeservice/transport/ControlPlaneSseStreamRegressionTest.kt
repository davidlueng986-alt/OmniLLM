package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.EmbeddingRequestSpec
import com.omnillm.features.playground.ports.CancelPortResult
import com.omnillm.features.playground.ports.InferenceHandle
import com.omnillm.features.playground.ports.PlaygroundInferencePort
import com.omnillm.interfaces.http.SseEvent
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.sse.SseFraming
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COR-03 regression: HTTP SSE chat streaming must never throw inside the flow.
 *
 * The old path polled the LOCAL_UI-gated PlaygroundService.streamEvents, whose
 * `require()` threw on the first poll and broke the SSE connection mid-stream.
 * The handler now polls the inference port and routes every failure through a
 * terminal event; a throwing poll must produce a graceful terminal, never an
 * uncaught exception from the flow.
 */
class ControlPlaneSseStreamRegressionTest {

    private val principal = HttpPrincipal(
        principalId = "sse-regression-principal",
        tokenId = "tok-sse",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun handler(): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = JobManagerModule.createManager(),
            policyManager = PolicyModule.createManager(),
        )
    }

    private fun collect(stream: ControlPlaneHttpHandler, requestId: String, model: String, port: PlaygroundInferencePort): List<SseEvent> =
        runBlocking {
            val result = handler().streamOpenAiChatChunks(requestId, model, principal, port)
            assertTrue(result is com.omnillm.interfaces.http.SseHandlerResult.Stream)
            (result as com.omnillm.interfaces.http.SseHandlerResult.Stream).events.toList()
        }

    private fun portThrowing(): PlaygroundInferencePort = object : PlaygroundInferencePort {
        override suspend fun startChat(principal: PrincipalId, spec: ChatRequestSpec): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun startEmbedding(principal: PrincipalId, spec: EmbeddingRequestSpec): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun cancel(principal: PrincipalId, spec: CancelInferenceSpec): OmniResult<CancelPortResult> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun query(principal: PrincipalId, requestId: String): OmniResult<InferenceHandle> {
            // Simulates the old PlaygroundService LOCAL_UI require() crash.
            throw IllegalArgumentException("Playground accepts LOCAL_UI principal only (INV-011)")
        }
    }

    private fun portError(): PlaygroundInferencePort = object : PlaygroundInferencePort {
        override suspend fun startChat(principal: PrincipalId, spec: ChatRequestSpec): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun startEmbedding(principal: PrincipalId, spec: EmbeddingRequestSpec): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun cancel(principal: PrincipalId, spec: CancelInferenceSpec): OmniResult<CancelPortResult> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun query(principal: PrincipalId, requestId: String): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.NOT_FOUND(message = "request not found"))
    }

    /** Terminal handle carrying real assistant text (happy path). */
    private fun portTerminal(text: String = "hello from engine"): PlaygroundInferencePort =
        object : PlaygroundInferencePort {
            override suspend fun startChat(principal: PrincipalId, spec: ChatRequestSpec): OmniResult<InferenceHandle> =
                OmniResult.err(OmniError.INTERNAL(message = "unused"))
            override suspend fun startEmbedding(principal: PrincipalId, spec: EmbeddingRequestSpec): OmniResult<InferenceHandle> =
                OmniResult.err(OmniError.INTERNAL(message = "unused"))
            override suspend fun cancel(principal: PrincipalId, spec: CancelInferenceSpec): OmniResult<CancelPortResult> =
                OmniResult.err(OmniError.INTERNAL(message = "unused"))
            override suspend fun query(principal: PrincipalId, requestId: String): OmniResult<InferenceHandle> =
                OmniResult.ok(
                    InferenceHandle(
                        requestId = requestId,
                        operationKind = "CHAT",
                        state = "COMPLETED",
                        actualModelRevisionId = "model-rev",
                        assistantText = text,
                    ),
                )
        }

    /** STREAMING on first poll, COMPLETED afterwards (live stream case). */
    private fun portProgressive(): PlaygroundInferencePort = object : PlaygroundInferencePort {
        private var polls = 0
        override suspend fun startChat(principal: PrincipalId, spec: ChatRequestSpec): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun startEmbedding(principal: PrincipalId, spec: EmbeddingRequestSpec): OmniResult<InferenceHandle> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun cancel(principal: PrincipalId, spec: CancelInferenceSpec): OmniResult<CancelPortResult> =
            OmniResult.err(OmniError.INTERNAL(message = "unused"))
        override suspend fun query(principal: PrincipalId, requestId: String): OmniResult<InferenceHandle> {
            polls++
            val terminal = polls > 1
            return OmniResult.ok(
                InferenceHandle(
                    requestId = requestId,
                    operationKind = "CHAT",
                    state = if (terminal) "COMPLETED" else "STREAMING",
                    assistantText = if (terminal) "final text" else null,
                ),
            )
        }
    }

    @Test
    fun throwingPoll_emitsTerminalEventInsteadOfCrashingTheFlow() {
        val events = collect(handler(), "req-1", "model-x", portThrowing())
        assertFalse("stream must emit events", events.isEmpty())
        val last = events.last()
        assertTrue("stream must end with a terminal event", last.isTerminal)
        assertEquals(SseFraming.EVENT_TERMINAL, last.event)
        assertTrue(last.data.contains("error"))
    }

    @Test
    fun errorPoll_emitsTerminalErrorEvent() {
        val events = collect(handler(), "req-2", "model-x", portError())
        val last = events.last()
        assertTrue(last.isTerminal)
        assertEquals(SseFraming.EVENT_TERMINAL, last.event)
    }

    @Test
    fun terminalHandle_streamsRealTextAndDone() {
        val events = collect(handler(), "req-3", "model-x", portTerminal(text = "real aggregated text"))
        assertTrue(events.isNotEmpty())
        // Role preamble first (OpenAI convention).
        assertEquals("0", events.first().id)
        // Real token text is emitted as a content delta — never a placeholder.
        val deltas = events.filter { it.id != "0" && !it.isTerminal }
        assertTrue(
            "delta chunks must carry the real engine text",
            deltas.any { it.data.contains("real aggregated text") },
        )
        // Terminal [DONE] closes the stream.
        val last = events.last()
        assertTrue(last.isTerminal)
        assertTrue(last.data.contains("[DONE]"))
    }

    @Test
    fun progressivePoll_terminatesAfterSecondPoll() {
        val events = collect(handler(), "req-4", "model-x", portProgressive())
        val last = events.last()
        assertTrue("progressive stream must reach a terminal", last.isTerminal)
        assertTrue(
            "final delta must carry the engine text",
            events.any { it.data.contains("final text") },
        )
        assertNotNull(last.id)
    }
}
