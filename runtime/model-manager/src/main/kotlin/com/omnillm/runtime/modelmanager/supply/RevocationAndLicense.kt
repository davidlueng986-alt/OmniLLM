package com.omnillm.runtime.modelmanager.supply

import com.omnillm.core.errors.generated.OmniError

/**
 * Catalog revocation record (SEC-SUPPLY §6).
 *
 * Older metadata cannot lift a newer revocation. Effective trust recompute
 * drives placement/drain after acceptance.
 */
enum class RevocationTargetKind {
    MODEL_REVISION,
    ARTIFACT_PACKAGE,
    KEY_ID,
    CATALOG_SOURCE,
    ENGINE_BUILD,
}

data class CatalogRevocationRecord(
    val authorityRole: CatalogMetadataRole,
    val metadataVersion: Long,
    val metadataSequence: Long,
    val reason: String,
    val targetKind: RevocationTargetKind,
    val targetId: String,
    val effectiveFromEpochMs: Long,
    val effectiveUntilEpochMs: Long?,
    val canonicalDigestHex: String,
) {
    init {
        require(metadataVersion >= 0L)
        require(metadataSequence >= 0L)
        require(reason.isNotEmpty() && reason.length <= 512)
        require(targetId.isNotEmpty())
        require(effectiveFromEpochMs >= 0L)
        effectiveUntilEpochMs?.let { require(it >= effectiveFromEpochMs) }
        require(canonicalDigestHex.matches(Regex("^[0-9a-f]{64}$")))
    }

    fun isEffectiveAt(nowEpochMs: Long): Boolean {
        if (nowEpochMs < effectiveFromEpochMs) return false
        val until = effectiveUntilEpochMs ?: return true
        return nowEpochMs <= until
    }
}

/**
 * In-memory revocation set with anti-rollback of lifts (SEC-SUPPLY §6).
 */
class RevocationLedger {
    private val byTarget = linkedMapOf<String, CatalogRevocationRecord>()

    private fun key(r: CatalogRevocationRecord): String = "${r.targetKind.name}:${r.targetId}"

    /**
     * Apply a revocation. A record with lower sequence cannot replace a higher one
     * (including attempted "lift" via weaker metadata).
     */
    fun apply(record: CatalogRevocationRecord): ApplyResult {
        val k = key(record)
        val existing = byTarget[k]
        if (existing != null && record.metadataSequence < existing.metadataSequence) {
            return ApplyResult.Rejected(
                OmniError.INVALID_REQUEST(
                    message = "older metadata cannot lift newer revocation",
                    details = mapOf(
                        "target" to k,
                        "existingSequence" to existing.metadataSequence.toString(),
                        "incomingSequence" to record.metadataSequence.toString(),
                    ),
                ),
            )
        }
        byTarget[k] = record
        return ApplyResult.Applied(record)
    }

    fun isRevoked(kind: RevocationTargetKind, targetId: String, nowEpochMs: Long): Boolean {
        val r = byTarget["${kind.name}:$targetId"] ?: return false
        return r.isEffectiveAt(nowEpochMs)
    }

    fun snapshot(): List<CatalogRevocationRecord> = byTarget.values.toList()

    sealed class ApplyResult {
        data class Applied(val record: CatalogRevocationRecord) : ApplyResult()
        data class Rejected(val error: OmniError) : ApplyResult()
    }
}

/**
 * License terms provenance (SEC-SUPPLY §7).
 *
 * Acceptance binds terms digest + user; identical bytes from different sources
 * do not automatically share acceptance.
 */
data class LicenseTermsProvenance(
    val canonicalTextBytes: ByteArray,
    val digestHex: String,
    val locale: String,
    val sourceAssertion: String,
    val version: String,
) {
    init {
        require(canonicalTextBytes.isNotEmpty())
        require(digestHex.matches(Regex("^[0-9a-f]{64}$")))
        require(locale.isNotEmpty())
        require(sourceAssertion.isNotEmpty())
        require(version.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LicenseTermsProvenance) return false
        return canonicalTextBytes.contentEquals(other.canonicalTextBytes) &&
            digestHex == other.digestHex &&
            locale == other.locale &&
            sourceAssertion == other.sourceAssertion &&
            version == other.version
    }

    override fun hashCode(): Int {
        var r = canonicalTextBytes.contentHashCode()
        r = 31 * r + digestHex.hashCode()
        r = 31 * r + locale.hashCode()
        r = 31 * r + sourceAssertion.hashCode()
        r = 31 * r + version.hashCode()
        return r
    }
}

/**
 * Acceptance event identity: terms digest + source + principal.
 * Same bytes + different sourceAssertion ⇒ distinct acceptance keys.
 */
data class LicenseAcceptanceKey(
    val termsDigestHex: String,
    val sourceAssertion: String,
    val principalId: String,
) {
    init {
        require(termsDigestHex.matches(Regex("^[0-9a-f]{64}$")))
        require(sourceAssertion.isNotEmpty())
        require(principalId.isNotEmpty())
    }
}
