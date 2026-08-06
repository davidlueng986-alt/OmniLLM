package com.omnillm.features.routing.api

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.features.routing.domain.SessionContinuityPolicy

/**
 * UI-domain models for FEAT-ROUTING.
 *
 * Pure projections — no domain mutation (FEATURE-SYSTEM, ADR-002).
 * FallbackPolicy / CapabilityState / PrefixDecision come from catalogs only.
 */

/** Screen-level presentation phase for routing explain / policy UI. */
enum class RoutingUiPhase {
    EMPTY,
    LOADING,
    READY,
    ERROR,
}

/**
 * Caller-facing routing preference view (catalog FallbackPolicy).
 * Prefer building via [com.omnillm.features.routing.domain.FallbackPolicyRules.buildPreference].
 */
data class RoutingPreferenceView(
    val fallbackPolicy: FallbackPolicy,
    val revisionAllowlistHex: List<String> = emptyList(),
    val allowedBackends: Set<String> = emptySet(),
    val minimumPlacementClass: String,
    val preferredBackend: String? = null,
)

/** One routing candidate row for explainability (FEAT-ROUTING §2 / §6.6). */
data class CandidateRowUi(
    val candidateId: String,
    val modelRevisionIdHex: String,
    val engineBuildId: String,
    val backend: String,
    val placementClass: String,
    val installationId: String,
    val isPrimary: Boolean,
    val selected: Boolean,
    val viable: Boolean,
)

/** Stable rejection reason projected for UI / Dashboard consistency. */
data class RejectionRowUi(
    val candidateId: String,
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Actual routing disclosure (FEAT-ROUTING: never silent fallback).
 * Always populated when a candidate was admitted.
 */
data class ActualRoutingUi(
    val modelRevisionIdHex: String,
    val engineBuildId: String,
    val backend: String,
    val placementClass: String,
    val installationId: String,
    val candidateId: String,
    val usedFallback: Boolean,
    val fallbackPolicy: FallbackPolicy,
    val rejectionTrail: List<RejectionRowUi> = emptyList(),
)

/**
 * Session continuity projection (FEAT-ROUTING §4).
 * When [disposition] is REBUILT, UI must not claim original KV continuity.
 */
data class ContinuityUi(
    val disposition: String,
    val prefixDecision: PrefixDecision,
    val kvReused: Boolean,
    val disclosedRebuild: Boolean,
    val reason: String,
) {
    init {
        require(SessionContinuityPolicy.isKnownDisposition(disposition)) {
            "unknown continuity disposition: $disposition"
        }
    }
}

/**
 * Full routing decision explanation for one plan/submit outcome.
 * Every unelected candidate has at least one rejection (explainability).
 */
data class RoutingDecisionView(
    val requestedRevisionIdHex: String,
    val preference: RoutingPreferenceView,
    val candidates: List<CandidateRowUi>,
    val rejections: List<RejectionRowUi>,
    val viableCount: Int,
    val selected: CandidateRowUi?,
    val actualRouting: ActualRoutingUi?,
    val continuity: ContinuityUi?,
    val earliestStartPolicyVersion: String? = null,
    val earliestStartMonotonic: Long? = null,
    val requestId: String? = null,
    val requestState: String? = null,
) {
    val hasViable: Boolean get() = viableCount > 0
    val usedFallback: Boolean get() = actualRouting?.usedFallback == true
}

/**
 * Capability negotiation cell for MULTI_MODEL_ROUTING / FALLBACK_POLICY / …
 * (CAPABILITY_NEGOTIATION; fail closed on UNKNOWN / UNSUPPORTED).
 */
data class RoutingCapabilityCellUi(
    val capabilityId: String,
    val state: CapabilityState,
    val conditions: List<String> = emptyList(),
)

data class RoutingCapabilityNegotiation(
    val allSupported: Boolean,
    val cells: List<RoutingCapabilityCellUi>,
) {
    val unsupportedIds: List<String>
        get() = cells.filter { it.state == CapabilityState.UNSUPPORTED }.map { it.capabilityId }

    val blockingIds: List<String>
        get() = cells.filter {
            it.state != CapabilityState.SUPPORTED && it.state != CapabilityState.CONDITIONAL
        }.map { it.capabilityId }
}

/** Snapshot for routing screen / Dashboard strip. */
data class RoutingSnapshot(
    val phase: RoutingUiPhase,
    val lastDecision: RoutingDecisionView?,
    val preference: RoutingPreferenceView?,
    val negotiation: RoutingCapabilityNegotiation?,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    /** Alias table keys for expert controls (values are revision hex). */
    val aliasEntries: Map<String, String> = emptyMap(),
)

/**
 * Alias → revision fixation result at request accept (FEAT-ROUTING §1).
 */
data class AliasResolveView(
    val input: String,
    val revisionIdHex: String,
    val resolvedFromAlias: Boolean,
)

/**
 * Inputs for plan-only routing (no execute). Client supplies candidates already
 * resolved to exact revisions (alias fixed at accept).
 */
data class PlanRouteSpec(
    val requestedRevisionIdHex: String,
    val preference: RoutingPreferenceView,
    /** Opaque candidates already assembled by control plane / catalog. */
    val candidates: List<RoutingCandidateSpec>,
    val requiredCapabilityIds: Set<String> = emptySet(),
    val sourceSessionPresent: Boolean = false,
    val sourceRevisionIdHex: String? = null,
    val sourceLoadKeyDigestHex: String? = null,
    val sourceEngineBuildId: String? = null,
    val provenStateTransfer: Boolean = false,
)

/** Lightweight candidate identity for feature API (maps to orchestrator RoutingCandidate). */
data class RoutingCandidateSpec(
    val candidateId: String,
    val modelRevisionIdHex: String,
    val installationId: String,
    val engineBuildId: String,
    val backend: String,
    val placementClass: String,
    val loadKeyDigestHex: String,
    val isPrimary: Boolean,
    val deviceExecutionFingerprint: String,
)

/**
 * Submit identity for routed inference (ADR-004/005).
 * Client generates requestId / idempotencyKey before send.
 */
data class RouteSubmitIdentity(
    val requestId: String,
    val idempotencyKey: String,
    val canonicalRequestDigestHex: String,
    val operationKind: String = "CHAT",
    val deadlineMonotonic: Long,
    val runtimeEpoch: Long = 1L,
    val revocationEpoch: Long = 0L,
    val costClass: String = "GENERATION",
    val costUnits: Long? = null,
    val sourceSessionId: String? = null,
    val sourceSessionEpoch: Long? = null,
)
