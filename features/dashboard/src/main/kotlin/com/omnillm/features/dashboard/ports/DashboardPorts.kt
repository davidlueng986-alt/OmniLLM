package com.omnillm.features.dashboard.ports

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.RequestId
import com.omnillm.runtime.governor.ResourceLedger
import com.omnillm.runtime.observability.MetricSnapshot
import com.omnillm.runtime.observability.RequestTrace
import com.omnillm.runtime.observability.ServiceHealthSnapshot

/**
 * Feature-level ports for FEAT-DASHBOARD.
 *
 * Feature packs compose these ports only — never open domain DB writers,
 * native engines, or write metrics (INV-001, ADR-010).
 */

/** SERVICE_HEALTH / ENGINE_HEALTH / MODEL_HEALTH read surface. */
interface DashboardHealthPort {
    fun serviceHealth(): ServiceHealthSnapshot
}

/**
 * PERFORMANCE_MEASUREMENT + EVIDENCE_LABELING metric snapshots.
 * Summary is allowlist operational; detail may include restricted
 * (FEAT-DASHBOARD §5 privacy).
 */
interface DashboardMetricsPort {
    fun metricSummary(): MetricSnapshot
    fun metricDetail(): MetricSnapshot
}

/** REQUEST_TRACE redacted views. */
interface DashboardTracePort {
    fun listCorrelationIds(): List<String>
    fun redactedTrace(correlationId: String): RequestTrace?
}

/**
 * RESOURCE_ACCOUNTING ledger projection.
 * Isolated worker self-report must not lower charge floors (FEAT-DASHBOARD §3).
 */
interface DashboardResourcePort {
    fun ledgerSnapshot(): ResourceLedger?
    /** Evidence for the ledger numbers (control-plane MEASURED vs REPORTED). */
    fun ledgerEvidence(): LedgerEvidence =
        LedgerEvidence(
            evidenceLabel = EvidenceLabel.MEASURED,
            sampledAtEpochMs = System.currentTimeMillis(),
            source = "resource-governor",
        )
}

data class LedgerEvidence(
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val source: String? = null,
) {
    init {
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) { "REPORTED ledger must disclose source" }
        }
    }
}

/**
 * Active request / cancel / query surface for Requests & Jobs section.
 * Client-generated [RequestId] is required for cancel/query (ADR-004/005).
 */
interface DashboardRequestPort {
    fun listActiveRequests(): List<ActiveRequestFact>
    fun query(requestId: RequestId): OmniResult<ActiveRequestFact>
    fun cancel(requestId: RequestId): OmniResult<ActiveRequestFact>
}

/**
 * Control-plane fact for one request (not full orchestrator internals).
 * [requestId] is client-generated UUID.
 */
data class ActiveRequestFact(
    val requestId: String,
    val correlationId: String? = null,
    val principalId: String? = null,
    val phase: String,
    val engineBuildId: String? = null,
    val modelRevisionId: String? = null,
    val queuePosition: Int? = null,
    val deadlineMonotonic: Long? = null,
    val cancelAllowed: Boolean = false,
    val replyLossRecoverable: Boolean = false,
) {
    init {
        require(requestId.isNotBlank()) { "requestId must be non-blank" }
        require(phase.isNotBlank()) { "phase must be non-blank" }
    }
}

/**
 * CAPABILITY_NEGOTIATION lookup for dashboard-gated operations and tests.
 * Unknown capability IDs must fail closed (INV-018).
 */
interface DashboardCapabilityPort {
    fun state(capability: CapabilityId): CapabilityState
}

/**
 * Last sealed measurement run for MEASUREMENTS tab (FEAT-BENCHMARK projection).
 * Control plane supplies from BenchmarkApi snapshot — never invents engine PASS.
 */
interface DashboardMeasurementPort {
    fun lastSealedRun(): LastMeasurementFact?
}

data class LastMeasurementFact(
    val runId: String,
    val profileId: String,
    val runSeq: Long,
    val outcome: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
    val ttftMs: Double?,
    val throughputTokensPerSec: Double?,
    val endToEndLatencyMs: Double?,
    val evidenceLabel: EvidenceLabel,
    val methodVersion: String?,
    val fixtureSource: String?,
    val deviationReasons: List<String> = emptyList(),
)

object EmptyDashboardMeasurementPort : DashboardMeasurementPort {
    override fun lastSealedRun(): LastMeasurementFact? = null
}

/**
 * Bundle of ports used by FEAT-DASHBOARD use-cases and view-models.
 */
data class DashboardFeaturePorts(
    val health: DashboardHealthPort,
    val metrics: DashboardMetricsPort,
    val traces: DashboardTracePort,
    val resources: DashboardResourcePort = EmptyDashboardResourcePort,
    val requests: DashboardRequestPort = EmptyDashboardRequestPort,
    val capabilities: DashboardCapabilityPort = AllSupportedCapabilityPort,
    val measurements: DashboardMeasurementPort = EmptyDashboardMeasurementPort,
    val clockWallMs: () -> Long = { System.currentTimeMillis() },
)

/** No resource governor attached yet. */
object EmptyDashboardResourcePort : DashboardResourcePort {
    override fun ledgerSnapshot(): ResourceLedger? = null
}

