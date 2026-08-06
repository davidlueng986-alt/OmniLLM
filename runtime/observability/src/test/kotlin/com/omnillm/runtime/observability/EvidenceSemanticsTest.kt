package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceSemanticsTest {

    @Test
    fun catalogLabelsMatchProdQuality() {
        assertEquals(
            listOf(
                EvidenceLabel.MEASURED,
                EvidenceLabel.ESTIMATED,
                EvidenceLabel.REPORTED,
                EvidenceLabel.LAST_SAMPLED,
                EvidenceLabel.UNKNOWN,
            ),
            EvidenceSemantics.ALL,
        )
    }

    @Test
    fun unknownNeverAllowsNumericDisplayAsTruth() {
        assertFalse(EvidenceSemantics.allowsNumericDisplay(EvidenceLabel.UNKNOWN))
        assertTrue(EvidenceSemantics.allowsNumericDisplay(EvidenceLabel.MEASURED))
        assertTrue(EvidenceSemantics.allowsNumericDisplay(EvidenceLabel.LAST_SAMPLED))
    }

    @Test
    fun onlyMeasuredDrivesHardAdmission() {
        assertTrue(EvidenceSemantics.mayDriveHardAdmission(EvidenceLabel.MEASURED))
        assertFalse(EvidenceSemantics.mayDriveHardAdmission(EvidenceLabel.REPORTED))
        assertFalse(EvidenceSemantics.mayDriveHardAdmission(EvidenceLabel.ESTIMATED))
        assertFalse(EvidenceSemantics.mayDriveHardAdmission(EvidenceLabel.LAST_SAMPLED))
        assertFalse(EvidenceSemantics.mayDriveHardAdmission(EvidenceLabel.UNKNOWN))
    }

    @Test
    fun reportedRequiresSource() {
        EvidencedValue(
            value = 1.0,
            evidenceLabel = EvidenceLabel.REPORTED,
            sampledAtEpochMs = 1000L,
            source = "engine-worker",
        )
        try {
            EvidencedValue(
                value = 1.0,
                evidenceLabel = EvidenceLabel.REPORTED,
                sampledAtEpochMs = 1000L,
                source = null,
            )
            throw AssertionError("expected failure for REPORTED without source")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun ageMsIsNonNegative() {
        val v = EvidencedValue(
            value = null,
            evidenceLabel = EvidenceLabel.UNKNOWN,
            sampledAtEpochMs = 5000L,
        )
        assertEquals(0L, v.ageMs(4000L))
        assertEquals(2500L, v.ageMs(7500L))
    }
}
