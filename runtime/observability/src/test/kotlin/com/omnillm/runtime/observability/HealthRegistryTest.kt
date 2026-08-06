package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRegistryTest {

    @Test
    fun emptySubjectsAreUnknown() {
        assertEquals(HealthLevel.UNKNOWN, HealthAggregator.overallLevel(emptyList()))
    }

    @Test
    fun unavailableDominates() {
        val views = listOf(
            view(HealthLevel.HEALTHY),
            view(HealthLevel.UNAVAILABLE, reasons = listOf("DB_INTEGRITY")),
            view(HealthLevel.DEGRADED, reasons = listOf("THERMAL")),
        )
        assertEquals(HealthLevel.UNAVAILABLE, HealthAggregator.overallLevel(views))
        assertEquals(listOf("DB_INTEGRITY", "THERMAL"), HealthAggregator.collectReasons(views))
    }

    @Test
    fun healthyPlusUnknownIsDegraded() {
        val views = listOf(
            view(HealthLevel.HEALTHY, id = "a"),
            view(HealthLevel.UNKNOWN, id = "b"),
        )
        assertEquals(HealthLevel.DEGRADED, HealthAggregator.overallLevel(views))
    }

    @Test
    fun registrySnapshotTracksRuntimeStateAndReasons() {
        var now = 10_000L
        val reg = HealthRegistry(clockWallMs = { now })
        reg.setRuntimeState("READY")
        reg.report(
            kind = HealthSubjectKind.ENGINE_MODULE,
            subjectId = "llama-cpp@cpu",
            level = HealthLevel.DEGRADED,
            reasonCodes = listOf("REPEATED_CRASH"),
            affectedCapabilities = listOf("inference.chat"),
            automaticActions = listOf("fence-engine"),
            recommendedActions = listOf("restart-engine"),
            evidenceLabel = EvidenceLabel.MEASURED,
        )
        reg.report(
            kind = HealthSubjectKind.RESOURCE_GOVERNOR,
            subjectId = "governor",
            level = HealthLevel.HEALTHY,
            evidenceLabel = EvidenceLabel.MEASURED,
        )

        val snap = reg.snapshot()
        assertEquals("READY", snap.runtimeState)
        assertEquals(HealthLevel.DEGRADED, snap.overallLevel)
        assertTrue(snap.degradedReasons.contains("REPEATED_CRASH"))
        assertEquals(2, snap.subjects.size)
        assertTrue(snap.resourceVersion > 0L)

        // same level keeps since
        val sinceBefore = reg.get(
            HealthSubject(HealthSubjectKind.ENGINE_MODULE, "llama-cpp@cpu"),
        )!!.sinceEpochMs
        now = 20_000L
        reg.report(
            kind = HealthSubjectKind.ENGINE_MODULE,
            subjectId = "llama-cpp@cpu",
            level = HealthLevel.DEGRADED,
            reasonCodes = listOf("REPEATED_CRASH"),
        )
        val after = reg.get(
            HealthSubject(HealthSubjectKind.ENGINE_MODULE, "llama-cpp@cpu"),
        )!!
        assertEquals(sinceBefore, after.sinceEpochMs)
        assertEquals(20_000L, after.sampledAtEpochMs)
    }

    @Test
    fun reportedHealthRequiresSource() {
        try {
            HealthView(
                subject = HealthSubject(HealthSubjectKind.DEVICE, "dev-1"),
                level = HealthLevel.DEGRADED,
                reasonCodes = listOf("THERMAL"),
                sinceEpochMs = 1L,
                sampledAtEpochMs = 1L,
                evidenceLabel = EvidenceLabel.REPORTED,
                source = null,
            )
            throw AssertionError("expected source required")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun view(
        level: HealthLevel,
        id: String = "s1",
        reasons: List<String> = emptyList(),
    ): HealthView =
        HealthView(
            subject = HealthSubject(HealthSubjectKind.SERVICE, id),
            level = level,
            reasonCodes = reasons,
            sinceEpochMs = 1L,
            sampledAtEpochMs = 1L,
            evidenceLabel = EvidenceLabel.MEASURED,
        )
}
