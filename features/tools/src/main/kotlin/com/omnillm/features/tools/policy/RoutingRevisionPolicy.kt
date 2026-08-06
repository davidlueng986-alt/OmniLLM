package com.omnillm.features.tools.policy

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.errors.generated.OmniError

/**
 * Cross-revision routing guard for structured/tools requests (FEAT-ROUTING + task rule).
 *
 * Never silent cross-revision fallback: only [FallbackPolicy.ALLOW_LIST] with an
 * explicit allowlist may change revision, and actual revision must be disclosed.
 */
object RoutingRevisionPolicy {

    data class RoutingOutcome(
        val actualModelRevisionId: String,
        val fallbackApplied: Boolean,
        val fallbackPolicy: FallbackPolicy,
        val reason: String? = null,
    )

    fun resolveActualRevision(
        requestedRevisionId: String,
        selectedRevisionId: String,
        fallbackPolicy: FallbackPolicy,
        allowedRevisionIds: Set<String>,
    ): Result {
        require(requestedRevisionId.isNotBlank()) { "requestedRevisionId must be non-blank" }
        require(selectedRevisionId.isNotBlank()) { "selectedRevisionId must be non-blank" }

        if (selectedRevisionId == requestedRevisionId) {
            return Result.Ok(
                RoutingOutcome(
                    actualModelRevisionId = selectedRevisionId,
                    fallbackApplied = false,
                    fallbackPolicy = fallbackPolicy,
                ),
            )
        }

        // Cross-revision attempt.
        return when (fallbackPolicy) {
            FallbackPolicy.NONE -> Result.Denied(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "cross-revision fallback forbidden under FallbackPolicy.NONE",
                    details = mapOf(
                        "requestedRevisionId" to requestedRevisionId,
                        "selectedRevisionId" to selectedRevisionId,
                        "fallbackPolicy" to FallbackPolicy.NONE.name,
                        "silentFallback" to "false",
                    ),
                ),
            )
            FallbackPolicy.SAME_REVISION_ONLY -> Result.Denied(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "SAME_REVISION_ONLY forbids changing model revision",
                    details = mapOf(
                        "requestedRevisionId" to requestedRevisionId,
                        "selectedRevisionId" to selectedRevisionId,
                        "fallbackPolicy" to FallbackPolicy.SAME_REVISION_ONLY.name,
                    ),
                ),
            )
            FallbackPolicy.ALLOW_LIST -> {
                if (selectedRevisionId !in allowedRevisionIds) {
                    Result.Denied(
                        OmniError.CAPABILITY_UNSUPPORTED(
                            message = "selected revision not on fallback allowlist",
                            details = mapOf(
                                "requestedRevisionId" to requestedRevisionId,
                                "selectedRevisionId" to selectedRevisionId,
                                "fallbackPolicy" to FallbackPolicy.ALLOW_LIST.name,
                            ),
                        ),
                    )
                } else {
                    Result.Ok(
                        RoutingOutcome(
                            actualModelRevisionId = selectedRevisionId,
                            fallbackApplied = true,
                            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
                            reason = "allowlist-cross-revision",
                        ),
                    )
                }
            }
        }
    }

    sealed class Result {
        data class Ok(val outcome: RoutingOutcome) : Result()
        data class Denied(val error: OmniError) : Result()
    }
}
