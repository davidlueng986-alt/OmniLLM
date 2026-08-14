package com.omnillm.features.playground

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.usecase.PlaygroundService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COR-03 regression: [PlaygroundService.streamEvents] is polled by HTTP/SSE
 * transports with non-LOCAL_UI principals. A `require()` (LOCAL_UI gate) would
 * throw inside the caller's stream flow and break the SSE connection mid-stream;
 * it must return an honest catalog error instead — never throw.
 */
class PlaygroundStreamEventsLocalUiGateTest {

    private fun service(): PlaygroundService =
        PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = FakeInferencePort(),
                capabilities = FakeCapabilityPort(),
            ),
        )

    @Test
    fun streamEvents_nonLocalUiPrincipal_returnsForbiddenWithoutThrowing() = runBlocking {
        val result = service().streamEvents(
            principal = PrincipalId.parse("http-remote-principal"),
            requestId = "11111111-1111-1111-1111-111111111111",
            afterSeq = 0L,
        )
        assertTrue("must return an error, not throw", result is OmniResult.Err)
        assertEquals(
            OmniErrorCode.FORBIDDEN,
            (result as OmniResult.Err).error.code,
        )
    }

    @Test
    fun streamEvents_localUiPrincipal_stillServes() = runBlocking {
        val fake = FakeInferencePort()
        val service = PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = fake,
                capabilities = FakeCapabilityPort(),
            ),
        )
        val requestId = "11111111-1111-1111-1111-111111111111"
        // Seed a real chat handle so the LOCAL_UI projection has assistant text
        // to stream (the previous test asserted `Ok || Err` — a tautology; the
        // projection must deterministically serve an Ok batch with the delta).
        val started = service.startChat(
            principal = com.omnillm.interfaces.admin.LocalUiPrincipal.ID,
            spec = com.omnillm.features.playground.api.ChatRequestSpec(
                identity = com.omnillm.features.playground.api.InferenceIdentity(
                    requestId = requestId,
                    idempotencyKey = "local-ui-stream-1",
                    canonicalInputDigest = "a".repeat(64),
                ),
                modelRevisionId = "cd".repeat(32),
                messages = listOf(
                    com.omnillm.features.playground.api.ChatMessage(
                        role = "user",
                        content = "hi",
                    ),
                ),
            ),
        )
        assertTrue("startChat must serve LOCAL_UI: $started", started is OmniResult.Ok)
        val result = service.streamEvents(
            principal = com.omnillm.interfaces.admin.LocalUiPrincipal.ID,
            requestId = requestId,
            afterSeq = 0L,
        )
        assertTrue("LOCAL_UI stream projection must succeed: $result", result is OmniResult.Ok)
        val batch = (result as OmniResult.Ok).value
        assertTrue(
            "stream projection must include the assistant text delta",
            batch.events.any {
                it.kind == "delta" && it.textDelta == "hello from fake"
            },
        )
    }
}
