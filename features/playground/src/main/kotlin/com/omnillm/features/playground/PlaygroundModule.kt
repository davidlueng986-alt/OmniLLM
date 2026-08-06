package com.omnillm.features.playground

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.features.playground.api.PlaygroundApi
import com.omnillm.features.playground.ports.EmptyPlaygroundModelCatalogPort
import com.omnillm.features.playground.ports.PlaygroundCapabilityPort
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.ports.PlaygroundInferencePort
import com.omnillm.features.playground.ports.PlaygroundMetricsPort
import com.omnillm.features.playground.ports.PlaygroundModelCatalogPort
import com.omnillm.features.playground.ports.PlaygroundRuntimeStatusPort
import com.omnillm.features.playground.ports.NoOpPlaygroundMetricsPort
import com.omnillm.features.playground.ports.StaticRuntimeReadyPort
import com.omnillm.features.playground.usecase.PlaygroundService
import com.omnillm.features.playground.viewmodel.PlaygroundViewModel

/**
 * Feature pack `:features:playground` (FEAT-PLAYGROUND).
 *
 * Composes:
 * - TEXT_GENERATION / EMBEDDING / VISION_INPUT / AUDIO_INPUT
 * - REQUEST_LIFECYCLE / SESSION_LIFECYCLE / STREAMING / CANCELLATION / DEADLINE
 * - AIDL_INTERFACE / LOCAL_UI_INTERFACE
 * - USABILITY_VALIDATION / ACCESSIBILITY_VALIDATION
 *
 * Does **not** redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 * UI process talks only through this API / runtime binder — never opens domain
 * DB or loads native engines (INV-001).
 *
 * Client generates requestId / idempotencyKey before inference (ADR-004/005).
 */
object PlaygroundModule {
    const val MODULE_PATH: String = ":features:playground"
    const val FEATURE_ID: String = "FEAT-PLAYGROUND"

    val REQUIRED_CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.TEXT_GENERATION,
        CapabilityId.EMBEDDING,
        CapabilityId.VISION_INPUT,
        CapabilityId.AUDIO_INPUT,
        CapabilityId.REQUEST_LIFECYCLE,
        CapabilityId.SESSION_LIFECYCLE,
        CapabilityId.STREAMING,
        CapabilityId.CANCELLATION,
        CapabilityId.DEADLINE,
        CapabilityId.AIDL_INTERFACE,
        CapabilityId.LOCAL_UI_INTERFACE,
        CapabilityId.USABILITY_VALIDATION,
        CapabilityId.ACCESSIBILITY_VALIDATION,
    )

    /**
     * Wire control-plane ports. Call only from runtime host or test harness —
     * never construct inference engines inside the UI process.
     */
    fun createApi(
        inference: PlaygroundInferencePort,
        capabilities: PlaygroundCapabilityPort,
        models: PlaygroundModelCatalogPort = EmptyPlaygroundModelCatalogPort,
        metrics: PlaygroundMetricsPort = NoOpPlaygroundMetricsPort,
        runtimeStatus: PlaygroundRuntimeStatusPort = StaticRuntimeReadyPort,
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): PlaygroundApi =
        PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = inference,
                capabilities = capabilities,
                models = models,
                metrics = metrics,
                runtimeStatus = runtimeStatus,
            ),
            clockMs = clockMs,
        )

    fun createApi(ports: PlaygroundFeaturePorts, clockMs: () -> Long = { System.currentTimeMillis() }): PlaygroundApi =
        PlaygroundService(ports = ports, clockMs = clockMs)

    fun createViewModel(api: PlaygroundApi): PlaygroundViewModel = PlaygroundViewModel(api)
}
