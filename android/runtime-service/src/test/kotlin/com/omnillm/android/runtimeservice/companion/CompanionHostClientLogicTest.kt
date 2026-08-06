package com.omnillm.android.runtimeservice.companion

import com.omnillm.engines.api.PlacementClassLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic coverage for host ticket + gate + identity + grant table
 * (no Android binder / dual-APK required).
 */
class CompanionHostClientLogicTest {

    @Test
    fun hostTicket_rejectsNonExternalPlacement() {
        try {
            HostSandboxExecutionTicket(
                runtimeInstanceId = "rt-1",
                runtimeEpoch = 1L,
                bootId = "boot-1",
                operationId = "op-1",
                commitId = "commit-1",
                engineBuildId = "eng-1",
                modelContentIds = listOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                backend = "gpu",
                resourceEnvelopeSummary = "vram=1",
                operatingConstraintSummary = "none",
                monotonicDeadlineMs = 9_999L,
                nonce = "nonce-1",
                placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            )
            org.junit.Assert.fail("must reject non-external placement on host ticket")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun gate_untrustedAccelWithoutCompanion_neverSameUid() {
        val r = CompanionPlacementGate.resolve(
            authenticityOk = false,
            engineCodeTrusted = true,
            engineStabilitySufficient = true,
            requiresAccelerator = true,
            isolatedCpuSupported = true,
            companion = CompanionAvailability.unavailable("not installed"),
        )
        assertFalse(r.executable)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, r.placementClass)
        assertFalse(CompanionPlacementGate.allowedInSameUidWorker(r.placementClass))
        assertTrue(r.reason.contains("companion") || r.errorCode == "TRUST_PLACEMENT_REQUIRED")
    }

    @Test
    fun missingCompanion_mapsToTrustPlacementRequired() {
        val unavailable = HostCompanionResult.Unavailable(
            errorCode = CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
            message = "companion APK not installed; never same-UID sandbox",
        )
        assertEquals(
            CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
            unavailable.errorCode,
        )
        assertTrue(unavailable.message.contains("not installed"))
        // Explicit product rule: message documents no same-UID fallback.
        assertTrue(unavailable.message.contains("never same-UID"))
    }

    @Test
    fun identityValidation_acceptsMatchingPackageAndProtocol() {
        val report = HostIdentityValidation.IdentityReport(
            packageName = CompanionHostConstants.COMPANION_PACKAGE,
            packageVersionName = "0.0.1",
            packageVersionCode = 1L,
            signerDigestHex = "abc123",
            processInstanceId = "pi-1",
            pid = 42,
            protocolMajor = CompanionHostConstants.PROTOCOL_MAJOR,
            protocolMinor = CompanionHostConstants.PROTOCOL_MINOR,
            uid = 10099,
        )
        val v = HostIdentityValidation.validate(
            report = report,
            expectedPackageNames = setOf(CompanionHostConstants.COMPANION_PACKAGE),
            mainAppUid = 10001,
        )
        assertTrue(v is HostIdentityValidation.Result.Accepted)
    }

    @Test
    fun identityValidation_rejectsSameUidAsMain() {
        val report = HostIdentityValidation.IdentityReport(
            packageName = CompanionHostConstants.COMPANION_PACKAGE,
            packageVersionName = "0.0.1",
            packageVersionCode = 1L,
            signerDigestHex = "abc123",
            processInstanceId = "pi-1",
            pid = 42,
            protocolMajor = 1,
            protocolMinor = 0,
            uid = 10001,
        )
        val v = HostIdentityValidation.validate(
            report = report,
            expectedPackageNames = setOf(CompanionHostConstants.COMPANION_PACKAGE),
            mainAppUid = 10001,
        )
        assertTrue(v is HostIdentityValidation.Result.Rejected)
        assertEquals(
            CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
            (v as HostIdentityValidation.Result.Rejected).errorCode,
        )
    }

    @Test
    fun identityValidation_rejectsProtocolMajorMismatch() {
        val report = HostIdentityValidation.IdentityReport(
            packageName = CompanionHostConstants.COMPANION_PACKAGE,
            packageVersionName = "0.0.1",
            packageVersionCode = 1L,
            signerDigestHex = "abc123",
            processInstanceId = "pi-1",
            pid = 42,
            protocolMajor = 99,
            protocolMinor = 0,
            uid = 10099,
        )
        val v = HostIdentityValidation.validate(
            report = report,
            expectedPackageNames = setOf(CompanionHostConstants.COMPANION_PACKAGE),
        )
        assertTrue(v is HostIdentityValidation.Result.Rejected)
    }

    @Test
    fun interpretHandshakeReply_handshakeOk() {
        val attrs = mapOf(
            "packageName" to CompanionHostConstants.COMPANION_PACKAGE,
            "packageVersionName" to "0.0.1",
            "packageVersionCode" to "1",
            "signerDigestHex" to "deadbeef",
            "processInstanceId" to "pi",
            "pid" to "10",
            "protocolMajor" to "1",
            "protocolMinor" to "0",
            "uid" to "10099",
        )
        val r = HostIdentityValidation.interpretHandshakeReply(
            resultKind = CompanionBinderWire.RESULT_HANDSHAKE_OK,
            errorCode = null,
            message = null,
            attributes = attrs,
            expectedPackageNames = setOf(CompanionHostConstants.COMPANION_PACKAGE),
            mainAppUid = 1,
        )
        assertTrue(r is HostCompanionResult.HandshakeOk)
    }

    @Test
    fun grantTable_rejectsPathTokens_andAcceptsOpaque() {
        val table = HostPfdGrantTable()
        val bad = table.grant(
            token = "/data/data/com.omnillm/files/x",
            requestId = "r1",
            runtimeEpoch = 1L,
            bootId = "boot",
            nowMonotonicMs = 1L,
        )
        assertTrue(bad is HostPfdGrantTable.GrantResult.Rejected)

        val ok = table.grant(
            token = "fd-token-1",
            requestId = "r1",
            runtimeEpoch = 1L,
            bootId = "boot",
            nowMonotonicMs = 1L,
        )
        assertTrue(ok is HostPfdGrantTable.GrantResult.Ok)
        assertEquals(1, table.size)

        assertEquals(1, table.revokeAllForRequest("r1"))
        assertEquals(0, table.size)
    }

    @Test
    fun grantTable_rejectsWriteMode() {
        val table = HostPfdGrantTable()
        val r = table.grant(
            token = "t1",
            mode = "READ_WRITE",
            requestId = "r",
            runtimeEpoch = 1L,
            bootId = "b",
            nowMonotonicMs = 0L,
        )
        assertTrue(r is HostPfdGrantTable.GrantResult.Rejected)
    }

    @Test
    fun companionPackageForMain_debugSuffix() {
        assertEquals(
            CompanionHostConstants.COMPANION_PACKAGE_DEBUG,
            CompanionHostConstants.companionPackageForMain("com.omnillm.debug"),
        )
        assertEquals(
            CompanionHostConstants.COMPANION_PACKAGE,
            CompanionHostConstants.companionPackageForMain("com.omnillm"),
        )
    }

    @Test
    fun binderWire_transactionCodesMatchExpectedOffsets() {
        // Host and companion must share FIRST_CALL_TRANSACTION+N layout.
        assertEquals(1, CompanionBinderWire.TRANSACTION_ATTACH_SUPERVISOR)
        assertEquals(2, CompanionBinderWire.TRANSACTION_HANDSHAKE)
        assertEquals(3, CompanionBinderWire.TRANSACTION_REGISTER_RO_PFD)
        assertEquals(CompanionBinderWire.RESULT_HANDSHAKE_OK, 1)
    }

    @Test
    fun hostCompanionClient_typealias_exists() {
        // Compile-time alias HostCompanionClient = CompanionHostClient
        val cls: Class<*> = HostCompanionClient::class.java
        assertEquals(CompanionHostClient::class.java, cls)
    }
}
