package com.omnillm.features.server

import com.omnillm.features.server.api.DeveloperServerApi
import com.omnillm.features.server.ports.ServerRuntimePorts
import com.omnillm.features.server.usecase.DeveloperServerService
import com.omnillm.features.server.viewmodel.DeveloperServerViewModel

/**
 * Feature Pack `:features:server` (FEAT-SERVER).
 *
 * Composes HTTP_INTERFACE, AIDL_INTERFACE, CAPABILITY_NEGOTIATION,
 * REQUEST_LIFECYCLE, STREAMING, CANCELLATION, IDEMPOTENCY, ASSET_LIFECYCLE
 * via control-plane ports only — does not redefine Request / Session / Trust
 * (FEATURE-SYSTEM). HTTP transport lives in `:interfaces:http` and is hosted
 * by `:android:runtime-service`.
 *
 * UI process talks only through Admin binder → these ports. Never open domain
 * DB or load native engines (INV-001 / ADR-010).
 */
object ServerFeatureModule {
    const val MODULE_PATH: String = ":features:server"
    const val FEATURE_ID: String = "FEAT-SERVER"

    /** Default loopback OpenAPI server port (OpenAPI servers[0]). */
    const val DEFAULT_LOOPBACK_PORT: Int = 11434

    /** Packaged OpenAPI authority path (shared with interfaces:http). */
    const val OPENAPI_SPEC_PATH: String = "specs/openapi/omnillm.openapi.yaml"

    /**
     * Build the public API surface for UI / Admin wiring.
     * Runtime ports must be control-plane implementations (ADR-010).
     */
    fun createApi(ports: ServerRuntimePorts): DeveloperServerApi =
        DeveloperServerService(ports)

    /**
     * UI-facing ViewModel projection. Does not load engines or write DB (INV-001).
     */
    fun createViewModel(api: DeveloperServerApi): DeveloperServerViewModel =
        DeveloperServerViewModel(api)
}
