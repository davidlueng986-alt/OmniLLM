package com.omnillm.features.tools

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.domain.ToolResult
import com.omnillm.features.tools.domain.digestPayload
import com.omnillm.features.tools.observability.ToolsTraceRedaction
import com.omnillm.features.tools.policy.ToolExecutionPolicy
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.observability.Redactor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-TOOLS §7.5 session uncertainty + §7.6 privacy; report ≠ telemetry.
 */
class SessionUncertaintyAndPrivacyTest {

    private val modelId = "f".repeat(64)

    @Test
    fun uncertainProposal_rejectsResultWithoutInventingOppositeState() = runBlocking {
        val (svc, _, _) = buildService(modelId = modelId, mode = StructuredMode.POST_VALIDATE)
        val started = svc.startToolCalling(LocalUiPrincipal.ID, toolSpec(modelId)) as OmniResult.Ok
        val prop = started.value.proposals.first()

        val marked = svc.markProposalUncertain(LocalUiPrincipal.ID, prop.proposalId) as OmniResult.Ok
        assertEquals(ToolProposalState.UNCERTAIN, marked.value.state)

        val body = """{"done":true}"""
        val submit = svc.submitToolResult(
            LocalUiPrincipal.ID,
            ToolResult(
                requestId = prop.requestId,
                proposalId = prop.proposalId,
                attempt = 1,
                idempotencyKey = "after-uncertain",
                resultPayloadDigest = digestPayload(body),
                resultBodyJson = body,
                submittedAtEpochMs = 9_000L,
            ),
        )
        assertTrue(submit is OmniResult.Err)
        assertEquals(OmniErrorCode.ABORTED_UNCERTAIN, (submit as OmniResult.Err).error.code)

        // Still uncertain — not auto-committed to opposite.
        val q = svc.queryProposal(LocalUiPrincipal.ID, prop.proposalId) as OmniResult.Ok
        assertEquals(ToolProposalState.UNCERTAIN, q.value.state)
    }

    @Test
    fun markUncertain_helper() {
        val p = ToolProposal(
            proposalId = "x",
            requestId = "11111111-1111-1111-1111-111111111111",
            toolId = "t",
            schemaDigest = "a".repeat(64),
            argumentsJson = """{"secret":"value"}""",
            createdAtEpochMs = 1L,
        )
        val u = ToolExecutionPolicy.markUncertain(p)
        assertEquals(ToolProposalState.UNCERTAIN, u.state)
    }

    @Test
    fun trace_excludesArgumentsAndResults() {
        val p = ToolProposal(
            proposalId = "prop-priv",
            requestId = "11111111-1111-1111-1111-111111111111",
            toolId = "get_weather",
            schemaDigest = "b".repeat(64),
            argumentsJson = """{"city":"secret-city","token":"Bearer abc.def.ghi"}""",
            createdAtEpochMs = 1L,
        )
        val fields = ToolsTraceRedaction.proposalTraceFields(
            proposal = p,
            mode = StructuredMode.POST_VALIDATE,
            timingMs = 12L,
        )
        assertFalse(fields.containsKey("arguments"))
        assertFalse(fields.values.any { it.contains("secret-city") })
        assertFalse(fields.values.any { it.contains("Bearer") })
        assertEquals("get_weather", fields["toolId"])
        assertEquals(p.schemaDigest, fields["schemaDigest"])
        assertTrue(fields.containsKey("actualMode"))
    }

    @Test
    fun maybeArguments_defaultRedacted() {
        assertEquals(
            Redactor.REDACTED,
            ToolsTraceRedaction.maybeArguments("""{"a":1}""", includeSensitive = false),
        )
        assertEquals(
            """{"a":1}""",
            ToolsTraceRedaction.maybeArguments("""{"a":1}""", includeSensitive = true),
        )
    }

    @Test
    fun metadata_rejectsLongLivedSecrets() {
        val err = ToolsTraceRedaction.rejectLongLivedSecretInMetadata(
            mapOf("refreshToken" to "super-secret-value"),
        )
        assertNotNull(err)
        assertTrue(err!!.contains("secret"))
    }

    @Test
    fun metadata_rejectsBearerPatterns() {
        val err = ToolsTraceRedaction.rejectLongLivedSecretInMetadata(
            mapOf("note" to "Bearer abcdefghijklmnop"),
        )
        assertNotNull(err)
    }

    @Test
    fun allowlist_doesNotIncludeContentReportFields() {
        // report ≠ telemetry: content-report fields are not in tools default allowlist
        assertFalse(ToolsTraceRedaction.DEFAULT_ALLOWLIST.contains("reportId"))
        assertFalse(ToolsTraceRedaction.DEFAULT_ALLOWLIST.contains("contentReportCategory"))
        assertFalse(ToolsTraceRedaction.DEFAULT_ALLOWLIST.contains("arguments"))
        assertFalse(ToolsTraceRedaction.DEFAULT_ALLOWLIST.contains("resultBody"))
    }
}
