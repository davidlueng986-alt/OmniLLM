package com.omnillm.features.modelhub

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.identity.InstallationId as IdentityInstallationId
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.data.modelstore.ContentIdentityCheck
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.PromoteResult
import com.omnillm.data.modelstore.QuarantineFileRecord
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.data.modelstore.QuarantineSnapshot
import com.omnillm.data.modelstore.ReadOnlyContentFd
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.EngineLoadPort
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.engines.api.LoadedModelHandle
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.features.modelhub.api.AcquisitionChannel
import com.omnillm.features.modelhub.api.AcquisitionDeclaredFile
import com.omnillm.features.modelhub.api.AcquisitionMaterializedFile
import com.omnillm.features.modelhub.api.AcquisitionProgressUpdate
import com.omnillm.features.modelhub.api.CancelAcquisitionSpec
import com.omnillm.features.modelhub.api.CatalogModelEntry
import com.omnillm.features.modelhub.api.ModelHubAction
import com.omnillm.features.modelhub.api.ModelHubCommandIdentity
import com.omnillm.features.modelhub.api.SetPinSpec
import com.omnillm.features.modelhub.api.StartDeleteSpec
import com.omnillm.features.modelhub.api.StartDownloadSpec
import com.omnillm.features.modelhub.api.StartImportSpec
import com.omnillm.features.modelhub.ports.FixedSuggestedCatalogPort
import com.omnillm.features.modelhub.ports.LiveReferenceQueryPort
import com.omnillm.features.modelhub.projection.ModelCardProjector
import com.omnillm.features.modelhub.usecase.ModelHubService
import com.omnillm.features.modelhub.viewmodel.ModelHubViewModel
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.domain.RevisionLeaseSnapshot
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.LiveReferences
import com.omnillm.runtime.modelmanager.ports.LoadedModelRepository
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.PrivilegedReverifyRequest
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.RevisionLeaseRepository
import com.omnillm.runtime.modelmanager.ports.TrustEvaluationPort
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-MODELHUB: catalog → download/import → install → delete wiring,
 * happy path, failure, and cancel.
 */
class ModelHubServiceTest {

    private lateinit var installations: MemInstallationRepo
    private lateinit var loadedModels: MemLoadedModelRepo
    private lateinit var leases: MemLeaseRepo
    private lateinit var modelStore: MemModelStore
    private lateinit var refs: MutableRefs
    private lateinit var modelManager: ModelManager
    private lateinit var jobManager: JobManager
    private lateinit var api: ModelHubService
    private lateinit var vm: ModelHubViewModel

    private val rev = ModelRevisionId.parse("c".repeat(64))
    private val pkg = ArtifactPackageId.parse("d".repeat(64))
    private val blob = BlobId.parse("a".repeat(64))
    private val digest = "e".repeat(64)

    private val catalogEntry = CatalogModelEntry(
        modelRevisionId = rev.hex,
        artifactPackageId = pkg.hex,
        displayName = "Tiny Demo",
        acquisitionChannel = AcquisitionChannel.SIGNED_CATALOG,
        byteLength = 1024L,
        licenseDigest = "f".repeat(64),
    )

    @Before
    fun setUp() {
        installations = MemInstallationRepo()
        loadedModels = MemLoadedModelRepo()
        leases = MemLeaseRepo()
        modelStore = MemModelStore()
        refs = MutableRefs()
        modelManager = ModelManager.create(
            installationRepository = installations,
            loadedModelRepository = loadedModels,
            revisionLeaseRepository = leases,
            modelStore = modelStore,
            trustEvaluation = FixedTrust(),
            references = refs,
            engine = NoopEngine(),
            privilegedReverify = OkReverify(),
        )
        jobManager = JobManager()
        api = ModelHubService(
            jobManager = jobManager,
            modelManager = modelManager,
            catalog = FixedSuggestedCatalogPort(listOf(catalogEntry)),
            references = object : LiveReferenceQueryPort {
                override suspend fun installationReferences(installationId: String): LiveReferences =
                    refs.installationRefs
            },
        )
        vm = ModelHubViewModel(api)
    }

    @Test
    fun snapshot_showsSuggestedUntilInstalled() = runBlocking {
        val snap = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(1, snap.suggested.size)
        assertEquals("Tiny Demo", snap.suggested[0].displayName)
        assertTrue(snap.installed.isEmpty())
        assertTrue(ModelHubAction.DOWNLOAD in snap.suggested[0].allowedActions)
        // Dimensions not collapsed.
        assertNull(snap.suggested[0].installationState)
        assertEquals(AcquisitionChannel.SIGNED_CATALOG, snap.suggested[0].acquisitionChannel)
    }

