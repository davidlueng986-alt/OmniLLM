package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError

/**
 * Product-mode policy gates (FTR-03).
 *
 * Two product modes are implemented as configuration-catalog flags and stay
 * **fail-closed by default** (both OFF):
 *
 * 1. **Research Mode** (`product.researchModeEnabled`) — enables raw diagnostics
 *    and backend-selection options for local experimentation. It never weakens
 *    security/trust posture; it only widens the *diagnostic surface* the caller
 *    may read back and the backend options the caller may select.
 *
 * 2. **Risky Performance Mode** (`product.riskyPerformanceModeEnabled`) —
 *    enables the performance path (higher concurrency / reduced safety margins).
 *    Every use requires an explicit per-use [RiskAck] from the acting principal;
 *    without an acknowledged ack the gate refuses with FORBIDDEN (fail closed).
 *
 * The acknowledgment pattern mirrors the durable one-time receipts used by the
 * Secret Broker (SEC-AUTH-NET): an ack is created once by an authorized local
 * admin action and consumed per risky use. Persisting ack records durably is a
 * data-layer port (like AccessTokenStore); this wave ships an in-memory ledger +
 * the gate logic with unit tests, and the durable adapter is reported for a
 * later wave.
 *
 * Honest capability projection: capability reports must reflect the *effective*
 * modes — see [projectedModes] — never advertise research/risky capabilities
 * when the flags are off.
 */
object ProductModePolicy {

    const val RISKY_PERFORMANCE_RISK_ID: String = "risky-performance-mode-v1"

    /**
     * Explicit risk acknowledgment for one risky-performance use.
     *
     * @param ackId unique ack id (one ack, one use — replay is refused).
     * @param principalId acting local principal (LOCAL_UI / local admin).
     * @param riskId must equal [RISKY_PERFORMANCE_RISK_ID] (fail closed on unknown).
     * @param policyVersion the settings resourceVersion the ack was issued at.
     */
    data class RiskAck(
        val ackId: String,
        val principalId: String,
        val riskId: String,
        val policyVersion: Long,
        val acknowledgedAtEpochMs: Long,
        val consumed: Boolean = false,
    ) {
        init {
            require(ackId.isNotBlank()) { "ackId must be non-blank" }
            require(principalId.isNotBlank()) { "principalId must be non-blank" }
            require(riskId.isNotBlank()) { "riskId must be non-blank" }
            require(policyVersion >= 0L) { "policyVersion must be non-negative" }
            require(acknowledgedAtEpochMs >= 0L) { "acknowledgedAtEpochMs must be non-negative" }
        }
    }

    /** Ledger for risk acks. In-memory default; durable adapter reported. */
    interface RiskAckStore {
        fun save(ack: RiskAck)
        fun find(ackId: String): RiskAck?
    }

    /** Process-local ack ledger (unit tests / bootstrap). */
    class InMemoryRiskAckStore : RiskAckStore {
        private val acks = java.util.concurrent.ConcurrentHashMap<String, RiskAck>()
        override fun save(ack: RiskAck) {
            acks[ack.ackId] = ack
        }

        override fun find(ackId: String): RiskAck? = acks[ackId]
    }

    /** Whether research mode is effectively enabled (absent key => OFF). */
    fun researchModeEnabled(snapshot: SettingsSnapshot?): Boolean =
        ConfigurationCatalog.productModes(snapshot).researchModeEnabled

    /** Whether risky performance mode is effectively enabled (absent key => OFF). */
    fun riskyPerformanceModeEnabled(snapshot: SettingsSnapshot?): Boolean =
        ConfigurationCatalog.productModes(snapshot).riskyPerformanceModeEnabled

    /**
     * Honest capability projection: which product-mode surfaces may be
     * advertised. Research mode implies raw-diagnostics + backend-selection
     * surfaces; risky performance mode implies the performance path. Both
     * fail closed when the snapshot is absent or the flags are off.
     */
    data class ModeProjection(
        val researchModeEnabled: Boolean,
        val riskyPerformanceModeEnabled: Boolean,
        val rawDiagnosticsAllowed: Boolean,
        val backendSelectionOptionsAllowed: Boolean,
        val performancePathAllowed: Boolean,
    )

    fun projectedModes(snapshot: SettingsSnapshot?): ModeProjection {
        val modes = ConfigurationCatalog.productModes(snapshot)
        return ModeProjection(
            researchModeEnabled = modes.researchModeEnabled,
            riskyPerformanceModeEnabled = modes.riskyPerformanceModeEnabled,
            rawDiagnosticsAllowed = modes.researchModeEnabled,
            backendSelectionOptionsAllowed = modes.researchModeEnabled,
            performancePathAllowed = modes.riskyPerformanceModeEnabled,
        )
    }

    /**
     * Gate for one risky-performance use (FTR-03).
     *
     * Refuses (FORBIDDEN, fail closed) when:
     * - the mode flag is off, or
     * - no [RiskAck] was presented, or
     * - the ack is unknown / consumed / wrong risk / wrong principal.
     * Consumes the ack on success so one ack = one use (replay refused).
     */
    fun requireRiskyPerformanceAccess(
        snapshot: SettingsSnapshot?,
        store: RiskAckStore,
        ack: RiskAck?,
        nowEpochMs: Long,
    ): OmniResult<Unit> {
        if (!riskyPerformanceModeEnabled(snapshot)) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "risky performance mode disabled",
                    details = mapOf("setting" to "product.riskyPerformanceModeEnabled"),
                ),
            )
        }
        val presented = ack ?: return OmniResult.err(
            OmniError.FORBIDDEN(message = "risky performance path requires explicit risk acknowledgment"),
        )
        if (presented.riskId != RISKY_PERFORMANCE_RISK_ID) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "unknown risk ack id (fail closed)",
                    details = mapOf("riskId" to presented.riskId),
                ),
            )
        }
        if (presented.policyVersion != (snapshot?.resourceVersion ?: 0L)) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "risk ack policy version mismatch; re-acknowledge after settings change",
                    details = mapOf(
                        "ackPolicyVersion" to presented.policyVersion.toString(),
                        "currentPolicyVersion" to (snapshot?.resourceVersion ?: 0L).toString(),
                    ),
                ),
            )
        }
        val stored = store.find(presented.ackId)
        if (stored == null || stored.consumed || stored.riskId != presented.riskId ||
            stored.principalId != presented.principalId
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "risk ack unknown, consumed, or not bound to principal"),
            )
        }
        if (nowEpochMs < stored.acknowledgedAtEpochMs) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "risk ack timestamp invalid (clock skew)"),
            )
        }
        store.save(stored.copy(consumed = true))
        return OmniResult.ok(Unit)
    }
}
