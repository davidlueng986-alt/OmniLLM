package com.omnillm.features.autosetup

import com.omnillm.features.autosetup.api.AutoSetupApi
import com.omnillm.features.autosetup.ports.AutoSetupRuntimePorts
import com.omnillm.features.autosetup.usecase.AutoSetupService
import com.omnillm.features.autosetup.viewmodel.AutoSetupViewModel

/**
 * Feature Pack `:features:auto-setup` (FEAT-AUTOSETUP).
 *
 * Composes DEVICE_DISCOVERY, RECOMMENDATION, AUTOMATED_CONFIGURATION with
 * MODEL_ACQUISITION / SAFE_INSTALLATION / RESOURCE_ACCOUNTING via
 * Orchestrator / Job / Model ports only — does not redefine Request / Session /
 * Trust semantics (FEATURE-SYSTEM).
 */
object AutoSetupModule {
    const val MODULE_PATH: String = ":features:auto-setup"
    const val FEATURE_ID: String = "FEAT-AUTOSETUP"

    /**
     * Build the public API surface for UI / Admin wiring.
     * Runtime ports must be control-plane implementations (ADR-010).
     */
    fun createApi(ports: AutoSetupRuntimePorts): AutoSetupApi =
        AutoSetupService(ports)

    /**
     * UI-facing ViewModel projection. Does not load engines or write DB (INV-001).
     */
    fun createViewModel(api: AutoSetupApi): AutoSetupViewModel =
        AutoSetupViewModel(api)
}
