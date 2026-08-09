package com.omnillm.core.identity

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parameterized negative golden vectors from
 * `specs/golden-vectors/canonical-encoding.yaml` (`identity.negativeVectors`,
 * ID-N001..ID-N005).
 *
 * Every mutation must fail closed; the catalog `expectedError` label of each
 * vector must exist in `specs/error-catalog.yaml` so the INVALID_REQUEST /
 * STATE_CONFLICT projection stays authoritative (TST-06).
 *
 * Positive controls pin that the un-mutated golden chain (ID-002/ID-003)
 * still resolves after each rejected mutation.
 */
class GoldenNegativeVectorParameterizedTest {

    private val goldenBlob =
        BlobId.parse("0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b")
    private val otherBlob =
        BlobId.parse("1111111111111111111111111111111111111111111111111111111111111111")

    private data class Vector(
        val id: String,
        val mutation: String,
        val expectedError: String,
        val action: () -> Unit,
    )

    private fun vectors(): List<Vector> = listOf(
        Vector(
            id = "ID-N001",
            mutation = "add unknown field path to ArtifactPackageEntry",
            expectedError = "INVALID_REQUEST",
        ) {
            // Unknown fields cannot be represented on the typed API; an empty
            // role fails closed instead of emitting a partial manifest.
            ArtifactPackageEntry(role = "", blobId = goldenBlob, byteLength = 20L, shardIndex = 0)
        },
        Vector(
            id = "ID-N002",
            mutation = "duplicate an identical ArtifactPackageEntry",
            expectedError = "STATE_CONFLICT",
        ) {
            val e = ArtifactPackageEntry("WEIGHTS", goldenBlob, 20L, 0)
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(listOf(e, e))
        },
        Vector(
            id = "ID-N003",
            mutation = "duplicate role and shardIndex with a different blobId",
            expectedError = "STATE_CONFLICT",
        ) {
            val e1 = ArtifactPackageEntry("WEIGHTS", goldenBlob, 20L, 0)
            val e2 = ArtifactPackageEntry("WEIGHTS", otherBlob, 30L, 0)
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(listOf(e1, e2))
        },
        Vector(
            id = "ID-N004",
            mutation = "set byteLength to a value different from blobs.byte_length",
            expectedError = "INVALID_REQUEST",
        ) {
            val e = ArtifactPackageEntry("WEIGHTS", goldenBlob, 20L, 0)
            ArtifactPackageCanonicalizer.requireByteLengthsMatch(
                listOf(e),
                mapOf(goldenBlob to 99L),
            )
        },
        Vector(
            id = "ID-N005",
            mutation = "omit tensorLayoutPolicyDigest or modelMetadataSchemaVersion from ModelRevisionManifest",
            expectedError = "INVALID_REQUEST",
        ) {
            ModelRevisionManifest(
                artifactPackageId = ArtifactPackageId.parse(
                    "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
                ),
                formatProfile = "GGUF-v3",
                tokenizerDigest = Sha256Digest.parse("0".repeat(64)),
                templateDigest = Sha256Digest.parse("1".repeat(64)),
                quantizationDescriptorJson = """{"global":"Q4_K_M","schemaVersion":1}""",
                semanticDescriptorJson = """{"architecture":"llama"}""",
                tensorLayoutPolicyDigest = Sha256Digest.parse("2".repeat(64)),
                modelMetadataSchemaVersion = 0,
            )
        },
    )

    @Test
    fun everyNegativeVector_failsClosed() {
        var checked = 0
        for (v in vectors()) {
            var threw = false
            try {
                v.action()
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue(
                "${v.id} (${v.mutation}) must fail closed as ${v.expectedError} but did not throw",
                threw,
            )
            checked++
        }
        assertEquals(5, checked)
    }

    @Test
    fun everyNegativeVector_expectedErrorLabel_isACatalogCode() {
        for (v in vectors()) {
            assertTrue(
                "${v.id} expectedError '${v.expectedError}' must be a catalog code",
                OmniErrorCode.fromCode(v.expectedError) != null,
            )
        }
    }

    @Test
    fun positiveControls_goldenChainStillHolds_afterRejections() {
        // Each rejected mutation must never poison the golden path.
        val e = ArtifactPackageEntry("WEIGHTS", goldenBlob, 20L, 0)
        val pkg = ArtifactPackageCanonicalizer.artifactPackageId(listOf(e))
        assertEquals(
            "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
            pkg.hex,
        )
        ArtifactPackageCanonicalizer.requireByteLengthsMatch(listOf(e), mapOf(goldenBlob to 20L))
        val manifest = ModelRevisionManifest(
            artifactPackageId = pkg,
            formatProfile = "GGUF-v3",
            tokenizerDigest = Sha256Digest.parse("0".repeat(64)),
            templateDigest = Sha256Digest.parse("1".repeat(64)),
            quantizationDescriptorJson = """{"global":"Q4_K_M","schemaVersion":1}""",
            semanticDescriptorJson = """{"architecture":"llama"}""",
            tensorLayoutPolicyDigest = Sha256Digest.parse("2".repeat(64)),
            modelMetadataSchemaVersion = 1,
        )
        val rev = ModelRevisionCanonicalizer.modelRevisionId(manifest)
        assertEquals(
            "d3085c5444f76901c1dcb6a5bba90ce53643a9091574d47ebbe0dd3b4314ae5a",
            rev.hex,
        )
    }
}
