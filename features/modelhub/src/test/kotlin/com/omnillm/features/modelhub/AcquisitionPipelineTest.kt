package com.omnillm.features.modelhub

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.JobId
import com.omnillm.data.modelstore.FilesystemModelStorePort
import com.omnillm.data.modelstore.MaterializeBounds
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.modelhub.acquisition.AcquisitionPipeline
import com.omnillm.features.modelhub.acquisition.FixtureArtifactSource
import com.omnillm.features.modelhub.acquisition.PinnedDownloadResolver
import com.omnillm.features.modelhub.acquisition.StreamArtifactSource
import com.omnillm.features.modelhub.api.AcquisitionChannel
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import com.omnillm.features.modelhub.catalog.OfflineFixtureCatalog
import com.omnillm.features.modelhub.ports.InMemoryAcquisitionLinkStore
import com.omnillm.features.modelhub.usecase.ModelHubService
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.memory.DefaultTrustEvaluationPort
import com.omnillm.runtime.modelmanager.ports.TrustEvaluationPort
import com.omnillm.runtime.policy.download.DownloadUrlPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Software E2E: catalog pin + HTTPS policy + SAF import + quarantine + digest
 * verify + atomic promote + cancel job (FEAT-MODELHUB, SEC-INPUT, SEC-SUPPLY).
 *
 * No physical device / network required — offline fixture artifact only.
 */
class AcquisitionPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val catalog = OfflineFixtureCatalog.DEFAULT
    private val entry = catalog.pinnedEntry()

    @Test
    fun fixtureCatalog_hasStableIdentityAndPinUrl() {
        assertEquals(FixtureArtifact.revisionIdHex(), entry.modelRevisionId)
        assertEquals(FixtureArtifact.packageIdHex(), entry.artifactPackageId)
        assertEquals(AcquisitionChannel.PINNED_DOWNLOAD, entry.acquisitionChannel)
        assertEquals(FixtureArtifact.BYTE_LENGTH, entry.byteLength)
        assertNotNull(catalog.findByPinnedUrl(FixtureArtifact.PINNED_HTTPS_URL))
        assertEquals(2, catalog.listSuggested().size) // pin + SAF hint
    }

    @Test
    fun httpsPolicy_rejectsHttpAndUserinfo() {
        assertTrue(
            DownloadUrlPolicy.admitUrl("http://cdn.example.com/m.bin") is
                DownloadUrlPolicy.Outcome.Rejected,
        )
        assertTrue(
            DownloadUrlPolicy.admitUrl("https://user:pass@cdn.example.com/m.bin") is
                DownloadUrlPolicy.Outcome.Rejected,
        )
        assertTrue(
            PinnedDownloadResolver.resolve("http://evil.example/m.bin") is
                PinnedDownloadResolver.Outcome.Rejected,
        )
        val ok = PinnedDownloadResolver.resolve(FixtureArtifact.PINNED_HTTPS_URL)
        assertTrue(ok is PinnedDownloadResolver.Outcome.Ready)
    }

    @Test
    fun pinnedDownload_fixture_reachesReady_withRealFsDigest() = runBlocking {
        val (api, pipeline, modelManager) = harness()
        val installationId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()

        val result = assertOk(
            pipeline.executePinnedDownload(
                installationId = installationId,
                jobId = jobId,
                modelRevisionId = entry.modelRevisionId,
                artifactPackageId = entry.artifactPackageId,
                sourceUrl = FixtureArtifact.PINNED_HTTPS_URL,
                expectedSha256 = FixtureArtifact.blobIdHex(),
                expectedBytes = FixtureArtifact.BYTE_LENGTH,
                displayName = entry.displayName,
                command = AcquisitionPipeline.command("pin-happy"),
            ),
        )

        assertEquals("READY", result.installationState)
        assertEquals("READY", result.card!!.installationState)
        assertTrue(result.phases.contains("HTTPS_ADMITTED"))
        assertTrue(result.phases.contains("QUARANTINED"))
        assertTrue(result.phases.contains("PROMOTED_READY"))
        assertEquals("READY", modelManager.getInstallation(InstallationId(installationId))!!.state)
        val jobs = assertOk(api.listAcquisitionJobs(LocalUiPrincipal.ID))
        val jobView = jobs.firstOrNull { it.jobId == jobId }
        assertNotNull(jobView)
        assertEquals("SUCCEEDED", jobView!!.state)
        // Pin channel after READY still not SOURCE_UNVERIFIED
        assertFalse(result.card!!.riskFlags.contains("SOURCE_UNVERIFIED"))
    }

    @Test
    fun safImport_marksSourceUnverified_andReachesReady() = runBlocking {
        val (api, pipeline, modelManager) = harness()
        val installationId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        val source = StreamArtifactSource(
            mapOf(FixtureArtifact.ROLE_WEIGHTS to { ByteArrayInputStream(FixtureArtifact.PAYLOAD_BYTES.copyOf()) }),
        )

        val result = assertOk(
            pipeline.executeSafImport(
                installationId = installationId,
                jobId = jobId,
                modelRevisionId = entry.modelRevisionId,
                artifactPackageId = entry.artifactPackageId,
                assetId = "asset-saf-fixture-1",
                expectedSha256 = FixtureArtifact.blobIdHex(),
                expectedBytes = FixtureArtifact.BYTE_LENGTH,
                displayName = "User SAF Fixture",
                command = AcquisitionPipeline.command("saf-happy"),
                source = source,
            ),
        )

        assertEquals("READY", result.installationState)
        assertEquals("READY", modelManager.getInstallation(InstallationId(installationId))!!.state)
        val card = assertOk(api.getModelCard(LocalUiPrincipal.ID, installationId = installationId))
        assertEquals(AcquisitionChannel.LOCAL_IMPORT, card.acquisitionChannel)
        assertTrue(card.riskFlags.contains("SOURCE_UNVERIFIED"))
        assertTrue(result.phases.contains("SAF_IMPORT"))
    }

    @Test
    fun digestMismatch_failsClosed_noReady() = runBlocking {
        val (_, pipeline, modelManager) = harness()
        val installationId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        val wrongDigest = "0".repeat(64)

        val result = pipeline.executePinnedDownload(
            installationId = installationId,
            jobId = jobId,
            modelRevisionId = entry.modelRevisionId,
            artifactPackageId = entry.artifactPackageId,
            sourceUrl = FixtureArtifact.PINNED_HTTPS_URL,
            expectedSha256 = wrongDigest,
            expectedBytes = FixtureArtifact.BYTE_LENGTH,
            displayName = entry.displayName,
            command = AcquisitionPipeline.command("digest-fail"),
            sourceOverride = FixtureArtifactSource(),
        )
        assertTrue(result is OmniResult.Err)
        val snap = modelManager.getInstallation(InstallationId(installationId))
        assertTrue(snap == null || snap.state != "READY")
    }

    @Test
    fun cancelJob_whileAcquiring_rejectsInstallation() = runBlocking {
        val (api, pipeline, modelManager) = harness()
        val installationId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        val cancel = AtomicBoolean(false)

        // Start download + begin acquire only, then cancel via pipeline API.
        assertOk(
            api.startDownload(
                LocalUiPrincipal.ID,
                com.omnillm.features.modelhub.api.StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = entry.modelRevisionId,
                    artifactPackageId = entry.artifactPackageId,
                    sourceUrl = FixtureArtifact.PINNED_HTTPS_URL,
                    expectedSha256 = FixtureArtifact.blobIdHex(),
                    expectedBytes = FixtureArtifact.BYTE_LENGTH,
                    displayName = entry.displayName,
                    command = AcquisitionPipeline.command("cancel-setup"),
                ),
            ),
        )
        assertOk(
            api.beginAcquisitionAttempt(
                jobId,
                listOf(
                    com.omnillm.features.modelhub.api.AcquisitionDeclaredFile(
                        role = FixtureArtifact.ROLE_WEIGHTS,
                        blobId = FixtureArtifact.blobIdHex(),
                        byteLength = FixtureArtifact.BYTE_LENGTH,
                    ),
                ),
            ),
        )
        assertEquals("ACQUIRING", modelManager.getInstallation(InstallationId(installationId))!!.state)

        val cancelled = assertOk(
            pipeline.cancelJob(jobId, AcquisitionPipeline.command("cancel-job")),
        )
        assertEquals("CANCELLED", cancelled.state)
        assertEquals("REJECTED", modelManager.getInstallation(InstallationId(installationId))!!.state)

        // Cancel flag set before start fails closed (no READY install created).
        cancel.set(true)
        val midInstall = UUID.randomUUID().toString()
        val mid = pipeline.executePinnedDownload(
            installationId = midInstall,
            jobId = UUID.randomUUID().toString(),
            modelRevisionId = entry.modelRevisionId,
            artifactPackageId = entry.artifactPackageId,
            sourceUrl = FixtureArtifact.PINNED_HTTPS_URL,
            expectedSha256 = FixtureArtifact.blobIdHex(),
            expectedBytes = FixtureArtifact.BYTE_LENGTH,
            displayName = entry.displayName,
            command = AcquisitionPipeline.command("cancel-mid"),
            cancel = cancel,
        )
        assertTrue(
            "pre-start cancel must fail closed: $mid",
            mid is OmniResult.Err && (mid as OmniResult.Err).error.code == OmniErrorCode.CANCELLED,
        )
        val midSnap = modelManager.getInstallation(InstallationId(midInstall))
        assertTrue(midSnap == null || midSnap.state != "READY")
    }

    @Test
    fun installStateMachine_phasesOrdered_discoverToReady() = runBlocking {
        val (_, pipeline, _) = harness()
        val result = assertOk(
            pipeline.executePinnedDownload(
                installationId = UUID.randomUUID().toString(),
                jobId = UUID.randomUUID().toString(),
                modelRevisionId = entry.modelRevisionId,
                artifactPackageId = entry.artifactPackageId,
                sourceUrl = FixtureArtifact.PINNED_HTTPS_URL,
                expectedSha256 = FixtureArtifact.blobIdHex(),
                expectedBytes = FixtureArtifact.BYTE_LENGTH,
                displayName = entry.displayName,
                command = AcquisitionPipeline.command("phases"),
            ),
        )
        val phases = result.phases
        val https = phases.indexOf("HTTPS_ADMITTED")
        val acq = phases.indexOf("ACQUIRING")
        val q = phases.indexOf("QUARANTINED")
        val ready = phases.indexOf("PROMOTED_READY")
        assertTrue(https >= 0 && acq > https && q > acq && ready > q)
    }

    // ------------------------------------------------------------------

    private fun harness(): Triple<ModelHubService, AcquisitionPipeline, com.omnillm.runtime.modelmanager.ModelManager> {
        val filesRoot = tmp.newFolder("model-store").toPath()
        val modelStore = FilesystemModelStorePort.create(
            filesRoot = filesRoot,
            bounds = MaterializeBounds.DEFAULT,
            monotonicNowMs = { 1_000L },
        )
        val trust: TrustEvaluationPort = object : TrustEvaluationPort {
            override suspend fun evaluate(
                installationId: InstallationId,
                modelRevisionId: com.omnillm.core.canonical.generated.ModelRevisionId,
                currentTrustEpoch: Long,
            ): OmniResult<EvaluationDimensions> =
                OmniResult.ok(
                    EvaluationDimensions(
                        authenticityOk = true,
                        licenseOk = true,
                        compatibilityOk = false, // never elevates authenticity (ADR-009)
                        performanceRecorded = false,
                        placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                        trustEpoch = currentTrustEpoch + 1,
                    ),
                )
        }
        val modelManager = ModelManagerModule.createInMemoryControlPlane(
            modelStore = modelStore,
            trustEvaluation = trust,
        )
        val jobManager = JobManager()
        val api = ModelHubService(
            jobManager = jobManager,
            modelManager = modelManager,
            catalog = catalog,
            links = InMemoryAcquisitionLinkStore(),
        )
        val pipeline = ModelhubModule.createAcquisitionPipeline(api, modelStore)
        return Triple(api, pipeline, modelManager)
    }

    private fun <T> assertOk(result: OmniResult<T>): T {
        if (result is OmniResult.Err) {
            throw AssertionError("expected Ok: ${result.error.code} ${result.error.message} ${result.error.details}")
        }
        return (result as OmniResult.Ok).value
    }
}
