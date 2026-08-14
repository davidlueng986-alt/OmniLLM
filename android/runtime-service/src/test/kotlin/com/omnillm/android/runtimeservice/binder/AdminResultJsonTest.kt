package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.RequestStripUi
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.playground.projection.UiSeverity
import com.omnillm.features.server.api.SmokeTestResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D18 regression: admin result JSON must be built with kotlinx.serialization
 * (complete escaping) — the old facade concatenated raw strings with
 * hand-rolled replace() escaping that broke on quotes / backslashes /
 * control characters.
 */
class AdminResultJsonTest {

    private fun strip(
        assistantText: String?,
        error: OmniError? = null,
        cancelPhase: CancelPhase? = null,
        degraded: Boolean = false,
    ): RequestStripUi = RequestStripUi(
        requestId = "req-1",
        operationKind = "CHAT",
        state = "COMPLETED",
        labelKey = "state.completed",
        severity = UiSeverity.INFO,
        isTerminal = true,
        cancelPhase = cancelPhase,
        allowedActions = emptyList(),
        actualModelRevisionId = "a".repeat(64),
        engineBuildId = "engine-1",
        backend = "cpu",
        sessionId = null,
        degraded = degraded,
        degradedReasons = emptyList(),
        metrics = emptyList(),
        error = error,
        assistantText = assistantText,
        embeddingDimensions = null,
        embeddingCount = null,
    )

    @Test
    fun playgroundStrip_quotesBackslashesControlChars_roundTrip() {
        val nasty = "say \"hi\" and C:\\path\\file, line1\nline2\ttab \u0000nul"
        val err = OmniError.INTERNAL(message = "engine said \"boom\"\\here")
        val json = AdminResultJson.playgroundStrip(strip(assistantText = nasty, error = err))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals(
            "assistantText must round-trip unescaped content exactly",
            nasty,
            obj["assistantText"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "errorMessage must round-trip (and NOT be quote-mutated)",
            err.message,
            obj["errorMessage"]?.jsonPrimitive?.content,
        )
        assertEquals(err.code.code, obj["errorCode"]?.jsonPrimitive?.content)
    }

    @Test
    fun playgroundStrip_nullableFields_areJsonNull() {
        val json = AdminResultJson.playgroundStrip(strip(assistantText = null))
        val obj = Json.parseToJsonElement(json).jsonObject
        assertTrue(obj.containsKey("assistantText"))
        assertNull(obj["assistantText"]?.jsonPrimitive?.contentOrNull)
        assertFalse("degraded stays a real boolean", json.contains("\"degraded\":null"))
    }

    @Test
    fun serverSmoke_errorWithQuotes_roundTrips() {
        val result = SmokeTestResult(
            step = "infer",
            success = false,
            requestId = "smoke-1",
            requestState = "FAILED",
            error = OmniError.RATE_LIMITED(message = "slow \"down\"\\please"),
            completedAtEpochMs = 1L,
            actualModelRevisionId = null,
            actualEngineBuildId = "eng-2",
            actualBackend = "npu",
        )
        val json = AdminResultJson.serverSmoke(result, fallbackRequestId = "fallback")
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("slow \"down\"\\please", obj["errorMessage"]?.jsonPrimitive?.content)
        assertEquals("smoke-1", obj["requestId"]?.jsonPrimitive?.content)
        assertEquals("RATE_LIMITED", obj["errorCode"]?.jsonPrimitive?.content)
        assertTrue(obj.containsKey("modelRevisionId"))
        assertNull(obj["modelRevisionId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun serverSmoke_missingRequestId_fallsBack() {
        val result = SmokeTestResult(
            step = "probe",
            success = true,
            requestId = null,
            requestState = null,
            error = null,
            completedAtEpochMs = 1L,
        )
        val json = AdminResultJson.serverSmoke(result, fallbackRequestId = "fallback-req")
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("fallback-req", obj["requestId"]?.jsonPrimitive?.content)
        assertNull(obj["errorCode"]?.jsonPrimitive?.contentOrNull)
        assertNull(obj["errorMessage"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun playgroundCancel_roundTrips() {
        val json = AdminResultJson.playgroundCancel(
            requestId = "req-cancel",
            phase = CancelPhase.ACKNOWLEDGED,
            requestState = "RUNNING",
            isTerminal = false,
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("req-cancel", obj["requestId"]?.jsonPrimitive?.content)
        assertEquals("ACKNOWLEDGED", obj["phase"]?.jsonPrimitive?.content)
        assertEquals("RUNNING", obj["requestState"]?.jsonPrimitive?.content)
        assertTrue(obj["isTerminal"]?.jsonPrimitive?.content == "false")
    }
}
