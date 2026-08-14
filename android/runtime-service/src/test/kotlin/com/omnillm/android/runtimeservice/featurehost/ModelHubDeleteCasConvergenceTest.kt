package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.EngineLoadPort
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.engines.api.LoadedModelHandle
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.modelhub.api.AcquisitionChannel
import com.omnillm.features.modelhub.api.AcquisitionDeclaredFile
import com.omnillm.features.modelhub.api.AcquisitionMaterializedFile
import com.omnillm.features.modelhub.api.CatalogModelEntry
import com.omnillm.features.modelhub.api.ModelHubCommandIdentity
import com.omnillm.features.modelhub.api.StartDeleteSpec
import com.omnillm.features.modelhub.api.StartDownloadSpec
import com.omnillm.features.modelhub.api.StartLoadSpec
import com.omnillm.features.modelhub.ports.FixedSuggestedCatalogPort
import com.omnillm.features.modelhub.ports.InMemoryLicenseAcceptanceLedger
import com.omnillm.features.modelhub.ports.ModelLoadRuntimePort
import com.omnillm.features.modelhub.usecase.ModelHubService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.modelmanager.memory.InMemoryModelStorePort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.PrivilegedReverifyRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D7 (COR-18 residual): the delete CAS must converge after a load and after a
 * process restart.
 *
 * Pre-fix the delete CAS compared against an IN-MEMORY counter
 * ([com.omnillm.features.modelhub.ports.InMemoryInstallationResourceVersionPort])
 * while the snapshot/card version came from the DURABLE installation row
 * (SqlInstallationRepository advances it +1 per save). The two counters
 * drifted — a load is 2 durable saves + 1 port bump — so a delete using a
 * fresh snapshot's version failed with STATE_CONFLICT, and after a restart the
 * in-memory authority was empty and the "re-fetch snapshot" guidance could
 * never converge.
 *
 * These tests use the REAL production composition: SQLite-backed
 * [ControlPlaneDatabase] + SqlInstallationRepository (+1 per save) + the
 * durable resource-version port. Restart is simulated by closing the DB and
 * reopening the same file with fresh ModelManager / ModelHubService / port
 * instances.
 */
class ModelHubDeleteCasConvergenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = { "2026-08-15T00:00:00Z" }

    private val rev = ModelRevisionId.parse("c".repeat(64))
    private val pkg = ArtifactPackageId.parse("d".repeat(64))
    private val blob = BlobId.parse("a".repeat(64))
    private val digest = "e".repeat(64)

    private val catalogEntry = CatalogModelEntry(
        modelRevisionId = rev.hex,
        artifactPackageId = pkg.hex,
        displayName = "D7 Fixture",
        acquisitionChannel = AcquisitionChannel.SIGNED_CATALOG,
        byteLength = 1024L,
        // No license digest → no acceptance gate on load.
    )

    private class DurableStack(
        val db: ControlPlaneDatabase,
        val service: ModelHubService,
        val modelManager: ModelManager,
        val jobManager: JobManager,
    )

    private fun stack(db: ControlPlaneDatabase): DurableStack {
        val modelManager = ModelManagerModule.createDurableControlPlane(
            installationPorts = db.installations,
            leasePorts = db.revisionLeases,
            modelStore = InMemoryModelStorePort(),
            engine = NoopEngine(),
            privilegedReverify = OkReverify(),
        )
        val jobManager = JobManager()
        val service = ModelHubService(
            jobManager = jobManager,
            modelManager = modelManager,
            catalog = FixedSuggestedCatalogPort(listOf(catalogEntry)),
            loadRuntime = object : ModelLoadRuntimePort {
                override fun primaryEngineBuildId(): String = "engine-d7"
                override fun deviceExecutionFingerprint(): DeviceExecutionFingerprint =
                    DeviceExecutionFingerprint.parse("device-fp-d7")
            },
            licenseAcceptance = InMemoryLicenseAcceptanceLedger(),
            resourceVersions = SqlDelightInstallationResourceVersionPort(db.installations),
        )
        return DurableStack(db, service, modelManager, jobManager)
    }

    private suspend fun <T> withDb(file: java.io.File, block: suspend (DurableStack) -> T): T {
        val db = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        return try {
            block(stack(db))
        } finally {
            db.close()
        }
    }

    @Test
    fun deleteAfterLoad_versionConverges() = runBlocking {
        withDb(tmp.newFile("d7-load.db")) { s ->
            val installationId = "11111111-2222-4333-8444-5555555555a1"
            promoteToReady(s.service, installationId, "job-d7-load")

            val loaded = assertOk(
                s.service.startLoad(
                    LocalUiPrincipal.ID,
                    StartLoadSpec(installationId = installationId, command = cmd("d7-load")),
                ),
            )
            assertEquals("LOADED", loaded.state)

            val card = assertOk(s.service.getSnapshot(LocalUiPrincipal.ID)).installed.single()
            assertTrue(
                "card must expose the durable resourceVersion (delete CAS target)",
                card.resourceVersion != null,
            )
            val expected = card.resourceVersion!!

            val delete = s.service.startDelete(
                LocalUiPrincipal.ID,
                StartDeleteSpec(
                    jobId = "33333333-3333-4333-8333-3333333333a1",
                    installationId = installationId,
                    expectedResourceVersion = expected,
                    command = cmd("d7-del"),
                ),
            )
            assertTrue("delete with the snapshot's version must succeed (D7): $delete", delete is OmniResult.Ok)
            assertEquals("DRAINING", s.modelManager.getInstallation(InstallationId(installationId))!!.state)
        }
    }

    @Test
    fun deleteAfterRestart_versionPersists() = runBlocking {
        val file = tmp.newFile("d7-restart.db")
        val installationId = "11111111-2222-4333-8444-5555555555b2"

        val versionBefore = withDb(file) { s ->
            promoteToReady(s.service, installationId, "job-d7-restart")
            val card = assertOk(s.service.getSnapshot(LocalUiPrincipal.ID)).installed.single()
            assertTrue("card must expose the durable resourceVersion before restart", card.resourceVersion != null)
            card.resourceVersion!!
        }

        // Restart: fresh ModelManager / ModelHubService / port over the SAME
        // database file — the in-memory counter would be empty here (COR-18).
        withDb(file) { s ->
            val card = assertOk(s.service.getSnapshot(LocalUiPrincipal.ID)).installed.single()
            assertTrue("re-fetched card version must be durable after restart", card.resourceVersion != null)
            val refetched = card.resourceVersion!!
            assertEquals("resourceVersion must persist across restart", versionBefore, refetched)

            val delete = s.service.startDelete(
                LocalUiPrincipal.ID,
                StartDeleteSpec(
                    jobId = "33333333-3333-4333-8333-3333333333b2",
                    installationId = installationId,
                    expectedResourceVersion = refetched,
                    command = cmd("d7-del-restart"),
                ),
            )
            assertTrue(
                "delete after restart must succeed with the re-fetched version (D7): $delete",
                delete is OmniResult.Ok,
            )
        }
    }

    private suspend fun promoteToReady(api: ModelHubService, installationId: String, jobId: String) {
        assertOk(
            api.startDownload(
                LocalUiPrincipal.ID,
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = rev.hex,
                    artifactPackageId = pkg.hex,
                    sourceUrl = "https://example.invalid/d7.bin",
                    expectedSha256 = digest,
                    expectedBytes = 10L,
                    displayName = "D7 Fixture",
                    command = cmd("dl-$jobId"),
                ),
            ),
        )
        assertOk(
            api.beginAcquisitionAttempt(
                jobId,
                listOf(AcquisitionDeclaredFile(role = "weights", blobId = blob.hex, byteLength = 10L)),
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

    /** Minimal happy-path engine: plans + commits successfully (load reaches LOADED). */
    private class NoopEngine : EngineLoadPort {
        override val engineBuildId: EngineBuildId = EngineBuildId.parse("engine-d7")
        override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
            val d = Sha256Digest.parse("b".repeat(64))
            return OmniResult.ok(
                LoadPlan(
                    planId = PlanId.parse("plan-d7"),
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
                    loadedModelId = LoadedModelId("lm-d7"),
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("alloc-d7"),
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
                    ticketId = "t-d7",
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
