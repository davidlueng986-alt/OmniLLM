package com.omnillm.features.autosetup.domain

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.PlacementClassLabels

/**
 * Stable recommendation reason / rejection codes (FEAT-AUTOSETUP §4, §8.6).
 *
 * Strings for UI/diagnostics projection — same pattern as orchestrator
 * [com.omnillm.runtime.orchestrator.CandidateRejectionCodes]. Not catalog enums.
 */
object RecommendationReasonCodes {
    const val SAFETY_POLICY: String = "SAFETY_POLICY"
    const val TRUST_PLACEMENT: String = "TRUST_PLACEMENT"
    const val LICENSE: String = "LICENSE"
    const val OPERATION_CAPABILITY: String = "OPERATION_CAPABILITY"
    const val CAPABILITY_UNKNOWN: String = "CAPABILITY_UNKNOWN"
    const val RESOURCE_FIT: String = "RESOURCE_FIT"
    const val STORAGE_BUDGET: String = "STORAGE_BUDGET"
    const val STABILITY_EVIDENCE: String = "STABILITY_EVIDENCE"
    const val ACCELERATOR_UNKNOWN: String = "ACCELERATOR_UNKNOWN"
    const val USER_PREFERENCE: String = "USER_PREFERENCE"
    const val PERFORMANCE_ESTIMATE: String = "PERFORMANCE_ESTIMATE"
    const val SELECTED: String = "SELECTED"
    const val ALTERNATIVE: String = "ALTERNATIVE"

    val ALL: Set<String> = setOf(
        SAFETY_POLICY,
        TRUST_PLACEMENT,
        LICENSE,
        OPERATION_CAPABILITY,
        CAPABILITY_UNKNOWN,
        RESOURCE_FIT,
        STORAGE_BUDGET,
        STABILITY_EVIDENCE,
        ACCELERATOR_UNKNOWN,
        USER_PREFERENCE,
        PERFORMANCE_ESTIMATE,
        SELECTED,
        ALTERNATIVE,
    )

    fun isKnown(code: String): Boolean = code in ALL
}

/** One explainable reason attached to a ranked or rejected candidate. */
data class RecommendationReason(
    val code: String,
    val message: String,
    val evidenceLabel: EvidenceLabel = EvidenceLabel.ESTIMATED,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(RecommendationReasonCodes.isKnown(code)) {
            "unknown recommendation reason code (fail closed): $code"
        }
        require(message.isNotEmpty()) { "reason message must be non-empty" }
    }
}

/**
 * Catalog / installed candidate input to pure recommendation ranking.
 * Does not open weights or mutate domain state (ADR-002).
 */
data class ModelCandidateInput(
    val candidateId: String,
    val displayName: String,
    val modelRevisionId: ModelRevisionId,
    val artifactPackageId: ArtifactPackageId,
    val engineBuildId: EngineBuildId,
    val backend: String,
    val placementClass: String,
    val operationCapabilityState: CapabilityState,
    val targetOperation: CapabilityId,
    /** Estimated peak working set bytes for conservative fit check. */
    val estimatedPeakBytes: Long,
    /** Download / package size bytes. */
    val packageBytes: Long,
    val licenseOk: Boolean,
    val authenticityOk: Boolean,
    /** Known-stable when MEASURED/PASS-like evidence exists; UNKNOWN otherwise. */
    val stabilityEvidence: EvidenceLabel = EvidenceLabel.UNKNOWN,
    /** Relative quality score 0.0–1.0 (catalog or ESTIMATED). */
    val qualityScore: Double = 0.5,
    /** Relative speed score 0.0–1.0 (higher = faster). */
    val speedScore: Double = 0.5,
    val licenseLabel: String? = null,
    val sourceTrustLabel: String? = null,
    /** Already READY installation id string when present; null if needs acquire. */
    val readyInstallationId: String? = null,
    val notes: Map<String, String> = emptyMap(),
) {
    init {
        require(candidateId.isNotEmpty()) { "candidateId must be non-empty" }
        require(displayName.isNotEmpty()) { "displayName must be non-empty" }
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(PlacementClassLabels.isKnown(placementClass)) {
            "unknown placement class: $placementClass"
        }
        require(estimatedPeakBytes >= 0L) { "estimatedPeakBytes must be non-negative" }
        require(packageBytes >= 0L) { "packageBytes must be non-negative" }
        require(qualityScore in 0.0..1.0) { "qualityScore must be 0..1" }
        require(speedScore in 0.0..1.0) { "speedScore must be 0..1" }
        CapabilityId.requireFromId(targetOperation.id)
    }
}

/** Ranked viable recommendation shown to the user. */
data class RankedRecommendation(
    val candidate: ModelCandidateInput,
    val rank: Int,
    val confidence: EvidenceLabel,
    val reasonCodes: List<RecommendationReason>,
    val estimatedDownloadBytes: Long,
    val estimatedPeakBytes: Long,
) {
    init {
        require(rank >= 1) { "rank must be >= 1" }
        require(reasonCodes.isNotEmpty()) { "at least one reason required (FEAT-AUTOSETUP §8.6)" }
        require(estimatedDownloadBytes >= 0L) { "estimatedDownloadBytes must be non-negative" }
        require(estimatedPeakBytes >= 0L) { "estimatedPeakBytes must be non-negative" }
    }
}

/** Rejected candidate with stable reason trail. */
data class RejectedCandidate(
    val candidate: ModelCandidateInput,
    val reasons: List<RecommendationReason>,
) {
    init {
        require(reasons.isNotEmpty()) { "rejected candidate requires at least one reason" }
    }
}

/**
 * Full recommendation outcome (FEAT-AUTOSETUP §4).
 * Includes ranked list, rejections, and optional minimal viable adjustments.
 */
data class RecommendationResult(
    val device: DeviceDiscoverySnapshot,
    val preferences: UserSetupPreferences,
    val ranked: List<RankedRecommendation>,
    val rejected: List<RejectedCandidate>,
    /** Suggested soft adjustments when no viable candidate (smaller context, CPU, etc.). */
    val minimalViableAdjustments: List<String> = emptyList(),
    val producedAtEpochMs: Long,
) {
    val hasViable: Boolean get() = ranked.isNotEmpty()
    val top: RankedRecommendation? get() = ranked.firstOrNull()
}
