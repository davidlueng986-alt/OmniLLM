package com.omnillm.interfaces.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-12 contract test: the response message is AssistantMessage — content
 * nullable, tool_calls present, and additionalProperties:false (asset_ids
 * must never appear, unlike the request-side ChatMessage).
 */
class WireAssistantMessageContractTest {

    @Test
    fun assistantMessage_wireHasNullableContentAndToolCalls_noAssetIds() {
        val dto = AssistantMessageDto(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                ToolCallDto(
                    id = "call-1",
                    function = ToolCallFunctionDto(name = "lookup", arguments = "{\"q\":\"x\"}"),
                ),
            ),
        )
        val json = HttpJson.codec.encodeToString(AssistantMessageDto.serializer(), dto)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("tool_calls must serialize: $json", obj.containsKey("tool_calls"))
        assertEquals(
            "lookup",
            (obj["tool_calls"] as JsonArray).first().jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
        // additionalProperties:false — asset_ids must never appear on assistant messages.
        assertFalse("asset_ids must NOT leak onto AssistantMessage: $json", json.contains("asset_ids"))
    }

    @Test
    fun assistantMessage_withContentSerializesContent() {
        val dto = AssistantMessageDto(role = "assistant", content = "plain text")
        val json = HttpJson.codec.encodeToString(AssistantMessageDto.serializer(), dto)
        assertTrue(json.contains("\"content\":\"plain text\""))
        // No tool_calls requested → empty array still present (spec array type).
        assertTrue(json.contains("\"tool_calls\":[]"))
    }

    @Test
    fun chatCompletionChoice_messageIsAssistantMessageShape() {
        val dto = ChatCompletionChoiceDto(
            message = AssistantMessageDto(role = "assistant", content = "ok"),
            finishReason = "stop",
        )
        val json = HttpJson.codec.encodeToString(ChatCompletionChoiceDto.serializer(), dto)
        assertTrue(json.contains("\"role\":\"assistant\""))
        assertFalse("asset_ids must not leak via choice message: $json", json.contains("asset_ids"))
    }
}
