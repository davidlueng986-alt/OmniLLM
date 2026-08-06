package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.runtime.ObservabilityModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTraceAndRedactionTest {

    @Test
    fun traceMonotonicSequenceAndMetadata() {
        var t = 100L
        val rec = InMemoryTraceRecorder(clockMonotonicNs = { t.also { t += 10 } })
        assertTrue(
            rec.start(
                correlationId = "corr-1",
                causationId = "cause-0",
                principalId = "prin-local",
                requestId = "req-1",
                policyVersion = "pv-1",
                engineBuildId = "eng-1",
                modelRevisionId = "m".repeat(64),
                runtimeEpoch = 3L,
            ) is OmniResult.Ok,
        )
        val e0 = (rec.append("corr-1", "accepted", phase = "admit") as OmniResult.Ok).value
        val e1 = (rec.append(
            "corr-1",
            "first_token",
            phase = "execute",
            attributes = mapOf("errorCode" to "OK"),
        ) as OmniResult.Ok).value
        assertEquals(0L, e0.sequence)
        assertEquals(1L, e1.sequence)
        assertTrue(e1.monotonicNs > e0.monotonicNs)

        val finished = (rec.finish("corr-1") as OmniResult.Ok).value
        assertEquals(2, finished.events.size)
        assertEquals("eng-1", finished.engineBuildId)
        assertEquals(3L, finished.runtimeEpoch)
    }

    @Test
    fun traceRejectsPromptAttributeKey() {
        val rec = InMemoryTraceRecorder()
        rec.start(correlationId = "c2")
        val bad = rec.append(
            "c2",
            "log",
            attributes = mapOf("prompt" to "secret user text"),
        )
        assertTrue(bad is OmniResult.Err)
    }

    @Test
    fun redactorScrubsBearerAndPaths() {
        val input =
            "Authorization: Bearer abc.def.ghi path=/data/user/0/com.omnillm/files/model.gguf " +
                "win=C:\\Users\\daive\\secret.gguf file://tmp/x"
        val out = Redactor.redactFreeText(input)
        assertFalse(out.contains("abc.def.ghi"))
        assertFalse(out.contains("/data/user/0"))
        assertFalse(out.contains("C:\\Users\\daive"))
        assertTrue(out.contains(Redactor.REDACTED_TOKEN) || out.contains(Redactor.REDACTED_PATH))
        assertEquals(Redactor.REDACTED_PROMPT, Redactor.redactPrompt("hello"))
        assertEquals(Redactor.REDACTED_TOKEN, Redactor.redactToken("sekret"))
    }

    @Test
    fun diagnosticAllowlistDropsUnknownAndNeverExport() {
        val fields = mapOf(
            "bundleId" to "b1",
            "schemaVersion" to DiagnosticAllowlist.SCHEMA_VERSION,
            "prompt" to "should never export",
            "unknownField" to "drop me",
            "createdAt" to "2026-01-01T00:00:00Z",
        )
        val projected = DiagnosticAllowlist.project(
            DiagnosticAllowlist.Category.MANIFEST,
            fields,
        )
        assertTrue(projected.containsKey("bundleId"))
        assertTrue(projected.containsKey("createdAt"))
        assertFalse(projected.containsKey("prompt"))
        assertFalse(projected.containsKey("unknownField"))
        assertFalse(DiagnosticAllowlist.isAllowed(DiagnosticAllowlist.Category.MANIFEST, "prompt"))
    }

    @Test
    fun diagnosticAllowlist_exportSchema_isVersionedAndExcludesSecrets() {
        val doc = DiagnosticAllowlist.exportSchemaDocument()
        assertEquals(DiagnosticAllowlist.SCHEMA_VERSION, doc["schemaVersion"])
        assertTrue((doc["categoryCount"] as Int) >= 1)
        assertTrue((doc["allowedFieldCount"] as Int) >= 1)
        @Suppress("UNCHECKED_CAST")
        val never = doc["neverExport"] as List<*>
        val neverNames = never.map { it.toString() }
        assertTrue(neverNames.contains("prompt"))
        assertTrue(neverNames.contains("token"))
        assertTrue(neverNames.contains("bearerToken"))
        assertTrue(neverNames.contains("rawPrompt"))
        val text = DiagnosticAllowlist.exportSchemaCanonicalText()
        assertTrue(text.startsWith("schemaVersion="))
        // neverExport line lists forbidden field names for transparency (not secret values).
        assertTrue(text.contains("neverExport="))
        assertTrue(text.contains("prompt"))
        // Category field allowlists must not re-admit never-export keys as allowed fields.
        val categoryFieldLines = text.lines().filter {
            it.startsWith("category.") && it.contains(".fields=")
        }
        assertTrue(categoryFieldLines.isNotEmpty())
        for (line in categoryFieldLines) {
            val fieldsPart = line.substringAfter(".fields=")
            val fields = fieldsPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            assertFalse("prompt must not be an allowed category field: $line", fields.contains("prompt"))
            assertFalse("token must not be an allowed category field: $line", fields.contains("token"))
        }
    }

    @Test
    fun principalPseudonymStable() {
        val a = Redactor.pseudonymizePrincipal("user-1", "salt")
        val b = Redactor.pseudonymizePrincipal("user-1", "salt")
        val c = Redactor.pseudonymizePrincipal("user-2", "salt")
        assertEquals(a, b)
        assertTrue(a != c)
        assertTrue(a.startsWith("prin_"))
    }

    @Test
    fun facadeWiresSummaryAndHealth() {
        val facade = ObservabilityModule.createFacade(
            clockWallMs = { 50_000L },
            clockMonotonicNs = { 1_000L },
        )
        facade.health.setRuntimeState("READY")
        facade.health.report(
            kind = HealthSubjectKind.SERVICE,
            subjectId = "runtime",
            level = HealthLevel.HEALTHY,
        )
        assertTrue(
            facade.recordMetric(
                MetricId.REQUEST_ERROR_COUNT,
                1.0,
                com.omnillm.core.canonical.generated.EvidenceLabel.MEASURED,
                mapOf("errorCode" to "INTERNAL", "phase" to "execute"),
            ) is OmniResult.Ok,
        )
        val summary = facade.metricSummary()
        assertNotNull(summary)
        assertTrue(summary.samples.any { it.name == "request.error_count" })
        val health = facade.serviceHealth()
        assertEquals("READY", health.runtimeState)
        assertEquals(HealthLevel.HEALTHY, health.overallLevel)
    }
}
