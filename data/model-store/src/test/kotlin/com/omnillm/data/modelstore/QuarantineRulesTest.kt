package com.omnillm.data.modelstore

import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineRulesTest {

    private val rules = QuarantineRules(
        bounds = MaterializeBounds(
            maxFileBytes = 1_000,
            maxTotalBytes = 2_000,
            maxFileCount = 3,
            maxReadTimeMs = 1_000,
        ),
    )

    @Test
    fun admitDeclared_acceptsValid() {
        val declared = listOf(
            QuarantineDeclaredEntry(
                role = "weights",
                expectedByteLength = 500,
                expectedSha256Hex = "a".repeat(64),
            ),
            QuarantineDeclaredEntry(
                role = "tokenizer",
                expectedByteLength = 100,
                expectedSha256Hex = "b".repeat(64),
            ),
        )
        val r = QuarantineRuleEngine.admitDeclared(declared, rules)
        assertTrue(r is QuarantineRuleEngine.Outcome.Accepted)
        assertEquals(2, (r as QuarantineRuleEngine.Outcome.Accepted).declaredCount)
    }

    @Test
    fun admitDeclared_rejectsTraversalAndOversize() {
        val badPath = QuarantineRuleEngine.admitDeclared(
            listOf(
                QuarantineDeclaredEntry(
                    role = "weights",
                    relativePath = "../escape",
                    expectedByteLength = 10,
                ),
            ),
            rules,
        )
        assertTrue(badPath is QuarantineRuleEngine.Outcome.Rejected)

        val oversize = QuarantineRuleEngine.admitDeclared(
            listOf(
                QuarantineDeclaredEntry(role = "weights", expectedByteLength = 5_000),
            ),
            rules,
        )
        assertTrue(oversize is QuarantineRuleEngine.Outcome.Rejected)
        assertEquals(
            OmniErrorCode.TRANSPORT_TOO_LARGE,
            (oversize as QuarantineRuleEngine.Outcome.Rejected).error.code,
        )
    }

    @Test
    fun admitObserved_rejectsSymlinkAndDigestMismatch() {
        val declared = listOf(
            QuarantineDeclaredEntry(
                role = "weights",
                expectedByteLength = 10,
                expectedSha256Hex = "a".repeat(64),
            ),
        )
        val symlink = QuarantineRuleEngine.admitObserved(
            declared,
            listOf(
                QuarantineObservedFile(
                    role = "weights",
                    actualByteLength = 10,
                    actualSha256Hex = "a".repeat(64),
                    isRegularFile = true,
                    isSymlink = true,
                ),
            ),
            rules,
        )
        assertTrue(symlink is QuarantineRuleEngine.Outcome.Rejected)

        val digest = QuarantineRuleEngine.admitObserved(
            declared,
            listOf(
                QuarantineObservedFile(
                    role = "weights",
                    actualByteLength = 10,
                    actualSha256Hex = "c".repeat(64),
                    isRegularFile = true,
                ),
            ),
            rules,
        )
        assertTrue(digest is QuarantineRuleEngine.Outcome.Rejected)
    }

    @Test
    fun admitObserved_oneToOneMatch() {
        val declared = listOf(
            QuarantineDeclaredEntry(
                role = "weights",
                expectedByteLength = 10,
                expectedSha256Hex = "a".repeat(64),
            ),
        )
        val ok = QuarantineRuleEngine.admitObserved(
            declared,
            listOf(
                QuarantineObservedFile(
                    role = "weights",
                    actualByteLength = 10,
                    actualSha256Hex = "a".repeat(64),
                    isRegularFile = true,
                ),
            ),
            rules,
        )
        assertTrue(ok is QuarantineRuleEngine.Outcome.Accepted)
    }
}
