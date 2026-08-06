package com.omnillm.features.diagnostics.ports

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.runtime.observability.DiagnosticAllowlist
import com.omnillm.runtime.observability.HealthView
import com.omnillm.runtime.observability.MetricSample
import com.omnillm.runtime.observability.RequestTrace

/**
 * Capability availability for FEAT-DIAGNOSTICS negotiation.
 * Unknown capability ⇒ fail closed (INV-018).
 */
interface CapabilityAvailabilityPort {
    fun resolve(capabilityId: CapabilityId): CapabilityState
}

/**
 * Default: required feature capabilities are SUPPORTED; everything else UNKNOWN.
 * Tests / runtime host may override with evidence-driven states.
 */
class DefaultCapabilityAvailabilityPort(
    private val supported: Set<CapabilityId> = com.omnillm.features.diagnostics.DiagnosticsModule.REQUIRED_CAPABILITIES,
) : CapabilityAvailabilityPort {
    override fun resolve(capabilityId: CapabilityId): CapabilityState =
        if (capabilityId in supported) CapabilityState.SUPPORTED else CapabilityState.UNKNOWN
}

/**
 * Optional control-plane sources feeding the redacted exporter.
 * Empty defaults keep unit tests hermetic (INV-001 feature pack).
 */
interface DiagnosticSourcePort {
    fun metricSamples(): List<MetricSample> = emptyList()
    fun serviceHealth(): HealthView? = null
    fun recentTraces(limit: Int = 16): List<RequestTrace> = emptyList()
    fun requestJobStateFields(): List<Map<String, String>> = emptyList()
    fun capabilitySnapshotFields(): List<Map<String, String>> = emptyList()
    fun modelEngineIdFields(): List<Map<String, String>> = emptyList()
    fun resourceSnapshotFields(): List<Map<String, String>> = emptyList()
    fun errorChainFields(): List<Map<String, String>> = emptyList()
    fun crashSummaryFields(): List<Map<String, String>> = emptyList()
    fun configurationFields(): Map<String, String> = emptyMap()
    fun runtimeVersionFields(): Map<String, String> = emptyMap()
    fun reproductionHints(): Map<String, String> = emptyMap()
}

object EmptyDiagnosticSourcePort : DiagnosticSourcePort

/**
 * In-memory source used by tests to inject redacted-safe field maps.
 */
class InMemoryDiagnosticSourcePort(
    private val metrics: List<MetricSample> = emptyList(),
    private val health: HealthView? = null,
    private val traces: List<RequestTrace> = emptyList(),
    private val requestJobs: List<Map<String, String>> = emptyList(),
    private val capabilities: List<Map<String, String>> = emptyList(),
    private val modelEngines: List<Map<String, String>> = emptyList(),
    private val resources: List<Map<String, String>> = emptyList(),
    private val errors: List<Map<String, String>> = emptyList(),
    private val crashes: List<Map<String, String>> = emptyList(),
    private val configuration: Map<String, String> = emptyMap(),
    private val runtimeVersions: Map<String, String> = emptyMap(),
    private val reproduction: Map<String, String> = emptyMap(),
) : DiagnosticSourcePort {
    override fun metricSamples(): List<MetricSample> = metrics
    override fun serviceHealth(): HealthView? = health
    override fun recentTraces(limit: Int): List<RequestTrace> = traces.take(limit)
    override fun requestJobStateFields(): List<Map<String, String>> = requestJobs
    override fun capabilitySnapshotFields(): List<Map<String, String>> = capabilities
    override fun modelEngineIdFields(): List<Map<String, String>> = modelEngines
    override fun resourceSnapshotFields(): List<Map<String, String>> = resources
    override fun errorChainFields(): List<Map<String, String>> = errors
    override fun crashSummaryFields(): List<Map<String, String>> = crashes
    override fun configurationFields(): Map<String, String> = configuration
    override fun runtimeVersionFields(): Map<String, String> = runtimeVersions
    override fun reproductionHints(): Map<String, String> = reproduction
}

/** Category name parsing — fail closed on unknown. */
fun parseExportCategory(name: String): DiagnosticAllowlist.Category? =
    DiagnosticAllowlist.Category.entries.firstOrNull { it.name == name }
