package com.omnillm.interfaces.http

import com.omnillm.interfaces.http.sse.SseFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseAndClaimTest {

    @Test
    fun claimShape_asyncInference() {
        val ok = AsyncInferenceRequestClaimDto(
            requestId = "11111111-1111-1111-1111-111111111111",
            idempotencyKey = "k1",
            operation = "CHAT",
        )
        assertTrue(ClaimShape.isValidAsyncInferenceClaim(ok))
    }

    @Test
    fun sse_sessionDispositionHeaderConstant() {
        // Documented default for SSE (CORE-INTERFACE §4).
        assertEquals("terminal", SseFraming.EVENT_TERMINAL)
        assertEquals("[DONE]", SseFraming.OPENAI_DONE)
    }

    @Test
    fun multiLineData_framing() {
        val framed = SseFraming.frame(
            SseEvent(data = "line1\nline2", event = "delta", id = "3"),
        )
        assertTrue(framed.contains("id: 3"))
        assertTrue(framed.contains("event: delta"))
        assertTrue(framed.contains("data: line1\n"))
        assertTrue(framed.contains("data: line2\n"))
    }
}
