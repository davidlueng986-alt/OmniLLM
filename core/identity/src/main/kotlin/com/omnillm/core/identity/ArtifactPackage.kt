package com.omnillm.core.identity

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId

/**
 * ArtifactPackage entry used for total-order normalization before hashing.
 *
 * Catalog: `ArtifactPackageEntry` in specs/canonical-types.yaml.
 * Sort keys: role, blobId, byteLength, shardIndex.
 * Rejects duplicate exact entries and duplicate (role, shardIndex) slots.
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
 * after total-order normalization (DATA-IDENTITY / golden vectors ID-002, ID-004).
 */
object ArtifactPackageCanonicalizer {
    /**
     * Normalize [files] and return canonical JSON bytes as UTF-8 string.
     * @throws IllegalArgumentException on empty list, duplicate entry, or duplicate slot
     */
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

    /**
     * Validate that each entry's [ArtifactPackageEntry.byteLength] matches the
     * corresponding blob length map (catalog: byteLength must equal blobs.byte_length).
     * @throws IllegalArgumentException on mismatch or missing blob
     */
    fun requireByteLengthsMatch(
        files: List<ArtifactPackageEntry>,
        blobLengths: Map<BlobId, Long>,
    ) {
        for (e in files) {
            val expected = blobLengths[e.blobId]
                ?: throw IllegalArgumentException("unknown blobId in package: ${e.blobId.hex}")
            require(expected == e.byteLength) {
                "byteLength mismatch for ${e.blobId.hex}: entry=${e.byteLength} blob=$expected"
            }
        }
    }

    private fun escapeJson(s: String): String =
        buildString {
            for (c in s) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (c.code < 0x20) {
                            append("\\u%04x".format(c.code))
                        } else {
                            append(c)
                        }
                    }
                }
            }
        }
}
