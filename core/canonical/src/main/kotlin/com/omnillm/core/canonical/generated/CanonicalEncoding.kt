// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

/**
 * Digest / encoding rules from specs/canonical-types.yaml `encoding`.
 * Digests are always lower-case hexadecimal without prefix (INV identity hash).
 */
object CanonicalEncoding {
    const val DIGEST_PATTERN: String = "^[0-9a-f]{64}$"
    const val DIGEST_HEX_LENGTH: Int = 64

    /** Domain separators for identity hashes (UTF-8 + LF + RFC8785 bytes). */
    const val ARTIFACT_PACKAGE_ID_V1: String = "OmniLLM.ArtifactPackageId.v1"
    const val MODEL_REVISION_ID_V1: String = "OmniLLM.ModelRevisionId.v1"
    const val CONTENT_REPORT_PAYLOAD_V1: String = "OmniLLM.ContentReportPayload.v1"

    private val digestRegex = Regex(DIGEST_PATTERN)

    fun isDigestHex64(value: String): Boolean = digestRegex.matches(value)

    fun requireDigestHex64(value: String, label: String = "digest"): String {
        require(isDigestHex64(value)) {
            "\$label must be lower-case 64-char hex SHA-256, got length=\${value.length}"
        }
        return value
    }
}
