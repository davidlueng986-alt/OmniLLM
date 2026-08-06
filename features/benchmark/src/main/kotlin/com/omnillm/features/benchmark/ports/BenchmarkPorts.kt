package com.omnillm.features.benchmark.ports

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.features.benchmark.BenchmarkFeatureModule
import com.omnillm.features.benchmark.domain.EnvironmentSnapshot

/**
 * Capability availability for FEAT-BENCHMARK negotiation.
 * Unknown capability ⇒ fail closed (INV-018).
 */
interface BenchmarkCapabilityAvailabilityPort {
    fun resolve(capabilityId: CapabilityId): CapabilityState
}

/**
 * Default: required feature capabilities are SUPPORTED; everything else UNKNOWN.
 */
class DefaultBenchmarkCapabilityAvailabilityPort(
    private val supported: Set<CapabilityId> = BenchmarkFeatureModule.REQUIRED_CAPABILITIES,
) : BenchmarkCapabilityAvailabilityPort {
    override fun resolve(capabilityId: CapabilityId): CapabilityState =
        if (capabilityId in supported) CapabilityState.SUPPORTED else CapabilityState.UNKNOWN
}

/**
 * Fixed map for tests / host injection.
 */
class FixedBenchmarkCapabilityAvailabilityPort(
    private val states: Map<CapabilityId, CapabilityState>,
    private val defaultState: CapabilityState = CapabilityState.UNKNOWN,
) : BenchmarkCapabilityAvailabilityPort {
    override fun resolve(capabilityId: CapabilityId): CapabilityState =
        states[capabilityId] ?: defaultState
}

/**
 * Environment snapshot source (thermal, power, actual load identity).
 * Control-plane only — never from UI process (INV-001).
 */
interface BenchmarkEnvironmentPort {
    fun capture(
        requestedModelRevisionId: String,
        requestedEngineBuildId: String,
        requestedBackend: String,
    ): EnvironmentSnapshot
}

object EmptyBenchmarkEnvironmentPort : BenchmarkEnvironmentPort {
    override fun capture(
        requestedModelRevisionId: String,
        requestedEngineBuildId: String,
        requestedBackend: String,
    ): EnvironmentSnapshot =
        EnvironmentSnapshot(
            thermalCelsius = 35,
            thermalState = "NOMINAL",
            batteryPercent = 80,
            powerSource = "battery",
            backgroundRestriction = false,
            driverResetDetected = false,
            backendActual = requestedBackend,
            engineBuildIdActual = requestedEngineBuildId,
            modelRevisionIdActual = requestedModelRevisionId,
            capturedAtEpochMs = 0L,
        )
}

/**
 * In-memory environment for hermetic tests.
 */
class FixedBenchmarkEnvironmentPort(
    private val snapshot: EnvironmentSnapshot,
) : BenchmarkEnvironmentPort {
    override fun capture(
        requestedModelRevisionId: String,
        requestedEngineBuildId: String,
        requestedBackend: String,
    ): EnvironmentSnapshot = snapshot
}
