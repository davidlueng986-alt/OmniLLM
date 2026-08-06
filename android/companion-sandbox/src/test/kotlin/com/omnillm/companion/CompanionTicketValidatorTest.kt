package com.omnillm.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTicketValidatorTest {

    private fun ticket(
        epoch: Long = 1L,
        boot: String = "boot-1",
        instance: String = "rt-1",
        nonce: String = "n1",
        placement: String = CompanionTicketValidator.PLACEMENT_EXTERNAL_UID_ACCELERATED,
        deadline: Long = 10_000L,
        major: Int = CompanionSandboxModule.PROTOCOL_MAJOR,
        minor: Int = CompanionSandboxModule.PROTOCOL_MINOR,
    ) = SandboxExecutionTicket(
        protocolMajor = major,
        protocolMinor = minor,
        runtimeInstanceId = instance,
        runtimeEpoch = epoch,
        bootId = boot,
        operationId = "op-1",
        commitId = "commit-1",
        engineBuildId = "engine-1",
        modelContentIds = listOf("a".repeat(64)),
        backend = "gpu",
        resourceEnvelopeSummary = "rv=stub",
        operatingConstraintSummary = "oc=stub",
        monotonicDeadlineMs = deadline,
        nonce = nonce,
        placementClass = placement,
    )

    @Test
    fun acceptsValidTicket() {
        val r = CompanionTicketValidator.validate(
            ticket = ticket(),
            expectedRuntimeEpoch = 1L,
            expectedBootId = "boot-1",
            expectedRuntimeInstanceId = "rt-1",
            claimedNonces = emptySet(),
            nowMonotonicMs = 100L,
        )
        assertTrue(r is TicketValidationResult.Accepted)
    }

    @Test
    fun rejectsStaleEpoch() {
        val r = CompanionTicketValidator.validate(
            ticket = ticket(epoch = 1L),
            expectedRuntimeEpoch = 2L,
            expectedBootId = "boot-1",
            expectedRuntimeInstanceId = "rt-1",
            claimedNonces = emptySet(),
            nowMonotonicMs = 100L,
        )
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_REQUEST", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsReplayNonce() {
        val r = CompanionTicketValidator.validate(
            ticket = ticket(nonce = "used"),
            expectedRuntimeEpoch = 1L,
            expectedBootId = "boot-1",
            expectedRuntimeInstanceId = "rt-1",
            claimedNonces = setOf("used"),
            nowMonotonicMs = 100L,
        )
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("IDEMPOTENCY_CONFLICT", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsWrongPlacementClass() {
        val r = CompanionTicketValidator.validate(
            ticket = ticket(placement = "CRASH_CONTAINED_TRUSTED"),
            expectedRuntimeEpoch = 1L,
            expectedBootId = "boot-1",
            expectedRuntimeInstanceId = "rt-1",
            claimedNonces = emptySet(),
            nowMonotonicMs = 100L,
        )
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals(
            "TRUST_PLACEMENT_REQUIRED",
            (r as TicketValidationResult.Rejected).errorCode,
        )
    }

    @Test
    fun rejectsProtocolMajorMismatch() {
        val r = CompanionTicketValidator.validate(
            ticket = ticket(major = 99),
            expectedRuntimeEpoch = 1L,
            expectedBootId = "boot-1",
            expectedRuntimeInstanceId = "rt-1",
            claimedNonces = emptySet(),
            nowMonotonicMs = 100L,
        )
        assertTrue(r is TicketValidationResult.Rejected)
    }

    @Test
    fun rejectsPastDeadline() {
        val r = CompanionTicketValidator.validate(
            ticket = ticket(deadline = 50L),
            expectedRuntimeEpoch = 1L,
            expectedBootId = "boot-1",
            expectedRuntimeInstanceId = "rt-1",
            claimedNonces = emptySet(),
            nowMonotonicMs = 100L,
        )
        assertTrue(r is TicketValidationResult.Rejected)
    }
}