    @Test
    fun download_install_happyPath_reachesReady() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111111"
        val jobId = "22222222-2222-2222-2222-222222222222"

        val handle = assertOk(
            api.startDownload(
                LocalUiPrincipal.ID,
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    sourceUrl = "https://example.invalid/model.bin",
                    expectedSha256 = digest,
                    expectedBytes = 10L,
                    displayName = "Tiny Demo",
                    command = cmd("dl-1"),
                ),
            ),
        )
        assertTrue(handle.createdNew)
        assertEquals("QUEUED", handle.state)
        assertEquals("DISCOVERED", modelManager.getInstallation(InstallationId(installationId))!!.state)

        assertOk(
            api.beginAcquisitionAttempt(
                jobId = jobId,
                declaredRoles = listOf(
                    AcquisitionDeclaredFile(role = "weights", blobId = blob.hex, byteLength = 10L),
                ),
            ),
        )
        assertEquals("ACQUIRING", modelManager.getInstallation(InstallationId(installationId))!!.state)
        assertEquals("RUNNING", jobManager.query(com.omnillm.core.state.domain.JobId(jobId)).getOrNull()!!.state)

        assertOk(
            api.updateAcquisitionProgress(
                AcquisitionProgressUpdate(
                    jobId = jobId,
                    networkBytes = 10L,
                    materializedBytes = 10L,
                    totalBytesKnown = 10L,
                    currentPhase = "MATERIALIZE",
                ),
            ),
        )

        val card = assertOk(
            api.completeAcquisitionMaterialize(
                jobId = jobId,
                files = listOf(
                    AcquisitionMaterializedFile(
                        role = "weights",
                        expectedBlobId = blob.hex,
                        expectedByteLength = 10L,
                        materializeHandle = "q-weights",
                    ),
                ),
            ),
        )
        assertEquals("READY", card.installationState)
        assertTrue(card.authenticityOk == true)
        assertEquals(PlacementClassLabels.PRIVILEGED_TRUSTED, card.placementClass)
        assertTrue(ModelHubAction.DELETE in card.allowedActions)
        assertTrue(ModelHubAction.PIN in card.allowedActions)
        // Import risk flag not present for pinned download path after channel set.
        assertFalse(card.riskFlags.contains("SOURCE_UNVERIFIED"))

        val snap = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertTrue(snap.suggested.isEmpty()) // installed revision filtered out
        assertEquals(1, snap.installed.size)
        assertEquals("READY", snap.installed[0].installationState)
        assertEquals(
            "SUCCEEDED",
            jobManager.query(com.omnillm.core.state.domain.JobId(jobId)).getOrNull()!!.state,
        )
    }

    @Test
    fun import_marksSourceUnverified() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111112"
        val jobId = "22222222-2222-2222-2222-222222222223"
        assertOk(
            api.startImport(
                LocalUiPrincipal.ID,
                StartImportSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    assetId = "asset-1",
                    displayName = "User Import",
                    command = cmd("imp-1"),
                ),
            ),
        )
        val card = assertOk(
            api.getModelCard(LocalUiPrincipal.ID, installationId = installationId),
        )
        assertEquals(AcquisitionChannel.LOCAL_IMPORT, card.acquisitionChannel)
        assertTrue(card.riskFlags.contains("SOURCE_UNVERIFIED"))
    }

    @Test
    fun cancel_stopsJobAndRejectsAcquiringInstallation() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111113"
        val jobId = "22222222-2222-2222-2222-222222222224"
        assertOk(
            api.startDownload(
                LocalUiPrincipal.ID,
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    sourceUrl = "https://example.invalid/m.bin",
                    displayName = "Cancel Me",
                    command = cmd("dl-cancel"),
                ),
            ),
        )
        assertOk(
            api.beginAcquisitionAttempt(
                jobId,
                listOf(AcquisitionDeclaredFile("weights", blob.hex, 1L)),
            ),
        )
        assertEquals("ACQUIRING", modelManager.getInstallation(InstallationId(installationId))!!.state)

        val cancelled = assertOk(
            api.cancelAcquisition(
                LocalUiPrincipal.ID,
                CancelAcquisitionSpec(jobId = jobId, command = cmd("cancel-1")),
            ),
        )
        assertEquals("CANCELLED", cancelled.state)
        assertEquals("REJECTED", modelManager.getInstallation(InstallationId(installationId))!!.state)
    }

    @Test
    fun failAcquisition_marksJobFailed() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111114"
        val jobId = "22222222-2222-2222-2222-222222222225"
        assertOk(
            api.startDownload(
                LocalUiPrincipal.ID,
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    sourceUrl = "https://example.invalid/m.bin",
                    displayName = "Fail Me",
                    command = cmd("dl-fail"),
                ),
            ),
        )
        assertOk(
            api.beginAcquisitionAttempt(
                jobId,
                listOf(AcquisitionDeclaredFile("weights", blob.hex, 1L)),
            ),
        )
        val failed = assertOk(api.failAcquisition(jobId, "network reset"))
        assertEquals("FAILED", failed.state)
        assertEquals("REJECTED", modelManager.getInstallation(InstallationId(installationId))!!.state)
    }

    @Test
    fun delete_waitsForReferencesThenCommits() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111115"
        val jobId = "22222222-2222-2222-2222-222222222226"
        promoteToReady(installationId, jobId)

        // Live lease reference → cannot complete delete.
        refs.installationRefs = LiveReferences(leaseCount = 1)
        val delJobId = "33333333-3333-3333-3333-333333333333"
        assertOk(
            api.startDelete(
                LocalUiPrincipal.ID,
                StartDeleteSpec(
                    jobId = delJobId,
                    installationId = installationId,
                    expectedResourceVersion = 2L, // beginAcquire + promote bumps
                    command = cmd("del-1"),
                ),
            ),
        )
        assertEquals("DRAINING", modelManager.getInstallation(InstallationId(installationId))!!.state)

        val blocked = api.completeDeleteWhenQuiescent(delJobId)
        assertTrue(blocked is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (blocked as OmniResult.Err).error.code)

        // Refs clear → delete commits.
        refs.installationRefs = LiveReferences()
        val done = assertOk(api.completeDeleteWhenQuiescent(delJobId))
        assertEquals("SUCCEEDED", done.state)
        assertNull(modelManager.getInstallation(InstallationId(installationId)))
    }

    @Test
    fun startDownload_claimOrReturn_sameKey() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111116"
        val jobId = "22222222-2222-2222-2222-222222222227"
        val spec = StartDownloadSpec(
            jobId = jobId,
            installationId = installationId,
            modelRevisionId = rev.hex,
            artifactPackageId = pkg.hex,
            sourceUrl = "https://example.invalid/m.bin",
            displayName = "Idem",
            command = cmd("idem-1"),
        )
        val first = assertOk(api.startDownload(LocalUiPrincipal.ID, spec))
        assertTrue(first.createdNew)
        val second = assertOk(api.startDownload(LocalUiPrincipal.ID, spec))
        assertFalse(second.createdNew)
        assertEquals(first.jobId, second.jobId)
    }

    @Test
    fun pin_onlyAffectsEvictionFlag() = runBlocking {
        val installationId = "11111111-1111-1111-1111-111111111117"
        val jobId = "22222222-2222-2222-2222-222222222228"
        promoteToReady(installationId, jobId)

        val pinned = assertOk(
            api.setPinned(
                LocalUiPrincipal.ID,
                SetPinSpec(installationId = installationId, pinned = true, command = cmd("pin-1")),
            ),
        )
        assertTrue(pinned.pinned)
        assertTrue(ModelHubAction.UNPIN in pinned.allowedActions)
        assertEquals("READY", pinned.installationState)
    }

    @Test
    fun viewModel_refreshAndDownload() = runBlocking {
        vm.refresh()
        assertNotNull(vm.state.value.snapshot)
        assertEquals(1, vm.state.value.suggested.size)

        val installationId = "11111111-1111-1111-1111-111111111118"
        val jobId = "22222222-2222-2222-2222-222222222229"
        assertOk(
            vm.download(
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    sourceUrl = "https://example.invalid/m.bin",
                    displayName = "VM Demo",
                    command = cmd("vm-dl"),
                ),
            ),
        )
        assertNotNull(vm.state.value.lastJob)
        assertTrue(vm.state.value.installed.isNotEmpty() || vm.state.value.snapshot!!.installed.isNotEmpty() ||
            modelManager.getInstallation(InstallationId(installationId)) != null)
    }

    @Test
    fun projector_keepsDimensionsSeparate() {
        val entry = catalogEntry
        val card = ModelCardProjector.fromCatalog(entry)
        assertTrue(card.authenticityOk == true) // signed catalog assertion projection
        assertEquals("NOT_CHECKED", card.compatibilityStatus)
        assertNull(card.placementClass)
        assertFalse(card.performanceRecorded)
    }

    // ------------------------------------------------------------------

    private suspend fun promoteToReady(installationId: String, jobId: String) {
        assertOk(
            api.startDownload(
                LocalUiPrincipal.ID,
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    sourceUrl = "https://example.invalid/m.bin",
                    expectedBytes = 10L,
                    displayName = "Ready Model",
                    command = cmd("dl-$jobId"),
                ),
            ),
        )
        assertOk(
            api.beginAcquisitionAttempt(
                jobId,
                listOf(AcquisitionDeclaredFile("weights", blob.hex, 10L)),
            ),
        )
        assertOk(
            api.completeAcquisitionMaterialize(
                jobId,
                listOf(
                    AcquisitionMaterializedFile(
                        role = "weights",
                        expectedBlobId = blob.hex,
                        expectedByteLength = 10L,
                        materializeHandle = "q-w",
                    ),
                ),
            ),
        )
    }

    private fun cmd(key: String) = ModelHubCommandIdentity(
        commandId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        idempotencyKey = key,
        canonicalInputDigest = digest,
    )

    private fun <T> assertOk(result: OmniResult<T>): T {
        if (result is OmniResult.Err) {
            throw AssertionError("expected Ok but got ${result.error.code}: ${result.error.message}")
        }
        return (result as OmniResult.Ok).value
    }

    // --- fakes ---

    private class MemInstallationRepo : InstallationRepository {
        private val map = linkedMapOf<String, InstallationSnapshot>()
        override suspend fun get(installationId: InstallationId) = map[installationId.value]
        override suspend fun findByRevision(modelRevisionId: ModelRevisionId) =
            map.values.filter { it.modelRevisionId.hex == modelRevisionId.hex }
        override suspend fun listAll() = map.values.toList()
        override suspend fun save(snapshot: InstallationSnapshot): OmniResult<Unit> {
            map[snapshot.installationId.value] = snapshot
            return OmniResult.ok(Unit)
        }
        override suspend fun delete(installationId: InstallationId): OmniResult<Unit> {
            map.remove(installationId.value)
            return OmniResult.ok(Unit)
        }
    }

    private class MemLoadedModelRepo : LoadedModelRepository {
        private val map = linkedMapOf<String, LoadedModelSnapshot>()
        override suspend fun get(loadedModelId: LoadedModelId) = map[loadedModelId.value]
        override suspend fun findByInstallation(installationId: InstallationId) =
            map.values.filter { it.installationId.value == installationId.value }
        override suspend fun save(snapshot: LoadedModelSnapshot): OmniResult<Unit> {
            map[snapshot.loadedModelId.value] = snapshot
            return OmniResult.ok(Unit)
        }
        override suspend fun delete(loadedModelId: LoadedModelId): OmniResult<Unit> {
            map.remove(loadedModelId.value)
            return OmniResult.ok(Unit)
        }
    }

    private class MemLeaseRepo : RevisionLeaseRepository {
        private val map = linkedMapOf<String, RevisionLeaseSnapshot>()
        override suspend fun get(leaseId: com.omnillm.core.state.domain.RevisionLeaseId) =
            map[leaseId.value]
        override suspend fun findActiveByRevision(modelRevisionId: ModelRevisionId) =
            map.values.filter { it.modelRevisionId.hex == modelRevisionId.hex && it.blocksDelete() }
        override suspend fun save(snapshot: RevisionLeaseSnapshot): OmniResult<Unit> {
            map[snapshot.leaseId.value] = snapshot
            return OmniResult.ok(Unit)
        }
        override suspend fun delete(leaseId: com.omnillm.core.state.domain.RevisionLeaseId): OmniResult<Unit> {
            map.remove(leaseId.value)
            return OmniResult.ok(Unit)
        }
    }

    private class MutableRefs : ReferenceSnapshotPort {
        var installationRefs: LiveReferences = LiveReferences()
        override suspend fun installationReferences(installationId: InstallationId) = installationRefs
        override suspend fun loadedModelReferences(loadedModelId: LoadedModelId) = LiveReferences()
        override suspend fun leaseReferences(leaseId: com.omnillm.core.state.domain.RevisionLeaseId) =
            LiveReferences()
    }

    private class FixedTrust : TrustEvaluationPort {
        override suspend fun evaluate(
            installationId: InstallationId,
            modelRevisionId: ModelRevisionId,
            currentTrustEpoch: Long,
        ): OmniResult<EvaluationDimensions> =
            OmniResult.ok(
                EvaluationDimensions(
                    authenticityOk = true,
                    licenseOk = true,
                    compatibilityOk = true,
                    performanceRecorded = false,
                    placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                    trustEpoch = currentTrustEpoch + 1,
                ),
            )
    }

    private class MemModelStore : ModelStorePort {
        private val q = linkedMapOf<String, QuarantineSnapshot>()
        private fun k(key: QuarantineKey) = "${key.jobId}/${key.attemptId}"
        override suspend fun openQuarantine(
            key: QuarantineKey,
            installationId: IdentityInstallationId,
            modelRevisionId: ModelRevisionId,
            artifactPackageId: ArtifactPackageId,
            declared: List<DeclaredArtifactFile>,
            deadlineMonotonic: Long,
        ): OmniResult<QuarantineSnapshot> {
            val snap = QuarantineSnapshot(
                key, installationId, modelRevisionId, artifactPackageId, emptyList(), deadlineMonotonic,
            )
            q[k(key)] = snap
            return OmniResult.ok(snap)
        }
        override suspend fun recordMaterialized(key: QuarantineKey, file: QuarantineFileRecord): OmniResult<QuarantineSnapshot> {
            val cur = q[k(key)]!!
            val next = cur.copy(files = cur.files + file)
            q[k(key)] = next
            return OmniResult.ok(next)
        }
        override suspend fun getQuarantine(key: QuarantineKey): OmniResult<QuarantineSnapshot> =
            OmniResult.ok(q[k(key)]!!)
        override suspend fun verifyQuarantineIdentity(key: QuarantineKey): OmniResult<ContentIdentityCheck> =
            OmniResult.ok(ContentIdentityCheck(ok = true))
        override suspend fun cleanupQuarantine(key: QuarantineKey): OmniResult<Unit> {
            q.remove(k(key))
            return OmniResult.ok(Unit)
        }
        override suspend fun atomicPromote(
            key: QuarantineKey,
            installationId: IdentityInstallationId,
            modelRevisionId: ModelRevisionId,
            artifactPackageId: ArtifactPackageId,
        ): OmniResult<PromoteResult> {
            q.remove(k(key))
            return OmniResult.ok(
                PromoteResult(installationId, "ready/${installationId.value}", listOf(BlobId.parse("a".repeat(64)))),
            )
        }
        override suspend fun openReadOnly(installationId: IdentityInstallationId, storageRootKey: String) =
            OmniResult.ok(emptyList<ReadOnlyContentFd>())
        override suspend fun verifyOpenFds(fds: List<ReadOnlyContentFd>) =
            OmniResult.ok(ContentIdentityCheck(ok = true))
        override suspend fun closeFds(fds: List<ReadOnlyContentFd>) = OmniResult.ok(Unit)
    }

    private class NoopEngine : EngineLoadPort {
        override val engineBuildId: EngineBuildId = EngineBuildId.parse("engine-test")
        override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
            val d = Sha256Digest.parse("b".repeat(64))
            return OmniResult.ok(
                LoadPlan(
                    planId = PlanId.parse("plan-1"),
                    requestId = input.requestId,
                    principalId = input.principalId,
                    engineBuildId = engineBuildId,
                    loadKey = input.loadKey,
                    installationId = input.installationId,
                    modelRevisionId = input.modelRevisionId,
                    resourceEnvelope = ResourceEnvelope(
                        steady = ResourceVector(cpuAnonBytes = 1),
                        peak = ResourceVector(cpuAnonBytes = 2),
                    ),
                    proposedPlacementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                    phaseCapabilityDigest = d,
                    canonicalInputDigest = d,
                    expiryMonotonic = 1L,
                    runtimeEpoch = input.runtimeEpoch,
                ),
            )
        }
        override suspend fun commitLoad(
            plan: LoadPlan,
            reservation: Reservation,
            commit: CommitContext,
        ): OmniResult<LoadedModelHandle> =
            OmniResult.ok(
                LoadedModelHandle(
                    loadedModelId = LoadedModelId("lm-1"),
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("alloc-1"),
                    placementClass = plan.proposedPlacementClass,
                    runtimeEpoch = plan.runtimeEpoch,
                ),
            )
        override suspend fun queryCommit(commitId: com.omnillm.core.contracts.CommitId): OmniResult<CommitQueryState> =
            OmniResult.ok(CommitQueryState(commitId, "COMMITTED"))
    }

    private class OkReverify : PrivilegedLoadReverifyPort {
        override suspend fun reverify(request: PrivilegedReverifyRequest): OmniResult<PrivilegedLoadTicket> =
            OmniResult.ok(
                PrivilegedLoadTicket(
                    ticketId = "t1",
                    installationId = request.installationId,
                    modelRevisionId = request.modelRevisionId,
                    placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                    contentIdentityOk = true,
                    signatureChainOk = true,
                    revocationOk = true,
                    installationStateOk = true,
                    epochsOk = true,
                    issuedMonotonic = 1L,
                    expiryMonotonic = 100L,
                ),
            )
    }
}
