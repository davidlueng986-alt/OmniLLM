package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.resource.Reservation

/**
 * Unified engine adapter SPI (CORE-ENGINE §2).
 *
 * Extends [EngineLoadPort] so Model Manager load coordination remains compatible.
 * Adapters map upstream runtimes to shared lifecycle; they must **not**:
 * - write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - return native pointers across process
 * - silently ignore unsupported parameters
 * - mutate domain state during Plan (ADR-002)
 *
 * Probe/load/commit/start semantics: Plan → Reserve → Commit → Execute.
 * Plan has no domain mutation. Commit is one-shot, idempotent, queryable.
 */
interface OmniEngine : EngineLoadPort {
    override val engineBuildId: EngineBuildId

    /**
     * Static/runtime engine descriptor for a device (no mutation).
     * Capability cells still default UNKNOWN until Registry evidence says otherwise.
     */
    suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor>

    /** Pure probe plan — no domain mutation (ADR-002). */
    suspend fun planProbe(input: ProbeInput): OmniResult<ProbePlan>

    /**
     * Execute a probe under an already-issued [reservation].
     * Not a durable commit; results are advisory measurement only.
     */
    suspend fun probe(
        plan: ProbePlan,
        reservation: Reservation,
        op: OperationContext,
    ): OmniResult<ProbeResult>

    /**
     * Obtain [LoadedModelPort] for a successfully committed load handle.
     * Control plane owns LoadedModel aggregate identity; this only binds the adapter port.
     */
    fun bindLoadedModel(handle: LoadedModelHandle): OmniResult<LoadedModelPort>
}

/**
 * Engine self-description returned by [OmniEngine.describe].
 * Does not elevate trust or mark capabilities SUPPORTED (INV-008).
 */
data class EngineDescriptor(
    val engineBuildId: EngineBuildId,
    val engineId: String,
    val backends: List<String>,
    val supportedFormats: List<String> = emptyList(),
    /** Placement class labels from [PlacementClassLabels] this build may propose. */
    val placementClasses: List<String> = emptyList(),
    /** Per-phase cancellation mode labels from [CancellationModes]; missing ⇒ UNKNOWN. */
    val phaseCancellation: Map<String, String> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
) {
    init {
        require(engineId.isNotEmpty()) { "engineId must be non-empty" }
        backends.forEach { require(it.isNotEmpty()) { "backend must be non-empty" } }
        supportedFormats.forEach { require(it.isNotEmpty()) { "format must be non-empty" } }
        placementClasses.forEach {
            require(PlacementClassLabels.isKnown(it)) {
                "unknown placement class (fail closed): $it"
            }
        }
        phaseCancellation.forEach { (phase, mode) ->
            require(EnginePhases.isKnown(phase)) { "unknown phase: $phase" }
            require(CancellationModes.isKnown(mode)) { "unknown cancellation mode: $mode" }
        }
    }
}

/** Input for pure [OmniEngine.planProbe]. */
data class ProbeInput(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val device: DeviceDescriptor,
    val backend: String,
    val runtimeEpoch: Long,
    val workloadEnvelope: String = "default",
) {
    init {
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(workloadEnvelope.isNotEmpty()) { "workloadEnvelope must be non-empty" }
    }
}

/** Pure probe plan (no mutation). */
data class ProbePlan(
    val planId: PlanId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val engineBuildId: EngineBuildId,
    val backend: String,
    val resourceEnvelope: ResourceEnvelope,
    val phaseCapabilityDigest: Sha256Digest,
    val canonicalInputDigest: Sha256Digest,
    val expiryMonotonic: Long,
    val runtimeEpoch: Long,
) {
    init {
        require(backend.isNotEmpty()) { "backend must be non-empty" }
        require(expiryMonotonic >= 0L) { "expiryMonotonic must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}

/** Probe measurement result (advisory; never elevates model trust). */
data class ProbeResult(
    val planId: PlanId,
    val success: Boolean,
    val observedEnvelope: ResourceEnvelope? = null,
    val attributes: Map<String, String> = emptyMap(),
)
