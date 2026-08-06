package com.omnillm.runtime.policy.input

import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTreeBudgetTest {

    private val tight = JsonParseLimits(
        maxCompressedBytes = 100,
        maxDecompressedBytes = 200,
        maxParseTimeMs = 5_000,
        maxNestingDepth = 3,
        maxNodeCount = 20,
        maxArrayLength = 5,
        maxObjectKeys = 4,
        maxStringBytes = 16,
        maxKeyBytes = 8,
    )

    @Test
    fun admitRawSize_rejectsOversize() {
        val r = JsonTreeBudget.admitRawSize(
            compressedBytes = 50,
            decompressedBytes = 500,
            limits = tight,
        )
        assertTrue(r is JsonTreeBudget.Outcome.Rejected)
        val rej = r as JsonTreeBudget.Outcome.Rejected
        assertEquals(OmniErrorCode.TRANSPORT_TOO_LARGE, rej.error.code)
    }

    @Test
    fun admitTree_acceptsShallowObject() {
        val tree = mapOf("a" to 1, "b" to listOf(true, null))
        val r = JsonTreeBudget.admitTree(tree, tight)
        assertTrue(r is JsonTreeBudget.Outcome.Accepted)
        val ok = r as JsonTreeBudget.Outcome.Accepted
        assertTrue(ok.nodeCount >= 4)
        assertTrue(ok.maxDepthSeen >= 1)
    }

    @Test
    fun admitTree_rejectsDeepNesting() {
        var node: Any = "leaf"
        repeat(6) { node = mapOf("n" to node) }
        val r = JsonTreeBudget.admitTree(node, tight)
        assertTrue(r is JsonTreeBudget.Outcome.Rejected)
        assertEquals(
            OmniErrorCode.TRANSPORT_TOO_LARGE,
            (r as JsonTreeBudget.Outcome.Rejected).error.code,
        )
    }

    @Test
    fun admitTree_rejectsLongString() {
        val r = JsonTreeBudget.admitTree(mapOf("k" to "x".repeat(64)), tight)
        assertTrue(r is JsonTreeBudget.Outcome.Rejected)
    }

    @Test
    fun admitTree_rejectsTooManyNodes() {
        val big = (0 until 30).associate { "k$it" to it }
        // maxObjectKeys is 4 — hits key count first
        val r = JsonTreeBudget.admitTree(big, tight)
        assertTrue(r is JsonTreeBudget.Outcome.Rejected)
    }

    @Test
    fun projectIgnoredParams_capsListAndDigest() {
        val names = (0 until 40).map { "param$it" }
        val proj = JsonTreeBudget.projectIgnoredParams(
            names,
            HeaderLimits(maxIgnoredParamsListed = 5, maxIgnoredParamsListBytes = 10_000),
        )
        assertEquals(5, proj.listedNames.size)
        assertEquals(35, proj.omittedCount)
        assertEquals(40, proj.totalCount)
        assertTrue(proj.totalDigestHex != null && proj.totalDigestHex!!.length == 64)
    }

    @Test
    fun admitHeaders_rejectsTotalOverflow() {
        val headers = listOf(
            "Authorization" to "Bearer " + "a".repeat(40),
            "X-Extra" to "b".repeat(40),
        )
        val r = JsonTreeBudget.admitHeaders(
            headers,
            HeaderLimits(maxTotalHeaderBytes = 80, maxSingleHeaderBytes = 80, maxHeaderCount = 10),
        )
        assertTrue(r is JsonTreeBudget.Outcome.Rejected)
    }
}
