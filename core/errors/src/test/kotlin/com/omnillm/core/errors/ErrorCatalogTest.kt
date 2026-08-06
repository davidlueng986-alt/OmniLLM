package com.omnillm.core.errors

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorCatalogTest {

    @Test
    fun goldenErrorProjection_httpAndRetryable() {
        // specs/golden-vectors/canonical-encoding.yaml errorProjection
        assertEquals(400, OmniErrorCode.INVALID_REQUEST.httpStatus)
        assertFalse(OmniErrorCode.INVALID_REQUEST.retryable)

        assertEquals(409, OmniErrorCode.IDEMPOTENCY_CONFLICT.httpStatus)
        assertFalse(OmniErrorCode.IDEMPOTENCY_CONFLICT.retryable)

        assertEquals(413, OmniErrorCode.CONTEXT_LIMIT_EXCEEDED.httpStatus)
        assertFalse(OmniErrorCode.CONTEXT_LIMIT_EXCEEDED.retryable)

        assertEquals(503, OmniErrorCode.ADMISSION_REJECTED.httpStatus)
        assertTrue(OmniErrorCode.ADMISSION_REJECTED.retryable)

        assertEquals(410, OmniErrorCode.CURSOR_GONE.httpStatus)
        assertFalse(OmniErrorCode.CURSOR_GONE.retryable)

        assertEquals(503, OmniErrorCode.CONTENT_REPORT_UNAVAILABLE.httpStatus)
        assertTrue(OmniErrorCode.CONTENT_REPORT_UNAVAILABLE.retryable)
    }

    @Test
    fun sealedErrorsCarryCodeMetadata() {
        val err: OmniError = OmniError.ADMISSION_REJECTED(message = "busy")
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, err.code)
        assertEquals(503, err.httpStatus)
        assertTrue(err.retryable)
        assertEquals("busy", err.message)
    }

    @Test
    fun unknownCodeFailsClosed() {
        var threw = false
        try {
            OmniErrorCode.requireFromCode("NOT_IN_CATALOG")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun ofFactoryCoversAllCodes() {
        for (code in OmniErrorCode.entries) {
            val err = OmniError.of(code)
            assertEquals(code, err.code)
        }
    }

    @Test
    fun errorMapping_fromCodeAndRetryability() {
        val err = ErrorMapping.fromCode("ADMISSION_REJECTED", message = "busy")
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, err.code)
        assertTrue(ErrorMapping.isRetryable("ADMISSION_REJECTED"))
        assertFalse(ErrorMapping.isRetryable(OmniErrorCode.INVALID_REQUEST))
        assertEquals(503, ErrorMapping.httpStatus("ADMISSION_REJECTED"))
        assertEquals(
            "reduce-resource-requirement-close-resident-work-or-wait",
            ErrorMapping.requiredClientAction("ADMISSION_REJECTED"),
        )
        assertEquals(OmniErrorCode.entries.size, ErrorMapping.allCodes().size)
    }

    @Test
    fun transportDeliveryGuarantees_documentedAndDifferByTransport() {
        // INV-013: delivery is explicit; not implied from error code alone.
        assertFalse(TransportDeliveryGuarantee.HTTP_SSE.establishesClientDeliveredCheckpoint)
        assertFalse(TransportDeliveryGuarantee.HTTP_JSON.establishesClientDeliveredCheckpoint)
        assertTrue(TransportDeliveryGuarantee.AIDL_STREAM.establishesClientDeliveredCheckpoint)
        assertFalse(TransportDeliveryGuarantee.AIDL_UNARY.establishesClientDeliveredCheckpoint)
        assertFalse(TransportDeliveryGuarantee.ADMIN_BINDER.establishesClientDeliveredCheckpoint)

        for (g in TransportDeliveryGuarantee.entries) {
            assertTrue(g.supportsQueryAfterReplyLoss)
            assertTrue(g.notes.isNotBlank())
            assertEquals(g, TransportDeliveryGuarantee.forLabel(g.label))
        }

        // streamRule parity with generated OmniError.STREAM_RULE (error-catalog authority).
        assertTrue(TransportDeliveryGuarantee.STREAM_RULE.contains("terminal error event"))
        assertTrue(OmniError.STREAM_RULE.contains("terminal error event"))
    }

    @Test
    fun errorMapping_withTransportDelivery_annotatesDetailsWithoutChangingCode() {
        val base = OmniError.STREAM_INTERRUPTED(message = "socket dropped")
        val annotated = ErrorMapping.withTransportDelivery(
            base,
            TransportDeliveryGuarantee.HTTP_SSE,
        )
        assertEquals(OmniErrorCode.STREAM_INTERRUPTED, annotated.code)
        assertEquals(
            TransportDeliveryGuarantee.HTTP_SSE.label,
            annotated.details[TransportDeliveryGuarantee.DETAIL_KEY],
        )
        assertTrue(
            annotated.details[TransportDeliveryGuarantee.DETAIL_NOTES_KEY]!!
                .contains("Socket write"),
        )
        assertEquals(
            TransportDeliveryGuarantee.HTTP_SSE,
            ErrorMapping.transportDeliveryOf(annotated),
        )
    }

    @Test
    fun unknownTransportLabel_failClosed() {
        var threw = false
        try {
            TransportDeliveryGuarantee.forLabel("NOT_A_TRANSPORT")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
