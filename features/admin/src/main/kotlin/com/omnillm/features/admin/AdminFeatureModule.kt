package com.omnillm.features.admin

import com.omnillm.features.admin.ports.AdminApiServiceAdapter
import com.omnillm.features.admin.ports.AdminFeaturePorts
import com.omnillm.features.admin.ports.AdminModelPort
import com.omnillm.features.admin.ports.AdminRuntimeStatusPort
import com.omnillm.features.admin.ports.EmptyAdminModelPort
import com.omnillm.features.admin.usecase.AdminFeatureApi
import com.omnillm.features.admin.viewmodel.AdminHomeViewModel
import com.omnillm.features.admin.viewmodel.AdminJobsViewModel
import com.omnillm.features.admin.viewmodel.AdminSettingsViewModel
import com.omnillm.interfaces.admin.AdminApiService

/**
 * Feature pack entry for `:features:admin` (FEAT-ADMIN).
 *
 * Composes platform Admin / Job / Policy capabilities; does not redefine
 * Request, Session, or Trust semantics (FEATURE-SYSTEM).
 *
 * UI process talks only through Admin binder → [AdminApiService] → these ports.
 * Never open domain DB or load native engines from UI (INV-001).
 */
object AdminFeatureModule {
    const val MODULE_PATH: String = ":features:admin"
    const val FEATURE_ID: String = "FEAT-ADMIN"

    /**
     * Build feature API from the runtime-hosted [AdminApiService].
     *
     * @param models optional Model Manager projection (ADR-008 identities only)
     * @param runtimeStatus optional runtime/resource strip for Admin home
     */
    fun createApi(
        adminApi: AdminApiService,
        models: AdminModelPort = EmptyAdminModelPort,
        runtimeStatus: AdminRuntimeStatusPort? = null,
    ): AdminFeatureApi {
        val adapter = AdminApiServiceAdapter(adminApi)
        val ports = adapter.asFeaturePorts(models = models, runtimeStatus = runtimeStatus)
        return AdminFeatureApi(ports)
    }

    fun createApi(ports: AdminFeaturePorts): AdminFeatureApi = AdminFeatureApi(ports)

    fun createHomeViewModel(api: AdminFeatureApi): AdminHomeViewModel =
        AdminHomeViewModel(api)

    fun createJobsViewModel(api: AdminFeatureApi): AdminJobsViewModel =
        AdminJobsViewModel(api)

    fun createSettingsViewModel(api: AdminFeatureApi): AdminSettingsViewModel =
        AdminSettingsViewModel(api)
}
