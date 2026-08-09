package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.OmniChatMessage
import ai.omnillm.api.OmniChatRequest
import ai.omnillm.api.OmniEmbeddingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * COR-13 regression: canonical digests hash FULL content — two requests with
 * identical lengths but different content must produce different digests so
 * replay with substituted content maps to IDEMPOTENCY_CONFLICT instead of
 * silently succeeding.
 */
class OmniRuntimeFacadeDigestTest {

    private fun chatRequest(
        requestId: String,
        role: String = "user",
        content: String,
        assetIds: List<String> = emptyList(),
    ): OmniChatRequest = OmniChatRequest().apply {
        this.requestId = requestId
        idempotencyKey = "idem-$requestId"
        model = "m".repeat(64)
        messages = arrayOf(
            OmniChatMessage().apply {
                this.role = role
                this.content = content
                this.assetIds = assetIds.toTypedArray()
            },
        )
        stream = true
    }

    @Test
    fun chatDigest_sameLengthDifferentContent_differs() {
        val a = OmniRuntimeFacade.chatDigest(chatRequest("req-a", content = "aaaa"))
        val b = OmniRuntimeFacade.chatDigest(chatRequest("req-b", content = "bbbb"))
        assertNotEquals(
            "same-length different content must produce different digests (COR-13)",
            a.hex,
            b.hex,
        )
    }

    @Test
    fun chatDigest_sameLengthDifferentAssetIds_differs() {
        val a = OmniRuntimeFacade.chatDigest(
            chatRequest("req-a", content = "same-length", assetIds = listOf("asset-1")),
        )
        val b = OmniRuntimeFacade.chatDigest(
            chatRequest("req-b", content = "same-length", assetIds = listOf("asset-2")),
        )
        assertNotEquals(
            "same-length different assetIds must differ (COR-13)",
            a.hex,
            b.hex,
        )
    }

    @Test
    fun chatDigest_identicalRequest_isStable() {
        val a = OmniRuntimeFacade.chatDigest(chatRequest("req-a", content = "hello"))
        val b = OmniRuntimeFacade.chatDigest(chatRequest("req-a", content = "hello"))
        assertEquals("identical requests must hash identically", a.hex, b.hex)
    }

    @Test
    fun chatDigest_roleMismatch_differs() {
        val a = OmniRuntimeFacade.chatDigest(chatRequest("req-a", role = "user", content = "x"))
        val b = OmniRuntimeFacade.chatDigest(chatRequest("req-b", role = "system", content = "x"))
        assertNotEquals(a.hex, b.hex)
    }

    @Test
    fun embedDigest_sameCountDifferentInputs_differs() {
        val a = OmniEmbeddingRequest().apply {
            requestId = "e1"
            model = "m".repeat(64)
            inputs = arrayOf("alpha", "beta")
        }
        val b = OmniEmbeddingRequest().apply {
            requestId = "e2"
            model = "m".repeat(64)
            inputs = arrayOf("ALPHA", "beta") // same length, different bytes
        }
        assertNotEquals(
            "embedding inputs of equal length but different content must differ (COR-13)",
            OmniRuntimeFacade.embedDigest(a).hex,
            OmniRuntimeFacade.embedDigest(b).hex,
        )
    }

    @Test
    fun embedDigest_identicalInputs_stable() {
        val a = OmniEmbeddingRequest().apply {
            requestId = "e3"
            model = "m".repeat(64)
            inputs = arrayOf("one", "two")
        }
        val b = OmniEmbeddingRequest().apply {
            requestId = "e3"
            model = "m".repeat(64)
            inputs = arrayOf("one", "two")
        }
        assertEquals(
            OmniRuntimeFacade.embedDigest(a).hex,
            OmniRuntimeFacade.embedDigest(b).hex,
        )
    }
}
