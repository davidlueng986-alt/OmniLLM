package com.omnillm.features.server

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.server.api.CapabilityBlockerKind
import com.omnillm.features.server.api.ServerCommandIdentity
import com.omnillm.features.server.projection.ServerStateProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-SERVER acceptance: capability negotiation unsupported/unknown paths,
 * and cancel + query (ADR-004/005, INV-018).
 */
class CapabilityNegotiationAndCancelTest {

    @Test
    fun negotiate_unsupportedCapability_failsClosed() = runBlocking {
        val caps = FakeCapabilities(
            models = listOf(
                FakeCapabilities.defaultModel(
                    textGen = CapabilityState.SUPPORTED,
                    vision = CapabilityState.UNSUPPORTED,
                ),
            ),
        )
        val api = service(ports(capabilities = caps))

        val r = api.negotiateCapabilities(
            modelId = "local-demo",
            requiredCapabilities = setOf("VISION_INPUT"),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)
        assertEquals("VISION_INPUT", r.error.details["capabilityId"])
    }

    @Test
    fun negotiate_unknownCapabilityId_failsClosed() = runBlocking {
        val api = service()
        val r = api.negotiateCapabilities(
            modelId = null,
            requiredCapabilities = setOf("NOT_A_CATALOG_CAPABILITY"),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, (r as OmniResult.Err).error.code)
    }

    @Test
    fun negotiate_unknownCellState_failsClosedNotTreatedAsUnsupported() = runBlocking {
        val caps = FakeCapabilities(
            models = listOf(FakeCapabilities.defaultModel()),
        )
        val api = service(ports(capabilities = caps))

        // STREAMING is advertised as UNKNOWN on the fake model.
        val r = api.negotiateCapabilities(
            modelId = "local-demo",
            requiredCapabilities = setOf("STREAMING"),
        )
        assertTrue(r is OmniResult.Err)
        // UNKNOWN must not be coerced to "device unsupported" (UX-STATE-CATALOG §8).
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, (r as OmniResult.Err).error.code)
    }

    @Test
    fun negotiate_supportedPath_usable() = runBlocking {
        val api = service()
        val r = api.negotiateCapabilities(
            modelId = "local-demo",
            requiredCapabilities = setOf("TEXT_GENERATION", "EMBEDDING"),
        )
        assertTrue(r is OmniResult.Ok)
        val neg = (r as OmniResult.Ok).value
        assertTrue(neg.usable)
        assertFalse(neg.hasUnsupported)
        assertFalse(neg.hasUnknown)
        assertEquals(2, neg.cells.size)
    }

    @Test
    fun negotiate_pureProjection_listsBlockers() {
        val models = listOf(
            FakeCapabilities.defaultModel(
                textGen = CapabilityState.SUPPORTED,
                vision = CapabilityState.UNSUPPORTED,
            ),
        )
        val result = ServerStateProjection.negotiate(
            models = models,
            modelId = "local-demo",
            requiredCapabilities = setOf("TEXT_GENERATION", "VISION_INPUT", "STREAMING"),
        )
        assertFalse(result.usable)
        assertTrue(result.hasUnsupported)
        assertTrue(result.hasUnknown)
        val kinds = result.blocking.map { it.kind }.toSet()
        assertTrue(CapabilityBlockerKind.UNSUPPORTED in kinds)
        assertTrue(CapabilityBlockerKind.UNKNOWN in kinds)
        // TEXT_GENERATION must not appear as a blocker.
        assertFalse(result.blocking.any { it.capabilityId == "TEXT_GENERATION" })
    }

    @Test
    fun smoke_blockedByUnsupported_neverSubmits() = runBlocking {
        val inference = FakeInference()
        val caps = FakeCapabilities(
            models = listOf(
                FakeCapabilities.defaultModel(vision = CapabilityState.UNSUPPORTED),
            ),
        )
        val api = service(ports(capabilities = caps, inference = inference))

        val r = api.runSmokeInference(
            claim = claim(
                requestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                capabilities = setOf("VISION_INPUT"),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (r as OmniResult.Err).error.code)
        // Production path: no submit after failed negotiation.
        assertEquals(null, inference.lastClaim)
        assertEquals("capability-negotiation", api.snapshot().lastSmoke!!.step)
        assertFalse(api.snapshot().lastSmoke!!.success)
    }

    @Test
    fun cancel_queuedRequest_reachesCancelled() = runBlocking {
        val inference = FakeInference()
        val api = service(ports(inference = inference))

        val requestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val submitted = api.runSmokeInference(claim = claim(requestId = requestId))
        assertTrue(submitted is OmniResult.Ok)
        assertEquals("QUEUED", (submitted as OmniResult.Ok).value.requestState)

        val cancelled = api.cancelRequest(
            requestId = requestId,
            command = ServerCommandIdentity(
                commandId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                idempotencyKey = "cancel-1",
            ),
        )
        assertTrue(cancelled is OmniResult.Ok)
        assertEquals("CANCELLED", (cancelled as OmniResult.Ok).value.requestState)
        assertTrue(cancelled.value.success)
        assertEquals("cancel", cancelled.value.step)

        val queried = api.queryRequest(requestId = requestId)
        assertTrue(queried is OmniResult.Ok)
        assertEquals("CANCELLED", (queried as OmniResult.Ok).value.requestState)
        assertEquals("query", queried.value.step)
    }

    @Test
    fun cancel_unknownRequest_failsClosed() = runBlocking {
        val api = service()
        val r = api.cancelRequest(
            requestId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            command = ServerCommandIdentity(
                commandId = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                idempotencyKey = "cancel-missing",
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.NOT_FOUND, (r as OmniResult.Err).error.code)
    }

    @Test
    fun cancel_invalidRequestId_rejected() = runBlocking {
        val api = service()
        val r = api.cancelRequest(
            requestId = "not-a-uuid",
            command = ServerCommandIdentity(
                commandId = "11111111-1111-4111-8111-111111111111",
                idempotencyKey = "cancel-bad-id",
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun query_afterSubmit_replyLossSafe() = runBlocking {
        val inference = FakeInference()
        val api = service(ports(inference = inference))
        val requestId = "22222222-2222-4222-8222-222222222222"
        api.runSmokeInference(claim = claim(requestId = requestId))

        // Simulate reply loss: query without re-submitting.
        val q1 = api.queryRequest(requestId = requestId) as OmniResult.Ok
        val q2 = api.queryRequest(requestId = requestId) as OmniResult.Ok
        assertEquals(q1.value.requestState, q2.value.requestState)
        assertEquals(requestId, q2.value.requestId)
        // Still only one submit recorded.
        assertEquals(requestId, inference.lastClaim!!.requestId)
    }
}
