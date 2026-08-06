package com.omnillm.features.diagnostics

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.export.DiagnosticBundleBuilder
import com.omnillm.features.diagnostics.ports.InMemoryDiagnosticSourcePort
import com.omnillm.runtime.observability.DiagnosticAllowlist
import com.omnillm.runtime.observability.MetricSample
import com.omnillm.runtime.observability.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-DIAGNOSTICS redaction + integrity acceptance.
 */
class DiagnosticBundleBuilderTest {

    @Test
    fun defaultExport_excludesPromptTokenAndPrivatePath() {
        val sources = InMemoryDiagnosticSourcePort(
            requestJobs = listOf(
                mapOf(
                    "requestId" to "11111111-1111-1111-1111-111111111111",
                    "jobId" to "22222222-2222-2222-2222-222222222222",
                    "state" to "FAILED",
                    "phase" to "execute",
                    "errorCode" to "INTERNAL",
                    "principalClass" to "LOCAL_UI",
                    "operationKind" to "chat.completions",
                    "prompt" to "secret user prompt",
                    "token" to "Bearer super-secret",
                    "absolutePath" to "/data/user/0/com.omnillm/files/model.gguf",
                ),
            ),
            configuration = mapOf(
                "policyVersion" to "pv-1",
                "runtimeEpoch" to "3",
                "resourceCapsSummary" to "ram=4g",
                "featureFlagsSummary" to "diag=on",
                "tokenHash" to "should-drop",
            ),
            runtimeVersions = mapOf(
                "appVersion" to "1.0.0",
                "runtimeBuildId" to "rb-1",
                "platformOs" to "android",
                "platformApiLevel" to "36",
            ),
            metrics = listOf(
                MetricSample(
                    name = "request.error_count",
                    value = 1.0,
                    unit = "count",
                    evidenceLabel = EvidenceLabel.MEASURED,
                    sampledAtEpochMs = 1_000L,
                ),
            ),
        )

        val bundle = DiagnosticBundleBuilder.build(
            bundleId = "bundle-1",
            jobId = "job-1",
            ownerPrincipalClass = "LOCAL_UI",
            categories = listOf(
                DiagnosticAllowlist.Category.REQUEST_JOB_STATE,
                DiagnosticAllowlist.Category.CONFIGURATION,
                DiagnosticAllowlist.Category.RUNTIME_VERSIONS,
                DiagnosticAllowlist.Category.EVIDENCE_LABELS,
            ),
            sources = sources,
            includeDetail = false,
            createdAtEpochMs = 1_000L,
            seal = true,
            shareIrreversibleDisclosed = true,
        )

        assertEquals(DiagnosticBundleStates.READY, bundle.state)
        assertNotNull(bundle.manifestDigest)
        assertTrue(DiagnosticBundleBuilder.verifyIntegrity(bundle))

        // Payload files only — redaction_allowlist intentionally names never-export keys.
        val payloadText = bundle.files
            .filter { it.pathRole != DiagnosticBundleBuilder.REDACTION_ALLOWLIST_PATH_ROLE }
            .joinToString("\n") { it.redactedUtf8.orEmpty() }
        assertFalse(payloadText.contains("secret user prompt"))
        assertFalse(payloadText.contains("super-secret"))
        assertFalse(payloadText.contains("/data/user/0"))
        // Never-export keys must not appear as exported field=value rows.
        assertFalse(payloadText.lines().any { it.startsWith("tokenHash=") })
        assertFalse(payloadText.contains("should-drop"))
        assertTrue(payloadText.contains("requestId") || payloadText.contains("errorCode"))

        // Schema transparency may list denied field names; must not carry secret values.
        val allowlistBody = bundle.files
            .first { it.pathRole == DiagnosticBundleBuilder.REDACTION_ALLOWLIST_PATH_ROLE }
            .redactedUtf8.orEmpty()
        assertTrue(allowlistBody.contains("neverExport="))
        assertTrue(allowlistBody.contains("prompt") || allowlistBody.contains("tokenHash"))
        assertFalse(allowlistBody.contains("secret user prompt"))
        assertFalse(allowlistBody.contains("should-drop"))
    }

