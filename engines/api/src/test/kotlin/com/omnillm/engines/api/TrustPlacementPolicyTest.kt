package com.omnillm.engines.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPlacementPolicyTest {

    private fun base(
        authenticityOk: Boolean = true,
        engineCodeTrusted: Boolean = true,
        engineStabilitySufficient: Boolean = true,
        requiresAccelerator: Boolean = false,
        isolatedCpuSupported: Boolean = true,
        companionAvailable: Boolean = false,
        phaseQualificationKnown: Boolean = true,
    ) = TrustPlacementPolicy.Input(
        authenticityOk = authenticityOk,
        engineCodeTrusted = engineCodeTrusted,
        engineStabilitySufficient = engineStabilitySufficient,
        requiresAccelerator = requiresAccelerator,
        isolatedCpuSupported = isolatedCpuSupported,
        companionAvailable = companionAvailable,
        phaseQualificationKnown = phaseQualificationKnown,
    )

    @Test
    fun trustedStable_privileged() {
        val d = TrustPlacementPolicy.resolve(base())
        assertTrue(d.executable)
        assertEquals(PlacementClassLabels.PRIVILEGED_TRUSTED, d.placementClass)
    }

    @Test
    fun trustedUnstable_crashContained() {
        val d = TrustPlacementPolicy.resolve(base(engineStabilitySufficient = false))
        assertTrue(d.executable)
        assertEquals(PlacementClassLabels.CRASH_CONTAINED_TRUSTED, d.placementClass)
    }

    @Test
    fun untrustedAccelerator_withCompanion_externalUid() {
        val d = TrustPlacementPolicy.resolve(
            base(
                authenticityOk = false,
                requiresAccelerator = true,
                companionAvailable = true,
            ),
        )
        assertTrue(d.executable)
        assertEquals(PlacementClassLabels.EXTERNAL_UID_ACCELERATED, d.placementClass)
    }

    @Test
    fun untrustedAccelerator_withoutCompanion_trustPlacementRequired() {
        val d = TrustPlacementPolicy.resolve(
            base(
                authenticityOk = false,
                requiresAccelerator = true,
                companionAvailable = false,
                isolatedCpuSupported = true,
            ),
        )
        assertFalse(d.executable)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, d.placementClass)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, d.errorCode)
        // Must not silently select same-UID trusted/crash classes.
        assertFalse(TrustPlacementPolicy.allowedInSameUidWorker(d.placementClass))
    }

    @Test
    fun untrustedCpu_isolatedWhenSupported() {
        val d = TrustPlacementPolicy.resolve(
            base(authenticityOk = false, requiresAccelerator = false, isolatedCpuSupported = true),
        )
        assertTrue(d.executable)
        assertEquals(PlacementClassLabels.ISOLATED_CPU_UNTRUSTED, d.placementClass)
        assertFalse(TrustPlacementPolicy.allowedInSameUidWorker(d.placementClass))
    }

    @Test
    fun untrustedCpu_noIsolated_failClosed() {
        val d = TrustPlacementPolicy.resolve(
            base(
                authenticityOk = false,
                requiresAccelerator = false,
                isolatedCpuSupported = false,
                companionAvailable = false,
            ),
        )
        assertFalse(d.executable)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, d.placementClass)
    }

    @Test
    fun unknownPhaseQualification_failClosed() {
        val d = TrustPlacementPolicy.resolve(base(phaseQualificationKnown = false))
        assertFalse(d.executable)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, d.placementClass)
    }

    @Test
    fun gateProposed_externalWithoutCompanion_required() {
        val d = TrustPlacementPolicy.gateProposed(
            proposedPlacementClass = PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
            companionAvailable = false,
            isolatedCpuAvailable = true,
        )
        assertFalse(d.executable)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, d.errorCode)
    }

    @Test
    fun gateProposed_externalWithCompanion_ok() {
        val d = TrustPlacementPolicy.gateProposed(
            proposedPlacementClass = PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
            companionAvailable = true,
            isolatedCpuAvailable = false,
        )
        assertTrue(d.executable)
        assertEquals(PlacementClassLabels.EXTERNAL_UID_ACCELERATED, d.placementClass)
    }

    @Test
    fun sameUidWorkerAllowsOnlyTrustedClasses() {
        assertTrue(TrustPlacementPolicy.allowedInSameUidWorker(PlacementClassLabels.PRIVILEGED_TRUSTED))
        assertTrue(TrustPlacementPolicy.allowedInSameUidWorker(PlacementClassLabels.CRASH_CONTAINED_TRUSTED))
        assertFalse(TrustPlacementPolicy.allowedInSameUidWorker(PlacementClassLabels.EXTERNAL_UID_ACCELERATED))
        assertFalse(TrustPlacementPolicy.allowedInSameUidWorker(PlacementClassLabels.ISOLATED_CPU_UNTRUSTED))
        assertFalse(TrustPlacementPolicy.allowedInSameUidWorker(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED))
    }

    @Test
    fun neverUsesSameUidAsSecuritySandboxForUntrustedAccel() {
        // Even if isolated CPU is available, accelerator path must not pick it as
        // a silent "same-UID high-performance sandbox" substitute for companion.
        val d = TrustPlacementPolicy.resolve(
            base(
                authenticityOk = false,
                requiresAccelerator = true,
                isolatedCpuSupported = true,
                companionAvailable = false,
            ),
        )
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, d.placementClass)
        assertTrue(
            d.placementClass != PlacementClassLabels.CRASH_CONTAINED_TRUSTED &&
                d.placementClass != PlacementClassLabels.PRIVILEGED_TRUSTED &&
                d.placementClass != PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
        )
    }
}
