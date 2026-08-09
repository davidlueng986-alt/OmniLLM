package com.omnillm.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTicketValidatorTest {

    private val key: ByteArray = ByteArray(SandboxTicketMac.KEY_BYTES) { it.toByte() }
    private val otherKey: ByteArray = ByteArray(SandboxTicketMac.KEY_BYTES) { (it + 1).toByte() }

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
        macHex = "",
    )

    /** MAC a ticket with the session key (like a correct host would). */
    private fun signed(t: SandboxExecutionTicket, k: ByteArray = key): SandboxExecutionTicket =
        t.copy(macHex = SandboxTicketMac.computeHex(k, t))

    private fun validate(
        t: SandboxExecutionTicket,
        k: ByteArray = key,
        epoch: Long = 1L,
        boot: String = "boot-1",
        instance: String = "rt-1",
        claimed: Set<String> = emptySet(),
        now: Long = 100L,
    ): TicketValidationResult = CompanionTicketValidator.validate(
        ticket = t,
        macKey = k,
        expectedRuntimeEpoch = epoch,
        expectedBootId = boot,
        expectedRuntimeInstanceId = instance,
        claimedNonces = claimed,
        nowMonotonicMs = now,
    )

    @Test
    fun acceptsValidSignedTicket() {
        val r = validate(signed(ticket()))
        assertTrue(r is TicketValidationResult.Accepted)
    }

    @Test
    fun rejectsForgedTicket() {
        // Fields tampered without recomputing the MAC ⇒ authentication failure.
        val forged = signed(ticket()).copy(backend = "cpu")
        val r = validate(forged)
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_AUTH", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsTicketWithWrongKey() {
        val r = validate(signed(ticket(), k = otherKey))
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_AUTH", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsTamperedMacField() {
        val original = signed(ticket())
        val tampered = original.copy(macHex = flipLastChar(original.macHex))
        val r = validate(tampered)
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_AUTH", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsMissingMac() {
        // No MAC at all (e.g., pre-SEC-07 host or stripped wire).
        val r = validate(ticket())
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_AUTH", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsStaleEpoch() {
        val r = validate(signed(ticket(epoch = 1L)), epoch = 2L)
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_REQUEST", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsReplayNonce() {
        val r = validate(signed(ticket(nonce = "used")), claimed = setOf("used"))
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("IDEMPOTENCY_CONFLICT", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsWrongPlacementClass() {
        val r = validate(signed(ticket(placement = "CRASH_CONTAINED_TRUSTED")))
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals(
            "TRUST_PLACEMENT_REQUIRED",
            (r as TicketValidationResult.Rejected).errorCode,
        )
    }

    @Test
    fun rejectsProtocolMajorMismatch() {
        val r = validate(signed(ticket(major = 99)))
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_REQUEST", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun rejectsPastDeadline() {
        val r = validate(signed(ticket(deadline = 50L)), now = 100L)
        assertTrue(r is TicketValidationResult.Rejected)
        assertEquals("INVALID_REQUEST", (r as TicketValidationResult.Rejected).errorCode)
    }

    @Test
    fun macVerification_constantTimePath() {
        val t = signed(ticket())
        assertTrue(SandboxTicketMac.verify(key, t))
        assertFalse(SandboxTicketMac.verify(otherKey, t))
        assertFalse(SandboxTicketMac.verify(key, t.copy(operationId = "op-2")))
        assertFalse(SandboxTicketMac.verify(key, t.copy(macHex = "")))
        assertFalse(SandboxTicketMac.verify(key, t.copy(macHex = "zz")))
        assertFalse(SandboxTicketMac.verify(key, t.copy(macHex = "not-hex")))
        // Key size is enforced fail-fast (never silently downgraded).
        try {
            SandboxTicketMac.verify(ByteArray(16), t)
            throw AssertionError("expected IllegalArgumentException for 128-bit key")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun macRecomputeIsDeterministicAcrossFields() {
        val a = signed(ticket())
        val b = signed(ticket())
        assertEquals(a.macHex, b.macHex)
        // Changing any authenticated field changes the MAC.
        assertFalse(a.macHex == signed(ticket(nonce = "n2")).macHex)
        assertFalse(a.macHex == signed(ticket(epoch = 2L)).macHex)
    }

    private fun flipLastChar(hex: String): String =
        if (hex.isEmpty()) hex else hex.dropLast(1) + (if (hex.last() == '0') '1' else '0')
}
