package com.omnillm.features.server.domain

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt

/**
 * Screen-level presentation states for FEAT-SERVER (UX-STATE-CATALOG).
 *
 * These are **UI presentation** labels only — durable lifecycle remains
 * TOKEN / CLIENT_REGISTRATION / REQUEST / RUNTIME from state-machines.yaml.
 * Do not invent domain FSM states here.
 */
enum class ServerScreenPhase {
    /** No snapshot yet. */
    EMPTY,
    /** Snapshot / mutation in flight. */
    LOADING,
    /** Loopback reachable and control plane READY. */
    READY,
    /** Service reachable but degraded (partial capabilities / recovering). */
    DEGRADED,
    /** Unrecoverable projection error for this screen. */
    ERROR,
    ;

    companion object {
        fun isKnown(name: String): Boolean =
            entries.any { it.name == name }
    }
}

/**
 * Full developer-server screen snapshot for UI binding.
 * [presentation] drives empty / loading / error / degraded chrome;
 * underlying lists remain empty rather than inventing placeholder rows.
 */
data class DeveloperServerSnapshot(
    val presentation: ServerScreenPhase,
    val loopback: LoopbackServerStatus?,
    val clients: List<ClientSummaryView>,
    val tokens: List<DeveloperTokenView>,
    /**
     * Plaintext bearer shown only once after issue (SEC-PROFILE).
     * Cleared after acknowledge / expiry window.
     */
    val pendingTokenReceipt: TokenIssuanceReceipt?,
    val capabilities: List<ModelCapabilityView>,
    val metrics: List<EvidencedMetricView>,
    val lastSmoke: SmokeTestResult?,
    val lastRequestId: String?,
    val lastRequestState: String?,
    val error: OmniError?,
    val updatedAtEpochMs: Long,
) {
    init {
        require(updatedAtEpochMs >= 0L) { "updatedAtEpochMs must be non-negative" }
    }

    val isEmpty: Boolean
        get() = presentation == ServerScreenPhase.EMPTY ||
            (loopback == null && clients.isEmpty() && tokens.isEmpty() && error == null)

    val isLoading: Boolean get() = presentation == ServerScreenPhase.LOADING

    val isDegraded: Boolean get() = presentation == ServerScreenPhase.DEGRADED

    val isError: Boolean get() = presentation == ServerScreenPhase.ERROR
}
