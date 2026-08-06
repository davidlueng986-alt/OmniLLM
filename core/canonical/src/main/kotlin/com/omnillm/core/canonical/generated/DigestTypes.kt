// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

/**
 * Typed digest / identity wrappers from specs/canonical-types.yaml.
 * All digest strings are lower-case hex SHA-256 (64 chars).
 */
@JvmInline
value class BlobId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "BlobId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): BlobId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "BlobId")
            return BlobId(v)
        }

        fun ofValidated(hex: String): BlobId = BlobId(hex)
    }
}

@JvmInline
value class ArtifactPackageId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "ArtifactPackageId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): ArtifactPackageId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "ArtifactPackageId")
            return ArtifactPackageId(v)
        }

        fun ofValidated(hex: String): ArtifactPackageId = ArtifactPackageId(hex)
    }
}

@JvmInline
value class ModelRevisionId private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "ModelRevisionId")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): ModelRevisionId {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "ModelRevisionId")
            return ModelRevisionId(v)
        }

        fun ofValidated(hex: String): ModelRevisionId = ModelRevisionId(hex)
    }
}

@JvmInline
value class Sha256Digest private constructor(val hex: String) {
    init {
        CanonicalEncoding.requireDigestHex64(hex, "Sha256Digest")
    }

    override fun toString(): String = hex

    companion object {
        fun parse(hex: String): Sha256Digest {
            val v = hex.lowercase()
            CanonicalEncoding.requireDigestHex64(v, "Sha256Digest")
            return Sha256Digest(v)
        }

        fun ofValidated(hex: String): Sha256Digest = Sha256Digest(hex)
    }
}
