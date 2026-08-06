package com.omnillm.features.autosetup.ranking

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.ModelCandidateInput
import com.omnillm.features.autosetup.domain.RankedRecommendation
import com.omnillm.features.autosetup.domain.RecommendationReason
import com.omnillm.features.autosetup.domain.RecommendationReasonCodes
import com.omnillm.features.autosetup.domain.RecommendationResult
import com.omnillm.features.autosetup.domain.RejectedCandidate
import com.omnillm.features.autosetup.domain.UserSetupPreferences

/**
 * Pure recommendation ranking (FEAT-AUTOSETUP §4).
 *
 * Hard order:
 * 1. safety / policy executable
 * 2. operation capability
 * 3. full resource fit
 * 4. known stability
 * 5. user preference
 * 6. performance estimate
 *
 * Unknown accelerator must not outrank known available CPU.
 * No domain mutation (ADR-002).
 */
object RecommendationRanker {

    fun rank(
        device: DeviceDiscoverySnapshot,
        preferences: UserSetupPreferences,
        candidates: List<ModelCandidateInput>,
        producedAtEpochMs: Long,
    ): RecommendationResult {
        val rejected = mutableListOf<RejectedCandidate>()
        val viable = mutableListOf<Scored>()

        for (c in candidates) {
            val hardReject = hardFilter(device, preferences, c)
            if (hardReject != null) {
                rejected += RejectedCandidate(c, listOf(hardReject))
                continue
            }
            val softReasons = mutableListOf<RecommendationReason>()
            softReasons += RecommendationReason(
                code = RecommendationReasonCodes.OPERATION_CAPABILITY,
                message = "operation capability ${c.targetOperation.id} is ${c.operationCapabilityState.name}",
                evidenceLabel = EvidenceLabel.REPORTED,
                details = mapOf(
                    "capability" to c.targetOperation.id,
                    "state" to c.operationCapabilityState.name,
                ),
            )
            if (c.stabilityEvidence == EvidenceLabel.MEASURED ||
                c.stabilityEvidence == EvidenceLabel.LAST_SAMPLED
            ) {
                softReasons += RecommendationReason(
                    code = RecommendationReasonCodes.STABILITY_EVIDENCE,
                    message = "stability evidence ${c.stabilityEvidence.name}",
                    evidenceLabel = c.stabilityEvidence,
                )
            }
            val score = scoreCandidate(device, preferences, c, softReasons)
            viable += Scored(c, score, softReasons)
        }

        // Sort: higher score first; CPU-known over unknown-accel when scores close.
        val ordered = viable.sortedWith(
            compareByDescending<Scored> { it.score }
                .thenBy { backendUnknownPenalty(device, it.candidate) }
                .thenBy { it.candidate.candidateId },
        )

        val ranked = ordered.mapIndexed { index, s ->
            val reasons = s.reasons.toMutableList()
            reasons += RecommendationReason(
                code = if (index == 0) {
                    RecommendationReasonCodes.SELECTED
                } else {
                    RecommendationReasonCodes.ALTERNATIVE
                },
                message = if (index == 0) {
                    "top recommendation after hard filters and preference scoring"
                } else {
                    "viable alternative rank ${index + 1}"
                },
                evidenceLabel = confidenceFor(s.candidate),
            )
            RankedRecommendation(
                candidate = s.candidate,
                rank = index + 1,
                confidence = confidenceFor(s.candidate),
                reasonCodes = reasons,
                estimatedDownloadBytes = s.candidate.packageBytes,
                estimatedPeakBytes = s.candidate.estimatedPeakBytes,
            )
        }

        val adjustments = if (ranked.isEmpty()) {
            minimalAdjustments(device, preferences, rejected)
        } else {
            emptyList()
        }

        return RecommendationResult(
            device = device,
            preferences = preferences,
            ranked = ranked,
            rejected = rejected,
            minimalViableAdjustments = adjustments,
            producedAtEpochMs = producedAtEpochMs,
        )
    }

    // ------------------------------------------------------------------
    // Hard filters (order matches FEAT-AUTOSETUP §4)
    // ------------------------------------------------------------------

