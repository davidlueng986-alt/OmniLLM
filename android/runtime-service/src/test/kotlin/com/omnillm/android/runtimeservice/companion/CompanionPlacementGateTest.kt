package com.omnillm.android.runtimeservice.companion

import com.omnillm.engines.api.PlacementClassLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionPlacementGateTest {

    @Test
    fun untrustedAccelWithoutCompanion_trustPlacementRequired() {
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
        assertEquals(CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED, r.errorCode)
        assertFalse(CompanionPlacementGate.allowedInSameUidWorker(r.placementClass))
    }

    @Test
    fun untrustedAccelWithCompanion_externalUid() {
        val r = CompanionPlacementGate.resolve(
            authenticityOk = false,
            engineCodeTrusted = true,
            engineStabilitySufficient = true,
            requiresAccelerator = true,
            isolatedCpuSupported = false,
            companion = CompanionAvailability.available(),
        )
        assertTrue(r.executable)
        assertEquals(PlacementClassLabels.EXTERNAL_UID_ACCELERATED, r.placementClass)
    }

    @Test
    fun gateProposed_neverSameUidFallback() {
        val r = CompanionPlacementGate.gateProposed(
            proposedPlacementClass = PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
            companion = CompanionAvailability.unavailable("disabled"),
            isolatedCpuAvailable = true,
        )
        assertFalse(r.executable)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, r.placementClass)
        assertTrue(r.reason.contains("never same-UID"))
    }

    @Test
    fun sameUidWorkerRejectsExternalAndIsolated() {
        assertFalse(
            CompanionPlacementGate.allowedInSameUidWorker(
                PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
            ),
        )
        assertFalse(
            CompanionPlacementGate.allowedInSameUidWorker(
                PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
            ),
        )
        assertTrue(
            CompanionPlacementGate.allowedInSameUidWorker(
                PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
        )
    }

    @Test
    fun packageRules_requireDifferentPackages() {
        assertTrue(
            CompanionPackageRules.packagesMustDiffer(
                CompanionHostConstants.MAIN_APP_PACKAGE,
                CompanionHostConstants.COMPANION_PACKAGE,
            ),
        )
        assertFalse(
            CompanionPackageRules.packagesMustDiffer("com.omnillm", "com.omnillm"),
        )
        assertTrue(CompanionPackageRules.rejectsSharedUserIdClaim(null))
        assertFalse(CompanionPackageRules.rejectsSharedUserIdClaim("com.omnillm.uid"))
    }

    @Test
    fun hostTicketRequiresExternalPlacement() {
        val t = HostSandboxTicketFactory.issue(
            runtimeInstanceId = "rt",
            runtimeEpoch = 1L,
            bootId = "boot",
            operationId = "op",
            commitId = "c",
            engineBuildId = "e",
            modelContentIds = listOf("m"),
            backend = "gpu",
            resourceEnvelopeSummary = "rv",
            operatingConstraintSummary = "oc",
            monotonicDeadlineMs = 100L,
            nonce = "n",
        )
        assertEquals(
            CompanionHostConstants.PLACEMENT_EXTERNAL_UID_ACCELERATED,
            t.placementClass,
        )
    }
}
