package com.omnillm.features.routing.domain

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.orchestrator.CandidateRejection
import com.omnillm.runtime.orchestrator.CandidateRejectionCodes
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference

/**
 * Pure fallback-policy validation and candidate filtering (FEAT-ROUTING §1, §6).
 *
 * Normative semantics:
 * - [FallbackPolicy.NONE]: no backend or revision substitution — primary (exact)
 *   candidate only. Failure returns rejection reasons; never silent fallback.
 * - [FallbackPolicy.SAME_REVISION_ONLY]: same revision, alternate backends allowed
 *   when caller policy and backend allowlist permit.
 * - [FallbackPolicy.ALLOW_LIST]: primary revision + explicit revision allowlist only.
 *   Cross-revision never occurs without a non-empty allowlist.
 *
 * Does not mutate domain state (ADR-002).
 */
object FallbackPolicyRules {

    /**
     * Validate a caller [RoutingPreference] before planning.
     * Unknown placement / empty ALLOW_LIST / self-contradictory allowlists fail closed.
     */
    fun validatePreference(preference: RoutingPreference): PolicyValidation {
        if (!PlacementClassLabels.isKnown(preference.minimumPlacementClass)) {
            return PolicyValidation.Invalid(
                message = "unknown placement class (fail closed)",
                code = CandidateRejectionCodes.TRUST_PLACEMENT,
                details = mapOf("minimumPlacementClass" to preference.minimumPlacementClass),
            )
        }
        if (!PlacementClassLabels.isExecutable(preference.minimumPlacementClass) &&
            preference.minimumPlacementClass != PlacementClassLabels.TRUST_PLACEMENT_REQUIRED
        ) {
            // TRUST_PLACEMENT_REQUIRED is a valid minimum that rejects all executable candidates.
            // Other non-executable labels are not valid minima.
        }
        when (preference.fallbackPolicy) {
            FallbackPolicy.NONE -> {
                if (preference.revisionAllowlist.isNotEmpty()) {
                    return PolicyValidation.Invalid(
                        message = "NONE forbids a revision allowlist (no cross-revision fallback)",
                        code = CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
                        details = mapOf(
                            "fallbackPolicy" to FallbackPolicy.NONE.name,
                            "allowlistSize" to preference.revisionAllowlist.size.toString(),
                        ),
                    )
                }
            }
            FallbackPolicy.SAME_REVISION_ONLY -> {
                if (preference.revisionAllowlist.isNotEmpty()) {
                    return PolicyValidation.Invalid(
                        message = "SAME_REVISION_ONLY forbids a revision allowlist; use ALLOW_LIST",
                        code = CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
                        details = mapOf(
                            "fallbackPolicy" to FallbackPolicy.SAME_REVISION_ONLY.name,
                        ),
                    )
                }
            }
            FallbackPolicy.ALLOW_LIST -> {
                if (preference.revisionAllowlist.isEmpty()) {
                    return PolicyValidation.Invalid(
                        message = "ALLOW_LIST requires a non-empty revisionAllowlist",
                        code = CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
                        details = mapOf("fallbackPolicy" to FallbackPolicy.ALLOW_LIST.name),
                    )
                }
            }
        }
        val preferred = preference.preferredBackend
        if (preferred != null &&
            preference.allowedBackends.isNotEmpty() &&
            preferred !in preference.allowedBackends
        ) {
            return PolicyValidation.Invalid(
                message = "preferredBackend not in allowedBackends",
                code = CandidateRejectionCodes.ENGINE_BACKEND,
                details = mapOf(
                    "preferredBackend" to preferred,
                    "allowedBackends" to preference.allowedBackends.joinToString(","),
                ),
            )
        }
        return PolicyValidation.Ok
    }

    /**
     * Build a catalog-backed [RoutingPreference] with fail-closed validation.
     * Prefer this over constructing [RoutingPreference] when feature-level rules apply.
     */
    fun buildPreference(
        fallbackPolicy: FallbackPolicy,
        revisionAllowlist: List<ModelRevisionId> = emptyList(),
        allowedBackends: Set<String> = emptySet(),
        minimumPlacementClass: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
        preferredBackend: String? = null,
    ): PolicyBuildResult {
        // Catalog constructor already requires non-empty allowlist for ALLOW_LIST.
        val pref = try {
            RoutingPreference(
                fallbackPolicy = fallbackPolicy,
                revisionAllowlist = revisionAllowlist,
                allowedBackends = allowedBackends,
                minimumPlacementClass = minimumPlacementClass,
                preferredBackend = preferredBackend,
            )
        } catch (e: IllegalArgumentException) {
            return PolicyBuildResult.Invalid(
                message = e.message ?: "invalid routing preference",
                code = CandidateRejectionCodes.POLICY,
            )
        }
        return when (val v = validatePreference(pref)) {
            is PolicyValidation.Ok -> PolicyBuildResult.Ok(pref)
            is PolicyValidation.Invalid ->
                PolicyBuildResult.Invalid(message = v.message, code = v.code, details = v.details)
        }
    }

