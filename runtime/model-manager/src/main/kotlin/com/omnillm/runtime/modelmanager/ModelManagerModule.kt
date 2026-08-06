package com.omnillm.runtime.modelmanager

import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.persistence.InstallationLedgerPorts
import com.omnillm.data.persistence.RevisionLeaseLedgerPorts
import com.omnillm.engines.api.EngineLoadPort
import com.omnillm.runtime.modelmanager.durable.SqlInstallationRepository
import com.omnillm.runtime.modelmanager.durable.SqlRevisionLeaseRepository
import com.omnillm.runtime.modelmanager.memory.DefaultTrustEvaluationPort
import com.omnillm.runtime.modelmanager.memory.FailClosedEngineLoadPort
import com.omnillm.runtime.modelmanager.memory.FailClosedPrivilegedLoadReverify
import com.omnillm.runtime.modelmanager.memory.InMemoryInstallationRepository
import com.omnillm.runtime.modelmanager.memory.InMemoryLoadedModelRepository
import com.omnillm.runtime.modelmanager.memory.InMemoryModelStorePort
import com.omnillm.runtime.modelmanager.memory.InMemoryRevisionLeaseRepository
import com.omnillm.runtime.modelmanager.memory.ZeroReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.LoadedModelRepository
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.RevisionLeaseRepository
import com.omnillm.runtime.modelmanager.ports.TrustEvaluationPort
import com.omnillm.runtime.modelmanager.supply.EmbeddedCatalogRoot
import com.omnillm.runtime.modelmanager.supply.InMemorySupplyChainHooks
import com.omnillm.runtime.modelmanager.supply.NoopSupplyChainHooks
import com.omnillm.runtime.modelmanager.supply.SupplyChainBootstrapService
import com.omnillm.runtime.modelmanager.supply.SupplyChainHooks

/**
 * Module `:runtime:model-manager` — acquisition, trust, installation, load lifecycle.
 *
 * Authority: CORE-MODEL, SEC-SUPPLY, DATA-STORAGE, ADR-008, ADR-009.
 * Installation vs LoadedModel are separate aggregates; RevisionLease fences delete.
 * Engine planLoad/commitLoad are ports only (no native engines in this module).
 *
 * Supply-chain hooks: [SupplyChainHooks] / [CatalogRootBootstrap] (SEC-SUPPLY §1–§8).
 *
 * Production: [createDurableControlPlane] with SQL installation/lease + filesystem
 * model store. [createInMemoryControlPlane] is test/bootstrap only.
 */
object ModelManagerModule {
    const val MODULE_PATH: String = ":runtime:model-manager"

    fun noopSupplyChainHooks(): SupplyChainHooks = NoopSupplyChainHooks()

    fun inMemorySupplyChainHooks(root: EmbeddedCatalogRoot): SupplyChainHooks =
        InMemorySupplyChainHooks(root)

    fun supplyChainBootstrap(
        hooks: SupplyChainHooks,
        clockEpochMs: () -> Long = { System.currentTimeMillis() },
    ): SupplyChainBootstrapService = SupplyChainBootstrapService(hooks, clockEpochMs)

    /**
     * In-memory installation/load/lease stores (**test-only** / host smoke).
     * Engine load is fail-closed (no QUALIFIED claim).
     *
     * Production must use [createDurableControlPlane] (ADR-010 / INV-001).
     */
    fun createInMemoryControlPlane(
        modelStore: ModelStorePort = InMemoryModelStorePort(),
        engine: EngineLoadPort = FailClosedEngineLoadPort(),
        trustEvaluation: TrustEvaluationPort = DefaultTrustEvaluationPort(),
        references: ReferenceSnapshotPort = ZeroReferenceSnapshotPort,
        privilegedReverify: PrivilegedLoadReverifyPort = FailClosedPrivilegedLoadReverify(),
        installations: InstallationRepository = InMemoryInstallationRepository(),
        loadedModels: LoadedModelRepository = InMemoryLoadedModelRepository(),
        leases: RevisionLeaseRepository = InMemoryRevisionLeaseRepository(),
    ): ModelManager =
        ModelManager.create(
            installationRepository = installations,
            loadedModelRepository = loadedModels,
            revisionLeaseRepository = leases,
            modelStore = modelStore,
            trustEvaluation = trustEvaluation,
            references = references,
            engine = engine,
            privilegedReverify = privilegedReverify,
        )

    /**
     * Process-crash durable control-plane Model Manager (ADR-010 sole writer).
     *
     * - Installations + revision leases → SQLDelight via [installationPorts] /
     *   [leasePorts] from [com.omnillm.data.persistence.ControlPlaneDatabase]
     * - Model bytes → [modelStore] (filesystem quarantine + atomic promote)
     * - LoadedModel stays in-process (native handles non-durable per DATA-OWNERSHIP)
     *
     * Call only from runtime control plane attach.
     */
    fun createDurableControlPlane(
        installationPorts: InstallationLedgerPorts,
        leasePorts: RevisionLeaseLedgerPorts,
        modelStore: ModelStorePort,
        engine: EngineLoadPort = FailClosedEngineLoadPort(),
        trustEvaluation: TrustEvaluationPort = DefaultTrustEvaluationPort(),
        references: ReferenceSnapshotPort = ZeroReferenceSnapshotPort,
        privilegedReverify: PrivilegedLoadReverifyPort =
            DefaultPrivilegedLoadReverify.failClosedUntilSupplyWired(modelStore),
        loadedModels: LoadedModelRepository = InMemoryLoadedModelRepository(),
        clock: () -> String = { java.time.Instant.now().toString() },
    ): ModelManager {
        val installations = SqlInstallationRepository(ports = installationPorts, clock = clock)
        val leases = SqlRevisionLeaseRepository(ports = leasePorts, clock = clock)
        return ModelManager.create(
            installationRepository = installations,
            loadedModelRepository = loadedModels,
            revisionLeaseRepository = leases,
            modelStore = modelStore,
            trustEvaluation = trustEvaluation,
            references = references,
            engine = engine,
            privilegedReverify = privilegedReverify,
        )
    }
}
