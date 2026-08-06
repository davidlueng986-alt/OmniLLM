package com.omnillm.core.canonical

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.CanonicalEncoding
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.Sha256Digest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Identity hashing helpers for ADR-008 identities.
 *
 * Catalog rule (`specs/canonical-types.yaml`):
 * identity hash input = UTF-8 domain separator + LF + RFC 8785 bytes.
 * Digests are lower-case hex without prefix.
 *
 * For golden vectors, callers may pass already-canonicalized JSON (RFC 8785)
 * from `specs/golden-vectors/canonical-encoding.yaml`.
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

/**
 * ArtifactPackage entry used for total-order normalization before hashing.
 * Sort keys: role, blobId, byteLength, shardIndex (catalog normalization).
 */
data class ArtifactPackageEntry(
    val role: String,
    val blobId: BlobId,
    val byteLength: Long,
    val shardIndex: Int,
) {
    init {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(byteLength >= 0L) { "byteLength must be non-negative" }
        require(shardIndex >= 0) { "shardIndex must be non-negative" }
    }
}

/**
 * Builds RFC8785-style compact JSON for ArtifactPackageManifest schemaVersion=1
 * after total-order normalization. Rejects duplicate canonical entries and
 * duplicate role/shardIndex slots (catalog rules).
 */
object ArtifactPackageCanonicalizer {
    fun normalizeAndCanonicalJson(files: List<ArtifactPackageEntry>): String {
        require(files.isNotEmpty()) { "files must be non-empty" }

        val sorted = files.sortedWith(
            compareBy<ArtifactPackageEntry> { it.role }
                .thenBy { it.blobId.hex }
                .thenBy { it.byteLength }
                .thenBy { it.shardIndex },
        )

        val seenExact = HashSet<String>()
        val seenSlot = HashSet<String>()
        for (e in sorted) {
            val exact = "${e.role}|${e.blobId.hex}|${e.byteLength}|${e.shardIndex}"
            val slot = "${e.role}|${e.shardIndex}"
            require(exact !in seenExact) { "duplicate ArtifactPackageEntry" }
            require(slot !in seenSlot) { "duplicate role/shardIndex slot" }
            seenExact.add(exact)
            seenSlot.add(slot)
        }

        val fileParts = sorted.joinToString(",") { e ->
            // Key order matches golden vector ID-002 (blobId, byteLength, role, shardIndex).
            """{"blobId":"${e.blobId.hex}","byteLength":${e.byteLength},"role":"${escapeJson(e.role)}","shardIndex":${e.shardIndex}}"""
        }
        // Object keys sorted: files, schemaVersion (RFC 8785 lexicographic).
        return """{"files":[$fileParts],"schemaVersion":1}"""
    }

    fun artifactPackageId(files: List<ArtifactPackageEntry>): ArtifactPackageId =
        IdentityHashing.artifactPackageIdOfCanonicalJson(normalizeAndCanonicalJson(files))

    private fun escapeJson(s: String): String =
        buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
}
