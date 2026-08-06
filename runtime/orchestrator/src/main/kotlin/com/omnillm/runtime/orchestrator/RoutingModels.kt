package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.SessionHandleId
import com.omnillm.core.identity.InstallationId
import com.omnillm.engines.api.PlacementClassLabels

/**
 * Caller routing inputs (FEAT-ROUTING §1, CORE-ORCHESTRATOR §4).
 *
 * Cross-revision fallback is allowed only when [fallbackPolicy] is [FallbackPolicy.ALLOW_LIST]
 * and [revisionAllowlist] is non-empty. Alias resolution is fixed at claim time.
 */
data class RoutingPreference(
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.NONE,
    /** Explicit revision allowlist for [FallbackPolicy.ALLOW_LIST]. */
    val revisionAllowlist: List<ModelRevisionId> = emptyList(),
    /** Optional backend allowlist (empty = any known executable backend). */
    val allowedBackends: Set<String> = emptySet(),
    /** Minimum trust placement class required (SEC-PLACEMENT label). */
    val minimumPlacementClass: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
    val preferredBackend: String? = null,
) {
    init {
        if (fallbackPolicy == FallbackPolicy.ALLOW_LIST) {
            require(revisionAllowlist.isNotEmpty()) {
                "ALLOW_LIST requires a non-empty revisionAllowlist"
            }
        }
        require(PlacementClassLabels.isKnown(minimumPlacementClass)) {
            "unknown placement class: $minimumPlacementClass"
        }
    }
}

/**
 * A concrete routing candidate before evaluation (exact revision or allowlisted alt).
 * Candidate id is stable for diagnostics (not a client-mutable handle).
 */
data class RoutingCandidate(
    val candidateId: String,
    val modelRevisionId: ModelRevisionId,
    val installationId: InstallationId,
    val engineBuildId: EngineBuildId,
    val backend: String,
    val placementClass: String,
    val loadKeyDigest: Sha256Digest,
    /** Whether this candidate is the primary (requested) vs a fallback. */
    val isPrimary: Boolean,
    val deviceExecutionFingerprint: DeviceExecutionFingerprint,
) {
    init {
        require(candidateId.isNotEmpty()) { "candidateId must be non-empty" }
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(PlacementClassLabels.isKnown(placementClass)) {
            "unknown placement class: $placementClass"
        }
    }
}

/** Accepted candidate with pure plan envelope (ADR-002 — no domain mutation). */
data class PlannedCandidate(
    val candidate: RoutingCandidate,
    val resourceEnvelope: ResourceEnvelope,
    val planInputDigest: Sha256Digest,
    val costClass: String,
    val costUnits: Long,
) {
    init {
        require(CostClassLabels.isKnown(costClass)) { "unknown cost class: $costClass" }
        require(costUnits > 0L) { "costUnits must be positive" }
    }
}

/**
 * Full planning outcome: ordered viable candidates + every rejection reason.
 * Plan phase must not mutate domain state (ADR-002 / INV-002–003).
 */
data class PlanningResult(
    val viable: List<PlannedCandidate>,
    val rejections: List<CandidateRejection>,
) {
    val hasViable: Boolean get() = viable.isNotEmpty()
}

/**
 * Actual routing chosen after admission (FEAT-ROUTING: report actual revision/engine/backend).
 * Always disclosed on the response/trace path — never silent fallback.
 */
data class ActualRouting(
    val modelRevisionId: ModelRevisionId,
    val engineBuildId: EngineBuildId,
    val backend: String,
    val placementClass: String,
    val installationId: InstallationId,
    val candidateId: String,
    val usedFallback: Boolean,
    val fallbackPolicy: FallbackPolicy,
    val rejectionTrail: List<CandidateRejection> = emptyList(),
) {
    init {
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(candidateId.isNotEmpty()) { "candidateId must be non-empty" }
        require(PlacementClassLabels.isKnown(placementClass)) {
            "unknown placement class: $placementClass"
        }
    }
}

/**
 * Client-facing orchestration request after transport claim envelope parsing.
 * [requestId] / [idempotencyKey] are client-generated (ADR-004/005).
 */
data class OrchestrationRequest(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val idempotencyKey: IdempotencyKey,
    val operationKind: String,
    val canonicalRequestDigest: Sha256Digest,
    val requiredCapabilities: Set<CapabilityId>,
    /** Primary requested revision (alias already resolved at accept). */
    val requestedRevisionId: ModelRevisionId,
    val candidates: List<RoutingCandidate>,
    val routing: RoutingPreference = RoutingPreference(),
    val costClass: String = CostClassLabels.GENERATION,
    val costUnits: Long? = null,
    val sourceSessionId: SessionHandleId? = null,
    val sourceSessionEpoch: Long? = null,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val deadlineMonotonic: Long,
    val cancelRequested: Boolean = false,
) {
    init {
        require(operationKind.isNotEmpty()) { "operationKind must be non-empty" }
        require(candidates.isNotEmpty()) { "at least one candidate is required" }
        require(CostClassLabels.isKnown(costClass)) { "unknown cost class: $costClass" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(deadlineMonotonic >= 0L) { "deadlineMonotonic must be non-negative" }
        sourceSessionEpoch?.let {
            require(it >= 0L) { "sourceSessionEpoch must be non-negative" }
        }
        costUnits?.let { require(it > 0L) { "costUnits must be positive when set" } }
        // Unknown capabilities fail closed (INV-018) — set membership is catalog-backed.
        for (cap in requiredCapabilities) {
            CapabilityId.requireFromId(cap.id)
        }
    }

    fun resolvedCostUnits(): Long = costUnits ?: CostClassLabels.defaultCostUnits(costClass)
}
