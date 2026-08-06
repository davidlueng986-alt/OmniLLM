package com.omnillm.features.lan.domain

/**
 * Routing policy for LAN-originated inference (FEAT-LAN + FALLBACK_POLICY).
 *
 * Never silent cross-revision fallback: a client that pins [requestedModelRevisionId]
 * must execute against that exact revision or fail closed. Substitution of another
 * revision without explicit client policy is forbidden.
 */
object LanRoutingPolicy {

    data class RouteRequest(
        /** Hex ModelRevisionId the client pinned (64-char lowercase), or null for alias resolve. */
        val requestedModelRevisionId: String?,
        /** Optional display alias the client asked for. */
        val requestedModelAlias: String? = null,
        /** Whether the client explicitly allowed fallback to another revision. */
        val allowCrossRevisionFallback: Boolean = false,
    )

    data class RouteCandidate(
        val modelRevisionId: String,
        val modelAlias: String? = null,
        val available: Boolean,
    )

    /**
     * Select a route. Fail closed on cross-revision substitution unless explicitly allowed.
     */
    fun select(
        request: RouteRequest,
        candidates: List<RouteCandidate>,
    ): LanRouteDecision {
        val pinned = request.requestedModelRevisionId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (pinned != null) {
            if (!pinned.matches(HEX64)) {
                return LanRouteDecision.Reject(
                    reason = "invalid modelRevisionId (fail closed)",
                    code = "INVALID_REVISION_ID",
                )
            }
            val exact = candidates.firstOrNull { it.modelRevisionId.lowercase() == pinned }
            if (exact == null) {
                return LanRouteDecision.Reject(
                    reason = "pinned modelRevisionId not found",
                    code = "REVISION_NOT_FOUND",
                    details = mapOf("requestedModelRevisionId" to pinned),
                )
            }
            if (!exact.available) {
                return LanRouteDecision.Reject(
                    reason = "pinned modelRevisionId unavailable",
                    code = "REVISION_UNAVAILABLE",
                    details = mapOf("requestedModelRevisionId" to pinned),
                )
            }
            // Exact pin matched — never substitute another revision.
            return LanRouteDecision.Selected(
                modelRevisionId = exact.modelRevisionId,
                substituted = false,
            )
        }

        // Alias-only path: still no silent cross-revision when multiple revisions share alias
        // unless client opted into fallback.
        val alias = request.requestedModelAlias?.trim()?.takeIf { it.isNotEmpty() }
        if (alias != null) {
            val matching = candidates.filter {
                it.available && it.modelAlias != null &&
                    it.modelAlias.equals(alias, ignoreCase = true)
            }
            if (matching.isEmpty()) {
                return LanRouteDecision.Reject(
                    reason = "model alias not found or unavailable",
                    code = "ALIAS_NOT_FOUND",
                    details = mapOf("alias" to alias),
                )
            }
            if (matching.size == 1) {
                return LanRouteDecision.Selected(
                    modelRevisionId = matching.single().modelRevisionId,
                    substituted = false,
                )
            }
            // Multiple revisions for one alias: require explicit fallback policy.
            if (!request.allowCrossRevisionFallback) {
                return LanRouteDecision.Reject(
                    reason = "multiple revisions for alias; refuse silent cross-revision fallback",
                    code = "AMBIGUOUS_REVISION",
                    details = mapOf(
                        "alias" to alias,
                        "candidates" to matching.joinToString(",") { it.modelRevisionId },
                    ),
                )
            }
            // Explicit fallback still picks deterministically (first by revision id) —
            // and marks substituted so callers must disclose.
            val chosen = matching.minBy { it.modelRevisionId }
            return LanRouteDecision.Selected(
                modelRevisionId = chosen.modelRevisionId,
                substituted = true,
            )
        }

        return LanRouteDecision.Reject(
            reason = "request must pin modelRevisionId or model alias",
            code = "ROUTING_TARGET_REQUIRED",
        )
    }

    /**
     * Guard used after execute: actual revision must equal selected when not substituted.
     */
    fun assertNoSilentSubstitution(
        requestedModelRevisionId: String?,
        actualModelRevisionId: String?,
    ): LanRouteDecision {
        val pinned = requestedModelRevisionId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return LanRouteDecision.Selected(
                modelRevisionId = actualModelRevisionId.orEmpty(),
                substituted = false,
            )
        val actual = actualModelRevisionId?.trim()?.lowercase()
        if (actual.isNullOrEmpty()) {
            return LanRouteDecision.Reject(
                reason = "actual modelRevisionId missing after execute",
                code = "MISSING_ACTUAL_REVISION",
            )
        }
        if (actual != pinned) {
            return LanRouteDecision.Reject(
                reason = "silent cross-revision fallback forbidden",
                code = "CROSS_REVISION_FALLBACK",
                details = mapOf(
                    "requested" to pinned,
                    "actual" to actual,
                ),
            )
        }
        return LanRouteDecision.Selected(modelRevisionId = actual, substituted = false)
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")
}

sealed class LanRouteDecision {
    data class Selected(
        val modelRevisionId: String,
        val substituted: Boolean,
    ) : LanRouteDecision()

    data class Reject(
        val reason: String,
        val code: String,
        val details: Map<String, String> = emptyMap(),
    ) : LanRouteDecision()
}
