package com.omnillm.features.diagnostics

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.diagnostics.ports.CapabilityAvailabilityPort
import com.omnillm.features.diagnostics.ports.DefaultCapabilityAvailabilityPort
import com.omnillm.features.diagnostics.ports.DiagnosticSourcePort
import com.omnillm.features.diagnostics.ports.EmptyDiagnosticSourcePort
import com.omnillm.features.diagnostics.usecase.DiagnosticsService
import com.omnillm.features.diagnostics.viewmodel.DiagnosticsViewModel
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.observability.ObservabilityFacade

/**
 * Feature pack `:features:diagnostics` (FEAT-DIAGNOSTICS).
 *
 * Composes platform capabilities:
 * - DIAGNOSTIC_REASONING / REQUEST_TRACE / SERVICE_HEALTH / EVIDENCE_LABELING
 * - JOB_LIFECYCLE / JOB_RECOVERY / LOCAL_UI_INTERFACE
 *
 * Does **not** redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 * UI process talks only through this API / Admin binder (INV-001).
 * Bundle bytes and job ledger are written only by the runtime control plane (ADR-010).
 */
object DiagnosticsModule {
    const val MODULE_PATH: String = ":features:diagnostics"
    const val FEATURE_ID: String = "FEAT-DIAGNOSTICS"

    /** From `specs/feature-capability-map.yaml` for FEAT-DIAGNOSTICS. */
    val REQUIRED_CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.DIAGNOSTIC_REASONING,
        CapabilityId.REQUEST_TRACE,
        CapabilityId.SERVICE_HEALTH,
        CapabilityId.EVIDENCE_LABELING,
        CapabilityId.JOB_LIFECYCLE,
        CapabilityId.JOB_RECOVERY,
        CapabilityId.LOCAL_UI_INTERFACE,
    )

    /**
     * Wire control-plane dependencies. Call only from runtime host
     * (`:android:runtime-service`), never from UI process.
     */
    fun createApi(
        jobManager: JobManager,
        observability: ObservabilityFacade,
        sources: DiagnosticSourcePort = EmptyDiagnosticSourcePort,
        capabilityAvailability: CapabilityAvailabilityPort = DefaultCapabilityAvailabilityPort(),
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): DiagnosticsApi =
        DiagnosticsService(
            jobManager = jobManager,
            observability = observability,
            sources = sources,
            capabilityAvailability = capabilityAvailability,
            clockMs = clockMs,
        )

    fun createViewModel(api: DiagnosticsApi): DiagnosticsViewModel =
        DiagnosticsViewModel(api)
}
