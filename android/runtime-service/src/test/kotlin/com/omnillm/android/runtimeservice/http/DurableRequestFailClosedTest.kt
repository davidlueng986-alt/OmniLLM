package com.omnillm.android.runtimeservice.http

import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.AsyncInferenceRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.HttpJson
import com.omnillm.interfaces.http.NativeChatPayloadDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D8 wire-contract drift: the durable `/omni/v1/requests` path silently drops
 * `response_format` / `tools` / `tool_choice` (NativeChatPayloadDto never
 * modeled them + JsonConfig ignoreUnknownKeys=true), so a client asking for
 * structured output gets a plain chat executed — a silent capability lie.
 *
 * Required behavior:
 * 1. The DTO must model the three spec fields (spec NativeChatPayload
 *    :2624-2649 properties response_format/tools/tool_choice).
 * 2. The durable path must fail closed when they are present (mirror the sync
 *    path) — never silently accept-and-drop.
 */
class DurableRequestFailClosedTest {

    private val principal = HttpPrincipal(
        principalId = "d8-principal",
        tokenId = "tok-d8",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun handler(): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
    }

    private fun decodeDurable(json: String): AsyncInferenceRequestDto =
        HttpJson.codec.decodeFromString(AsyncInferenceRequestDto.serializer(), json)

    private fun chatJson(extra: String): String =
        """{"request_id":"${uuid()}","idempotency_key":"d8-idem-${uuid()}","operation":"CHAT","chat":{"model":"m","messages":[{"role":"user","content":"hi"}]$extra}}"""

    // ----- RED: durable path must reject, never silently drop ---------------

    @Test
    fun durableRequest_withResponseFormat_isRejectedNotSilentlyAccepted() = runBlocking {
        val h = handler()
        val dto = decodeDurable(
            chatJson(""","response_format":{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}}"""),
        )
        val r = h.createAsyncInferenceRequest(principal, dto)
        assertTrue("durable response_format must fail closed: $r", r is HttpHandlerResult.Err)
        val err = (r as HttpHandlerResult.Err).error
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, err.code)
        assertEquals(
            "the rejection must name the offending parameter",
            "response_format",
            err.details["parameter"],
        )
    }

    @Test
    fun durableRequest_withTools_isRejectedNotSilentlyAccepted() = runBlocking {
        val h = handler()
        val dto = decodeDurable(
            chatJson(""","tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}]"""),
        )
        val r = h.createAsyncInferenceRequest(principal, dto)
        assertTrue("durable tools must fail closed: $r", r is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as HttpHandlerResult.Err).error.code)
        assertEquals("tools", r.error.details["parameter"])
    }

    @Test
    fun durableRequest_withToolChoice_isRejectedNotSilentlyAccepted() = runBlocking {
        val h = handler()
        val dto = decodeDurable(
            chatJson(""","tool_choice":"required""" + "\""),
        )
        val r = h.createAsyncInferenceRequest(principal, dto)
        assertTrue("durable tool_choice must fail closed: $r", r is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as HttpHandlerResult.Err).error.code)
        assertEquals("tool_choice", r.error.details["parameter"])
    }

    // ----- RED: DTO must model the spec fields (round-trip, no drop) --------

    @Test
    fun durableChatPayload_specFields_roundTripPreserved() {
        val wire =
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"response_format":{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}},"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}],"tool_choice":"required"}"""
        val dto = HttpJson.codec.decodeFromString(NativeChatPayloadDto.serializer(), wire)
        val reencoded = HttpJson.codec.encodeToString(NativeChatPayloadDto.serializer(), dto)
        assertTrue("response_format must survive the wire: $reencoded", reencoded.contains("\"response_format\""))
        assertTrue("tools must survive the wire: $reencoded", reencoded.contains("\"tools\""))
        assertTrue("tool_choice must survive the wire: $reencoded", reencoded.contains("\"tool_choice\""))
    }

    @Test
    fun durableChatPayload_specFields_decodeIntoTypedValues() {
        val wire =
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"response_format":{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}},"tool_choice":"required"}"""
        val dto = HttpJson.codec.decodeFromString(NativeChatPayloadDto.serializer(), wire)
        assertEquals("x", dto.responseFormat?.jsonSchema?.name)
        assertEquals("required", (dto.toolChoice as? JsonPrimitive)?.content)
    }
}