/** No active requests. */
object EmptyDashboardRequestPort : DashboardRequestPort {
    override fun listActiveRequests(): List<ActiveRequestFact> = emptyList()
    override fun query(requestId: RequestId): OmniResult<ActiveRequestFact> =
        OmniResult.err(
            com.omnillm.core.errors.generated.OmniError.NOT_FOUND(
                message = "request not found",
                details = mapOf("requestId" to requestId.value),
            ),
        )
    override fun cancel(requestId: RequestId): OmniResult<ActiveRequestFact> =
        OmniResult.err(
            com.omnillm.core.errors.generated.OmniError.NOT_FOUND(
                message = "request not found",
                details = mapOf("requestId" to requestId.value),
            ),
        )
}

/** Default: all catalog capabilities SUPPORTED (local admin test default). */
object AllSupportedCapabilityPort : DashboardCapabilityPort {
    override fun state(capability: CapabilityId): CapabilityState = CapabilityState.SUPPORTED
}

/**
 * Fixed map of capability states for tests / selective wiring.
 * Missing entries default to UNKNOWN (fail closed).
 */
class MapCapabilityPort(
    private val states: Map<CapabilityId, CapabilityState>,
    private val default: CapabilityState = CapabilityState.UNKNOWN,
) : DashboardCapabilityPort {
    override fun state(capability: CapabilityId): CapabilityState =
        states[capability] ?: default
}

/**
 * Wire [com.omnillm.runtime.observability.ObservabilityFacade] as health/metrics/trace ports.
 */
class ObservabilityDashboardAdapter(
    private val facade: com.omnillm.runtime.observability.ObservabilityFacade,
    private val knownCorrelationIds: () -> List<String> = { emptyList() },
) : DashboardHealthPort, DashboardMetricsPort, DashboardTracePort {
    override fun serviceHealth(): ServiceHealthSnapshot = facade.serviceHealth()
    override fun metricSummary(): MetricSnapshot = facade.metricSummary()
    override fun metricDetail(): MetricSnapshot = facade.metricDetail()
    override fun listCorrelationIds(): List<String> = knownCorrelationIds()
    override fun redactedTrace(correlationId: String): RequestTrace? =
        facade.traces.redactedView(correlationId)
}

/**
 * Wire [com.omnillm.runtime.governor.ResourceGovernor] as resource port.
 */
class GovernorResourceAdapter(
    private val snapshot: () -> ResourceLedger,
    private val evidenceLabel: EvidenceLabel = EvidenceLabel.MEASURED,
    private val clockWallMs: () -> Long = { System.currentTimeMillis() },
    private val source: String = "resource-governor",
) : DashboardResourcePort {
    override fun ledgerSnapshot(): ResourceLedger = snapshot()
    override fun ledgerEvidence(): LedgerEvidence =
        LedgerEvidence(
            evidenceLabel = evidenceLabel,
            sampledAtEpochMs = clockWallMs(),
            source = source,
        )
}

/**
 * In-memory request port for unit tests (client-generated request IDs).
 */
class InMemoryDashboardRequestPort : DashboardRequestPort {
    private val lock = Any()
    private val byId = linkedMapOf<String, ActiveRequestFact>()

    fun put(fact: ActiveRequestFact) = synchronized(lock) {
        byId[fact.requestId] = fact
    }

    override fun listActiveRequests(): List<ActiveRequestFact> = synchronized(lock) {
        byId.values.filter { !isTerminal(it.phase) }.toList()
    }

    override fun query(requestId: RequestId): OmniResult<ActiveRequestFact> =
        synchronized(lock) {
            val f = byId[requestId.value]
                ?: return OmniResult.err(
                    com.omnillm.core.errors.generated.OmniError.NOT_FOUND(
                        message = "request not found",
                        details = mapOf("requestId" to requestId.value),
                    ),
                )
            OmniResult.ok(f)
        }

    override fun cancel(requestId: RequestId): OmniResult<ActiveRequestFact> =
        synchronized(lock) {
            val f = byId[requestId.value]
                ?: return OmniResult.err(
                    com.omnillm.core.errors.generated.OmniError.NOT_FOUND(
                        message = "request not found",
                        details = mapOf("requestId" to requestId.value),
                    ),
                )
            if (!f.cancelAllowed && !isCancellablePhase(f.phase)) {
                return OmniResult.err(
                    com.omnillm.core.errors.generated.OmniError.STATE_CONFLICT(
                        message = "cancel not allowed in phase ${f.phase}",
                        details = mapOf("requestId" to requestId.value, "phase" to f.phase),
                    ),
                )
            }
            if (isTerminal(f.phase)) {
                return OmniResult.err(
                    com.omnillm.core.errors.generated.OmniError.STATE_CONFLICT(
                        message = "request already terminal",
                        details = mapOf("requestId" to requestId.value, "phase" to f.phase),
                    ),
                )
            }
            val cancelled = f.copy(phase = "CANCELLED", cancelAllowed = false)
            byId[requestId.value] = cancelled
            OmniResult.ok(cancelled)
        }

    companion object {
        private val TERMINAL = setOf(
            "COMPLETED", "FAILED", "CANCELLED", "ABORTED_UNCERTAIN",
        )
        private val CANCELLABLE = setOf(
            "RECEIVED", "CLAIMED", "PLANNING", "QUEUED", "RESERVED",
            "COMMITTING", "PREPARED", "STARTING", "STREAMING", "RECONCILING",
        )

        fun isTerminal(phase: String): Boolean = phase in TERMINAL
        fun isCancellablePhase(phase: String): Boolean = phase in CANCELLABLE
    }
}

