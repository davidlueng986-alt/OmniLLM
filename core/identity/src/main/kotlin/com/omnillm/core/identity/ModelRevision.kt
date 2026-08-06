package com.omnillm.core.identity

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.CanonicalEncoding
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.Sha256Digest

/**
 * ModelRevisionManifest fields from specs/canonical-types.yaml (schemaVersion = 1).
 *
 * Hash preimage: UTF-8("OmniLLM.ModelRevisionId.v1\\n") || RFC8785(manifest).
 * Missing required fields or unknown fields are rejected (fail closed).
 */
data class ModelRevisionManifest(
    val artifactPackageId: ArtifactPackageId,
    val formatProfile: String,
    val tokenizerDigest: Sha256Digest,
    val templateDigest: Sha256Digest,
    val quantizationDescriptorJson: String,
    val semanticDescriptorJson: String,
    val tensorLayoutPolicyDigest: Sha256Digest,
    val modelMetadataSchemaVersion: Int,
    val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1) { "ModelRevisionManifest.schemaVersion must be 1" }
        require(formatProfile.isNotEmpty()) { "formatProfile must be non-empty" }
        require(modelMetadataSchemaVersion > 0) {
            "modelMetadataSchemaVersion must be a positive integer"
        }
        require(quantizationDescriptorJson.isNotBlank()) {
            "quantizationDescriptor must be present (versioned object JSON)"
        }
        require(semanticDescriptorJson.isNotBlank()) {
            "semanticDescriptor must be present (versioned object JSON)"
        }
        // Descriptors must themselves be compact objects (reject empty / non-object).
        require(quantizationDescriptorJson.trimStart().startsWith("{")) {
            "quantizationDescriptor must be a JSON object"
        }
        require(semanticDescriptorJson.trimStart().startsWith("{")) {
            "semanticDescriptor must be a JSON object"
        }
    }
}

/**
 * RFC8785-style canonical JSON for ModelRevisionManifest (key order = golden ID-003).
 */
object ModelRevisionCanonicalizer {
    /**
     * Produce canonical JSON for [manifest]. Descriptor JSON fragments are
     * embedded as-is and **must already be RFC8785-canonical** (callers normalize
     * versioned descriptor schemas before hashing).
     */
    fun toCanonicalJson(manifest: ModelRevisionManifest): String {
        // Lexicographic key order matching golden vector ID-003:
        // artifactPackageId, formatProfile, modelMetadataSchemaVersion,
        // quantizationDescriptor, schemaVersion, semanticDescriptor,
        // templateDigest, tensorLayoutPolicyDigest, tokenizerDigest
        return buildString {
            append('{')
            append("\"artifactPackageId\":\"").append(manifest.artifactPackageId.hex).append('"')
            append(",\"formatProfile\":\"").append(escapeJson(manifest.formatProfile)).append('"')
            append(",\"modelMetadataSchemaVersion\":").append(manifest.modelMetadataSchemaVersion)
            append(",\"quantizationDescriptor\":").append(manifest.quantizationDescriptorJson.trim())
            append(",\"schemaVersion\":").append(manifest.schemaVersion)
            append(",\"semanticDescriptor\":").append(manifest.semanticDescriptorJson.trim())
            append(",\"templateDigest\":\"").append(manifest.templateDigest.hex).append('"')
            append(",\"tensorLayoutPolicyDigest\":\"")
                .append(manifest.tensorLayoutPolicyDigest.hex).append('"')
            append(",\"tokenizerDigest\":\"").append(manifest.tokenizerDigest.hex).append('"')
            append('}')
        }
    }

    fun modelRevisionId(manifest: ModelRevisionManifest): ModelRevisionId =
        IdentityHashing.modelRevisionIdOfCanonicalJson(toCanonicalJson(manifest))

    /**
     * Validate that a stored digest matches recomputation (DATA-IDENTITY §9).
     * @throws IllegalArgumentException on mismatch
     */
    fun requireMatchesStored(manifest: ModelRevisionManifest, stored: ModelRevisionId) {
        val computed = modelRevisionId(manifest)
        require(computed.hex == stored.hex) {
            "ModelRevisionId mismatch: computed=${computed.hex} stored=${stored.hex}"
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

/**
 * Installation identity (ADR-008): one installed instance of one ModelRevisionId.
 * Catalog kind: uuid.
 */
@JvmInline
value class InstallationId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val UUID_REGEX =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        fun parse(raw: String): InstallationId {
            val v = raw.lowercase()
            require(UUID_REGEX.matches(v)) {
                "InstallationId must be a UUID string, got length=${raw.length}"
            }
            return InstallationId(v)
        }

        fun ofValidated(value: String): InstallationId = InstallationId(value)
    }
}

/**
 * Convenience validators for digest-shaped identities from wire/DB.
 */
object IdentityValidation {
    fun isBlobId(hex: String): Boolean =
        CanonicalEncoding.isDigestHex64(hex.lowercase())

    fun isArtifactPackageId(hex: String): Boolean =
        CanonicalEncoding.isDigestHex64(hex.lowercase())

    fun isModelRevisionId(hex: String): Boolean =
        CanonicalEncoding.isDigestHex64(hex.lowercase())

    fun parseBlobId(hex: String): BlobId = BlobId.parse(hex)

    fun parseArtifactPackageId(hex: String): ArtifactPackageId = ArtifactPackageId.parse(hex)

    fun parseModelRevisionId(hex: String): ModelRevisionId = ModelRevisionId.parse(hex)
}
