package com.omnillm.core.identity

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.Sha256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative golden vectors from `specs/golden-vectors/canonical-encoding.yaml`
 * (identity.negativeVectors ID-N001..ID-N005) + ADR-008 separation.
 */
class IdentityNegativeVectorsTest {

    private val blob =
        BlobId.parse("0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b")

    @Test
    fun idN001_unknownFieldRejectedByManifestConstruction() {
        // ArtifactPackageEntry has a fixed field set; unknown path cannot be
        // represented on the typed API. Empty / invalid role fails closed.
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageEntry(role = "", blobId = blob, byteLength = 20L, shardIndex = 0)
        }
    }

    @Test
    fun idN002_duplicateIdenticalEntry_rejected() {
        val e = ArtifactPackageEntry("WEIGHTS", blob, 20L, 0)
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(listOf(e, e))
        }
    }

    @Test
    fun idN003_duplicateRoleShardDifferentBlob_rejected() {
        val blobB =
            BlobId.parse("1111111111111111111111111111111111111111111111111111111111111111")
        val e1 = ArtifactPackageEntry("WEIGHTS", blob, 20L, 0)
        val e2 = ArtifactPackageEntry("WEIGHTS", blobB, 30L, 0)
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(listOf(e1, e2))
        }
    }

    @Test
    fun idN004_byteLengthMismatch_rejected() {
        val e = ArtifactPackageEntry("WEIGHTS", blob, 20L, 0)
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.requireByteLengthsMatch(
                listOf(e),
                mapOf(blob to 99L),
            )
        }
        // Matching length is accepted.
        ArtifactPackageCanonicalizer.requireByteLengthsMatch(
            listOf(e),
            mapOf(blob to 20L),
        )
    }

    @Test
    fun idN005_omitTensorLayoutOrMetadataSchema_rejected() {
        // modelMetadataSchemaVersion must be positive; zero / negative fails.
        assertThrows(IllegalArgumentException::class.java) {
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
        }
        // Empty formatProfile rejected (required field).
        assertThrows(IllegalArgumentException::class.java) {
            ModelRevisionManifest(
                artifactPackageId = ArtifactPackageId.parse(
                    "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
                ),
                formatProfile = "",
                tokenizerDigest = Sha256Digest.parse("0".repeat(64)),
                templateDigest = Sha256Digest.parse("1".repeat(64)),
                quantizationDescriptorJson = """{"global":"Q4_K_M","schemaVersion":1}""",
                semanticDescriptorJson = """{"architecture":"llama"}""",
                tensorLayoutPolicyDigest = Sha256Digest.parse("2".repeat(64)),
                modelMetadataSchemaVersion = 1,
            )
        }
        // Blank descriptor JSON rejected.
        assertThrows(IllegalArgumentException::class.java) {
            ModelRevisionManifest(
                artifactPackageId = ArtifactPackageId.parse(
                    "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
                ),
                formatProfile = "GGUF-v3",
                tokenizerDigest = Sha256Digest.parse("0".repeat(64)),
                templateDigest = Sha256Digest.parse("1".repeat(64)),
                quantizationDescriptorJson = " ",
                semanticDescriptorJson = """{"architecture":"llama"}""",
                tensorLayoutPolicyDigest = Sha256Digest.parse("2".repeat(64)),
                modelMetadataSchemaVersion = 1,
            )
        }
    }

    @Test
    fun adr008_identitiesRemainDistinctTypes() {
        val blobId = IdentityHashing.blobIdOfUtf8("OmniLLM golden blob\n")
        val pkg = ArtifactPackageCanonicalizer.artifactPackageId(
            listOf(ArtifactPackageEntry("WEIGHTS", blobId, 20L, 0)),
        )
        val rev = ModelRevisionCanonicalizer.modelRevisionId(
            ModelRevisionManifest(
                artifactPackageId = pkg,
                formatProfile = "GGUF-v3",
                tokenizerDigest = Sha256Digest.parse("0".repeat(64)),
                templateDigest = Sha256Digest.parse("1".repeat(64)),
                quantizationDescriptorJson = """{"global":"Q4_K_M","schemaVersion":1}""",
                semanticDescriptorJson = """{"architecture":"llama"}""",
                tensorLayoutPolicyDigest = Sha256Digest.parse("2".repeat(64)),
                modelMetadataSchemaVersion = 1,
            ),
        )
        // Distinct values for the golden chain.
        assertEquals(
            "0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b",
            blobId.hex,
        )
        assertEquals(
            "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
            pkg.hex,
        )
        assertEquals(
            "d3085c5444f76901c1dcb6a5bba90ce53643a9091574d47ebbe0dd3b4314ae5a",
            rev.hex,
        )
        assertTrue(blobId.hex != pkg.hex)
        assertTrue(pkg.hex != rev.hex)
        // Installation is UUID-shaped, never a digest.
        val install = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000")
        assertTrue(install.value.contains("-"))
        assertTrue(install.value.length == 36)
    }

    @Test
    fun emptyFileList_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(emptyList())
        }
    }
}
