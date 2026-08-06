package com.omnillm.features.routing.domain

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.PrefixDecision
import com.omnillm.runtime.orchestrator.RoutingCandidate

/**
 * Session boundary policy for routing switches (FEAT-ROUTING §4).
 *
 * Session binds owner, revision, load key, engine build, tokenizer/template epoch.
 * When routing changes revision/backend without a proven state transfer, KV must
 * not be reused. Conversation text may be resent to open a new Session, but the
 * response **must** disclose continuity was rebuilt — never pretend original KV.
 *
 * Disposition labels are stable projection strings (not catalog enums).
 * Prefix decision uses catalog [PrefixDecision] only.
 */
object SessionContinuityPolicy {

    /** No prior session; fresh conversation. */
    const val DISPOSITION_NONE: String = "NONE"

    /** Same revision + compatible source session; KV may be retained. */
    const val DISPOSITION_RETAINED: String = "RETAINED"

    /**
     * Continuity rebuilt (new Session). Original KV not reused.
     * Must be disclosed on response/trace/UI.
     */
    const val DISPOSITION_REBUILT: String = "REBUILT"

    val ALL_DISPOSITIONS: Set<String> = setOf(
        DISPOSITION_NONE,
        DISPOSITION_RETAINED,
        DISPOSITION_REBUILT,
    )

    fun isKnownDisposition(code: String): Boolean = code in ALL_DISPOSITIONS

    /**
     * Decide continuity for the selected routing candidate vs prior session facts.
     *
     * @param requestedRevisionId alias-resolved revision at accept time
     * @param selected chosen routing candidate after plan/admission
     * @param sourceSession present when caller attached a prior session handle
     * @param sourceRevision revision bound to the prior session (null if none)
     * @param sourceLoadKeyDigest load-key digest of prior session (null if none)
     * @param sourceEngineBuildId engine build of prior session (null if none)
     * @param provenStateTransfer caller/engine proved transferable KV (rare; default false)
     */
    fun decide(
        requestedRevisionId: ModelRevisionId,
        selected: RoutingCandidate,
        sourceSession: Boolean,
        sourceRevision: ModelRevisionId? = null,
        sourceLoadKeyDigest: String? = null,
        sourceEngineBuildId: String? = null,
        provenStateTransfer: Boolean = false,
    ): ContinuityDecision {
        if (!sourceSession) {
            return ContinuityDecision(
                disposition = DISPOSITION_NONE,
                prefixDecision = PrefixDecision.NONE,
                kvReused = false,
                disclosedRebuild = false,
                reason = "no source session; fresh session",
            )
        }

        val sameRevision = sourceRevision != null &&
            sourceRevision.hex == selected.modelRevisionId.hex
        val sameLoadKey = sourceLoadKeyDigest != null &&
            sourceLoadKeyDigest == selected.loadKeyDigest.hex
        val sameEngine = sourceEngineBuildId != null &&
            sourceEngineBuildId == selected.engineBuildId.value

        // Cross-revision without proven transfer: always rebuild (FEAT-ROUTING §4 / §6.3).
        if (!sameRevision) {
            return ContinuityDecision(
                disposition = DISPOSITION_REBUILT,
                prefixDecision = PrefixDecision.NONE,
                kvReused = false,
                disclosedRebuild = true,
                reason = "cross-revision routing cannot reuse prior KV",
                details = mapOf(
                    "requestedRevision" to requestedRevisionId.hex,
                    "selectedRevision" to selected.modelRevisionId.hex,
                    "sourceRevision" to (sourceRevision?.hex ?: ""),
                ),
            )
        }

        // Same revision but backend/engine/load-key drift without proof → rebuild.
        if (!sameLoadKey || !sameEngine) {
            if (provenStateTransfer) {
                return ContinuityDecision(
                    disposition = DISPOSITION_RETAINED,
                    prefixDecision = PrefixDecision.EXACT_SAME_SESSION,
                    kvReused = true,
                    disclosedRebuild = false,
                    reason = "same revision with proven state transfer",
                )
            }
            return ContinuityDecision(
                disposition = DISPOSITION_REBUILT,
                prefixDecision = PrefixDecision.FORK,
                kvReused = false,
                disclosedRebuild = true,
                reason = "backend/engine/load-key change without proven state transfer",
                details = mapOf(
                    "sameLoadKey" to sameLoadKey.toString(),
                    "sameEngine" to sameEngine.toString(),
                    "backend" to selected.backend,
                ),
            )
        }

        // Same revision + load key + engine: retain.
        return ContinuityDecision(
            disposition = DISPOSITION_RETAINED,
            prefixDecision = PrefixDecision.EXACT_SAME_SESSION,
            kvReused = true,
            disclosedRebuild = false,
            reason = "same revision/load-key/engine; session KV retained",
        )
    }

    /**
     * Guard: never claim retained continuity across revisions.
     * Returns false when a disclosure would be dishonest.
     */
    fun mayClaimRetainedKv(
        disposition: String,
        selectedRevision: ModelRevisionId,
        sourceRevision: ModelRevisionId?,
    ): Boolean {
        if (disposition != DISPOSITION_RETAINED) return false
        if (sourceRevision == null) return false
        return selectedRevision.hex == sourceRevision.hex
    }
}

/**
 * Continuity decision exposed on routing explain / response projection.
 * [disclosedRebuild] true ⇒ UI/trace must show rebuild, not original KV.
 */
data class ContinuityDecision(
    val disposition: String,
    val prefixDecision: PrefixDecision,
    val kvReused: Boolean,
    val disclosedRebuild: Boolean,
    val reason: String,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(SessionContinuityPolicy.isKnownDisposition(disposition)) {
            "unknown continuity disposition (fail closed): $disposition"
        }
        require(reason.isNotEmpty()) { "reason must be non-empty" }
        // Invariant: rebuild disclosure implies KV not reused.
        if (disclosedRebuild) {
            require(!kvReused) { "disclosed rebuild must not claim kvReused" }
        }
        // Invariant: retained implies KV reused and no rebuild disclosure.
        if (disposition == SessionContinuityPolicy.DISPOSITION_RETAINED) {
            require(kvReused && !disclosedRebuild) {
                "RETAINED requires kvReused and no rebuild disclosure"
            }
        }
        // Invariant: never claim KV reuse under REBUILT.
        if (disposition == SessionContinuityPolicy.DISPOSITION_REBUILT) {
            require(!kvReused && disclosedRebuild) {
                "REBUILT requires disclosedRebuild and no kv reuse"
            }
        }
    }
}
