package com.omnillm.features.tools

import com.omnillm.features.tools.domain.SchemaAdmission
import com.omnillm.features.tools.domain.SchemaLimits
import com.omnillm.features.tools.domain.ValidationStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-TOOLS §7.1 — schema bomb rejected before engine compile.
 */
class SchemaBombTest {

    private val tight = SchemaLimits(
        maxDepth = 4,
        maxNodes = 20,
        maxEnumValues = 5,
        maxRegexLength = 16,
        maxSchemaBytes = 2_000,
        maxProperties = 8,
        maxReferenceExpansions = 3,
        maxValidateWallMs = 200,
    )

    @Test
    fun admit_simpleSchema_accepted() {
        val r = SchemaAdmission.admit(simpleSchema(), tight, "demo")
        assertTrue(r is SchemaAdmission.Outcome.Accepted)
        val ok = r as SchemaAdmission.Outcome.Accepted
        assertTrue(ok.schemaDigest.length == 64)
        assertTrue(ok.nodeCount > 0)
    }

    @Test
    fun admit_deepNesting_rejectedAsBomb() {
        var node: Any? = mapOf("type" to "string")
        repeat(10) {
            node = mapOf("type" to "object", "properties" to mapOf("x" to node))
        }
        @Suppress("UNCHECKED_CAST")
        val schema = node as Map<String, Any?>
        val r = SchemaAdmission.admit(schema, tight)
        assertTrue(r is SchemaAdmission.Outcome.Rejected)
        val rej = r as SchemaAdmission.Outcome.Rejected
        assertEquals(ValidationStatusCode.SCHEMA_BOMB, rej.code)
        assertTrue(rej.reason.contains("depth"))
    }

    @Test
    fun admit_cyclicRef_rejectedAsBomb() {
        // Walk expands $ref tokens on the path; nested $ref same token ⇒ cycle.
        val nested = mapOf(
            "\$ref" to "#/cycle",
            "items" to mapOf("\$ref" to "#/cycle"),
        )
        val r = SchemaAdmission.admit(nested, tight)
        assertTrue(r is SchemaAdmission.Outcome.Rejected)
        val rej = r as SchemaAdmission.Outcome.Rejected
        assertEquals(ValidationStatusCode.SCHEMA_BOMB, rej.code)
        assertTrue(
            rej.reason.contains("cyclic") || rej.reason.contains("expansion") ||
                rej.reason.contains("depth") || rej.reason.contains("node"),
        )
    }

    @Test
    fun admit_hugeEnum_rejectedAsBomb() {
        val enumVals = (1..50).map { "v$it" }
        val schema = mapOf(
            "type" to "string",
            "enum" to enumVals,
        )
        val r = SchemaAdmission.admit(schema, tight)
        assertTrue(r is SchemaAdmission.Outcome.Rejected)
        val rej = r as SchemaAdmission.Outcome.Rejected
        assertEquals(ValidationStatusCode.SCHEMA_BOMB, rej.code)
        assertTrue(rej.reason.contains("enum"))
    }

    @Test
    fun admit_hugeRegex_rejectedAsBomb() {
        val schema = mapOf(
            "type" to "string",
            "pattern" to "a".repeat(100),
        )
        val r = SchemaAdmission.admit(schema, tight)
        assertTrue(r is SchemaAdmission.Outcome.Rejected)
        val rej = r as SchemaAdmission.Outcome.Rejected
        assertEquals(ValidationStatusCode.SCHEMA_BOMB, rej.code)
        assertTrue(rej.reason.contains("regex"))
    }

    @Test
    fun admit_tooManyTools_rejected() {
        val tools = (1..10).map { toolDef(id = "t$it") }
        val r = SchemaAdmission.admitTools(tools, tight.copy(maxToolCount = 3))
        assertTrue(r is SchemaAdmission.Outcome.Rejected)
        val rej = r as SchemaAdmission.Outcome.Rejected
        assertEquals(ValidationStatusCode.SCHEMA_BOMB, rej.code)
    }

    @Test
    fun admit_oversizedSchemaBytes_rejected() {
        val props = (1..30).associate { i ->
            "p$i" to mapOf("type" to "string", "description" to "d".repeat(80))
        }
        val schema = mapOf("type" to "object", "properties" to props)
        val r = SchemaAdmission.admit(schema, tight.copy(maxNodes = 500, maxDepth = 8))
        assertTrue(r is SchemaAdmission.Outcome.Rejected)
        // either bytes or properties
        val rej = r as SchemaAdmission.Outcome.Rejected
        assertEquals(ValidationStatusCode.SCHEMA_BOMB, rej.code)
    }
}
