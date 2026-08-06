package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.binder.AdminAidlMapper
import com.omnillm.android.runtimeservice.binder.BinderErrors
import com.omnillm.core.errors.ErrorMapping
import com.omnillm.core.errors.TransportDeliveryGuarantee
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.admin.AdminCommandResult
import com.omnillm.interfaces.http.OmniErrorHttp
import com.omnillm.interfaces.http.OpenApiPaths
import com.omnillm.runtime.session.DeliverySemantics
import com.omnillm.runtime.session.establishesClientDeliveredCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-013 / ADR-011 — HTTP, AIDL, and Admin must project the **same** canonical
 * error (and delivery guarantees must be explicit and different).
 *
 * Fail closed: if transport adapters invent private codes or flip delivery
 * semantics, this test fails.
 */
class TransportParityTest {

    private val sampleCodes = listOf(
        OmniErrorCode.INVALID_REQUEST,
        OmniErrorCode.UNAUTHORIZED,
        OmniErrorCode.FORBIDDEN,
        OmniErrorCode.NOT_FOUND,
        OmniErrorCode.IDEMPOTENCY_CONFLICT,
        OmniErrorCode.STATE_CONFLICT,
        OmniErrorCode.ADMISSION_REJECTED,
        OmniErrorCode.CAPABILITY_UNSUPPORTED,
        OmniErrorCode.DEADLINE_EXCEEDED,
        OmniErrorCode.INTERNAL,
    )

    @Test
    fun errorCode_httpAndAidlAndAdmin_shareCanonicalCatalogCode() {
        for (code in sampleCodes) {
            val domain = OmniError.of(code, message = "parity-${code.code}", details = mapOf("k" to "v"))

            // HTTP projection
            val http = OmniErrorHttp.toDto(domain)
            assertEquals(code.code, http.code)
            assertEquals(domain.retryable, http.retryable)

            // AIDL projection (Admin mapper is the binder path for domain OmniError)
            val aidl = AdminAidlMapper.toAidlError(domain)
            assertEquals(code.code, aidl.code)
            assertEquals(domain.retryable, aidl.retryable)
            assertEquals(domain.message, aidl.message)

            // Admin projection embeds the same domain error on CommandResult
            val admin = AdminCommandResult.failed("00000000-0000-0000-0000-000000000001", domain)
            assertEquals(code, admin.error!!.code)
            assertEquals(code.code, admin.error!!.code.code)

            // Cross-transport identity of the wire code string
            assertEquals(http.code, aidl.code)
            assertEquals(http.code, admin.error!!.code.code)
        }
    }

    @Test
    fun deliveryGuarantees_areExplicitAndDifferByTransport() {
        // HTTP SSE: socket write ≠ client-delivered checkpoint
        assertFalse(
            DeliverySemantics.SseStatelessByDefault.establishesClientDeliveredCheckpoint(),
        )
        assertEquals(
            TransportDeliveryGuarantees.HTTP_SSE,
            TransportDeliveryGuarantees.forTransport("HTTP_SSE"),
        )
        assertFalse(TransportDeliveryGuarantees.HTTP_SSE.establishesClientDeliveredCheckpoint)

        // AIDL: only application ACK advances delivered checkpoint
        assertTrue(
            DeliverySemantics.AidlApplicationAck(streamEpoch = 1L, seqToExclusive = 2L)
                .establishesClientDeliveredCheckpoint(),
        )
        assertTrue(TransportDeliveryGuarantees.AIDL_STREAM.establishesClientDeliveredCheckpoint)

        // Admin binder: non-stream durable commands use claim-or-return + query
        assertFalse(TransportDeliveryGuarantees.ADMIN_BINDER.establishesClientDeliveredCheckpoint)
        assertTrue(TransportDeliveryGuarantees.ADMIN_BINDER.supportsQueryAfterReplyLoss)

        // core:errors catalog is the authority; runtime-service mirrors 1:1.
        for (core in TransportDeliveryGuarantee.entries) {
            val local = TransportDeliveryGuarantees.fromCore(core)
            assertEquals(core.label, local.label)
            assertEquals(
                core.establishesClientDeliveredCheckpoint,
                local.establishesClientDeliveredCheckpoint,
            )
        }
    }

    @Test
    fun errorProjection_carriesTransportDeliveryDetails() {
        val domain = OmniError.STREAM_INTERRUPTED(message = "mid-stream drop")
        val annotated = ErrorMapping.withTransportDelivery(
            domain,
            TransportDeliveryGuarantee.AIDL_STREAM,
        )
        val http = OmniErrorHttp.toDto(annotated)
        val deliveryLabel = http.details[TransportDeliveryGuarantee.DETAIL_KEY]
            ?.let { el ->
                (el as? kotlinx.serialization.json.JsonPrimitive)?.content
                    ?: el.toString().trim('"')
            }
        assertEquals(TransportDeliveryGuarantee.AIDL_STREAM.label, deliveryLabel)

        val aidl = BinderErrors.omniError(
            OmniErrorCode.STREAM_INTERRUPTED,
            "mid-stream drop",
            transport = TransportDeliveryGuarantee.AIDL_STREAM,
        )
        assertEquals(OmniErrorCode.STREAM_INTERRUPTED.code, aidl.code)
        assertTrue(
            aidl.details.any {
                it.key == TransportDeliveryGuarantee.DETAIL_KEY &&
                    it.stringValue == TransportDeliveryGuarantee.AIDL_STREAM.label
            },
        )
    }

    @Test
    fun launchCriticalOpenApiOps_areInventoried() {
        // CORE-INTERFACE launch surface — must remain in OpenApiPaths.
        val required = setOf(
            "getHealth",
            "listModels",
            "createChatCompletion",
            "createEmbedding",
            "getRequest",
            "cancelRequest",
            "getCommand",
            "createAsset",
            "getAsset",
            "createJob",
            "getJob",
            "cancelJob",
            "getMetricSummary",
            "createDiagnosticExport",
            "createContentReportProposal",
            "completeLanPairing",
        )
        assertTrue(OpenApiPaths.OPERATION_IDS.containsAll(required))
        // Admin snapshot is AIDL-only (IOmniAdmin.getSnapshot) — not an OpenAPI path.
        assertFalse(OpenApiPaths.OPERATION_IDS.contains("getAdminSnapshot"))
    }

    @Test
    fun unknownTransportLabel_failClosed() {
        try {
            TransportDeliveryGuarantees.forTransport("UNKNOWN_TRANSPORT")
            assertFalse("expected fail-closed on unknown transport", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fail closed") || e.message!!.contains("unknown"))
        }
    }
}
