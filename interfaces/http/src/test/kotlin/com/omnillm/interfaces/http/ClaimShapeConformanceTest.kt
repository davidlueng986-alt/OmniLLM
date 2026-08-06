package com.omnillm.interfaces.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract conformance placeholders for HTTP request/command idempotency claim shape.
 *
 * Authority:
 * - specs/openapi/omnillm.openapi.yaml `#/components/schemas/CommandRequest`
 * - specs/openapi/omnillm.openapi.yaml `#/components/schemas/AsyncInferenceRequest`
 * - specs/canonical-types.yaml `#CommandRequest`
 * - specs/command-conformance-fixtures.yaml
 *
 * TODO(runtime): wire claim-or-return against `:runtime:request-registry` and durable
 * fixtures (reply-loss → queryCommand, digest mismatch → IDEMPOTENCY_CONFLICT).
 */
class ClaimShapeConformanceTest {

    private val digest64 = "a".repeat(64)
    private val uuid = "11111111-1111-1111-1111-111111111111"

    @Test
    fun openApiDocument_isPackagedOnClasspath() {
        OpenApiAuthority.requirePackagedDocument().use { stream ->
            assertTrue(stream.readBytes().isNotEmpty())
        }
    }

    @Test
    fun commandRequest_requiresClientGeneratedIdsAndDigest() {
        val valid = CommandRequestDto(
            commandId = uuid,
            idempotencyKey = "cmd-key-1",
            canonicalInputDigest = digest64,
            expectedVersion = null,
        )
        assertTrue(ClaimShape.isValidCommandRequest(valid))
    }

    @Test
    fun commandRequest_rejectsInvalidDigestOrBlankKey() {
        assertFalse(
            ClaimShape.isValidCommandRequest(
                CommandRequestDto(uuid, "k", "not-a-sha256", null),
            ),
        )
        assertFalse(
            ClaimShape.isValidCommandRequest(
                CommandRequestDto(uuid, "", digest64, null),
            ),
        )
        assertFalse(
            ClaimShape.isValidCommandRequest(
                CommandRequestDto("not-uuid", "k", digest64, null),
            ),
        )
    }

    @Test
    fun asyncInferenceClaim_requiresRequestIdAndIdempotencyKey() {
        val valid = AsyncInferenceRequestClaimDto(
            requestId = uuid,
            idempotencyKey = "req-key-1",
            operation = "CHAT",
        )
        assertTrue(ClaimShape.isValidAsyncInferenceClaim(valid))
        assertFalse(
            ClaimShape.isValidAsyncInferenceClaim(
                valid.copy(operation = "UNKNOWN"),
            ),
        )
    }

    @Test
    fun operationIdInventory_coversCreateAsyncAndGetCommand() {
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("createAsyncInferenceRequest"))
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("getCommand"))
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("getRequest"))
        assertTrue(OpenApiPaths.OPERATION_IDS.contains("cancelRequest"))
        assertNotNull(OpenApiPaths.OMNI_REQUESTS)
    }

    @Test
    fun fixturePlaceholder_replyLossThenQuery() {
        // Placeholder: after durable accept, reply loss must query getRequest/getCommand
        // rather than replaying create with a new commandId (ADR-004/005).
        val accepted = AcceptedRequestDto(
            requestId = uuid,
            state = "RECEIVED",
            queryUrl = "/omni/v1/requests/$uuid",
        )
        assertTrue(accepted.queryUrl!!.contains(uuid))
    }

    @Test
    fun fixturePlaceholder_duplicateIdenticalClaimExecutesOnce() {
        // Placeholder for command-conformance-fixtures durableMutations semantics.
        val a = CommandRequestDto(uuid, "idem-1", digest64, null)
        val b = a.copy()
        assertTrue(a == b)
        assertTrue(ClaimShape.isValidCommandRequest(a))
    }
}
