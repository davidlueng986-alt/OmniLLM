package com.omnillm.core.canonical

import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parameterized negative golden vectors from
 * `specs/golden-vectors/canonical-encoding.yaml` (`identity.negativeVectors`).
 *
 * This module hosts the low-level canonicalizer ([ArtifactPackageCanonicalizer]
 * / [IdentityHashing]); vectors executable here are ID-N001..ID-N003. The
 * ID-N004 (byteLength vs blobs.byte_length) and ID-N005 (ModelRevisionManifest
 * field omission) guards live on the typed APIs in `:core:identity`
 * (`ArtifactPackageCanonicalizer.requireByteLengthsMatch`,
 * `ModelRevisionManifest` validation) and are parameterized in
 * `GoldenNegativeVectorParameterizedTest` there (TST-06).
 *
 * The catalog `expectedError` labels are pinned against
 * `specs/error-catalog.yaml` via [OmniErrorCode].
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
    )

    @Test
    fun executableNegativeVectors_failClosed() {
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
        assertEquals(3, checked)
    }

    @Test
    fun negativeVectorErrorLabels_areCatalogCodes() {
        for (v in vectors()) {
            assertTrue(
                "${v.id} expectedError '${v.expectedError}' must be a catalog code",
                OmniErrorCode.fromCode(v.expectedError) != null,
            )
        }
    }

    @Test
    fun positiveControl_goldenPackageStillResolves_afterRejections() {
        val e = ArtifactPackageEntry("WEIGHTS", goldenBlob, 20L, 0)
        val pkg = ArtifactPackageCanonicalizer.artifactPackageId(listOf(e))
        assertEquals(
            "6e15194451a16cc0c27924ca7331ba799444dc1d793765b723c64996379d9043",
            pkg.hex,
        )
    }
}
