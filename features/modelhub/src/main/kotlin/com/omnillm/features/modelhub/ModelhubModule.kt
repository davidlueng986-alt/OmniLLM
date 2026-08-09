package com.omnillm.features.modelhub

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.features.modelhub.acquisition.AcquisitionPipeline
import com.omnillm.features.modelhub.api.ModelHubApi
import com.omnillm.features.modelhub.catalog.OfflineFixtureCatalog
import com.omnillm.features.modelhub.ports.AcquisitionLinkStore
import com.omnillm.features.modelhub.ports.EmptySuggestedCatalogPort
import com.omnillm.features.modelhub.ports.InMemoryAcquisitionLinkStore
import com.omnillm.features.modelhub.ports.InMemoryModelDisplayMetadataPort
import com.omnillm.features.modelhub.ports.LiveReferenceQueryPort
import com.omnillm.features.modelhub.ports.LoadedModelQueryPort
import com.omnillm.features.modelhub.ports.LicenseAcceptancePort
import com.omnillm.features.modelhub.ports.ModelDisplayMetadataPort
import com.omnillm.features.modelhub.ports.ModelLoadRuntimePort
import com.omnillm.features.modelhub.ports.NoLicenseAcceptanceLedger
import com.omnillm.features.modelhub.ports.SuggestedCatalogPort
import com.omnillm.features.modelhub.ports.UnavailableModelLoadRuntimePort
import com.omnillm.features.modelhub.usecase.ModelHubService
import com.omnillm.features.modelhub.viewmodel.ModelHubViewModel
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.ports.LiveReferences
import com.omnillm.runtime.policy.download.DownloadUrlPolicy

/**
 * Feature pack `:features:modelhub` (FEAT-MODELHUB).
 *
 * Composes:
 * - MODEL_ACQUISITION / MODEL_IDENTITY / SAFE_INSTALLATION / MODEL_LIFECYCLE
 * - JOB_LIFECYCLE / JOB_RECOVERY / RESOURCE_ACCOUNTING / LOCAL_UI_INTERFACE
 * - COMPATIBILITY_EVALUATION
 *
 * Does **not** redefine Request / Session / Trust semantics (FEATURE-SYSTEM).
 * UI process talks only through this API / Admin binder (INV-001).
 */
object ModelhubModule {
    const val MODULE_PATH: String = ":features:modelhub"
    const val FEATURE_ID: String = "FEAT-MODELHUB"

    val REQUIRED_CAPABILITIES: Set<CapabilityId> = setOf(
        CapabilityId.MODEL_ACQUISITION,
        CapabilityId.MODEL_IDENTITY,
        CapabilityId.MODEL_LIFECYCLE,
        CapabilityId.SAFE_INSTALLATION,
        CapabilityId.COMPATIBILITY_EVALUATION,
        CapabilityId.JOB_LIFECYCLE,
        CapabilityId.JOB_RECOVERY,
        CapabilityId.RESOURCE_ACCOUNTING,
        CapabilityId.LOCAL_UI_INTERFACE,
    )

    /**
     * Wire control-plane dependencies. Call only from runtime host
     * (`:android:runtime-service`), never from UI process.
     */
    fun createApi(
        jobManager: JobManager,
        modelManager: ModelManager,
        catalog: SuggestedCatalogPort = OfflineFixtureCatalog.DEFAULT,
        display: ModelDisplayMetadataPort = InMemoryModelDisplayMetadataPort(),
        links: AcquisitionLinkStore = InMemoryAcquisitionLinkStore(),
        loadedModels: LoadedModelQueryPort = object : LoadedModelQueryPort {
            override suspend fun findByInstallation(installationId: String): List<LoadedModelSnapshot> =
                emptyList()
        },
        references: LiveReferenceQueryPort = object : LiveReferenceQueryPort {
            override suspend fun installationReferences(installationId: String): LiveReferences =
                LiveReferences()
        },
        loadRuntime: ModelLoadRuntimePort = UnavailableModelLoadRuntimePort,
        licenseAcceptance: LicenseAcceptancePort = NoLicenseAcceptanceLedger,
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): ModelHubApi =
        ModelHubService(
            jobManager = jobManager,
            modelManager = modelManager,
            catalog = catalog,
            display = display,
            links = links,
            loadedModels = loadedModels,
            references = references,
            loadRuntime = loadRuntime,
            licenseAcceptance = licenseAcceptance,
            clockMs = clockMs,
        )

    /**
     * Software E2E acquisition executor (HTTPS pin / SAF stream → quarantine → READY).
     * Control-plane / host tests only — never constructed in UI process (ADR-010).
     */
    fun createAcquisitionPipeline(
        api: ModelHubApi,
        modelStore: ModelStorePort,
        urlPolicy: DownloadUrlPolicy.Policy = DownloadUrlPolicy.Policy.DEFAULT,
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): AcquisitionPipeline =
        AcquisitionPipeline(
            modelHub = api,
            modelStore = modelStore,
            urlPolicy = urlPolicy,
            clockMs = clockMs,
        )

    /** Offline fixture suggested catalog (software E2E default). */
    fun offlineFixtureCatalog(): SuggestedCatalogPort = OfflineFixtureCatalog.DEFAULT

    fun emptyCatalog(): SuggestedCatalogPort = EmptySuggestedCatalogPort()

    fun createViewModel(api: ModelHubApi): ModelHubViewModel = ModelHubViewModel(api)
}
