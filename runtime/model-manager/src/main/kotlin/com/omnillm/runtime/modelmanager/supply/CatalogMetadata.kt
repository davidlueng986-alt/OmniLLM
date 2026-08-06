package com.omnillm.runtime.modelmanager.supply

import com.omnillm.core.errors.generated.OmniError

/**
 * Catalog metadata roles and envelopes (SEC-SUPPLY §2, §8).
 *
 * Root, timestamp, snapshot, targets, and revocation records are typed —
 * free-form `statement_json` must never drive trust decisions.
 * Unknown major schema version fails closed.
 */
enum class CatalogMetadataRole {
    ROOT,
    TIMESTAMP,
    SNAPSHOT,
    TARGETS,
    REVOCATION,
}

/**
 * Versioned signed metadata envelope (fields only — crypto verify is a port).
 */
data class CatalogMetadataEnvelope(
    val role: CatalogMetadataRole,
    val schemaMajorVersion: Int,
    val schemaMinorVersion: Int = 0,
    val version: Long,
    val sequence: Long,
    val expiresAtEpochMs: Long?,
    /** Threshold of signatures required (role-defined). */
    val signatureThreshold: Int,
    /** Key ids that signed; actual verify is outside this pure type. */
    val signerKeyIds: List<String>,
    /** Canonical bytes covered by signatures. */
    val canonicalBytes: ByteArray,
    val canonicalDigestHex: String,
) {
    init {
        require(schemaMajorVersion >= 1)
        require(schemaMinorVersion >= 0)
        require(version >= 0L)
        require(sequence >= 0L)
        require(signatureThreshold >= 1)
        require(signerKeyIds.isNotEmpty())
        require(canonicalBytes.isNotEmpty())
        require(canonicalDigestHex.matches(Regex("^[0-9a-f]{64}$")))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CatalogMetadataEnvelope) return false
        return role == other.role &&
            schemaMajorVersion == other.schemaMajorVersion &&
            schemaMinorVersion == other.schemaMinorVersion &&
            version == other.version &&
            sequence == other.sequence &&
            expiresAtEpochMs == other.expiresAtEpochMs &&
            signatureThreshold == other.signatureThreshold &&
            signerKeyIds == other.signerKeyIds &&
            canonicalBytes.contentEquals(other.canonicalBytes) &&
            canonicalDigestHex == other.canonicalDigestHex
    }

    override fun hashCode(): Int {
        var r = role.hashCode()
        r = 31 * r + schemaMajorVersion
        r = 31 * r + schemaMinorVersion
        r = 31 * r + version.hashCode()
        r = 31 * r + sequence.hashCode()
        r = 31 * r + (expiresAtEpochMs?.hashCode() ?: 0)
        r = 31 * r + signatureThreshold
        r = 31 * r + signerKeyIds.hashCode()
        r = 31 * r + canonicalBytes.contentHashCode()
        r = 31 * r + canonicalDigestHex.hashCode()
        return r
    }
}

/**
 * Field limits for parser output of security-critical metadata (SEC-SUPPLY §8).
 */
data class CatalogParserLimits(
    val maxCanonicalBytes: Int = 512 * 1024,
    val maxSignerKeys: Int = 32,
    val maxFieldStringBytes: Int = 4_096,
    val maxSupportedSchemaMajor: Int = 1,
) {
    init {
        require(maxCanonicalBytes > 0)
        require(maxSignerKeys > 0)
        require(maxFieldStringBytes > 0)
        require(maxSupportedSchemaMajor >= 1)
    }

    companion object {
        val DEFAULT: CatalogParserLimits = CatalogParserLimits()
    }
}

object CatalogMetadataAdmission {

    sealed class Outcome {
        data class Accepted(val envelope: CatalogMetadataEnvelope) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    fun admit(
        envelope: CatalogMetadataEnvelope,
        limits: CatalogParserLimits = CatalogParserLimits.DEFAULT,
        nowEpochMs: Long,
    ): Outcome {
        if (envelope.schemaMajorVersion > limits.maxSupportedSchemaMajor) {
            return Outcome.Rejected(
                error = OmniError.CAPABILITY_UNKNOWN(
                    message = "unknown catalog metadata schema major",
                    details = mapOf(
                        "role" to envelope.role.name,
                        "schemaMajorVersion" to envelope.schemaMajorVersion.toString(),
                    ),
                ),
                reason = "unknown schema major",
            )
        }
        if (envelope.canonicalBytes.size > limits.maxCanonicalBytes) {
            return Outcome.Rejected(
                error = OmniError.TRANSPORT_TOO_LARGE(
                    message = "catalog metadata exceeds size cap",
                    details = mapOf(
                        "bytes" to envelope.canonicalBytes.size.toString(),
                        "max" to limits.maxCanonicalBytes.toString(),
                    ),
                ),
                reason = "canonical too large",
            )
        }
        if (envelope.signerKeyIds.size > limits.maxSignerKeys) {
            return Outcome.Rejected(
                error = OmniError.INVALID_REQUEST(message = "too many signer keys"),
                reason = "too many signers",
            )
        }
        if (envelope.signerKeyIds.size < envelope.signatureThreshold) {
            return Outcome.Rejected(
                error = OmniError.INVALID_REQUEST(message = "signer count below threshold"),
                reason = "below threshold",
            )
        }
        if (envelope.expiresAtEpochMs != null && nowEpochMs > envelope.expiresAtEpochMs) {
            return Outcome.Rejected(
                error = OmniError.MODEL_REVOKED(
                    message = "catalog metadata expired",
                    details = mapOf("role" to envelope.role.name),
                ),
                reason = "expired",
            )
        }
        return Outcome.Accepted(envelope)
    }
}
