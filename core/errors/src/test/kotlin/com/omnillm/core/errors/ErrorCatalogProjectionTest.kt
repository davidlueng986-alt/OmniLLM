package com.omnillm.core.errors

import com.omnillm.core.errors.generated.ErrorCategory
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parameterized projection test over **all 25 codes** of
 * `specs/error-catalog.yaml` (schemaVersion 2): httpStatus, category,
 * retryable, requiredClientAction, plus the HTTP projection carried by the
 * generated sealed [OmniError] hierarchy and the [ErrorMapping] boundary
 * helpers (TST-07).
 *
 * The golden-vectors `errorProjection` section (6 codes) is additionally
 * pinned here so the two spec authorities cannot drift apart.
 */
class ErrorCatalogProjectionTest {

    private data class CatalogEntry(
        val code: OmniErrorCode,
        val httpStatus: Int,
        val category: ErrorCategory,
        val retryable: Boolean,
        val requiredClientAction: String,
    )

    /**
     * Transcribed 1:1 from specs/error-catalog.yaml `errors:` (order = yaml).
     * Adding or removing a code here must mirror the yaml; the count guard
     * below (25) fails the suite on drift in either direction.
     */
    private fun catalog(): List<CatalogEntry> = listOf(
        CatalogEntry(OmniErrorCode.INVALID_REQUEST, 400, ErrorCategory.SEMANTIC, false, "correct-request"),
        CatalogEntry(OmniErrorCode.UNAUTHORIZED, 401, ErrorCategory.SEMANTIC, false, "reauthenticate"),
        CatalogEntry(OmniErrorCode.FORBIDDEN, 403, ErrorCategory.SEMANTIC, false, "request-scope-or-complete-local-approval"),
        CatalogEntry(OmniErrorCode.NOT_FOUND, 404, ErrorCategory.SEMANTIC, false, "refresh-resource"),
        CatalogEntry(OmniErrorCode.IDEMPOTENCY_CONFLICT, 409, ErrorCategory.SEMANTIC, false, "query-existing-or-use-a-new-key"),
        CatalogEntry(OmniErrorCode.STATE_CONFLICT, 409, ErrorCategory.SEMANTIC, false, "refresh-state-and-retry-only-if-safe"),
        CatalogEntry(OmniErrorCode.CONTEXT_LIMIT_EXCEEDED, 413, ErrorCategory.SEMANTIC, false, "reduce-context-or-output"),
        CatalogEntry(OmniErrorCode.TRANSPORT_TOO_LARGE, 413, ErrorCategory.TRANSPORT, false, "use-handle-chunk-or-client-preflight"),
        CatalogEntry(OmniErrorCode.RATE_LIMITED, 429, ErrorCategory.SEMANTIC, true, "respect-retry-after"),
        CatalogEntry(OmniErrorCode.CAPABILITY_UNSUPPORTED, 422, ErrorCategory.SEMANTIC, false, "select-a-supported-operation"),
        CatalogEntry(OmniErrorCode.CAPABILITY_UNKNOWN, 503, ErrorCategory.SEMANTIC, true, "qualify-the-cell-or-select-a-known-safe-option"),
        CatalogEntry(OmniErrorCode.ADMISSION_REJECTED, 503, ErrorCategory.SEMANTIC, true, "reduce-resource-requirement-close-resident-work-or-wait"),
        CatalogEntry(OmniErrorCode.MODEL_REVOKED, 410, ErrorCategory.SEMANTIC, false, "select-a-trusted-revision"),
        CatalogEntry(OmniErrorCode.TRUST_PLACEMENT_REQUIRED, 412, ErrorCategory.SEMANTIC, false, "use-the-safe-placement-or-complete-the-explicit-risk-flow"),
        CatalogEntry(OmniErrorCode.PAIRING_REQUIRED, 428, ErrorCategory.SEMANTIC, false, "complete-local-pairing"),
        CatalogEntry(OmniErrorCode.CURSOR_GONE, 410, ErrorCategory.SEMANTIC, false, "load-a-current-snapshot-and-resubscribe"),
        CatalogEntry(OmniErrorCode.ASSET_NOT_READY, 409, ErrorCategory.SEMANTIC, true, "wait-for-ready-or-query-asset"),
        CatalogEntry(OmniErrorCode.ASSET_EXPIRED, 410, ErrorCategory.SEMANTIC, false, "create-a-new-asset"),
        CatalogEntry(OmniErrorCode.DEADLINE_EXCEEDED, 504, ErrorCategory.SEMANTIC, true, "query-status-before-retry"),
        CatalogEntry(OmniErrorCode.CANCELLED, 499, ErrorCategory.SEMANTIC, false, "none"),
        CatalogEntry(OmniErrorCode.WORKER_DIED, 503, ErrorCategory.SEMANTIC, true, "query-and-reconcile"),
        CatalogEntry(OmniErrorCode.ABORTED_UNCERTAIN, 500, ErrorCategory.SEMANTIC, false, "query-diagnostics-and-never-reuse-the-affected-session"),
        CatalogEntry(OmniErrorCode.STREAM_INTERRUPTED, 503, ErrorCategory.SEMANTIC, true, "resume-only-with-an-explicit-protocol-checkpoint-otherwise-start-a-new-session"),
        CatalogEntry(OmniErrorCode.CONTENT_REPORT_UNAVAILABLE, 503, ErrorCategory.SEMANTIC, true, "keep-the-local-encrypted-draft-and-retry-with-user-consent"),
        CatalogEntry(OmniErrorCode.INTERNAL, 500, ErrorCategory.SEMANTIC, false, "collect-redacted-diagnostics"),
    )

