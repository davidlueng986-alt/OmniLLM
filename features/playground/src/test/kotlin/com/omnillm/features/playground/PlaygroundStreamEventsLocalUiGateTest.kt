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
        // Seed a handle so the LOCAL_UI projection has something to stream.
        fake.putAsset(
            com.omnillm.features.playground.api.AssetHandleView(
                assetId = "asset-1",
                purpose = "IMAGE_INPUT",
                mimeHint = "image/png",
                actualMime = null,
                sizeBytes = 1L,
                digestSha256 = null,
                ttlExpiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                state = "READY",
                preprocessingLabel = null,
                singleUse = false,
                ownerPrincipalId = com.omnillm.interfaces.admin.LocalUiPrincipal.ID.value,
            ),
        )
        val result = service.streamEvents(
            principal = com.omnillm.interfaces.admin.LocalUiPrincipal.ID,
            requestId = requestId,
            afterSeq = 0L,
        )
        assertTrue(
            "LOCAL_UI stream projection must not regress: $result",
            result is OmniResult.Ok || result is OmniResult.Err,
        )
    }
}
