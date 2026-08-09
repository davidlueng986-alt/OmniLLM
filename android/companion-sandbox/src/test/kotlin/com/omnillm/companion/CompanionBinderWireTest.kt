package com.omnillm.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure wire + identity map tests (no Android Parcel required).
 */
class CompanionBinderWireTest {

    @Test
    fun ticketWire_roundTripToTicket() {
        val key: ByteArray = ByteArray(SandboxTicketMac.KEY_BYTES) { it.toByte() }
        val ticket = SandboxExecutionTicket(
            protocolMajor = CompanionSandboxModule.PROTOCOL_MAJOR,
            protocolMinor = CompanionSandboxModule.PROTOCOL_MINOR,
            runtimeInstanceId = "rt-1",
            runtimeEpoch = 3L,
            bootId = "boot-1",
            operationId = "op-1",
            commitId = "c-1",
            engineBuildId = "eng",
            modelContentIds = listOf("m1", "m2"),
            backend = "gpu",
            resourceEnvelopeSummary = "rv",
            operatingConstraintSummary = "oc",
            monotonicDeadlineMs = 999L,
            nonce = "n-1",
            placementClass = CompanionTicketValidator.PLACEMENT_EXTERNAL_UID_ACCELERATED,
            macHex = "",
        ).let { it.copy(macHex = SandboxTicketMac.computeHex(key, it)) }
        val wire = CompanionBinderWire.TicketWire.fromTicket(ticket)
        val back = wire.toTicket()
        assertEquals(ticket, back)
        assertEquals(ticket.macHex, back.macHex)
    }

    @Test
    fun identityMap_roundTrip() {
        val report = CompanionIdentityReport(
            packageName = CompanionSandboxModule.PACKAGE_NAME,
            packageVersionName = "0.0.1-companion",
            packageVersionCode = 1L,
            signerDigestHex = "abcdef01",
            processInstanceId = "pi-xyz",
            pid = 1234,
            protocolMajor = 1,
            protocolMinor = 0,
            uid = 10123,
        )
        val map = CompanionBinderWire.identityToMap(report)
        val back = CompanionBinderWire.identityFromMap(map)
        assertNotNull(back)
        assertEquals(report, back)
    }

    @Test
    fun identityFromMap_rejectsIncomplete() {
        assertNull(CompanionBinderWire.identityFromMap(mapOf("packageName" to "x")))
    }

    @Test
    fun transactionCodes_areStable() {
        assertEquals(1, CompanionBinderWire.TRANSACTION_ATTACH_SUPERVISOR)
        assertEquals(2, CompanionBinderWire.TRANSACTION_HANDSHAKE)
        assertEquals(3, CompanionBinderWire.TRANSACTION_REGISTER_RO_PFD)
        assertEquals("com.omnillm.companion.ICompanionSandbox", CompanionBinderWire.DESCRIPTOR)
    }

    @Test
    fun packagesDiffer_andProcessRole() {
        assertTrue(
            CompanionSandboxModule.PACKAGE_NAME != CompanionSandboxModule.MAIN_APP_PACKAGE,
        )
        assertEquals("companion-sandbox", CompanionSandboxModule.PROCESS_ROLE)
    }
}
