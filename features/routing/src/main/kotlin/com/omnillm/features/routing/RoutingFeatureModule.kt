package com.omnillm.features.routing

import com.omnillm.features.routing.api.RoutingApi
import com.omnillm.features.routing.ports.RoutingFeaturePorts
import com.omnillm.features.routing.usecase.RoutingService
import com.omnillm.features.routing.viewmodel.RoutingViewModel

/**
 * Feature Pack `:features:routing` (FEAT-ROUTING).
 *
 * Composes MULTI_MODEL_ROUTING, FALLBACK_POLICY, CAPABILITY_NEGOTIATION,
 * REQUEST_LIFECYCLE, RESOURCE_ACCOUNTING via Orchestrator ports only —
 * does not redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 *
 * Hard rules:
 * - Never silent cross-revision fallback (only ALLOW_LIST + explicit allowlist)
 * - Plan has no domain mutation (ADR-002)
 * - Control-plane sole writer (ADR-010); UI never loads engines (INV-001)
 * - Actual revision/engine/backend always disclosed when fallback used
 */
object RoutingFeatureModule {
    const val MODULE_PATH: String = ":features:routing"
    const val FEATURE_ID: String = "FEAT-ROUTING"

    fun createApi(ports: RoutingFeaturePorts): RoutingApi = RoutingService(ports)

    fun createViewModel(api: RoutingApi): RoutingViewModel = RoutingViewModel(api)
}
