package com.omnillm.interfaces.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-09 contract test: OpenAIChatRequest models stop / response_format /
 * tools / tool_choice per spec (:1971-1994) on the wire and decodes the
 * spec-shaped JSON.
 */
class WireChatRequestContractTest {

    @Test
    fun chatRequest_wireModelsStopResponseFormatToolsToolChoice() {
        val dto = OpenAIChatRequestDto(
            model = "demo",
            messages = listOf(ChatMessageDto(role = "user", content = "hi")),
            stop = JsonPrimitive("</s>"),
            responseFormat = ResponseFormatDto(
                type = "json_schema",
                jsonSchema = JsonSchemaResponseFormatDto(
                    name = "answer",
                    schema = JsonObject(mapOf("type" to JsonPrimitive("object"))),
                ),
            ),
            tools = listOf(
                ToolDefinitionDto(
                    function = ToolFunctionDefinitionDto(
                        name = "lookup",
                        parameters = JsonObject(emptyMap()),
                    ),
                ),
            ),
            toolChoice = JsonPrimitive("auto"),
        )
        val json = HttpJson.codec.encodeToString(OpenAIChatRequestDto.serializer(), dto)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("stop must serialize: $json", obj.containsKey("stop"))
        assertTrue("response_format must serialize: $json", obj.containsKey("response_format"))
        assertTrue("tools must serialize: $json", obj.containsKey("tools"))
        assertTrue("tool_choice must serialize: $json", obj.containsKey("tool_choice"))
    }

    @Test
    fun chatRequest_decodesSpecShapedJsonWithNewParameters() {
        val json =
            """{"model":"demo","messages":[{"role":"user","content":"hi"}],"stop":["<|end|>","\n"],"response_format":{"type":"text"},"tools":[{"type":"function","function":{"name":"lookup","parameters":{}}}],"tool_choice":{"type":"function","function":{"name":"lookup"}}}"""
        val dto = HttpJson.codec.decodeFromString(OpenAIChatRequestDto.serializer(), json)
        assertTrue(dto.stop is JsonPrimitive || dto.stop is JsonArray)
        assertEquals("text", dto.responseFormat?.type)
        assertEquals("lookup", dto.tools?.firstOrNull()?.function?.name)
        assertTrue(dto.toolChoice is JsonObject)
    }
}
