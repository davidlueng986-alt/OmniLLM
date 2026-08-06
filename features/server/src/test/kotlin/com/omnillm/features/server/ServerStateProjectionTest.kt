package com.omnillm.features.server

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.domain.ServerScreenPhase
import com.omnillm.features.server.projection.ServerStateProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStateProjectionTest {

    @Test
    fun phase_emptyWhenNeverRefreshed() {
        val phase = ServerStateProjection.resolvePhase(
            loading = false,
            loopback = null,
            error = null,
            hasRefreshed = false,
        )
        assertEquals(ServerScreenPhase.EMPTY, phase)
    }

    @Test
    fun phase_loading() {
        val phase = ServerStateProjection.resolvePhase(
            loading = true,
            loopback = null,
            error = null,
            hasRefreshed = false,
        )
        assertEquals(ServerScreenPhase.LOADING, phase)
    }

    @Test
    fun phase_ready() {
        val phase = ServerStateProjection.resolvePhase(
            loading = false,
            loopback = LoopbackServerStatus(
                enabled = true,
                running = true,
                host = "127.0.0.1",
                port = 11434,
                runtimeState = "READY",
                resourceVersion = 1L,
            ),
            error = null,
            hasRefreshed = true,
        )
        assertEquals(ServerScreenPhase.READY, phase)
    }

    @Test
    fun phase_degraded() {
        val phase = ServerStateProjection.resolvePhase(
            loading = false,
            loopback = LoopbackServerStatus(
                enabled = true,
                running = true,
                host = "127.0.0.1",
                port = 11434,
                runtimeState = "DEGRADED",
                resourceVersion = 2L,
                degradedReasons = listOf("ENGINE"),
            ),
            error = null,
            hasRefreshed = true,
        )
        assertEquals(ServerScreenPhase.DEGRADED, phase)
    }

    @Test
    fun phase_errorWhenNoLoopbackAndError() {
        val phase = ServerStateProjection.resolvePhase(
            loading = false,
            loopback = null,
            error = OmniError.INTERNAL(message = "boom"),
            hasRefreshed = true,
        )
        assertEquals(ServerScreenPhase.ERROR, phase)
    }

    @Test
    fun project_preservesEmptyListsNotPlaceholders() {
        val snap = ServerStateProjection.project(
            loading = false,
            loopback = null,
            clients = emptyList(),
            tokens = emptyList(),
            pendingReceipt = null,
            capabilities = emptyList(),
            metrics = emptyList(),
            lastSmoke = null,
            lastRequestId = null,
            lastRequestState = null,
            error = null,
            nowEpochMs = 1L,
            hasRefreshed = false,
        )
        assertEquals(ServerScreenPhase.EMPTY, snap.presentation)
        assertTrue(snap.clients.isEmpty())
        assertTrue(snap.tokens.isEmpty())
        assertTrue(snap.metrics.isEmpty())
    }

    @Test
    fun clientAndTokenLabelKeys_knownStates() {
        assertEquals("client.active", ServerStateProjection.clientStateLabelKey("ACTIVE"))
        assertEquals("client.revoked", ServerStateProjection.clientStateLabelKey("REVOKED"))
        assertEquals("token.active", ServerStateProjection.tokenStateLabelKey("ACTIVE"))
        assertEquals("token.draining", ServerStateProjection.tokenStateLabelKey("DRAINING"))
        assertEquals("client.unknown", ServerStateProjection.clientStateLabelKey("NOPE"))
    }
}
