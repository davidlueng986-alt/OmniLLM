package com.omnillm.android.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerCommandGateTest {

    private fun commit(
        placement: String = WorkerPlacement.CRASH_CONTAINED_TRUSTED,
        nonce: String = "nonce-1",
        epoch: Long = 1L,
        boot: String = "boot-1",
    ) = WorkerCommand.CommitLoad(
        runtimeEpoch = epoch,
        bootId = boot,
        requestId = "req-1",
        monotonicDeadlineMs = 10_000L,
        commitId = "commit-1",
        oneShotNonce = nonce,
        installationId = "550e8400-e29b-41d4-a716-446655440000",
        modelRevisionId = "a".repeat(64),
        placementClass = placement,
        privilegedLoadTicketId = "ticket-1",
        contentFdTokens = listOf("fd-token-1"),
        engineBuildId = "engine-build-1",
    )

    @Test
    fun rejectsUntrustedAcceleratedPlacement() {
        val fence = FakeSupervisorFenceView()
        val result = WorkerCommandGate.gate(
            command = commit(placement = "EXTERNAL_UID_ACCELERATED"),
            fence = fence,
            claimedNonces = emptySet(),
        )
        assertTrue(result is WorkerCommandResult.Rejected)
        assertEquals(
            "TRUST_PLACEMENT_REQUIRED",
            (result as WorkerCommandResult.Rejected).errorCode,
        )
    }

    @Test
    fun rejectsStaleEpoch() {
        val fence = FakeSupervisorFenceView(expectedEpoch = 2L)
        val result = WorkerCommandGate.gate(
            command = commit(epoch = 1L),
            fence = fence,
            claimedNonces = emptySet(),
        )
        assertTrue(result is WorkerCommandResult.Rejected)
        assertEquals("INVALID_REQUEST", (result as WorkerCommandResult.Rejected).errorCode)
    }

    @Test
    fun rejectsReplayNonce() {
        val fence = FakeSupervisorFenceView()
        val result = WorkerCommandGate.gate(
            command = commit(nonce = "used"),
            fence = fence,
            claimedNonces = setOf("used"),
        )
        assertTrue(result is WorkerCommandResult.Rejected)
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            (result as WorkerCommandResult.Rejected).errorCode,
        )
    }

    @Test
    fun allowsTrustedCommitWhenEpochMatches() {
        val fence = FakeSupervisorFenceView()
        val result = WorkerCommandGate.gate(
            command = commit(),
            fence = fence,
            claimedNonces = emptySet(),
        )
        assertNull(result)
    }

    @Test
    fun attachRejectsIsolatedCpuOnSameUidWorker() {
        val fence = FakeSupervisorFenceView(isAccepting = false)
        val result = WorkerCommandGate.gate(
            command = WorkerCommand.AttachSupervisor(
                runtimeEpoch = 1L,
                bootId = "boot-1",
                requestId = "req",
                monotonicDeadlineMs = 1L,
                placementClass = "ISOLATED_CPU_UNTRUSTED",
            ),
            fence = fence,
            claimedNonces = emptySet(),
        )
        assertTrue(result is WorkerCommandResult.Rejected)
        assertEquals(
            "TRUST_PLACEMENT_REQUIRED",
            (result as WorkerCommandResult.Rejected).errorCode,
        )
    }
}
