package com.omnillm.features.tools

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ToolProposal
import com.omnillm.features.tools.domain.ToolProposalState
import com.omnillm.features.tools.domain.ToolResult
import com.omnillm.features.tools.domain.digestPayload
import com.omnillm.features.tools.policy.ToolExecutionPolicy
import com.omnillm.features.tools.policy.ToolResultClaimOutcome
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-TOOLS §7.3 non-execution + §7.4 reply-loss / idempotency.
 */
class ToolNonExecutionAndIdempotencyTest {

    private val modelId = "e".repeat(64)

    @Test
    fun platform_refuses_execution() {
        val err = ToolExecutionPolicy.refusePlatformExecution("shell")
        assertEquals(OmniErrorCode.FORBIDDEN, err.code)
        assertEquals(
            com.omnillm.features.tools.domain.ToolExecutionOwner.PLATFORM_FORBIDDEN.name,
            err.details["executionOwner"],
        )
    }

    @Test
    fun service_toolCalling_recordsProposal_doesNotImplyPlatformExecution() = runBlocking {
        val (svc, inference, _) = buildService(
            modelId = modelId,
            mode = StructuredMode.POST_VALIDATE,
        )
        val result = svc.startToolCalling(LocalUiPrincipal.ID, toolSpec(modelId))
        assertTrue(result is OmniResult.Ok)
        val view = (result as OmniResult.Ok).value
        assertEquals(1, view.proposals.size)
        assertEquals(ToolProposalState.PROPOSED, view.proposals[0].state)
        assertEquals(
            com.omnillm.features.tools.domain.ToolExecutionOwner.HOST,
            view.proposals[0].executionOwner,
        )
        assertEquals(1, inference.toolStartCount)
        // Trace must not contain raw arguments.
        assertTrue(view.proposals[0].argumentsRedacted.contains("REDACTED") ||
            view.proposals[0].argumentsRedacted == "[REDACTED]")
    }

    @Test
    fun host_submitToolResult_sameKey_isExistingNotDuplicate() = runBlocking {
        val (svc, _, _) = buildService(modelId = modelId, mode = StructuredMode.POST_VALIDATE)
        val started = svc.startToolCalling(LocalUiPrincipal.ID, toolSpec(modelId)) as OmniResult.Ok
        val prop = started.value.proposals.first()
        val body = """{"ok":true}"""
        val result = ToolResult(
            requestId = prop.requestId,
            proposalId = prop.proposalId,
            attempt = 1,
            idempotencyKey = "host-result-1",
            resultPayloadDigest = digestPayload(body),
            resultBodyJson = body,
            submittedAtEpochMs = 3_000L,
        )
        val first = svc.submitToolResult(LocalUiPrincipal.ID, result) as OmniResult.Ok
        assertEquals("ACCEPTED", first.value.claimStatus)
        assertEquals(ToolProposalState.RESULT_COMMITTED, first.value.state)

        val second = svc.submitToolResult(LocalUiPrincipal.ID, result) as OmniResult.Ok
        assertEquals("EXISTING", second.value.claimStatus)
        assertEquals(first.value.resultPayloadDigest, second.value.resultPayloadDigest)
    }

    @Test
    fun host_submitToolResult_sameKey_differentPayload_conflicts() = runBlocking {
        val (svc, _, _) = buildService(modelId = modelId, mode = StructuredMode.POST_VALIDATE)
        val started = svc.startToolCalling(LocalUiPrincipal.ID, toolSpec(modelId)) as OmniResult.Ok
        val prop = started.value.proposals.first()
        val body1 = """{"ok":true}"""
        val body2 = """{"ok":false}"""
        val r1 = ToolResult(
            requestId = prop.requestId,
            proposalId = prop.proposalId,
            attempt = 1,
            idempotencyKey = "host-result-2",
            resultPayloadDigest = digestPayload(body1),
            resultBodyJson = body1,
            submittedAtEpochMs = 3_000L,
        )
        assertTrue(svc.submitToolResult(LocalUiPrincipal.ID, r1) is OmniResult.Ok)

        val r2 = ToolResult(
            requestId = prop.requestId,
            proposalId = prop.proposalId,
            attempt = 1,
            idempotencyKey = "host-result-2",
            resultPayloadDigest = digestPayload(body2),
            resultBodyJson = body2,
            submittedAtEpochMs = 3_001L,
        )
        val conflict = svc.submitToolResult(LocalUiPrincipal.ID, r2)
        assertTrue(conflict is OmniResult.Err)
        assertEquals(
            OmniErrorCode.IDEMPOTENCY_CONFLICT,
            (conflict as OmniResult.Err).error.code,
        )
    }

    @Test
    fun unauthorizedHost_cannotSubmitToolResult() {
        val proposal = ToolProposal(
            proposalId = "p1",
            requestId = "11111111-1111-1111-1111-111111111111",
            toolId = "get_weather",
            schemaDigest = "f".repeat(64),
            argumentsJson = "{}",
            createdAtEpochMs = 1L,
        )
        val body = "{}"
        val incoming = ToolResult(
            requestId = proposal.requestId,
            proposalId = proposal.proposalId,
            attempt = 1,
            idempotencyKey = "k",
            resultPayloadDigest = digestPayload(body),
            resultBodyJson = body,
            submittedAtEpochMs = 2L,
        )
        val outcome = ToolExecutionPolicy.claimToolResult(
            existing = null,
            incoming = incoming,
            proposal = proposal,
            hostAuthorized = false,
        )
        assertTrue(outcome is ToolResultClaimOutcome.Denied)
        assertEquals(
            OmniErrorCode.FORBIDDEN,
            (outcome as ToolResultClaimOutcome.Denied).error.code,
        )
    }

    @Test
    fun allowlist_blocksUnlistedTool() = runBlocking {
        val (svc, inference, _) = buildService(
            modelId = modelId,
            mode = StructuredMode.POST_VALIDATE,
        )
        val result = svc.startToolCalling(
            LocalUiPrincipal.ID,
            toolSpec(modelId, allowlist = setOf("other_tool")),
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (result as OmniResult.Err).error.code)
        assertEquals(0, inference.toolStartCount)
    }

    @Test
    fun principalWithoutScopes_failClosedForbidden() = runBlocking {
        val rejected = ConfigurableScopePort(acceptedPrincipal = "HTTP_LAN").apply { denyAll() }
        val (svc, inference, _) = buildService(modelId = modelId, scopes = rejected)
        val r = svc.startStructured(
            PrincipalId.parse("HTTP_LAN"),
            structuredSpec(modelId),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as OmniResult.Err).error.code)
        assertEquals(0, inference.structuredStartCount)
    }
}
