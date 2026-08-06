package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.PlacementClassLabels

/**
 * Pure candidate planning (CORE-ORCHESTRATOR §2, FEAT-ROUTING §2, ADR-002).
 *
 * Filter order (normative):
 * 1. requested capability
 * 2. model revision / alias resolution (caller supplies resolved candidates)
 * 3. caller fallback policy
 * 4. trust placement
 * 5. engine/backend/device capability
 * 6. Session ownership/fingerprint
 * 7. resource envelope (from pure engine plan)
 * 8. policy, health, thermal, revocation epoch
 *
 * Does **not** mutate domain state. Each rejection is retained for UI/diagnostics.
 */
class CandidatePlanner(
    private val capabilities: CapabilityLookup,
    private val health: HealthLookup,
    private val sessionCheck: SessionCompatibilityCheck =
        SessionCompatibilityCheck { _, _ -> null },
    private val engine: InferenceEnginePort,
) {

    /**
     * Evaluate all candidates in stable input order. Viable list preserves rank order
     * (primary first, then allowlisted fallbacks in caller order).
     */
    suspend fun plan(request: OrchestrationRequest): OmniResult<PlanningResult> {
        val rejections = mutableListOf<CandidateRejection>()
        val viable = mutableListOf<PlannedCandidate>()

        // Unknown capability on the request itself fails closed before candidate walk.
        for (cap in request.requiredCapabilities) {
            // Catalog membership already enforced in OrchestrationRequest init.
            // A candidate that cannot support it will be rejected below.
            @Suppress("UNUSED_VARIABLE")
            val _cap = cap
        }

        val ordered = orderByFallbackPolicy(request)
        if (ordered.isEmpty()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "no candidates remain after fallback policy filter",
                    details = mapOf(
                        "fallbackPolicy" to request.routing.fallbackPolicy.name,
                        "requestedRevision" to request.requestedRevisionId.hex,
                    ),
                ),
            )
        }

        for (candidate in ordered) {
            val rejection = evaluateFilters(request, candidate)
            if (rejection != null) {
                rejections += rejection
                continue
            }

            // Filter 7 — pure plan for resource envelope (ADR-002).
            when (val planOutcome = engine.planInference(request, candidate)) {
                is OmniResult.Err -> {
                    rejections += CandidateRejection(
                        candidateId = candidate.candidateId,
                        code = CandidateRejectionCodes.RESOURCE,
                        message = planOutcome.error.message ?: "planInference failed",
                        details = mapOf(
                            "errorCode" to planOutcome.error.code.code,
                        ),
                    )
                    continue
                }
                is OmniResult.Ok -> {
                    val envelope = planOutcome.value.resourceEnvelope
                    // Envelope sanity: peak must dominate steady (ResourceEnvelope init).
                    viable += PlannedCandidate(
                        candidate = candidate,
                        resourceEnvelope = envelope,
                        planInputDigest = planOutcome.value.planInputDigest,
                        costClass = request.costClass,
                        costUnits = request.resolvedCostUnits(),
                    )
                }
            }
        }

        if (viable.isEmpty()) {
            return OmniResult.err(
                OmniError.ADMISSION_REJECTED(
                    message = "no viable routing candidate after planning filters",
                    details = mapOf(
                        "rejectionCount" to rejections.size.toString(),
                        "rejectionCodes" to rejections.map { it.code }.distinct().joinToString(","),
                    ),
                ),
            )
        }

        return OmniResult.ok(PlanningResult(viable = viable, rejections = rejections))
    }

    /**
     * Apply fallback policy before expensive plan work (filter 3).
     * - NONE: only primary / exact requested revision
     * - SAME_REVISION_ONLY: same revision, any backend (backend allowlist still applies later)
     * - ALLOW_LIST: primary + allowlisted revisions only
     */
    fun orderByFallbackPolicy(request: OrchestrationRequest): List<RoutingCandidate> {
        val routing = request.routing
        val requested = request.requestedRevisionId
        return when (routing.fallbackPolicy) {
            FallbackPolicy.NONE ->
                request.candidates.filter { it.isPrimary || sameRevision(it.modelRevisionId, requested) }
                    .filter { sameRevision(it.modelRevisionId, requested) }
                    // Under NONE, still only exact revision; prefer primary first.
                    .sortedByDescending { it.isPrimary }

            FallbackPolicy.SAME_REVISION_ONLY ->
                request.candidates
                    .filter { sameRevision(it.modelRevisionId, requested) }
                    .sortedByDescending { it.isPrimary }

            FallbackPolicy.ALLOW_LIST -> {
                val allowed = routing.revisionAllowlist.toSet()
                request.candidates
                    .filter {
                        sameRevision(it.modelRevisionId, requested) ||
                            allowed.any { a -> sameRevision(it.modelRevisionId, a) }
                    }
                    .sortedWith(
                        compareByDescending<RoutingCandidate> { it.isPrimary }
                            .thenBy { it.candidateId },
                    )
            }
        }
    }

    private fun evaluateFilters(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): CandidateRejection? {
        // Filter 3 residual: non-primary under NONE should never reach here, but fail closed.
        if (request.routing.fallbackPolicy == FallbackPolicy.NONE &&
            !sameRevision(candidate.modelRevisionId, request.requestedRevisionId)
        ) {
            return reject(
                candidate,
                CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
                "fallback policy NONE forbids cross-revision/backend routing",
            )
        }
        if (request.routing.fallbackPolicy == FallbackPolicy.SAME_REVISION_ONLY &&
            !sameRevision(candidate.modelRevisionId, request.requestedRevisionId)
        ) {
            return reject(
                candidate,
                CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
                "fallback policy SAME_REVISION_ONLY forbids cross-revision routing",
            )
        }
        if (request.routing.fallbackPolicy == FallbackPolicy.ALLOW_LIST) {
            val allowed = request.routing.revisionAllowlist
            val ok = sameRevision(candidate.modelRevisionId, request.requestedRevisionId) ||
                allowed.any { sameRevision(candidate.modelRevisionId, it) }
            if (!ok) {
                return reject(
                    candidate,
                    CandidateRejectionCodes.FALLBACK_NOT_ALLOWED,
                    "revision not on caller allowlist",
                )
            }
        }

        // Filter 1 — capabilities
        for (cap in request.requiredCapabilities) {
            when (val state = capabilities.state(cap, candidate)) {
                CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
                CapabilityState.UNSUPPORTED ->
                    return reject(
                        candidate,
                        CandidateRejectionCodes.CAPABILITY,
                        "capability unsupported: ${cap.id}",
                        mapOf("capability" to cap.id, "state" to state.name),
                    )
                CapabilityState.UNKNOWN ->
                    return reject(
                        candidate,
                        CandidateRejectionCodes.CAPABILITY,
                        "capability unknown (fail closed): ${cap.id}",
                        mapOf("capability" to cap.id, "state" to state.name),
                    )
                CapabilityState.TEMPORARILY_UNAVAILABLE ->
                    return reject(
                        candidate,
                        CandidateRejectionCodes.CAPABILITY,
                        "capability temporarily unavailable: ${cap.id}",
                        mapOf("capability" to cap.id, "state" to state.name),
                    )
            }
        }

        // Filter 4 — trust placement
        if (!PlacementClassLabels.isExecutable(candidate.placementClass)) {
            return reject(
                candidate,
                CandidateRejectionCodes.TRUST_PLACEMENT,
                "placement not executable: ${candidate.placementClass}",
            )
        }
        if (!placementMeetsMinimum(candidate.placementClass, request.routing.minimumPlacementClass)) {
            return reject(
                candidate,
                CandidateRejectionCodes.TRUST_PLACEMENT,
                "placement below minimum: have ${candidate.placementClass}, need ${request.routing.minimumPlacementClass}",
            )
        }

        // Filter 5 — backend allowlist / device
        val allowedBackends = request.routing.allowedBackends
        if (allowedBackends.isNotEmpty() && candidate.backend !in allowedBackends) {
            return reject(
                candidate,
                CandidateRejectionCodes.ENGINE_BACKEND,
                "backend not on caller allowlist: ${candidate.backend}",
            )
        }

        // Filter 6 — session compatibility
        sessionCheck.incompatibilityReason(request, candidate)?.let { reason ->
            return reject(
                candidate,
                CandidateRejectionCodes.SESSION_COMPATIBILITY,
                reason,
            )
        }

        // Filter 8 — health / thermal / revocation
        val snap = health.snapshot(candidate)
        if (snap.revocationEpoch > request.revocationEpoch) {
            // Request epoch is stale vs live revocation — fence (INV-017).
            return reject(
                candidate,
                CandidateRejectionCodes.REVOCATION,
                "revocation epoch advanced",
                mapOf(
                    "requestEpoch" to request.revocationEpoch.toString(),
                    "liveEpoch" to snap.revocationEpoch.toString(),
                ),
            )
        }
        if (!snap.engineHealthy) {
            return reject(candidate, CandidateRejectionCodes.HEALTH, "engine unhealthy")
        }
        if (!snap.modelHealthy) {
            return reject(candidate, CandidateRejectionCodes.HEALTH, "model unhealthy")
        }
        if (!snap.thermalOk) {
            return reject(candidate, CandidateRejectionCodes.THERMAL, "thermal constraint blocks admission")
        }

        return null
    }

    private fun reject(
        candidate: RoutingCandidate,
        code: String,
        message: String,
        details: Map<String, String> = emptyMap(),
    ): CandidateRejection =
        CandidateRejection(
            candidateId = candidate.candidateId,
            code = code,
            message = message,
            details = details,
        )

    private fun sameRevision(a: ModelRevisionId, b: ModelRevisionId): Boolean = a.hex == b.hex

    /**
     * Coarse placement rank for minimum checks.
     * Higher rank = more privileged. EXTERNAL_UID_ACCELERATED is valid but untrusted.
     * TRUST_PLACEMENT_REQUIRED is never executable.
     */
    private fun placementMeetsMinimum(have: String, need: String): Boolean {
        val rank = mapOf(
            PlacementClassLabels.EXTERNAL_UID_ACCELERATED to 1,
            PlacementClassLabels.ISOLATED_CPU_UNTRUSTED to 2,
            PlacementClassLabels.CRASH_CONTAINED_TRUSTED to 3,
            PlacementClassLabels.PRIVILEGED_TRUSTED to 4,
        )
        val h = rank[have] ?: return false
        val n = rank[need] ?: return false
        // "minimum placement" means at least as trusted as need.
        return h >= n
    }
}
