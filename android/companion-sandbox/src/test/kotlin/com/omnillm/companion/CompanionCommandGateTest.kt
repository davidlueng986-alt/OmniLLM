package com.omnillm.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionCommandGateTest {

    private val key: ByteArray = ByteArray(SandboxTicketMac.KEY_BYTES) { it.toByte() }

    private fun ticket() = SandboxExecutionTicket(
        protocolMajor = CompanionSandboxModule.PROTOCOL_MAJOR,
        protocolMinor = CompanionSandboxModule.PROTOCOL_MINOR,
        runtimeInstanceId = "rt-1",
        runtimeEpoch = 1L,
        bootId = "boot-1",
        operationId = "op-1",
        commitId = "commit-1",
        engineBuildId = "engine-1",
        modelContentIds = listOf("digest1"),
        backend = "gpu",
        resourceEnvelopeSummary = "rv",
        operatingConstraintSummary = "oc",
        monotonicDeadlineMs = 10_000L,
        nonce = "n1",
        placementClass = CompanionTicketValidator.PLACEMENT_EXTERNAL_UID_ACCELERATED,
        macHex = "",
    ).let { it.copy(macHex = SandboxTicketMac.computeHex(key, it)) }

    @Test
    fun handshakeOkWhenSessionAccepting() {
        val session = FakeCompanionSessionView(isAcceptingHandshake = true, isBound = false)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.Handshake(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                runtimeInstanceId = "rt-1",
                ticket = ticket(),
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
            ticketMacKey = key,
        )
        assertNull(result)
    }

    @Test
    fun handshakeRejectedWithoutMacKey() {
        val session = FakeCompanionSessionView(isAcceptingHandshake = true, isBound = false)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.Handshake(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                runtimeInstanceId = "rt-1",
                ticket = ticket(),
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(result is CompanionCommandResult.Rejected)
        assertEquals("INVALID_AUTH", (result as CompanionCommandResult.Rejected).errorCode)
    }

    @Test
    fun handshakeRejectedWhenForged() {
        val session = FakeCompanionSessionView(isAcceptingHandshake = true, isBound = false)
        val forged = ticket().copy(commitId = "other-commit")
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.Handshake(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                runtimeInstanceId = "rt-1",
                ticket = forged,
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
            ticketMacKey = key,
        )
        assertTrue(result is CompanionCommandResult.Rejected)
        assertEquals("INVALID_AUTH", (result as CompanionCommandResult.Rejected).errorCode)
    }

    @Test
    fun rejectsPathTokenAsFd() {
        val session = FakeCompanionSessionView(isBound = true)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.RegisterReadOnlyFd(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                token = "/data/data/com.omnillm/databases/omnillm.db",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(result is CompanionCommandResult.Rejected)
        assertEquals("INVALID_REQUEST", (result as CompanionCommandResult.Rejected).errorCode)
    }

    @Test
    fun rejectsContentUriToken() {
        val session = FakeCompanionSessionView(isBound = true)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.RegisterReadOnlyFd(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                token = "content://com.omnillm.files/secret",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(result is CompanionCommandResult.Rejected)
    }

    @Test
    fun rejectsWriteMode() {
        val session = FakeCompanionSessionView(isBound = true)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.RegisterReadOnlyFd(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                token = "fd-token-1",
                mode = "READ_WRITE",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(result is CompanionCommandResult.Rejected)
    }

    @Test
    fun rejectsCommandsAfterFence() {
        val session = FakeCompanionSessionView(isBound = true, isFenced = true)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.Query(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertTrue(result is CompanionCommandResult.Rejected)
        assertEquals("WORKER_DIED", (result as CompanionCommandResult.Rejected).errorCode)
    }

    @Test
    fun allowsOpaqueTokenWhenBound() {
        val session = FakeCompanionSessionView(isBound = true)
        val result = CompanionCommandGate.gate(
            command = CompanionCommand.RegisterReadOnlyFd(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                token = "fd-token-1",
            ),
            session = session,
            claimedNonces = emptySet(),
            nowMonotonicMs = 1L,
        )
        assertNull(result)
    }
}
