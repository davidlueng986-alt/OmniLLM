package com.omnillm.features.server.projection

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.server.api.CapabilityBlocker
import com.omnillm.features.server.api.CapabilityBlockerKind
import com.omnillm.features.server.api.CapabilityCellView
import com.omnillm.features.server.api.CapabilityNegotiationResult
import com.omnillm.features.server.api.ClientSummaryView
import com.omnillm.features.server.api.DeveloperTokenView
import com.omnillm.features.server.api.EvidencedMetricView
import com.omnillm.features.server.api.LoopbackServerStatus
import com.omnillm.features.server.api.ModelCapabilityView
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.features.server.api.TokenIssuanceReceipt
import com.omnillm.features.server.domain.DeveloperServerSnapshot
import com.omnillm.features.server.domain.ServerScreenPhase
import com.omnillm.runtime.observability.EvidenceSemantics

/**
 * Projects control-plane facts into FEAT-SERVER UI snapshots
 * (UX-STATE-CATALOG / UX-PROJECTION). Does not invent domain FSM states.
 */
object ServerStateProjection {

    private val TOKEN_STATES: Set<String> = StateMachines.TOKEN.states
    private val CLIENT_STATES: Set<String> = StateMachines.CLIENT_REGISTRATION.states
    private val REQUEST_STATES: Set<String> = StateMachines.REQUEST.states

    fun project(
        loading: Boolean,
        loopback: LoopbackServerStatus?,
        clients: List<ClientSummaryView>,
        tokens: List<DeveloperTokenView>,
        pendingReceipt: TokenIssuanceReceipt?,
        capabilities: List<ModelCapabilityView>,
        metrics: List<EvidencedMetricView>,
        lastSmoke: SmokeTestResult?,
        lastRequestId: String?,
        lastRequestState: String?,
        error: OmniError?,
        nowEpochMs: Long,
        hasRefreshed: Boolean,
    ): DeveloperServerSnapshot {
        tokens.forEach { require(it.state in TOKEN_STATES) { "unknown TOKEN state: ${it.state}" } }
        clients.forEach {
            require(it.state in CLIENT_STATES) {
                "unknown CLIENT_REGISTRATION state: ${it.state}"
            }
        }
        lastRequestState?.let {
            require(it in REQUEST_STATES) { "unknown REQUEST state: $it" }
        }

        val presentation = resolvePhase(
            loading = loading,
            loopback = loopback,
            error = error,
            hasRefreshed = hasRefreshed,
        )

        return DeveloperServerSnapshot(
            presentation = presentation,
            loopback = loopback,
            clients = clients,
            tokens = tokens,
            pendingTokenReceipt = pendingReceipt,
            capabilities = capabilities,
            metrics = metrics,
            lastSmoke = lastSmoke,
            lastRequestId = lastRequestId,
            lastRequestState = lastRequestState,
            error = error,
            updatedAtEpochMs = nowEpochMs,
        )
    }

    fun resolvePhase(
        loading: Boolean,
        loopback: LoopbackServerStatus?,
        error: OmniError?,
        hasRefreshed: Boolean,
    ): ServerScreenPhase {
        if (loading) return ServerScreenPhase.LOADING
        if (error != null && loopback == null && !hasRefreshed) {
            return ServerScreenPhase.ERROR
        }
        if (error != null && loopback == null) return ServerScreenPhase.ERROR
        if (!hasRefreshed && loopback == null) return ServerScreenPhase.EMPTY
        if (loopback == null) return ServerScreenPhase.EMPTY
        if (!loopback.running && error != null) return ServerScreenPhase.ERROR
        if (loopback.isDegraded) return ServerScreenPhase.DEGRADED
        if (loopback.running && loopback.runtimeState == "READY") return ServerScreenPhase.READY
        if (loopback.running) return ServerScreenPhase.DEGRADED
        // Not running yet — empty onboarding, not hard error.
        return if (error != null) ServerScreenPhase.ERROR else ServerScreenPhase.EMPTY
    }

