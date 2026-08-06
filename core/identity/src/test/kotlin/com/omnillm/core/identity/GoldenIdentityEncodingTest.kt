package com.omnillm.core.identity

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.Sha256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors from specs/golden-vectors/canonical-encoding.yaml (identity profile).
 * Authority: DATA-IDENTITY / ADR-008.
 */
class GoldenIdentityEncodingTest {

    @Test
    fun id001_blobIdFromRawUtf8() {
        // rawBlobUtf8: "OmniLLM golden blob\n"
        val raw = "OmniLLM golden blob\n"
        val blobId = IdentityHashing.blobIdOfUtf8(raw)
        assertEquals(
            "0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b",
            blobId.hex,
        )
    }

    @Test
    fun id002_artifactPackageIdFromCanonicalJson() {
        val canonical =
            """{"files":[{"blobId":"0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b","byteLength":20,"role":"WEIGHTS","shardIndex":0}],"schemaVersion":1}"""
        val id = IdentityHashing.artifactPackageIdOfCanonicalJson(canonical)
        assertEquals(
            "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
            id.hex,
        )
    }

    @Test
    fun id003_modelRevisionIdFromCanonicalJson() {
        val canonical =
            """{"artifactPackageId":"6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043","formatProfile":"GGUF-v3","modelMetadataSchemaVersion":1,"quantizationDescriptor":{"global":"Q4_K_M","schemaVersion":1},"schemaVersion":1,"semanticDescriptor":{"architecture":"llama"},"templateDigest":"1111111111111111111111111111111111111111111111111111111111111111","tensorLayoutPolicyDigest":"2222222222222222222222222222222222222222222222222222222222222222","tokenizerDigest":"0000000000000000000000000000000000000000000000000000000000000000"}"""
        val id = IdentityHashing.modelRevisionIdOfCanonicalJson(canonical)
        assertEquals(
            "d3085c5444f76901c1dcb6a5bba90ce53643a9091574d47ebbe0dd3b4314ae5a",
            id.hex,
        )
    }

    @Test
    fun id003_modelRevisionManifestCanonicalizerMatchesGolden() {
        val manifest = ModelRevisionManifest(
            artifactPackageId = ArtifactPackageId.parse(
                "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
            ),
            formatProfile = "GGUF-v3",
            tokenizerDigest = Sha256Digest.parse(
                "0000000000000000000000000000000000000000000000000000000000000000",
            ),
            templateDigest = Sha256Digest.parse(
                "1111111111111111111111111111111111111111111111111111111111111111",
            ),
            quantizationDescriptorJson = """{"global":"Q4_K_M","schemaVersion":1}""",
            semanticDescriptorJson = """{"architecture":"llama"}""",
            tensorLayoutPolicyDigest = Sha256Digest.parse(
                "2222222222222222222222222222222222222222222222222222222222222222",
            ),
            modelMetadataSchemaVersion = 1,
        )
        val id = ModelRevisionCanonicalizer.modelRevisionId(manifest)
        assertEquals(
            "d3085c5444f76901c1dcb6a5bba90ce53643a9091574d47ebbe0dd3b4314ae5a",
            id.hex,
        )
        ModelRevisionCanonicalizer.requireMatchesStored(manifest, id)
    }

    @Test
    fun id004_permutationRule_sameArtifactPackageId() {
        val blob =
            BlobId.parse("0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b")
        val a = ArtifactPackageEntry("WEIGHTS", blob, 20L, 0)
        val b = ArtifactPackageEntry("CONFIG", blob, 20L, 0)
        val id1 = ArtifactPackageCanonicalizer.artifactPackageId(listOf(a, b))
        val id2 = ArtifactPackageCanonicalizer.artifactPackageId(listOf(b, a))
        assertEquals(id1.hex, id2.hex)

        val onlyWeights = ArtifactPackageCanonicalizer.artifactPackageId(listOf(a))
        assertEquals(
            "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
            onlyWeights.hex,
        )
    }

    @Test
    fun idN002_duplicateEntryRejected() {
        val blob =
            BlobId.parse("0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b")
        val e = ArtifactPackageEntry("WEIGHTS", blob, 20L, 0)
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(listOf(e, e))
        }
    }

    @Test
    fun idN003_duplicateRoleShardRejected() {
        val blobA =
            BlobId.parse("0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b")
        val blobB =
            BlobId.parse("1111111111111111111111111111111111111111111111111111111111111111")
        val e1 = ArtifactPackageEntry("WEIGHTS", blobA, 20L, 0)
        val e2 = ArtifactPackageEntry("WEIGHTS", blobB, 30L, 0)
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.normalizeAndCanonicalJson(listOf(e1, e2))
        }
    }

    @Test
    fun idN004_byteLengthMismatchRejected() {
        val blob =
            BlobId.parse("0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b")
        val e = ArtifactPackageEntry("WEIGHTS", blob, 20L, 0)
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageCanonicalizer.requireByteLengthsMatch(
                listOf(e),
                mapOf(blob to 99L),
            )
        }
    }

    @Test
    fun digestTypes_normalizeCaseAndRejectMalformed() {
        val ok = BlobId.parse("0340D116CF3EEEC6AFF212FB49CEDF59D25B8BEF8A9CC806D50178D75507D90B")
        assertEquals(
            "0340d116cf3eeec6aff212fb49cedf59d25b8bef8a9cc806d50178d75507d90b",
            ok.hex,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ArtifactPackageId.parse("not-a-digest")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelRevisionId.parse("abcd")
        }
        assertTrue(IdentityValidation.isBlobId(ok.hex))
    }

    @Test
    fun installationId_uuidValidation() {
        val id = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000")
        assertEquals("550e8400-e29b-41d4-a716-446655440000", id.value)
        assertThrows(IllegalArgumentException::class.java) {
            InstallationId.parse("not-a-uuid")
        }
    }
}