    @Test
    fun integrity_failsWhenFileMutated() {
        val sources = InMemoryDiagnosticSourcePort(
            runtimeVersions = mapOf(
                "appVersion" to "1.0.0",
                "runtimeBuildId" to "rb-1",
                "platformOs" to "android",
                "platformApiLevel" to "36",
            ),
        )
        val bundle = DiagnosticBundleBuilder.build(
            bundleId = "b2",
            jobId = null,
            ownerPrincipalClass = "LOCAL_UI",
            categories = listOf(DiagnosticAllowlist.Category.RUNTIME_VERSIONS),
            sources = sources,
            includeDetail = false,
            createdAtEpochMs = 2_000L,
            seal = true,
            shareIrreversibleDisclosed = false,
        )
        assertTrue(DiagnosticBundleBuilder.verifyIntegrity(bundle))

        val mutatedFiles = bundle.files.mapIndexed { idx, f ->
            if (idx == 0) f.copy(sha256 = "0".repeat(64)) else f
        }
        val tampered = bundle.copy(files = mutatedFiles)
        assertFalse(DiagnosticBundleBuilder.verifyIntegrity(tampered))
    }

    @Test
    fun unsealed_neverReady() {
        val bundle = DiagnosticBundleBuilder.build(
            bundleId = "b3",
            jobId = "j3",
            ownerPrincipalClass = "LOCAL_UI",
            categories = listOf(DiagnosticAllowlist.Category.MANIFEST),
            sources = InMemoryDiagnosticSourcePort(),
            includeDetail = false,
            createdAtEpochMs = 3_000L,
            seal = false,
            shareIrreversibleDisclosed = false,
        )
        assertEquals(DiagnosticBundleStates.COLLECTING, bundle.state)
        assertNull(bundle.manifestDigest)
        assertFalse(bundle.isShareable)
    }

    @Test
    fun freeText_redactsBearerPaths() {
        val input = "Authorization: Bearer abc.def path=/data/user/0/com.omnillm/x"
        val out = Redactor.redactFreeText(input)
        assertFalse(out.contains("abc.def"))
        assertFalse(out.contains("/data/user/0"))
    }

    @Test
    fun evidenceLabels_notMixedInExportBody() {
        val sources = InMemoryDiagnosticSourcePort(
            metrics = listOf(
                MetricSample(
                    name = "resource.reserved_bytes",
                    value = 100.0,
                    unit = "bytes",
                    evidenceLabel = EvidenceLabel.ESTIMATED,
                    sampledAtEpochMs = 10L,
                ),
                MetricSample(
                    name = "resource.allocated_bytes",
                    value = 80.0,
                    unit = "bytes",
                    evidenceLabel = EvidenceLabel.MEASURED,
                    sampledAtEpochMs = 10L,
                ),
            ),
        )
        val bundle = DiagnosticBundleBuilder.build(
            bundleId = "b4",
            jobId = null,
            ownerPrincipalClass = "LOCAL_UI",
            categories = listOf(DiagnosticAllowlist.Category.EVIDENCE_LABELS),
            sources = sources,
            includeDetail = false,
            createdAtEpochMs = 10L,
            seal = true,
            shareIrreversibleDisclosed = true,
        )
        val evidenceFile = bundle.files.first { it.pathRole == "evidence_labels" }
        val body = evidenceFile.redactedUtf8.orEmpty()
        assertTrue(body.contains("ESTIMATED"))
        assertTrue(body.contains("MEASURED"))
        // Each row keeps its own label — both appear, never collapsed.
        assertTrue(body.lines().count { it.contains("evidenceLabel=") } >= 2)
    }
}
