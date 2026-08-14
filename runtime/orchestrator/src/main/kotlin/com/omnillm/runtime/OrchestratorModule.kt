package com.omnillm.runtime

import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.runtime.governor.ResourceGovernor
import com.omnillm.runtime.orchestrator.CapabilityLookup
import com.omnillm.runtime.orchestrator.HealthLookup
import com.omnillm.runtime.orchestrator.HealthSnapshot
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.Orchestrator
import com.omnillm.runtime.orchestrator.OrchestratorFactory
import com.omnillm.runtime.orchestrator.SessionCompatibilityCheck
import com.omnillm.runtime.requestregistry.CommitLedger
import com.omnillm.runtime.requestregistry.RequestRegistry

/**
 * Module marker and wiring for `:runtime:orchestrator`
 * (CORE-ORCHESTRATOR, FEAT-ROUTING, ADR-002).
 *
 * Types and states must come from product `specs/` catalogs — do not invent enums.
 */
object OrchestratorModule {
    const val MODULE_PATH: String = ":runtime:orchestrator"

    /**
     * Wire Orchestrator with injected managers (control-plane sole writer path).
     */
    fun create(
        registry: RequestRegistry,
        governor: ResourceGovernor,
        engine: InferenceEnginePort,
        capabilities: CapabilityLookup,
        health: HealthLookup = HealthLookup { HealthSnapshot() },
        sessionCheck: SessionCompatibilityCheck = SessionCompatibilityCheck { _, _ -> null },
        policyVersion: String = "orchestrator-drr-v1",
        issuerBootId: String,
        runtimeEpoch: Long,
        clockMonotonic: () -> Long = { System.nanoTime() },
        /**
         * Durable commit recovery ledger (C-01). Production control plane
         * injects the SQLite-backed ledger so INTENT_RECORDED survives restart
         * (REL-RECOVERY); tests may leave it null (scaffold only).
         */
        commitLedger: CommitLedger? = null,
    ): Orchestrator =
        OrchestratorFactory.create(
            registry = registry,
            governor = governor,
            engine = engine,
            capabilities = capabilities,
            health = health,
            sessionCheck = sessionCheck,
            policyVersion = policyVersion,
            issuerBootId = issuerBootId,
            runtimeEpoch = runtimeEpoch,
            clockMonotonic = clockMonotonic,
            commitLedger = commitLedger,
        )

    /**
     * In-memory registry + governor factory for unit / integration tests.
     */
    fun createInMemoryHarness(
        capacity: ResourceVector = ResourceVector(cpuAnonBytes = 1_000_000L, nativeThreads = 64L),
        safetyMargin: ResourceVector = ResourceVector(cpuAnonBytes = 1_000L, nativeThreads = 2L),
        engine: InferenceEnginePort,
        capabilities: CapabilityLookup,
        health: HealthLookup = HealthLookup { HealthSnapshot() },
        issuerBootId: String = "boot-test",
        runtimeEpoch: Long = 1L,
        clockMonotonic: () -> Long = { 1_000L },
        policyVersion: String = "orchestrator-drr-v1",
    ): OrchestratorHarness {
        val (registry, _, store) = RequestRegistryModule.createInMemory(clock = { "2026-08-03T00:00:00Z" })
        val governor = ResourceGovernor(
            capacity = capacity,
            safetyMargin = safetyMargin,
            issuerBootId = issuerBootId,
            runtimeEpoch = runtimeEpoch,
            clockMonotonic = clockMonotonic,
            idSource = { java.util.UUID.randomUUID().toString() },
        )
        val orchestrator = create(
            registry = registry,
            governor = governor,
            engine = engine,
            capabilities = capabilities,
            health = health,
            issuerBootId = issuerBootId,
            runtimeEpoch = runtimeEpoch,
            clockMonotonic = clockMonotonic,
            policyVersion = policyVersion,
        )
        return OrchestratorHarness(
            orchestrator = orchestrator,
            registry = registry,
            governor = governor,
            schedulerPolicyVersion = policyVersion,
        )
    }
}

/** Test / early-wiring bundle. */
data class OrchestratorHarness(
    val orchestrator: Orchestrator,
    val registry: RequestRegistry,
    val governor: ResourceGovernor,
    val schedulerPolicyVersion: String,
)
