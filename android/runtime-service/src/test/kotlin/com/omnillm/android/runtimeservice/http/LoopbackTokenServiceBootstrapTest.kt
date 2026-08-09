package com.omnillm.android.runtimeservice.http

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SEC-08 bootstrap admin token hardening:
 * - default TTL reduced from 24h to 1h (parameterizable)
 * - single-peek plaintext display with masked-only afterwards
 * - staged Secret Broker receipt is wiped after first display
 */
class LoopbackTokenServiceBootstrapTest {

    private val fixed = Instant.parse("2026-01-01T00:00:00Z")

    private fun service(now: Instant = fixed): LoopbackTokenService =
        LoopbackTokenService(clock = { now })

    @Test
    fun bootstrapDefaultTtl_isOneHour() {
        val issued = service().issueBootstrapAdmin()
        assertEquals(Duration.ofHours(1), Duration.between(fixed, issued.expiresAt))
        assertEquals(LoopbackTokenService.BOOTSTRAP_ADMIN_TTL_SECONDS, 3_600L)
        assertTrue(issued.loopbackOnly)
        assertEquals("bootstrap", issued.label)
    }

    @Test
    fun bootstrapAcceptsExplicitShorterTtl() {
        val issued = service().issueBootstrapAdmin(ttlSeconds = 120L)
        assertEquals(Duration.ofSeconds(120), Duration.between(fixed, issued.expiresAt))
    }

    @Test
    fun bootstrapScopesAreBroadLoopbackAdmin() {
        val issued = service().issueBootstrapAdmin()
        assertTrue(issued.scopes.containsAll(LoopbackTokenService.BOOTSTRAP_SCOPES))
        assertTrue("tokens.manage" in issued.scopes)
        assertTrue("settings.write" in issued.scopes)
    }

    @Test
    fun takePlaintextOnce_returnsPlaintextOnceOnly() {
        val svc = service()
        val issued = svc.issueBootstrapAdmin()
        assertEquals(issued.plaintext, svc.takePlaintextOnce(issued.issuanceKey))
        assertNull(svc.takePlaintextOnce(issued.issuanceKey))
    }

    @Test
    fun erasePlaintextReceipt_wipesStagedPlaintext() {
        val svc = service()
        val issued = svc.issueBootstrapAdmin()
        svc.erasePlaintextReceipt(issued.issuanceKey)
        assertNull(svc.takePlaintextOnce(issued.issuanceKey))
    }

    @Test
    fun display_singlePeekThenMaskedOnly() {
        val display = BootstrapTokenDisplay("secret-token-1234")
        assertEquals("secret-token-1234", display.takePlaintextOnce())
        assertNull(display.takePlaintextOnce())
        assertTrue(display.hasBeenShown())
        assertFalse(display.isRetained())

        val masked = display.masked()
        assertFalse("masked must not contain the plaintext", masked.contains("secret-token"))
        assertTrue(masked.endsWith("1234"))
    }

    @Test
    fun display_maskedBeforePeekDoesNotConsume() {
        val display = BootstrapTokenDisplay("abc123")
        val masked = display.masked()
        assertFalse(masked.contains("abc123"))
        assertEquals("abc123", display.takePlaintextOnce())
        assertNull(display.takePlaintextOnce())
    }
}
