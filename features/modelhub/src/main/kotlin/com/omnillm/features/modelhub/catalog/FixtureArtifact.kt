package com.omnillm.features.modelhub.catalog

import com.omnillm.core.canonical.ArtifactPackageCanonicalizer
import com.omnillm.core.canonical.ArtifactPackageEntry
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId

/**
 * Tiny offline fixture artifact for host / software E2E (no device, no network).
 *
 * Bytes are content-addressed (ADR-008). Package / revision IDs are derived from
 * the canonical package manifest so identity is stable across processes.
 *
 * **Not** a real LLM weights file — synthetic material for install state machine,
 * digest verify, quarantine, and atomic promote tests (FEAT-MODELHUB / SEC-SUPPLY).
 */
object FixtureArtifact {
    /** UTF-8 payload (tiny; fits MaterializeBounds defaults). */
    val PAYLOAD_UTF8: String = "OmniLLM fixture weights v1\n"

    val PAYLOAD_BYTES: ByteArray = PAYLOAD_UTF8.toByteArray(Charsets.UTF_8)

    /** SHA-256 of [PAYLOAD_BYTES] — BlobId for role=weights. */
    val BLOB_ID: BlobId = IdentityHashing.blobIdOfRawBytes(PAYLOAD_BYTES)

    val BYTE_LENGTH: Long = PAYLOAD_BYTES.size.toLong()

    const val ROLE_WEIGHTS: String = "weights"

    private val packageEntries: List<ArtifactPackageEntry> = listOf(
        ArtifactPackageEntry(
            role = ROLE_WEIGHTS,
            blobId = BLOB_ID,
            byteLength = BYTE_LENGTH,
            shardIndex = 0,
        ),
    )

    val ARTIFACT_PACKAGE_ID: ArtifactPackageId =
        ArtifactPackageCanonicalizer.artifactPackageId(packageEntries)

    /**
     * Stable revision identity: domain-separated hash of package id + fixture tag.
     * Not a cryptographic model provenance claim — fixture only.
     */
    val MODEL_REVISION_ID: ModelRevisionId =
        IdentityHashing.modelRevisionIdOfCanonicalJson(
            """{"artifactPackageId":"${ARTIFACT_PACKAGE_ID.hex}","fixture":"omnillm-offline-v1","schemaVersion":1}""",
        )

    /** Canonical pin URL (HTTPS-only policy). Offline host tests resolve via [FixtureArtifactSource]. */
    const val PINNED_HTTPS_URL: String =
        "https://fixtures.omnillm.local/models/offline-v1/weights.bin"

    const val DISPLAY_NAME: String = "OmniLLM Offline Fixture"

    /** License terms digest for fixture acceptance projection (synthetic). */
    val LICENSE_DIGEST_HEX: String =
        IdentityHashing.sha256Hex("OmniLLM fixture license terms v1")

    fun blobIdHex(): String = BLOB_ID.hex

    fun packageIdHex(): String = ARTIFACT_PACKAGE_ID.hex

    fun revisionIdHex(): String = MODEL_REVISION_ID.hex
}
