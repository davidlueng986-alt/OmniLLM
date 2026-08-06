package com.omnillm.runtime.observability

import com.omnillm.core.canonical.generated.EvidenceLabel

/**
 * Presentation and semantic rules for [EvidenceLabel] (PROD-QUALITY §4,
 * CORE-OBSERVABILITY, observability-catalog.yaml).
 *
 * Hard rules:
 * - [EvidenceLabel.UNKNOWN] must never be coerced to zero / "normal" colour.
 * - [EvidenceLabel.LAST_SAMPLED] always carries age; never presented as live.
 * - [EvidenceLabel.REPORTED] must disclose source; not control-plane measured.
 * - [EvidenceLabel.ESTIMATED] carries confidence / conservative error bound.
 * - [EvidenceLabel.MEASURED] requires a complete measurement profile/run.
 */
object EvidenceSemantics {

    /** Catalog labels in stable declaration order (observability-catalog.yaml). */
    val ALL: List<EvidenceLabel> = listOf(
        EvidenceLabel.MEASURED,
        EvidenceLabel.ESTIMATED,
        EvidenceLabel.REPORTED,
        EvidenceLabel.LAST_SAMPLED,
        EvidenceLabel.UNKNOWN,
    )

    fun isCatalogLabel(name: String): Boolean =
        EvidenceLabel.fromCatalogName(name) != null

    /**
     * Whether a numeric UI field may render a concrete number.
     * UNKNOWN is displayable only as "unknown" — never as 0.
     */
    fun allowsNumericDisplay(label: EvidenceLabel): Boolean =
        label != EvidenceLabel.UNKNOWN

    /**
     * Whether the value may be presented without an age / sampledAt badge.
     * Only MEASURED (complete profile) is treated as "profile-bound present";
     * all others require sampledAt (catalog rule: all UI values expose
     * evidence label and sampledAt).
     */
    fun requiresSampledAt(label: EvidenceLabel): Boolean = true

    /**
     * Whether the value may drive hard admission / control-plane decisions
     * as if it were measured occupancy. REPORTED / ESTIMATED / LAST_SAMPLED
     * / UNKNOWN must not silently lower charge floors (FEAT-DASHBOARD §3).
     */
    fun mayDriveHardAdmission(label: EvidenceLabel): Boolean =
        label == EvidenceLabel.MEASURED

    /** Human-oriented short description (stable English key material). */
    fun descriptionKey(label: EvidenceLabel): String = when (label) {
        EvidenceLabel.MEASURED -> "evidence.measured"
        EvidenceLabel.ESTIMATED -> "evidence.estimated"
        EvidenceLabel.REPORTED -> "evidence.reported"
        EvidenceLabel.LAST_SAMPLED -> "evidence.last_sampled"
        EvidenceLabel.UNKNOWN -> "evidence.unknown"
    }
}

/**
 * A scalar bound to an evidence label and sample timestamp.
 * Catalog rule: all UI values expose evidence label and sampledAt.
 *
 * [value] is null only when [evidenceLabel] is [EvidenceLabel.UNKNOWN]
 * (or when the producer deliberately withholds a number). Never invent 0
 * for UNKNOWN.
 */
data class EvidencedValue(
    val value: Double?,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    /** Optional reporting source when label is REPORTED (engine/driver/worker). */
    val source: String? = null,
    /** Optional confidence in [0.0, 1.0] for ESTIMATED values. */
    val confidence: Double? = null,
    /** Optional conservative absolute error bound for ESTIMATED values. */
    val conservativeErrorBound: Double? = null,
    val unit: String? = null,
) {
    init {
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        confidence?.let {
            require(it in 0.0..1.0) { "confidence must be in [0.0, 1.0]" }
        }
        conservativeErrorBound?.let {
            require(it >= 0.0) { "conservativeErrorBound must be non-negative" }
        }
        if (evidenceLabel == EvidenceLabel.UNKNOWN) {
            // UNKNOWN may carry a null value; if a value is present it is still
            // labelled UNKNOWN and must not be treated as measured.
        }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) {
                "REPORTED values must disclose source (PROD-QUALITY §4)"
            }
        }
    }

    fun ageMs(nowEpochMs: Long): Long {
        require(nowEpochMs >= 0L) { "nowEpochMs must be non-negative" }
        return (nowEpochMs - sampledAtEpochMs).coerceAtLeast(0L)
    }
}
