package com.omnillm.core.identity

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.CanonicalEncoding
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.Sha256Digest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Identity hashing helpers for ADR-008 / DATA-IDENTITY.
 *
 * Catalog rule (`specs/canonical-types.yaml` encoding.identityHashInput):
 * UTF-8 domain separator + LF + RFC 8785 bytes.
 * Digests are lower-case hex without prefix.
 *
 * Golden vectors: `specs/golden-vectors/canonical-encoding.yaml` identity profile.
 */
object IdentityHashing {
    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") { b -> "%02x".format(b) }

    fun sha256Hex(text: String): String =
        sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

    /**
     * Domain-separated identity digest:
     * SHA-256(UTF-8(domainSeparator) || 0x0A || payload).
     */
    fun identityDigestHex(domainSeparator: String, payload: ByteArray): String {
        val sep = domainSeparator.toByteArray(StandardCharsets.UTF_8)
        val input = ByteArray(sep.size + 1 + payload.size)
        System.arraycopy(sep, 0, input, 0, sep.size)
        input[sep.size] = 0x0A
        System.arraycopy(payload, 0, input, sep.size + 1, payload.size)
        return sha256Hex(input)
    }

    fun identityDigestHex(domainSeparator: String, canonicalUtf8: String): String =
        identityDigestHex(domainSeparator, canonicalUtf8.toByteArray(StandardCharsets.UTF_8))

    /** BlobId = SHA-256(raw file bytes). No role/path/format in the preimage. */
    fun blobIdOfRawBytes(raw: ByteArray): BlobId =
        BlobId.parse(sha256Hex(raw))

    fun blobIdOfUtf8(rawUtf8: String): BlobId =
        blobIdOfRawBytes(rawUtf8.toByteArray(StandardCharsets.UTF_8))

    fun artifactPackageIdOfCanonicalJson(canonicalJson: String): ArtifactPackageId =
        ArtifactPackageId.parse(
            identityDigestHex(CanonicalEncoding.ARTIFACT_PACKAGE_ID_V1, canonicalJson),
        )

    fun modelRevisionIdOfCanonicalJson(canonicalJson: String): ModelRevisionId =
        ModelRevisionId.parse(
            identityDigestHex(CanonicalEncoding.MODEL_REVISION_ID_V1, canonicalJson),
        )

    fun contentReportPayloadDigest(canonicalJson: String): Sha256Digest =
        Sha256Digest.parse(
            identityDigestHex(CanonicalEncoding.CONTENT_REPORT_PAYLOAD_V1, canonicalJson),
        )
}