    private fun hardFilter(
        device: DeviceDiscoverySnapshot,
        preferences: UserSetupPreferences,
        c: ModelCandidateInput,
    ): RecommendationReason? {
        // 1. Safety / policy / trust placement
        if (!c.authenticityOk) {
            return reason(
                RecommendationReasonCodes.SAFETY_POLICY,
                "authenticity not ok — cannot recommend",
                EvidenceLabel.REPORTED,
            )
        }
        if (!c.licenseOk) {
            return reason(
                RecommendationReasonCodes.LICENSE,
                "license not accepted / not ok",
                EvidenceLabel.REPORTED,
            )
        }
        if (!PlacementClassLabels.isExecutable(c.placementClass)) {
            return reason(
                RecommendationReasonCodes.TRUST_PLACEMENT,
                "placement not executable: ${c.placementClass}",
                EvidenceLabel.REPORTED,
                mapOf("placementClass" to c.placementClass),
            )
        }
        if (preferences.preferTrustedOnly &&
            c.placementClass != PlacementClassLabels.PRIVILEGED_TRUSTED &&
            c.placementClass != PlacementClassLabels.CRASH_CONTAINED_TRUSTED
        ) {
            return reason(
                RecommendationReasonCodes.TRUST_PLACEMENT,
                "user prefers trusted placement only",
                EvidenceLabel.REPORTED,
                mapOf("placementClass" to c.placementClass),
            )
        }

        // 2. Operation capability — UNKNOWN and UNSUPPORTED separated (INV-018)
        when (c.operationCapabilityState) {
            CapabilityState.UNSUPPORTED ->
                return reason(
                    RecommendationReasonCodes.OPERATION_CAPABILITY,
                    "operation capability unsupported: ${c.targetOperation.id}",
                    EvidenceLabel.REPORTED,
                )
            CapabilityState.UNKNOWN ->
                return reason(
                    RecommendationReasonCodes.CAPABILITY_UNKNOWN,
                    "operation capability unknown (fail closed): ${c.targetOperation.id}",
                    EvidenceLabel.UNKNOWN,
                )
            CapabilityState.TEMPORARILY_UNAVAILABLE ->
                return reason(
                    RecommendationReasonCodes.OPERATION_CAPABILITY,
                    "operation capability temporarily unavailable: ${c.targetOperation.id}",
                    EvidenceLabel.LAST_SAMPLED,
                )
            CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
        }

        // Operation must match user target
        if (c.targetOperation != preferences.targetOperation) {
            return reason(
                RecommendationReasonCodes.OPERATION_CAPABILITY,
                "candidate targets ${c.targetOperation.id}, user wants ${preferences.targetOperation.id}",
                EvidenceLabel.REPORTED,
            )
        }

        // 3. Full resource fit (RAM + storage)
        val ram = device.ramClassBytes
        if (ram != null && c.estimatedPeakBytes > 0L && c.estimatedPeakBytes > ram) {
            return reason(
                RecommendationReasonCodes.RESOURCE_FIT,
                "estimated peak exceeds device RAM class",
                EvidenceLabel.ESTIMATED,
                mapOf(
                    "estimatedPeakBytes" to c.estimatedPeakBytes.toString(),
                    "ramClassBytes" to ram.toString(),
                ),
            )
        }
        val free = device.freeStorageBytes
        if (free != null && c.packageBytes > free) {
            return reason(
                RecommendationReasonCodes.RESOURCE_FIT,
                "package size exceeds free storage",
                EvidenceLabel.ESTIMATED,
                mapOf(
                    "packageBytes" to c.packageBytes.toString(),
                    "freeStorageBytes" to free.toString(),
                ),
            )
        }
        preferences.maxStorageBytes?.let { budget ->
            if (c.packageBytes > budget) {
                return reason(
                    RecommendationReasonCodes.STORAGE_BUDGET,
                    "package size exceeds user storage budget",
                    EvidenceLabel.REPORTED,
                    mapOf(
                        "packageBytes" to c.packageBytes.toString(),
                        "maxStorageBytes" to budget.toString(),
                    ),
                )
            }
        }

        // Unknown accelerator backend: do not select over known CPU when backend is non-CPU
        // and accelerator evidence is UNKNOWN (hard demote handled in scoring; hard reject
        // only when backend requires accel and evidence is UNKNOWN with no CPU fallback path).
        if (isAcceleratorBackend(c.backend) && acceleratorUnknown(device)) {
            // Prefer rejection only if no known path — mark as soft via scoring penalty.
            // Still allow if backend is "cpu" mixed; pure GPU/NPU unknown fails closed.
            if (!c.backend.equals("cpu", ignoreCase = true) &&
                !c.backend.equals("AUTO", ignoreCase = true)
            ) {
                return reason(
                    RecommendationReasonCodes.ACCELERATOR_UNKNOWN,
                    "accelerator evidence UNKNOWN; refuse non-CPU backend",
                    EvidenceLabel.UNKNOWN,
                    mapOf("backend" to c.backend),
                )
            }
        }

        return null
    }