    /** specs/golden-vectors/canonical-encoding.yaml `errorProjection` section. */
    private data class GoldenProjection(
        val code: OmniErrorCode,
        val http: Int,
        val retryable: Boolean,
    )

    private fun goldenProjections(): List<GoldenProjection> = listOf(
        GoldenProjection(OmniErrorCode.INVALID_REQUEST, 400, false),
        GoldenProjection(OmniErrorCode.IDEMPOTENCY_CONFLICT, 409, false),
        GoldenProjection(OmniErrorCode.CONTEXT_LIMIT_EXCEEDED, 413, false),
        GoldenProjection(OmniErrorCode.ADMISSION_REJECTED, 503, true),
        GoldenProjection(OmniErrorCode.CURSOR_GONE, 410, false),
        GoldenProjection(OmniErrorCode.CONTENT_REPORT_UNAVAILABLE, 503, true),
    )

    @Test
    fun catalog_hasExactly25Codes_andTableCoversAll() {
        assertEquals("specs/error-catalog.yaml defines exactly 25 codes", 25, catalog().size)
        assertEquals(25, OmniErrorCode.entries.size)
        val tableCodes = catalog().map { it.code }.toSet()
        for (code in OmniErrorCode.entries) {
            assertTrue("catalog table missing ${code.code}", code in tableCodes)
        }
        for (entry in catalog()) {
            assertTrue("enum missing ${entry.code.code}", entry.code in OmniErrorCode.entries)
        }
    }

    @Test
    fun everyCode_pinsHttpStatus_category_retryable_requiredClientAction() {
        for (entry in catalog()) {
            assertEquals("${entry.code.code} httpStatus", entry.httpStatus, entry.code.httpStatus)
            assertEquals("${entry.code.code} category", entry.category, entry.code.category)
            assertEquals("${entry.code.code} retryable", entry.retryable, entry.code.retryable)
            assertEquals(
                "${entry.code.code} requiredClientAction",
                entry.requiredClientAction,
                entry.code.requiredClientAction,
            )
        }
    }

    @Test
    fun everyCode_httpProjectionViaSealedOmniError() {
        for (entry in catalog()) {
            val err = OmniError.of(entry.code)
            assertEquals("${entry.code.code} err.code", entry.code, err.code)
            assertEquals("${entry.code.code} err.httpStatus", entry.httpStatus, err.httpStatus)
            assertEquals("${entry.code.code} err.retryable", entry.retryable, err.retryable)
            assertEquals(
                "${entry.code.code} err.requiredClientAction",
                entry.requiredClientAction,
                err.requiredClientAction,
            )
            assertEquals("${entry.code.code} err.category", entry.category, err.category)
        }
    }

    @Test
    fun everyCode_roundTripsThroughErrorMapping() {
        for (entry in catalog()) {
            assertEquals(
                "${entry.code.code} mapping.httpStatus",
                entry.httpStatus,
                ErrorMapping.httpStatus(entry.code.code),
            )
            assertEquals(
                "${entry.code.code} mapping.retryable",
                entry.retryable,
                ErrorMapping.isRetryable(entry.code.code),
            )
            assertEquals(
                "${entry.code.code} mapping.requiredClientAction",
                entry.requiredClientAction,
                ErrorMapping.requiredClientAction(entry.code.code),
            )
            assertEquals(entry.code, ErrorMapping.fromCode(entry.code.code).code)
            assertEquals(entry.code, ErrorMapping.fromCode(entry.code).code)
        }
        assertEquals(25, ErrorMapping.allCodes().size)
    }

    @Test
    fun goldenErrorProjection_sectionStillMatchesCatalogTable() {
        for (g in goldenProjections()) {
            val entry = catalog().first { it.code == g.code }
            assertEquals("golden ${g.code} http", g.http, entry.httpStatus)
            assertEquals("golden ${g.code} retryable", g.retryable, entry.retryable)
        }
        // Every golden projection code must be part of the full catalog table.
        assertEquals(goldenProjections().size, 6)
    }

    @Test
    fun retryableCodes_areExactlyTheCatalogMarkedOnes() {
        val retryable = catalog().filter { it.retryable }.map { it.code.code }.toSortedSet()
        assertEquals(
            sortedSetOf(
                "RATE_LIMITED",
                "CAPABILITY_UNKNOWN",
                "ADMISSION_REJECTED",
                "ASSET_NOT_READY",
                "DEADLINE_EXCEEDED",
                "WORKER_DIED",
                "STREAM_INTERRUPTED",
                "CONTENT_REPORT_UNAVAILABLE",
            ),
            retryable,
        )
        for (code in OmniErrorCode.entries) {
            if (code.retryable) {
                assertTrue("${code.code} must be marked retryable in the table", retryable.contains(code.code))
            } else {
                assertFalse("${code.code} must not be retryable", retryable.contains(code.code))
            }
        }
    }
}
