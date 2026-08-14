package com.omnillm.engines.mllm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-runnable tests for the OpenAI-compatible chat-completions protocol the
 * upstream mllm Go server speaks (mllm-cli/pkg/server/handlers.go verified).
 */
class MllmOpenAiProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun buildChatBody_includesModelStreamMessagesAndIds() {
        val body = MllmOpenAiProtocol.buildChatBody(
            modelName = "qwen3",
            promptUtf8 = "hello",
            requestId = "op-1",
            sessionId = "sess-1",
            systemPrompt = "You are a helpful assistant.",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("qwen3", root["model"]?.jsonPrimitive?.content)
        assertEquals("true", root["stream"]?.jsonPrimitive?.toString())
        assertEquals("op-1", root["id"]?.jsonPrimitive?.content)
        assertEquals("sess-1", root["session_id"]?.jsonPrimitive?.content)
        val messages = root["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("You are a helpful assistant.", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("user", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("hello", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildChatBody_omitsSessionWhenNull() {
        val body = MllmOpenAiProtocol.buildChatBody(
            modelName = "qwen3",
            promptUtf8 = "hi",
            requestId = "op-2",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertNull(root["session_id"])
        assertEquals(1, root["messages"]!!.jsonArray.size)
    }

    @Test
    fun parseSseData_doneMapsToStopCompleted() {
        val event = MllmOpenAiProtocol.parseSseData(MllmOpenAiProtocol.SSE_DONE)
        assertTrue(event is MllmOpenAiProtocol.SseEvent.Stop)
        assertEquals(MllmOpenAiProtocol.STOP_REASON_COMPLETED, (event as MllmOpenAiProtocol.SseEvent.Stop).reason)
    }

    @Test
    fun parseSseData_deltaContent() {
        val chunk = """{"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}"""
        val event = MllmOpenAiProtocol.parseSseData(chunk)
        assertTrue(event is MllmOpenAiProtocol.SseEvent.Delta)
        assertEquals("Hi", (event as MllmOpenAiProtocol.SseEvent.Delta).text)
    }

    @Test
    fun parseSseData_finishReasonStopMapsToStop() {
        val chunk = """{"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        val event = MllmOpenAiProtocol.parseSseData(chunk)
        assertTrue(event is MllmOpenAiProtocol.SseEvent.Stop)
        assertEquals(MllmOpenAiProtocol.STOP_REASON_COMPLETED, (event as MllmOpenAiProtocol.SseEvent.Stop).reason)
    }

    @Test
    fun parseSseData_finishReasonLengthMapsToStopLengthLimit() {
        val chunk = """{"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}"""
        val event = MllmOpenAiProtocol.parseSseData(chunk)
        assertTrue(event is MllmOpenAiProtocol.SseEvent.Stop)
        assertEquals(MllmOpenAiProtocol.STOP_REASON_LENGTH, (event as MllmOpenAiProtocol.SseEvent.Stop).reason)
    }

    @Test
    fun parseSseData_garbageYieldsWarning_notCrash() {
        val event = MllmOpenAiProtocol.parseSseData("not json at all")
        assertTrue(event is MllmOpenAiProtocol.SseEvent.Warning)
    }

    @Test
    fun parseSseData_emptyDeltaIgnored() {
        val chunk = """{"id":"x","choices":[{"index":0,"delta":{},"finish_reason":null}]}"""
        assertTrue(MllmOpenAiProtocol.parseSseData(chunk) is MllmOpenAiProtocol.SseEvent.Ignore)
    }

    @Test
    fun chatCompletionsUrl_isLoopbackFixedPort() {
        assertEquals(
            "http://127.0.0.1:8080/v1/chat/completions",
            MllmOpenAiProtocol.chatCompletionsUrl(),
        )
    }

    // --- D3 identity probe ---

    @Test
    fun identityProbeModel_embedsUniqueNonce() {
        val m1 = MllmOpenAiProtocol.identityProbeModel("nonce-1")
        val m2 = MllmOpenAiProtocol.identityProbeModel("nonce-2")
        assertTrue(m1.startsWith(MllmOpenAiProtocol.PROBE_MODEL_PREFIX))
        assertTrue(m1 != m2)
        assertTrue(MllmOpenAiProtocol.isIdentityProbeModel(m1))
        assertFalse(MllmOpenAiProtocol.isIdentityProbeModel("qwen3"))
    }

    @Test
    fun buildIdentityProbeBody_carriesProbeModelAndNonce() {
        val body = MllmOpenAiProtocol.buildIdentityProbeBody(
            probeModelName = "omnillm-identity-probe-n1",
            nonce = "n1",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("omnillm-identity-probe-n1", root["model"]?.jsonPrimitive?.content)
        assertEquals("false", root["stream"]?.jsonPrimitive?.toString())
        assertEquals("n1", root["id"]?.jsonPrimitive?.content)
        val messages = root["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("n1", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun verifyIdentityProbeResponse_upstream404TemplatePasses() {
        assertTrue(
            MllmOpenAiProtocol.verifyIdentityProbeResponse(
                status = 404,
                body = "Model 'omnillm-identity-probe-n1' is not available on this server.\n",
                probeModelName = "omnillm-identity-probe-n1",
            ),
        )
    }

    @Test
    fun verifyIdentityProbeResponse_nonReflectingOrWrongShapeFails() {
        val probeModel = "omnillm-identity-probe-n1"
        assertFalse(
            "generic OpenAI error shape must fail",
            MllmOpenAiProtocol.verifyIdentityProbeResponse(
                status = 404,
                body = """{"error":{"message":"model not found"}}""",
                probeModelName = probeModel,
            ),
        )
        assertFalse(
            "200 with arbitrary body must fail",
            MllmOpenAiProtocol.verifyIdentityProbeResponse(
                status = 200,
                body = "hello",
                probeModelName = probeModel,
            ),
        )
        assertFalse(
            "500 must fail",
            MllmOpenAiProtocol.verifyIdentityProbeResponse(
                status = 500,
                body = "boom",
                probeModelName = probeModel,
            ),
        )
        assertFalse(
            "template echoing a DIFFERENT model name must fail",
            MllmOpenAiProtocol.verifyIdentityProbeResponse(
                status = 404,
                body = "Model 'other' is not available on this server.",
                probeModelName = probeModel,
            ),
        )
    }
}