    private fun scoreCandidate(
        device: DeviceDiscoverySnapshot,
        preferences: UserSetupPreferences,
        c: ModelCandidateInput,
        reasons: MutableList<RecommendationReason>,
    ): Double {
        // 4. Known stability boost
        val stability = when (c.stabilityEvidence) {
            EvidenceLabel.MEASURED -> 1.0
            EvidenceLabel.LAST_SAMPLED -> 0.8
            EvidenceLabel.REPORTED -> 0.5
            EvidenceLabel.ESTIMATED -> 0.3
            EvidenceLabel.UNKNOWN -> 0.0
        }

        // 5. User preference blend
        val quality = c.qualityScore * preferences.qualityPreference
        val speed = c.speedScore * preferences.speedPreference
        reasons += RecommendationReason(
            code = RecommendationReasonCodes.USER_PREFERENCE,
            message = "quality=${"%.2f".format(c.qualityScore)} speed=${"%.2f".format(c.speedScore)}",
            evidenceLabel = EvidenceLabel.ESTIMATED,
        )

        // 6. Performance estimate (smaller peak slightly preferred when low power)
        val perf = if (preferences.preferLowPower) {
            1.0 - (c.estimatedPeakBytes.toDouble() / (device.ramClassBytes?.toDouble()?.coerceAtLeast(1.0) ?: 1.0))
                .coerceIn(0.0, 1.0)
        } else {
            c.speedScore
        }
        reasons += RecommendationReason(
            code = RecommendationReasonCodes.PERFORMANCE_ESTIMATE,
            message = "performance preference score ${"%.2f".format(perf)}",
            evidenceLabel = EvidenceLabel.ESTIMATED,
        )

        var score = stability * 0.30 + quality * 0.30 + speed * 0.25 + perf * 0.15

        // Preferred backend boost
        preferences.preferredBackend?.let { pref ->
            if (c.backend.equals(pref, ignoreCase = true)) {
                score += 0.05
            }
        }

        // Already installed READY slightly preferred (less download risk)
        if (c.readyInstallationId != null) {
            score += 0.08
        }

        // CPU preferred when accelerators unknown
        if (acceleratorUnknown(device) && c.backend.equals("cpu", ignoreCase = true)) {
            score += 0.10
        }

        return score
    }

    private fun confidenceFor(c: ModelCandidateInput): EvidenceLabel =
        when {
            c.stabilityEvidence == EvidenceLabel.MEASURED -> EvidenceLabel.MEASURED
            c.stabilityEvidence == EvidenceLabel.LAST_SAMPLED -> EvidenceLabel.LAST_SAMPLED
            c.stabilityEvidence == EvidenceLabel.REPORTED -> EvidenceLabel.REPORTED
            else -> EvidenceLabel.ESTIMATED
        }

    private fun backendUnknownPenalty(device: DeviceDiscoverySnapshot, c: ModelCandidateInput): Int {
        if (acceleratorUnknown(device) && isAcceleratorBackend(c.backend)) return 1
        return 0
    }

    private fun isAcceleratorBackend(backend: String): Boolean {
        val b = backend.lowercase()
        return b.contains("gpu") || b.contains("npu") || b.contains("vulkan") ||
            b.contains("opencl") || b.contains("nnapi") || b.contains("cuda")
    }

    private fun acceleratorUnknown(device: DeviceDiscoverySnapshot): Boolean =
        device.gpuEvidenceLabel == EvidenceLabel.UNKNOWN &&
            device.npuEvidenceLabel == EvidenceLabel.UNKNOWN

    private fun minimalAdjustments(
        device: DeviceDiscoverySnapshot,
        preferences: UserSetupPreferences,
        rejected: List<RejectedCandidate>,
    ): List<String> {
        val codes = rejected.flatMap { r -> r.reasons.map { it.code } }.toSet()
        val out = mutableListOf<String>()
        if (RecommendationReasonCodes.RESOURCE_FIT in codes ||
            RecommendationReasonCodes.STORAGE_BUDGET in codes
        ) {
            out += "reduce-model-size"
            out += "reduce-context"
            out += "choose-higher-quantization"
        }
        if (RecommendationReasonCodes.ACCELERATOR_UNKNOWN in codes) {
            out += "use-cpu-backend"
        }
        if (RecommendationReasonCodes.TRUST_PLACEMENT in codes) {
            out += "use-safe-mode"
            out += "review-risk"
        }
        if (RecommendationReasonCodes.LICENSE in codes) {
            out += "accept-license-or-select-another-model"
        }
        if (out.isEmpty() && rejected.isNotEmpty()) {
            out += "select-another-model"
        }
        if (device.freeStorageBytes != null && preferences.maxStorageBytes != null) {
            out += "increase-storage-budget-or-free-space"
        }
        return out.distinct()
    }

    private fun reason(
        code: String,
        message: String,
        evidence: EvidenceLabel,
        details: Map<String, String> = emptyMap(),
    ): RecommendationReason =
        RecommendationReason(code, message, evidence, details)

    private data class Scored(
        val candidate: ModelCandidateInput,
        val score: Double,
        val reasons: List<RecommendationReason>,
    )
}
