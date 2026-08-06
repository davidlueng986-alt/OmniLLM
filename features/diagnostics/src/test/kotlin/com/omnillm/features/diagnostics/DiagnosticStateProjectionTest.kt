package com.omnillm.features.diagnostics

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.features.diagnostics.projection.DiagnosticStateProjection
import com.omnillm.features.diagnostics.projection.MetricEvidenceProjection
import com.omnillm.runtime.observability.DiagnosticAllowlist
import com.omnillm.runtime.observability.HealthLevel
import com.omnillm.runtime.observability.HealthSubject
import com.omnillm.runtime.observability.HealthSubjectKind
import com.omnillm.runtime.observability.HealthView
import com.omnillm.runtime.observability.MetricSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticStateProjectionTest {

    @Test
    fun empty_whenNoBundlesNoJobNoPreview() {
        val phase = DiagnosticStateProjection.projectUiPhase(
            bundles = emptyList(),
            activeJob = null,
            health = null,
            lastError = null,
            hasPreview = false,
        )
        assertEquals(DiagnosticUiPhases.EMPTY, phase)
    }

    @Test
    fun preview_whenPlanPresent() {
        val phase = DiagnosticStateProjection.projectUiPhase(
            bundles = emptyList(),
            activeJob = null,
            health = null,
            lastError = null,
            hasPreview = true,
        )
        assertEquals(DiagnosticUiPhases.PREVIEW, phase)
    }

    @Test
    fun error_whenLastErrorAndNoReadyBundle() {
        val phase = DiagnosticStateProjection.projectUiPhase(
            bundles = emptyList(),
            activeJob = null,
            health = null,
            lastError = OmniError.INTERNAL(message = "boom"),
            hasPreview = false,
        )
        assertEquals(DiagnosticUiPhases.ERROR, phase)
    }

    @Test
    fun degraded_whenReadyButServiceDegraded() {
        val ready = sealedBundle()
        val health = HealthView(
            subject = HealthSubject(HealthSubjectKind.SERVICE, "runtime"),
            level = HealthLevel.DEGRADED,
            reasonCodes = listOf("engine.repeated-crash"),
            sinceEpochMs = 1L,
            sampledAtEpochMs = 2L,
            evidenceLabel = EvidenceLabel.MEASURED,
        )
        val phase = DiagnosticStateProjection.projectUiPhase(
            bundles = listOf(ready),
            activeJob = null,
            health = health,
            lastError = null,
            hasPreview = false,
        )
        assertEquals(DiagnosticUiPhases.DEGRADED, phase)
    }

    @Test
    fun content_whenReadyAndHealthy() {
        val ready = sealedBundle()
        val health = HealthView(
            subject = HealthSubject(HealthSubjectKind.SERVICE, "runtime"),
            level = HealthLevel.HEALTHY,
            sinceEpochMs = 1L,
            sampledAtEpochMs = 2L,
            evidenceLabel = EvidenceLabel.MEASURED,
        )
        val phase = DiagnosticStateProjection.projectUiPhase(
            bundles = listOf(ready),
            activeJob = null,
            health = health,
            lastError = null,
            hasPreview = false,
        )
        assertEquals(DiagnosticUiPhases.CONTENT, phase)
    }

    @Test
    fun unknownMetric_neverCoercedToZeroAsMeasured() {
        val view = DiagnosticStateProjection.projectUnknownMetric(
            name = "resource.reserved_bytes",
            unit = "bytes",
            sampledAtEpochMs = 50L,
        )
        assertEquals(EvidenceLabel.UNKNOWN, view.evidenceLabel)
        assertNull(view.value)
        assertNull(MetricEvidenceProjection.displayNumberOrNull(view))
        assertFalse(MetricEvidenceProjection.mayDriveHardAdmission(EvidenceLabel.UNKNOWN))
    }

    @Test
    fun measuredMetric_allowsNumericDisplay() {
        val sample = MetricSample(
            name = "request.ttft_ms",
            value = 42.0,
            unit = "ms",
            evidenceLabel = EvidenceLabel.MEASURED,
            sampledAtEpochMs = 1L,
        )
        val view = DiagnosticStateProjection.projectMetric(sample)
        assertEquals(42.0, MetricEvidenceProjection.displayNumberOrNull(view)!!, 0.0)
        assertTrue(MetricEvidenceProjection.mayDriveHardAdmission(EvidenceLabel.MEASURED))
        assertEquals("evidence.measured", MetricEvidenceProjection.descriptionKey(EvidenceLabel.MEASURED))
    }

    private fun sealedBundle(): DiagnosticBundleSnapshot {
        val files = listOf(
            com.omnillm.features.diagnostics.domain.BundleFileEntry(
                pathRole = "manifest",
                sha256 = "a".repeat(64),
                byteLength = 10L,
                redactedUtf8 = "bundleId=x",
            ),
        )
        val digest = com.omnillm.features.diagnostics.export.DiagnosticBundleBuilder.sealManifest(files)
        return DiagnosticBundleSnapshot(
            bundleId = "b-ready",
            jobId = null,
            ownerPrincipalClass = "LOCAL_UI",
            state = DiagnosticBundleStates.READY,
            schemaVersion = DiagnosticAllowlist.SCHEMA_VERSION,
            createdAtEpochMs = 1L,
            expiresAtEpochMs = 1_000_000L,
            categoriesIncluded = listOf("MANIFEST"),
            files = files,
            manifestDigest = digest,
            reason = null,
            estimatedBytes = 10L,
            shareIrreversibleDisclosed = true,
        )
    }
}
