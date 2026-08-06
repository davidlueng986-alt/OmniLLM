package com.omnillm.features.tools

import com.omnillm.features.tools.domain.StructuredOutputValidator
import com.omnillm.features.tools.domain.ValidationStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredOutputValidatorTest {

    private val personSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "count" to mapOf("type" to "integer", "minimum" to 0),
        ),
        "required" to listOf("name", "count"),
    )

    @Test
    fun valid_object_passes() {
        val r = StructuredOutputValidator.validateJson(
            """{"name":"x","count":1}""",
            personSchema,
        )
        assertTrue(r is StructuredOutputValidator.Outcome.Valid)
    }

    @Test
    fun missing_required_fails() {
        val r = StructuredOutputValidator.validateJson(
            """{"name":"x"}""",
            personSchema,
        )
        assertTrue(r is StructuredOutputValidator.Outcome.Invalid)
        val inv = r as StructuredOutputValidator.Outcome.Invalid
        assertEquals(ValidationStatusCode.OUTPUT_INVALID, inv.code)
    }

    @Test
    fun type_mismatch_fails() {
        val r = StructuredOutputValidator.validateJson(
            """{"name":"x","count":"nope"}""",
            personSchema,
        )
        assertTrue(r is StructuredOutputValidator.Outcome.Invalid)
    }

    @Test
    fun invalid_json_fails() {
        val r = StructuredOutputValidator.validateJson("{not-json", personSchema)
        assertTrue(r is StructuredOutputValidator.Outcome.Invalid)
    }

    @Test
    fun enum_enforced() {
        val schema = mapOf(
            "type" to "string",
            "enum" to listOf("a", "b"),
        )
        assertTrue(
            StructuredOutputValidator.validateJson("\"a\"", schema)
                is StructuredOutputValidator.Outcome.Valid,
        )
        assertTrue(
            StructuredOutputValidator.validateJson("\"c\"", schema)
                is StructuredOutputValidator.Outcome.Invalid,
        )
    }
}
