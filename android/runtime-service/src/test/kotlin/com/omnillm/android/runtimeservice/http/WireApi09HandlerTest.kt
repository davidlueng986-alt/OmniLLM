package com.omnillm.android.runtimeservice.http

import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.ChatMessageDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.OpenAIChatRequestDto
import com.omnillm.interfaces.http.ResponseFormatDto
import com.omnillm.interfaces.http.SseHandlerResult
import com.omnillm.interfaces.http.ToolDefinitionDto
import com.omnillm.interfaces.http.ToolFunctionDefinitionDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * API-09 handler tests: stop is wired/validated and response_format / tools /
 * tool_choice fail closed as CAPABILITY_UNSUPPORTED on the sync/SSE chat path
 * (no structured/tool pipeline reachable there).
 */
class WireApi09HandlerTest {

    private val admin = HttpPrincipal(
        principalId = "wire-admin",
        tokenId = "tok-wire",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun handler(): ControlPlaneHttpHandler {
        val ledgers = com.omnillm.runtime.RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun assertErrCode(result: HttpHandlerResult<*>, code: OmniErrorCode, context: String) {
        assertTrue("$context must fail closed with $code, got $result", result is HttpHandlerResult.Err)
        assertEquals(code, (result as HttpHandlerResult.Err).error.code)
    }

    @Test
    fun chat_toolsParam_failsClosedAsUnsupported() = runBlocking {
        val h = handler()
        val r = h.createChatCompletion(
            admin,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                tools = listOf(
                    ToolDefinitionDto(
                        function = ToolFunctionDefinitionDto(
                            name = "lookup",
                        ),
                    ),
                ),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "chat-tools",
        )
        assertErrCode(r, OmniErrorCode.CAPABILITY_UNSUPPORTED, "chat tools")
    }

    @Test
    fun chat_responseFormatParam_failsClosedAsUnsupported() = runBlocking {
        val h = handler()
        val r = h.createChatCompletion(
            admin,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                responseFormat = ResponseFormatDto(type = "json_object"),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "chat-rf",
        )
        assertErrCode(r, OmniErrorCode.CAPABILITY_UNSUPPORTED, "chat response_format")
    }

    @Test
    fun chat_streamWithToolChoice_failsClosedAsUnsupported() = runBlocking {
        val h = handler()
        val r = h.createChatCompletionStream(
            admin,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                stream = true,
                toolChoice = JsonPrimitive("required"),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "chat-tc",
        )
        assertTrue("stream must fail pre-stream: $r", r is SseHandlerResult.PreStreamError)
        assertEquals(
            OmniErrorCode.CAPABILITY_UNSUPPORTED,
            (r as SseHandlerResult.PreStreamError).error.code,
        )
    }

    @Test
    fun chat_malformedStop_failsClosedAsInvalidRequest() = runBlocking {
        val h = handler()
        val r = h.createChatCompletion(
            admin,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                stop = JsonPrimitive(7),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "chat-stop",
        )
        assertErrCode(r, OmniErrorCode.INVALID_REQUEST, "chat malformed stop")
    }
}
