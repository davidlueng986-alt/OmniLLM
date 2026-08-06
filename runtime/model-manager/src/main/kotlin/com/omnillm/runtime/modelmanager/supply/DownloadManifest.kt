package com.omnillm.runtime.modelmanager.supply

import com.omnillm.core.errors.generated.OmniError

/**
 * Download manifest entry (SEC-SUPPLY §5).
 *
 * Each artifact lists canonical URL policy, file role, size, sha256,
 * package/revision, license terms, and optional mirrors.
 * Redirect validation is performed by DownloadUrlPolicy + ResolvedAddressPolicy
 * (runtime:policy) per hop — never trust URL/TLS alone for provenance
 * (security-profile control).
 */
data class DownloadManifestEntry(
    val role: String,
    val canonicalUrl: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val artifactPackageIdHex: String?,
    val modelRevisionIdHex: String?,
    val licenseTermsDigestHex: String?,
    val mirrors: List<String> = emptyList(),
    val fileRole: String = role,
) {
    init {
        require(role.isNotEmpty())
        require(canonicalUrl.isNotEmpty())
        require(sizeBytes >= 0L)
        require(sha256Hex.matches(HEX64)) { "sha256Hex must be 64 hex" }
        artifactPackageIdHex?.let { require(it.matches(HEX64)) }
        modelRevisionIdHex?.let { require(it.matches(HEX64)) }
        licenseTermsDigestHex?.let { require(it.matches(HEX64)) }
        require(fileRole.isNotEmpty())
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

data class DownloadManifest(
    val schemaMajorVersion: Int = 1,
    val entries: List<DownloadManifestEntry>,
    val maxMirrorsPerEntry: Int = 8,
) {
    init {
        require(schemaMajorVersion >= 1)
        require(entries.isNotEmpty())
        require(maxMirrorsPerEntry >= 0)
        for (e in entries) {
            require(e.mirrors.size <= maxMirrorsPerEntry) {
                "mirrors exceed cap for role=${e.role}"
            }
        }
    }
}

object DownloadManifestAdmission {

    sealed class Outcome {
        data class Accepted(val manifest: DownloadManifest) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    fun admit(
        manifest: DownloadManifest,
        maxEntries: Int = 256,
        maxSupportedSchemaMajor: Int = 1,
    ): Outcome {
        if (manifest.schemaMajorVersion > maxSupportedSchemaMajor) {
            return Outcome.Rejected(
                error = OmniError.CAPABILITY_UNKNOWN(
                    message = "unknown download manifest schema major",
                    details = mapOf(
                        "schemaMajorVersion" to manifest.schemaMajorVersion.toString(),
                    ),
                ),
                reason = "unknown schema major",
            )
        }
        if (manifest.entries.size > maxEntries) {
            return Outcome.Rejected(
                error = OmniError.TRANSPORT_TOO_LARGE(
                    message = "download manifest entry count exceeds cap",
                    details = mapOf(
                        "count" to manifest.entries.size.toString(),
                        "max" to maxEntries.toString(),
                    ),
                ),
                reason = "too many entries",
            )
        }
        val roles = HashSet<String>()
        for (e in manifest.entries) {
            if (!roles.add(e.role)) {
                return Outcome.Rejected(
                    error = OmniError.INVALID_REQUEST(
                        message = "duplicate download manifest role",
                        details = mapOf("role" to e.role),
                    ),
                    reason = "duplicate role",
                )
            }
        }
        return Outcome.Accepted(manifest)
    }
}
