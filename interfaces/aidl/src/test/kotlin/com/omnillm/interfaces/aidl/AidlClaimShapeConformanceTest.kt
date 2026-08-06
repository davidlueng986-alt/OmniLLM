package com.omnillm.interfaces.aidl

import ai.omnillm.api.AidlClaimShape
import ai.omnillm.api.OmniChatRequestClaimMarker
import ai.omnillm.api.OmniCommandRequestMarker
import ai.omnillm.api.OmniEmbeddingRequestClaimMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract conformance placeholders for AIDL request/command claim shape.
 *
 * Authority: specs/aidl/omnillm-aidl.yaml (`OmniCommandRequest`, `OmniChatRequest`,
 * `OmniEmbeddingRequest`, `queryRequest` / `queryCommand` on IOmniRuntime).
 *
 * TODO(runtime): instrument Binder claim-or-return with same principal/operationKind
 * rules as HTTP; oneway stream callbacks still require query after reply loss.
 */
class AidlClaimShapeConformanceTest {

    private val digest64 = "b".repeat(64)
    private val uuid = "22222222-2222-2222-2222-222222222222"

    @Test
    fun declarationInventory_includesRuntimeAndCommandClaim() {
        assertTrue(AidlAuthority.DECLARATION_NAMES.contains("OmniCommandRequest"))
        assertTrue(AidlAuthority.DECLARATION_NAMES.contains("IOmniRuntime"))
        assertTrue(AidlAuthority.RUNTIME_INTERFACES.contains("IOmniRuntime"))
        assertEquals(2, AidlAuthority.SCHEMA_VERSION)
    }

    @Test
    fun aidlProjectionFiles_existForCommandRequest() {
        // When tests run from module dir, aidl sources are on disk relative to project.
        val candidates = listOf(
            File("src/main/aidl/ai/omnillm/api/OmniCommandRequest.aidl"),
            File("interfaces/aidl/src/main/aidl/ai/omnillm/api/OmniCommandRequest.aidl"),
        )
        assertTrue(
            "OmniCommandRequest.aidl projection missing",
            candidates.any { it.isFile },
        )
    }

    @Test
    fun commandRequestMarker_matchesClaimShape() {
        val marker = OmniCommandRequestMarker(
            commandId = uuid,
            idempotencyKey = "aidl-cmd-1",
            hasExpectedVersion = false,
            expectedVersion = 0L,
            canonicalInputDigest = digest64,
        )
        assertTrue(AidlClaimShape.isValidCommandRequest(marker))
        assertFalse(
            AidlClaimShape.isValidCommandRequest(
                marker.copy(canonicalInputDigest = "short"),
            ),
        )
    }

    @Test
    fun chatAndEmbeddingClaims_requireClientGeneratedRequestId() {
        assertTrue(
            AidlClaimShape.isValidChatClaim(
                OmniChatRequestClaimMarker(uuid, "chat-1", "model-a", stream = true),
            ),
        )
        assertTrue(
            AidlClaimShape.isValidEmbeddingClaim(
                OmniEmbeddingRequestClaimMarker(uuid, "emb-1", "model-a"),
            ),
        )
        assertFalse(
            AidlClaimShape.isValidChatClaim(
                OmniChatRequestClaimMarker("bad", "chat-1", "model-a", stream = false),
            ),
        )
    }

    @Test
    fun fixturePlaceholder_queryRequestAfterReplyLoss() {
        // Non-stream embedding still uses queryRequest/cancelRequest (CORE-INTERFACE).
        assertTrue(AidlAuthority.DECLARATION_NAMES.contains("OmniRequestState"))
        assertTrue(AidlAuthority.DECLARATION_NAMES.contains("IStreamSession"))
    }
}
