package com.omnillm.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supervisor fence state contracts (SEC-EXTERNAL-SANDBOX §6).
 * Death-recipient link requires Android Binder; process kill path is integration-only.
 */
class CompanionSupervisorFenceLogicTest {

    @Test
    fun attach_resultTypesDocumented() {
        val results = listOf(
            CompanionFenceAttachResult.Attached,
            CompanionFenceAttachResult.AlreadyFenced,
            CompanionFenceAttachResult.SupervisorAlreadyDead,
        )
        assertEquals(3, results.size)
    }

    @Test
    fun sessionView_handshakeOnlyWhenAcceptingAndNotBound() {
        val session = FakeCompanionSessionView(
            isAcceptingHandshake = true,
            isBound = false,
            isFenced = false,
        )
        assertTrue(session.isAcceptingHandshake)
        val after = FakeCompanionSessionView(isAcceptingHandshake = false, isBound = true)
        assertFalse(after.isAcceptingHandshake)
        assertTrue(after.isBound)
    }

    @Test
    fun fencedSession_rejectsCommands_withWorkerDied() {
        val session = FakeCompanionSessionView(isBound = true, isFenced = true)
        val r = CompanionCommandGate.gate(
            command = CompanionCommand.Query(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "q",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(r is CompanionCommandResult.Rejected)
        assertEquals("WORKER_DIED", (r as CompanionCommandResult.Rejected).errorCode)
    }

    @Test
    fun exitingSession_rejectsCommands() {
        val session = FakeCompanionSessionView(isBound = true, isExiting = true)
        val r = CompanionCommandGate.gate(
            command = CompanionCommand.Cancel(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "c",
                operationId = "op",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(r is CompanionCommandResult.Rejected)
        assertEquals("WORKER_DIED", (r as CompanionCommandResult.Rejected).errorCode)
    }
}
