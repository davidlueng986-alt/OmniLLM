package com.omnillm.interfaces.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D23e wire-contract drift: OmniExecutionInfo (spec :2179-2209) requires
 * actual_model_revision_id / engine_build_id / backend — when the engine is
 * silent the handler emitted the object with only request_id (nullable fields
 * omitted by the codec), which is schema-INVALID: missing required fields AND
 * a fabricated partial object.
 *
 * Honest rule (documented in WireDtos/ControlPlaneHttpHandler):
 * - engine exposed routing facts -> carry exactly those facts (nulls omitted)
 * - engine silent (no facts)    -> OMIT the object entirely; never fabricate
 *   the required fields.
 * Spec amendment for the specs agent: make the three fields (and possibly the
 * object) conditional on the engine actually reporting them.
 */
class OmniExecutionInfoOmissionTest {

    private val uuid = "11111111-1111-1111-1111-111111111111"
    private val digest64 = "a".repeat(64)

    private fun response(omnillm: OmniExecutionInfoDto?): ChatCompletionResponseDto =
        ChatCompletionResponseDto(
            id = "chatcmpl-1",
            created = 1L,
            model = "demo",
            choices = listOf(
                ChatCompletionChoiceDto(
                    message = AssistantMessageDto(role = "assistant", content = "hi"),
                    finishReason = "stop",
                ),
            ),
            usage = UsageDto(),
            omnillm = omnillm,
        )

    @Test
    fun engineSilent_factsOrNull_returnsNull() {
        // No routing facts -> nothing to report -> the object must be omitted.
        assertNull(
            OmniExecutionInfoDto.ofFactsOrNull(
                requestId = uuid,
                actualModelRevisionId = null,
                engineBuildId = null,
                backend = null,
                degradations = emptyList(),
                evidenceLabel = null,
            ),
        )
    }

    @Test
    fun engineSilent_chatCompletion_wireOmitsOmnillm() {
        val dto = response(
            OmniExecutionInfoDto.ofFactsOrNull(
                requestId = uuid,
                actualModelRevisionId = null,
                engineBuildId = null,
                backend = null,
                degradations = emptyList(),
                evidenceLabel = null,
            ),
        )
        val json = HttpJson.codec.encodeToString(ChatCompletionResponseDto.serializer(), dto)
        val obj = Json.parseToJsonElement(json).jsonObject
        assertFalse(
            "engine-silent response must NOT emit an invalid partial omnillm: $json",
            obj.containsKey("omnillm"),
        )
    }

    @Test
    fun engineFacts_chatCompletion_wireCarriesOnlyRealFacts() {
        val omnillm = OmniExecutionInfoDto.ofFactsOrNull(
            requestId = uuid,
            actualModelRevisionId = digest64,
            engineBuildId = "engine-1",
            backend = "cpu",
            degradations = listOf("degraded-x"),
            evidenceLabel = null,
        )
        assertTrue("real routing facts must produce the object", omnillm != null)
        val json = HttpJson.codec.encodeToString(ChatCompletionResponseDto.serializer(), response(omnillm))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue("facts present -> omnillm must be emitted: $json", obj.containsKey("omnillm"))
        assertTrue(json.contains("\"request_id\":\"$uuid\""))
        assertTrue(json.contains("\"actual_model_revision_id\":\"$digest64\""))
        // Never fabricated: absent facts are omitted, not invented.
        assertFalse("absent evidence_label must not be fabricated", json.contains("evidence_label"))
        // Schema-legal: the emitted object carries every spec-required field.
        assertTrue(json.contains("\"engine_build_id\":\"engine-1\""))
        assertTrue(json.contains("\"backend\":\"cpu\""))
    }

    @Test
    fun partialFacts_carryOnlyThem_neverFabricateRest() {
        // Engine reported only the backend -> object carries backend, and the
        // other required fields stay absent (honest omission), never placeholders.
        val omnillm = OmniExecutionInfoDto.ofFactsOrNull(
            requestId = uuid,
            actualModelRevisionId = null,
            engineBuildId = null,
            backend = "npu",
            degradations = emptyList(),
            evidenceLabel = null,
        )
        assertTrue(omnillm != null)
        val json = HttpJson.codec.encodeToString(OmniExecutionInfoDto.serializer(), omnillm!!)
        assertTrue(json.contains("\"backend\":\"npu\""))
        assertEquals(
            "spec amendment note: required-but-absent fields are omitted, not fabricated",
            "npu",
            omnillm.backend,
        )
    }
}
