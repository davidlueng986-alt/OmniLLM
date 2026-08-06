package com.omnillm.runtime.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryAllowlistTest {

    @Test
    fun defaultDisabledAndSchemaPinned() {
        assertFalse(TelemetryAllowlist.DEFAULT_ENABLED)
        assertEquals("telemetry-event-allowlist.v1", TelemetryAllowlist.SCHEMA_VERSION)
    }

    @Test
    fun projectEvent_dropsUnknownAndSecrets() {
        val projected = TelemetryAllowlist.projectEvent(
            eventName = "REQUEST_OUTCOME_AGGREGATE",
            fields = mapOf(
                "errorCode" to "INTERNAL",
                "phase" to "execute",
                "prompt" to "secret user text",
                "unknownField" to "drop",
                "count" to "3",
            ),
        )
        assertNotNull(projected)
        assertTrue(projected!!.containsKey("errorCode"))
        assertTrue(projected.containsKey("count"))
        assertFalse(projected.containsKey("prompt"))
        assertFalse(projected.containsKey("unknownField"))
        assertEquals("REQUEST_OUTCOME_AGGREGATE", projected["eventName"])
        assertEquals(TelemetryAllowlist.SCHEMA_VERSION, projected["schemaVersion"])
    }

    @Test
    fun projectEvent_rejectsUnknownEventName() {
        val projected = TelemetryAllowlist.projectEvent(
            eventName = "NOT_A_REAL_EVENT",
            fields = mapOf("count" to "1"),
        )
        assertNull(projected)
    }

    @Test
    fun nativeLogRedactor_scrubsPathsAndTokens() {
        val line = "load failed path=/data/user/0/com.omnillm/files/x Authorization: Bearer abc.def.ghi"
        val out = NativeLogRedactor.redactLine(line)
        assertFalse(out.contains("/data/user/0"))
        assertFalse(out.contains("abc.def.ghi"))
    }

    @Test
    fun diagnosticAllowlist_stillDeniesNeverExport() {
        assertFalse(
            DiagnosticAllowlist.isAllowed(
                DiagnosticAllowlist.Category.EVENTS_TRACES,
                "prompt",
            ),
        )
        assertTrue(
            DiagnosticAllowlist.isAllowed(
                DiagnosticAllowlist.Category.ERROR_CHAIN,
                "errorCode",
            ),
        )
    }
}
