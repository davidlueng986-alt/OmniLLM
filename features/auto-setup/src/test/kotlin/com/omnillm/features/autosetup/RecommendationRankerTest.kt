package com.omnillm.features.autosetup

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.autosetup.domain.RecommendationReasonCodes
import com.omnillm.features.autosetup.ranking.RecommendationRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationRankerTest {

    @Test
    fun ranksViableCandidates_prefersStableCpuWhenAcceleratorUnknown() {
        val dev = device(gpuEvidence = EvidenceLabel.UNKNOWN)
        val small = candidate(
            id = "small-cpu",
            name = "Small CPU",
            backend = "cpu",
            stability = EvidenceLabel.MEASURED,
            quality = 0.4,
            speed = 0.9,
            peak = 400L * 1024 * 1024,
        )
        val large = candidate(
            id = "large-cpu",
            name = "Large CPU",
            rev = revision('3'),
            backend = "cpu",
            stability = EvidenceLabel.ESTIMATED,
            quality = 0.9,
            speed = 0.3,
            peak = 2L * 1024 * 1024 * 1024,
            packageBytes = 1L * 1024 * 1024 * 1024,
        )
        val result = RecommendationRanker.rank(dev, prefs(), listOf(large, small), 1_000L)
        assertTrue(result.hasViable)
        assertEquals("small-cpu", result.top!!.candidate.candidateId)
        assertTrue(result.top!!.reasonCodes.any { it.code == RecommendationReasonCodes.SELECTED })
    }

    @Test
    fun rejectsUnknownCapability_failClosed() {
        val c = candidate(
            id = "unknown-cap",
            capState = CapabilityState.UNKNOWN,
        )
        val result = RecommendationRanker.rank(device(), prefs(), listOf(c), 1L)
        assertFalse(result.hasViable)
        assertEquals(1, result.rejected.size)
        assertEquals(
            RecommendationReasonCodes.CAPABILITY_UNKNOWN,
            result.rejected[0].reasons[0].code,
        )
    }

    @Test
    fun rejectsNonCpuWhenAcceleratorUnknown() {
        val c = candidate(
            id = "gpu-only",
            backend = "gpu-vulkan",
        )
        val result = RecommendationRanker.rank(
            device(gpuEvidence = EvidenceLabel.UNKNOWN),
            prefs(),
            listOf(c),
            1L,
        )
        assertFalse(result.hasViable)
        assertEquals(
            RecommendationReasonCodes.ACCELERATOR_UNKNOWN,
            result.rejected[0].reasons[0].code,
        )
        assertTrue(result.minimalViableAdjustments.contains("use-cpu-backend"))
    }

    @Test
    fun rejectsResourceOverRam() {
        val c = candidate(
            id = "too-big",
            peak = 16L * 1024 * 1024 * 1024,
        )
        val result = RecommendationRanker.rank(
            device(ramBytes = 4L * 1024 * 1024 * 1024),
            prefs(),
            listOf(c),
            1L,
        )
        assertFalse(result.hasViable)
        assertEquals(
            RecommendationReasonCodes.RESOURCE_FIT,
            result.rejected[0].reasons[0].code,
        )
    }

    @Test
    fun rejectsUntrustedWhenPreferTrustedOnly() {
        val c = candidate(
            id = "untrusted",
            placement = PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
        )
        val result = RecommendationRanker.rank(device(), prefs(preferTrusted = true), listOf(c), 1L)
        assertFalse(result.hasViable)
        assertEquals(
            RecommendationReasonCodes.TRUST_PLACEMENT,
            result.rejected[0].reasons[0].code,
        )
    }

    @Test
    fun rejectsLicenseNotOk() {
        val c = candidate(id = "nolic", licenseOk = false)
        val result = RecommendationRanker.rank(device(), prefs(), listOf(c), 1L)
        assertEquals(RecommendationReasonCodes.LICENSE, result.rejected[0].reasons[0].code)
    }

    @Test
    fun storageBudgetSoftReject() {
        val c = candidate(id = "big-pkg", packageBytes = 5L * 1024 * 1024 * 1024)
        val result = RecommendationRanker.rank(
            device(),
            prefs(maxStorage = 1L * 1024 * 1024 * 1024),
            listOf(c),
            1L,
        )
        assertEquals(
            RecommendationReasonCodes.STORAGE_BUDGET,
            result.rejected[0].reasons[0].code,
        )
    }
}