    /**
     * CAPABILITY_NEGOTIATION: fail closed on UNSUPPORTED / UNKNOWN / missing.
     * CONDITIONAL is usable when conditions are listed; TEMPORARILY_UNAVAILABLE blocks.
     */
    fun negotiate(
        models: List<ModelCapabilityView>,
        modelId: String?,
        requiredCapabilities: Set<String>,
    ): CapabilityNegotiationResult {
        val cells = mutableListOf<CapabilityCellView>()
        val blockers = mutableListOf<CapabilityBlocker>()

        // Unknown capability id ⇒ fail closed before model walk (INV-018).
        for (raw in requiredCapabilities) {
            val cap = CapabilityId.fromId(raw)
            if (cap == null) {
                blockers += CapabilityBlocker(
                    capabilityId = raw,
                    kind = CapabilityBlockerKind.UNKNOWN,
                    state = CapabilityState.UNKNOWN,
                    message = "unknown capability id (fail closed): $raw",
                )
            }
        }
        if (blockers.isNotEmpty()) {
            return CapabilityNegotiationResult(
                usable = false,
                cells = emptyList(),
                blocking = blockers,
            )
        }

        val model = when {
            modelId != null -> models.find { it.modelId == modelId || it.modelRevisionId == modelId }
            else -> models.firstOrNull()
        }
        if (model == null) {
            for (raw in requiredCapabilities) {
                blockers += CapabilityBlocker(
                    capabilityId = raw,
                    kind = CapabilityBlockerKind.MISSING,
                    state = CapabilityState.UNKNOWN,
                    message = "no model available for capability negotiation",
                )
            }
            return CapabilityNegotiationResult(
                usable = false,
                cells = emptyList(),
                blocking = blockers,
            )
        }

        val byId = model.capabilities.associateBy { it.capabilityId }
        for (raw in requiredCapabilities) {
            val cell = byId[raw]
            if (cell == null) {
                val missing = CapabilityCellView(
                    capabilityId = raw,
                    state = CapabilityState.UNKNOWN,
                    evidenceLabel = EvidenceLabel.UNKNOWN,
                    sampledAtEpochMs = 0L,
                )
                cells += missing
                blockers += CapabilityBlocker(
                    capabilityId = raw,
                    kind = CapabilityBlockerKind.MISSING,
                    state = CapabilityState.UNKNOWN,
                    message = "capability not advertised on model ${model.modelId}",
                )
                continue
            }
            cells += cell
            when (cell.state) {
                CapabilityState.SUPPORTED, CapabilityState.CONDITIONAL -> Unit
                CapabilityState.UNSUPPORTED -> blockers += CapabilityBlocker(
                    capabilityId = raw,
                    kind = CapabilityBlockerKind.UNSUPPORTED,
                    state = cell.state,
                    message = "capability unsupported on model ${model.modelId}",
                )
                CapabilityState.UNKNOWN -> blockers += CapabilityBlocker(
                    capabilityId = raw,
                    kind = CapabilityBlockerKind.UNKNOWN,
                    state = cell.state,
                    message = "capability unknown (not 'unsupported') on model ${model.modelId}",
                )
                CapabilityState.TEMPORARILY_UNAVAILABLE -> blockers += CapabilityBlocker(
                    capabilityId = raw,
                    kind = CapabilityBlockerKind.TEMPORARILY_UNAVAILABLE,
                    state = cell.state,
                    message = "capability temporarily unavailable on model ${model.modelId}",
                )
            }
        }

        return CapabilityNegotiationResult(
            usable = blockers.isEmpty(),
            cells = cells,
            blocking = blockers,
        )
    }

    /** UX label keys for CLIENT_REGISTRATION states. */
    fun clientStateLabelKey(state: String): String = when (state) {
        "PENDING" -> "client.pending"
        "ACTIVE" -> "client.active"
        "SUSPENDED" -> "client.suspended"
        "REVOCATION_REQUESTED" -> "client.revocation-requested"
        "DRAINING" -> "client.draining"
        "REVOKED" -> "client.revoked"
        "EXPIRED" -> "client.expired"
        else -> "client.unknown"
    }

    fun tokenStateLabelKey(state: String): String = when (state) {
        "ISSUING" -> "token.issuing"
        "ACTIVE" -> "token.active"
        "REVOCATION_REQUESTED" -> "token.revocation-requested"
        "DRAINING" -> "token.draining"
        "REVOKED" -> "token.revoked"
        "EXPIRED" -> "token.expired"
        "FAILED" -> "token.failed"
        else -> "token.unknown"
    }

    fun evidenceDescriptionKey(label: EvidenceLabel): String =
        EvidenceSemantics.descriptionKey(label)

    fun allowsNumericMetricDisplay(label: EvidenceLabel): Boolean =
        EvidenceSemantics.allowsNumericDisplay(label)
}
