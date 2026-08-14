package com.omnillm.android.runtimeservice.binder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * D23f regression: the admin playground chat canonical input digest must
 * cover the FULL message content. The old implementation hashed
 * `message.length`, so two same-length messages with different content
 * produced the SAME digest — an idempotent replay could silently substitute
 * content (COR-13 applies to the admin facade too).
 */
class AdminChatDigestTest {

    @Test
    fun sameLengthDifferentContent_yieldsDifferentDigests() {
        val reqId = "req-1"
        val idem = "idem-1"
        val model = "model-1"
        // RED evidence: the OLD length-based formula collides for any
        // same-length content — the bug this test protects against.
        val oldA = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
            "admin-playground-chat|$reqId|$idem|$model|${"AAAA".length}",
        )
        val oldB = com.omnillm.core.canonical.IdentityHashing.sha256Hex(
            "admin-playground-chat|$reqId|$idem|$model|${"BBBB".length}",
        )
        assertEquals("(RED premise) the old length-only digest collides", oldA, oldB)

        val a = AdminChatDigest.digest(reqId, idem, model, "AAAA")
        val b = AdminChatDigest.digest(reqId, idem, model, "BBBB")
        assertNotEquals(
            "same-length different content must NOT collide (length-only digest bug)",
            a,
            b,
        )
    }

    @Test
    fun identicalInput_yieldsSameDigest() {
        val a = AdminChatDigest.digest("req-1", "idem-1", "model-1", "hello world")
        val b = AdminChatDigest.digest("req-1", "idem-1", "model-1", "hello world")
        assertEquals(a, b)
    }

    @Test
    fun fullContentIncluded_notJustLength() {
        // A 12-char message vs a 12-char message must differ; also the digest
        // must differ from the length-only form (i.e. content contributes).
        val short = AdminChatDigest.digest("req-1", "idem-1", "model-1", "x".repeat(12))
        val same = AdminChatDigest.digest("req-1", "idem-1", "model-1", "y".repeat(12))
        assertNotEquals(short, same)
    }

    @Test
    fun identityFieldsContribute() {
        val withReqA = AdminChatDigest.digest("req-a", "idem-1", "model-1", "content")
        val withReqB = AdminChatDigest.digest("req-b", "idem-1", "model-1", "content")
        assertNotEquals(withReqA, withReqB)
    }
}
