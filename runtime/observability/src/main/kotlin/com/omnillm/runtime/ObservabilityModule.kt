package com.omnillm.runtime

import com.omnillm.runtime.observability.HealthRegistry
import com.omnillm.runtime.observability.InMemoryMetricRegistry
import com.omnillm.runtime.observability.InMemoryTraceRecorder
import com.omnillm.runtime.observability.MetricRegistry
import com.omnillm.runtime.observability.ObservabilityFacade
import com.omnillm.runtime.observability.TraceRecorder

/**
 * Module entry for `:runtime:observability` (CORE-OBSERVABILITY, SEC-PRIVACY).
 *
 * Structured metrics (with evidence labels), health views, request traces,
 * redaction helpers, diagnostic-export field allowlists, and telemetry
 * allowlists (default off). UI never writes here (INV-001); control plane
 * is the sole producer (ADR-010).
 *
 * Authority: `specs/observability-catalog.yaml`, PROD-QUALITY evidence labels.
 */
object ObservabilityModule {
    const val MODULE_PATH: String = ":runtime:observability"

    /**
     * Build an in-memory facade suitable for unit tests and early control-plane wiring.
     * Persistence / rollup storage is out of scope for this module's pure core.
     */
    fun createFacade(
        clockWallMs: () -> Long = { System.currentTimeMillis() },
        clockMonotonicNs: () -> Long = { System.nanoTime() },
    ): ObservabilityFacade {
        val metrics: MetricRegistry = InMemoryMetricRegistry(clockWallMs = clockWallMs)
        val health = HealthRegistry(clockWallMs = clockWallMs)
        val traces: TraceRecorder = InMemoryTraceRecorder(clockMonotonicNs = clockMonotonicNs)
        return ObservabilityFacade(
            metrics = metrics,
            health = health,
            traces = traces,
            clockWallMs = clockWallMs,
        )
    }
}