    /**
     * Apply fallback policy to candidates **before** expensive plan work (FEAT-ROUTING §6.1–3).
     *
     * Returns ordered survivors (primary first) plus explicit rejections for every
     * candidate filtered out — never silent drop.
     */
    fun apply(
        requestedRevisionId: ModelRevisionId,
        preference: RoutingPreference,
        candidates: List<RoutingCandidate>,
    ): PolicyFilterResult {
        require(candidates.isNotEmpty()) { "at least one candidate is required" }
        when (val v = validatePreference(preference)) {
            is PolicyValidation.Invalid ->
                return PolicyFilterResult(
                    allowed = emptyList(),
                    rejections = candidates.map {
                        CandidateRejection(
                            candidateId = it.candidateId,
                            code = v.code,
                            message = v.message,
                            details = v.details,
                        )
                    },
                )
            PolicyValidation.Ok -> Unit
        }

        val allowed = mutableListOf<RoutingCandidate>()
        val rejections = mutableListOf<CandidateRejection>()
        val allowset = preference.revisionAllowlist.map { it.hex }.toSet()

        for (c in candidates) {
            val reason = rejectionReason(requestedRevisionId, preference, c, allowset)
            if (reason != null) {
                rejections += reason
            } else {
                allowed += c
            }
        }

        // Stable order: primary first, then candidateId for determinism.
        val ordered = allowed.sortedWith(
            compareByDescending<RoutingCandidate> { it.isPrimary }
                .thenBy { it.candidateId },
        )
        return PolicyFilterResult(allowed = ordered, rejections = rejections)
    }

    /**
     * Whether [selected] is a fallback relative to the caller's primary request
     * (FEAT-ROUTING: actual backend/revision must be disclosed when true).
     */
    fun isFallbackUsage(
        requestedRevisionId: ModelRevisionId,
        selected: RoutingCandidate,
    ): Boolean =
        !selected.isPrimary || selected.modelRevisionId.hex != requestedRevisionId.hex

    /**
     * Cross-revision selection is legal only under ALLOW_LIST with the revision
     * on the allowlist (or equal to requested). Used for negative-case guards.
     */
    fun allowsCrossRevision(
        preference: RoutingPreference,
        requestedRevisionId: ModelRevisionId,
        candidateRevisionId: ModelRevisionId,
    ): Boolean {
        if (candidateRevisionId.hex == requestedRevisionId.hex) return true
        if (preference.fallbackPolicy != FallbackPolicy.ALLOW_LIST) return false
        return preference.revisionAllowlist.any { it.hex == candidateRevisionId.hex }
    }

    private fun rejectionReason(
        requestedRevisionId: ModelRevisionId,
        preference: RoutingPreference,
        candidate: RoutingCandidate,
        allowset: Set<String>,
    ): CandidateRejection? {
        val sameRev = candidate.modelRevisionId.hex == requestedRevisionId.hex
        return when (preference.fallbackPolicy) {
            FallbackPolicy.NONE -> {
                // Acceptance §6.1: no backend/revision substitution.
                if (!candidate.isPrimary || !sameRev) {
                    reject(
                        candidate,
                        "fallback policy NONE forbids backend/revision substitution",
                        mapOf(
                            "fallbackPolicy" to FallbackPolicy.NONE.name,
                            "isPrimary" to candidate.isPrimary.toString(),
                            "sameRevision" to sameRev.toString(),
                        ),
                    )
                } else {
                    null
                }
            }
            FallbackPolicy.SAME_REVISION_ONLY -> {
                if (!sameRev) {
                    reject(
                        candidate,
                        "fallback policy SAME_REVISION_ONLY forbids cross-revision routing",
                        mapOf(
                            "fallbackPolicy" to FallbackPolicy.SAME_REVISION_ONLY.name,
                            "requestedRevision" to requestedRevisionId.hex,
                            "candidateRevision" to candidate.modelRevisionId.hex,
                        ),
                    )
                } else {
                    null
                }
            }
            FallbackPolicy.ALLOW_LIST -> {
                val onList = allowset.contains(candidate.modelRevisionId.hex)
                if (!sameRev && !onList) {
                    reject(
                        candidate,
                        "revision not on caller allowlist (no silent cross-revision fallback)",
                        mapOf(
                            "fallbackPolicy" to FallbackPolicy.ALLOW_LIST.name,
                            "candidateRevision" to candidate.modelRevisionId.hex,
                        ),
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun reject(
        candidate: RoutingCandidate,
        message: String,
        details: Map<String, String>,
    ): CandidateRejection =
        CandidateRejection(
            candidateId = candidate.candidateId,
            code = CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
            message = message,
            details = details,
        )
}

/** Outcome of [FallbackPolicyRules.validatePreference]. */
sealed class PolicyValidation {
    data object Ok : PolicyValidation()
    data class Invalid(
        val message: String,
        val code: String,
        val details: Map<String, String> = emptyMap(),
    ) : PolicyValidation()
}

/** Outcome of [FallbackPolicyRules.buildPreference]. */
sealed class PolicyBuildResult {
    data class Ok(val preference: RoutingPreference) : PolicyBuildResult()
    data class Invalid(
        val message: String,
        val code: String,
        val details: Map<String, String> = emptyMap(),
    ) : PolicyBuildResult()
}

/**
 * Policy filter result: survivors ordered for planning + explicit rejections
 * for every unelected candidate (FEAT-ROUTING §2 explainability).
 */
data class PolicyFilterResult(
    val allowed: List<RoutingCandidate>,
    val rejections: List<CandidateRejection>,
) {
    val hasAllowed: Boolean get() = allowed.isNotEmpty()
}
