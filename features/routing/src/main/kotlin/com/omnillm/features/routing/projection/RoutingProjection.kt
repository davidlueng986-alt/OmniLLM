package com.omnillm.features.routing.projection

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.features.routing.api.ActualRoutingUi
import com.omnillm.features.routing.api.CandidateRowUi
import com.omnillm.features.routing.api.ContinuityUi
import com.omnillm.features.routing.api.RejectionRowUi
import com.omnillm.features.routing.api.RoutingDecisionView
import com.omnillm.features.routing.api.RoutingPreferenceView
import com.omnillm.features.routing.api.RoutingUiPhase
import com.omnillm.features.routing.domain.ContinuityDecision
import com.omnillm.runtime.orchestrator.ActualRouting
import com.omnillm.runtime.orchestrator.CandidateRejection
import com.omnillm.runtime.orchestrator.EarliestStartEstimate
import com.omnillm.runtime.orchestrator.PlanningResult
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference

/**
 * Pure projections for FEAT-ROUTING UI / Dashboard consistency (FEATURE-SYSTEM).
 * No domain mutation.
 */
object RoutingProjection {

    fun preferenceView(pref: RoutingPreference): RoutingPreferenceView =
        RoutingPreferenceView(
            fallbackPolicy = pref.fallbackPolicy,
            revisionAllowlistHex = pref.revisionAllowlist.map { it.hex },
            allowedBackends = pref.allowedBackends,
            minimumPlacementClass = pref.minimumPlacementClass,
            preferredBackend = pref.preferredBackend,
        )

    fun rejectionRow(r: CandidateRejection): RejectionRowUi =
        RejectionRowUi(
            candidateId = r.candidateId,
            code = r.code,
            message = r.message,
            details = r.details,
        )

    fun candidateRow(
        c: RoutingCandidate,
        selectedId: String?,
        viableIds: Set<String>,
    ): CandidateRowUi =
        CandidateRowUi(
            candidateId = c.candidateId,
            modelRevisionIdHex = c.modelRevisionId.hex,
            engineBuildId = c.engineBuildId.value,
            backend = c.backend,
            placementClass = c.placementClass,
            installationId = c.installationId.value,
            isPrimary = c.isPrimary,
            selected = selectedId != null && c.candidateId == selectedId,
            viable = c.candidateId in viableIds,
        )

    fun actualRoutingUi(a: ActualRouting): ActualRoutingUi =
        ActualRoutingUi(
            modelRevisionIdHex = a.modelRevisionId.hex,
            engineBuildId = a.engineBuildId.value,
            backend = a.backend,
            placementClass = a.placementClass,
            installationId = a.installationId.value,
            candidateId = a.candidateId,
            usedFallback = a.usedFallback,
            fallbackPolicy = a.fallbackPolicy,
            rejectionTrail = a.rejectionTrail.map { rejectionRow(it) },
        )

    fun continuityUi(d: ContinuityDecision): ContinuityUi =
        ContinuityUi(
            disposition = d.disposition,
            prefixDecision = d.prefixDecision,
            kvReused = d.kvReused,
            disclosedRebuild = d.disclosedRebuild,
            reason = d.reason,
        )

    /**
     * Build a decision view from policy filter + optional planner outcome.
     * Ensures every input candidate appears as selected, viable, or rejected.
     */
    fun decisionView(
        requestedRevisionHex: String,
        preference: RoutingPreference,
        allCandidates: List<RoutingCandidate>,
        policyRejections: List<CandidateRejection>,
        planning: PlanningResult?,
        selected: RoutingCandidate?,
        actual: ActualRouting?,
        continuity: ContinuityDecision?,
        earliest: EarliestStartEstimate? = null,
        requestId: String? = null,
        requestState: String? = null,
    ): RoutingDecisionView {
        val planRejections = planning?.rejections.orEmpty()
        val allRejections = (policyRejections + planRejections).map { rejectionRow(it) }
        val viableIds = planning?.viable?.map { it.candidate.candidateId }?.toSet().orEmpty()
        val selectedId = selected?.candidateId ?: actual?.candidateId
        val rows = allCandidates.map { candidateRow(it, selectedId, viableIds) }
        return RoutingDecisionView(
            requestedRevisionIdHex = requestedRevisionHex,
            preference = preferenceView(preference),
            candidates = rows,
            rejections = allRejections,
            viableCount = planning?.viable?.size ?: if (selected != null) 1 else 0,
            selected = selected?.let { candidateRow(it, selectedId, viableIds) },
            actualRouting = actual?.let { actualRoutingUi(it) },
            continuity = continuity?.let { continuityUi(it) },
            earliestStartPolicyVersion = earliest?.policyVersion,
            earliestStartMonotonic = earliest?.estimatedStartMonotonic,
            requestId = requestId,
            requestState = requestState,
        )
    }

    fun resolveUiPhase(
        hasDecision: Boolean,
        loading: Boolean,
        error: Boolean,
    ): RoutingUiPhase =
        when {
            loading -> RoutingUiPhase.LOADING
            error -> RoutingUiPhase.ERROR
            hasDecision -> RoutingUiPhase.READY
            else -> RoutingUiPhase.EMPTY
        }

    /**
     * Explainability invariant: every unelected candidate must have ≥1 rejection
     * when a selection exists (FEAT-ROUTING §6.6).
     */
    fun explainabilityHolds(view: RoutingDecisionView): Boolean {
        if (view.selected == null) {
            // Full failure: every candidate should be rejected if any were supplied.
            return view.candidates.isEmpty() ||
                view.candidates.all { c -> view.rejections.any { it.candidateId == c.candidateId } }
        }
        val selectedId = view.selected.candidateId
        return view.candidates
            .filter { it.candidateId != selectedId && !it.viable }
            .all { c -> view.rejections.any { it.candidateId == c.candidateId } }
    }

    /** Disclosure invariant: usedFallback implies non-NONE policy or non-primary. */
    fun fallbackDisclosureHolds(view: RoutingDecisionView): Boolean {
        val actual = view.actualRouting ?: return true
        if (!actual.usedFallback) return true
        // Must disclose policy + actual backend/revision (fields non-blank).
        return actual.backend.isNotEmpty() &&
            actual.modelRevisionIdHex.isNotEmpty() &&
            actual.engineBuildId.isNotEmpty() &&
            (actual.fallbackPolicy != FallbackPolicy.NONE || actual.usedFallback)
    }
}
