package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId as ContractRevisionLeaseId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.identity.InstallationId as IdentityInstallationId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.core.state.domain.RequestId as DomainRequestId
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.QuarantineFileRecord
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.ports.LiveReferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Model Manager integration tests: Installation vs LoadedModel separation,
 * quarantine → verify → atomic promote, RevisionLease, privileged re-verify.
 */
class ModelManagerTest {

    private lateinit var installations: InMemoryInstallationRepository
    private lateinit var loadedModels: InMemoryLoadedModelRepository
    private lateinit var leases: InMemoryRevisionLeaseRepository
    private lateinit var modelStore: InMemoryModelStore
    private lateinit var trust: FixedTrustEvaluationPort
    private lateinit var refs: MutableReferenceSnapshotPort
    private lateinit var engine: FakeEngineLoadPort
    private lateinit var reverify: FakePrivilegedReverifyPort
    private lateinit var manager: ModelManager

    private val installationId = InstallationId("11111111-1111-1111-1111-111111111111")
    private val revisionId = TestDigests.rev()
    private val packageId = TestDigests.pkg()

    @Before
    fun setUp() {
        installations = InMemoryInstallationRepository()
        loadedModels = InMemoryLoadedModelRepository()
        leases = InMemoryRevisionLeaseRepository()
        modelStore = InMemoryModelStore()
        trust = FixedTrustEvaluationPort()
        refs = MutableReferenceSnapshotPort()
        engine = FakeEngineLoadPort()
        reverify = FakePrivilegedReverifyPort()
        manager = ModelManager.create(
            installationRepository = installations,
            loadedModelRepository = loadedModels,
            revisionLeaseRepository = leases,
            modelStore = modelStore,
            trustEvaluation = trust,
            references = refs,
            engine = engine,
            privilegedReverify = reverify,
        )
    }

    @Test
    fun installation_quarantineVerifyAtomicPromote_reachesReady() = runBlocking {
        assertOk(manager.discoverInstallation(installationId, revisionId, packageId))

        val qKey = QuarantineKey(jobId = "job-1", attemptId = "a1")
        val declared = listOf(
            DeclaredArtifactFile(
                role = "weights",
                blobId = TestDigests.blob(),
                byteLength = 10L,
            ),
        )
        assertOk(
            manager.beginAcquire(
                installationId = installationId,
                quarantineKey = qKey,
                declared = declared,
                deadlineMonotonic = 1000L,
            ),
        )
        assertEquals("ACQUIRING", manager.getInstallation(installationId)!!.state)

        assertOk(
            manager.materializeComplete(
                installationId,
                listOf(
                    QuarantineFileRecord(
                        role = "weights",
                        expectedBlobId = TestDigests.blob(),
                        expectedByteLength = 10L,
                        materializeHandle = "q-weights",
                    ),
                ),
            ),
        )
        assertEquals("QUARANTINED", manager.getInstallation(installationId)!!.state)

        assertOk(manager.beginVerify(installationId))
        assertEquals("VERIFYING", manager.getInstallation(installationId)!!.state)

        assertOk(manager.completeIdentityVerify(installationId))
        val afterVerify = manager.getInstallation(installationId)!!
        assertEquals("COMPATIBILITY_CHECK", afterVerify.state)
        assertNotNull(afterVerify.evaluation)
        assertTrue(afterVerify.evaluation!!.authenticityOk)
        // Compatibility evidence present but must not be required for authenticity.
        assertTrue(afterVerify.evaluation!!.compatibilityOk)

        assertOk(manager.promoteToReady(installationId))
        val ready = manager.getInstallation(installationId)!!
        assertEquals("READY", ready.state)
        assertEquals("ready/${installationId.value}", ready.storageRootKey)
        assertEquals(null, ready.quarantineKey)
    }

    @Test
    fun installation_ready_isNotLoadedModel() = runBlocking {
        promoteToReadyHappyPath()
        val install = manager.getInstallation(installationId)!!
        assertTrue(install.isReady())
        assertFalse(install.state == "LOADED") // LOADED is LOADED_MODEL state only
        assertTrue(loadedModels.findByInstallation(installationId).isEmpty())
    }

    @Test
    fun identityFailure_rejectsWithoutPromote() = runBlocking {
        assertOk(manager.discoverInstallation(installationId, revisionId, packageId))
        val qKey = QuarantineKey("job-1", "a1")
        assertOk(
            manager.beginAcquire(
                installationId,
                qKey,
                listOf(DeclaredArtifactFile("weights", TestDigests.blob(), 1L)),
                1000L,
            ),
        )
        assertOk(
            manager.materializeComplete(
                installationId,
                listOf(
                    QuarantineFileRecord("weights", TestDigests.blob(), 1L, materializeHandle = "h"),
                ),
            ),
        )
        assertOk(manager.beginVerify(installationId))
        modelStore.identityOk = false
        assertOk(manager.completeIdentityVerify(installationId))
        assertEquals("REJECTED", manager.getInstallation(installationId)!!.state)
    }

    @Test
    fun revisionLease_blocksDeleteSemantics_activeThenRelease() = runBlocking {
        val leaseId = RevisionLeaseId("lease-1")
        val requestId = DomainRequestId("req-1")
        assertOk(
            manager.grantRevisionLease(
                leaseId = leaseId,
                requestId = requestId,
                modelRevisionId = revisionId,
                principalId = "principal-1",
                runtimeEpoch = 1L,
                installationId = installationId,
            ),
        )
        assertTrue(manager.isRevisionPinnedByLease(revisionId))
        assertEquals("ACTIVE", manager.getRevisionLease(leaseId)!!.state)

        assertOk(manager.beginLeaseRelease(leaseId))
        assertEquals("DRAINING", manager.getRevisionLease(leaseId)!!.state)
        assertTrue(manager.isRevisionPinnedByLease(revisionId))

        refs.leaseRefs = LiveReferences() // zero
        assertOk(manager.completeLeaseRelease(leaseId))
        assertEquals("RELEASED", manager.getRevisionLease(leaseId)!!.state)
        assertFalse(manager.isRevisionPinnedByLease(revisionId))
    }

    @Test
    fun privilegedLoad_reverifyRequired_beforeCommitLoad() = runBlocking {
        promoteToReadyHappyPath()

        val loadKey = LoadKey(
            modelRevisionId = revisionId,
            engineBuildId = EngineBuildId.parse("engine-build-test-1"),
            backend = "cpu",
            deviceExecutionFingerprint = TestDigests.device(),
            templateEpoch = 0L,
            tokenizerEpoch = 0L,
            loadConfigurationDigest = TestDigests.sha(),
        )
        val loadInput = LoadInput(
            requestId = RequestId.parse("00000000-0000-0000-0000-000000000001"),
            principalId = PrincipalId.parse("principal-1"),
            installationId = IdentityInstallationId.ofValidated(installationId.value),
            modelRevisionId = revisionId,
            loadKey = loadKey,
            device = DeviceDescriptor(deviceExecutionFingerprint = TestDigests.device()),
            storageRootKey = manager.getInstallation(installationId)!!.storageRootKey!!,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            templateEpoch = 0L,
            tokenizerEpoch = 0L,
        )
        val plan = assertOk(manager.planLoad(loadInput))

        val lmId = LoadedModelId("lm-1")
        assertOk(
            manager.admitLoad(
                loadedModelId = lmId,
                installationId = installationId,
                loadKey = loadKey,
                plan = plan,
                loadEnvelopeMatched = true,
            ),
        )
        assertEquals("RESERVED", manager.getLoadedModel(lmId)!!.state)

        val ticket = assertOk(
            manager.preparePrivilegedTicket(
                installationId = installationId,
                engineBuildId = plan.engineBuildId.value,
                revocationEpoch = 0L,
            ),
        )
        assertTrue(ticket.allOk)
        assertNotNull(reverify.lastRequest)

        val reservation = Reservation(
            reservationId = ReservationId.parse("res-1"),
            principalId = "principal-1",
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            nonce = "n1",
            deadlineMonotonic = 9999L,
            envelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 1024),
                peak = ResourceVector(cpuAnonBytes = 2048),
            ),
        )
        val commit = CommitContext(
            commitId = CommitId.parse("00000000-0000-0000-0000-0000000000aa"),
            requestId = RequestId.parse("00000000-0000-0000-0000-000000000001"),
            principalId = PrincipalId.parse("principal-1"),
            reservationId = ReservationId.parse("res-1"),
            revisionLeaseId = ContractRevisionLeaseId.parse("lease-wire-1"),
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            oneShotNonce = "nonce-1",
            privilegedLoadTicketId = ticket.ticketId,
        )

        val loaded = assertOk(
            manager.commitLoad(
                loadedModelId = lmId,
                plan = plan,
                reservation = reservation,
                commit = commit,
                placementQualified = true,
                loadEnvelopeMatched = true,
                revocationEpoch = 0L,
            ),
        )
        assertEquals("LOADED", loaded.state)
        assertNotNull(loaded.allocationHandleId)
        // Installation remains READY — separate aggregate.
        assertEquals("READY", manager.getInstallation(installationId)!!.state)
    }

    @Test
    fun privilegedLoad_revocationFailsClosed() = runBlocking {
        promoteToReadyHappyPath()
        reverify.revocationOk = false
        reverify.allOk = false

        val result = manager.preparePrivilegedTicket(
            installationId = installationId,
            engineBuildId = "engine-build-test-1",
            revocationEpoch = 2L,
        )
        assertTrue(result is OmniResult.Err)
        val err = (result as OmniResult.Err).error
        assertEquals(OmniErrorCode.MODEL_REVOKED, err.code)
    }

    @Test
    fun installationDrain_triggersLoadedModelDrain() = runBlocking {
        promoteToReadyHappyPath()
        // Manually seed a LOADED model pointing at this installation.
        val lmId = LoadedModelId("lm-drain")
        val loadKey = LoadKey(
            modelRevisionId = revisionId,
            engineBuildId = EngineBuildId.parse("engine-build-test-1"),
            backend = "cpu",
            deviceExecutionFingerprint = TestDigests.device(),
            templateEpoch = 0L,
            tokenizerEpoch = 0L,
            loadConfigurationDigest = TestDigests.sha(),
        )
        val planResult = manager.planLoad(
            LoadInput(
                requestId = RequestId.parse("00000000-0000-0000-0000-000000000002"),
                principalId = PrincipalId.parse("principal-1"),
                installationId = IdentityInstallationId.ofValidated(installationId.value),
                modelRevisionId = revisionId,
                loadKey = loadKey,
                device = DeviceDescriptor(deviceExecutionFingerprint = TestDigests.device()),
                storageRootKey = manager.getInstallation(installationId)!!.storageRootKey!!,
                runtimeEpoch = 1L,
                revocationEpoch = 0L,
                templateEpoch = 0L,
                tokenizerEpoch = 0L,
            ),
        )
        val plan = assertOk(planResult)
        assertOk(manager.admitLoad(lmId, installationId, loadKey, plan, true))
        val ticket = assertOk(manager.preparePrivilegedTicket(installationId, plan.engineBuildId.value, 0L))
        val reservation = Reservation(
            reservationId = ReservationId.parse("res-2"),
            principalId = "principal-1",
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            nonce = "n2",
            deadlineMonotonic = 9999L,
            envelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 1),
                peak = ResourceVector(cpuAnonBytes = 1),
            ),
        )
        assertOk(
            manager.commitLoad(
                loadedModelId = lmId,
                plan = plan,
                reservation = reservation,
                commit = CommitContext(
                    commitId = CommitId.parse("00000000-0000-0000-0000-0000000000bb"),
                    requestId = RequestId.parse("00000000-0000-0000-0000-000000000002"),
                    principalId = PrincipalId.parse("principal-1"),
                    reservationId = ReservationId.parse("res-2"),
                    revisionLeaseId = ContractRevisionLeaseId.parse("lease-wire-2"),
                    issuerBootId = "boot-1",
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    oneShotNonce = "nonce-2",
                    privilegedLoadTicketId = ticket.ticketId,
                ),
                placementQualified = true,
                loadEnvelopeMatched = true,
                revocationEpoch = 0L,
            ),
        )
        assertEquals("LOADED", manager.getLoadedModel(lmId)!!.state)

        val drained = assertOk(manager.onInstallationDraining(installationId))
        assertEquals(1, drained.size)
        assertEquals("DRAINING", drained[0].state)
        // Installation state not auto-mutated by load drain helper.
        assertEquals("READY", manager.getInstallation(installationId)!!.state)
    }

    @Test
    fun evaluationDimensions_compatibilityDoesNotBypassAuthenticity() {
        val dims = com.omnillm.runtime.modelmanager.domain.EvaluationDimensions(
            authenticityOk = false,
            licenseOk = true,
            compatibilityOk = true,
            performanceRecorded = true,
            placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
        )
        assertFalse(dims.allowsLoadPlanning { PlacementClassLabels.isExecutable(it) })
    }

    private suspend fun promoteToReadyHappyPath() {
        assertOk(manager.discoverInstallation(installationId, revisionId, packageId))
        val qKey = QuarantineKey("job-1", "a1")
        assertOk(
            manager.beginAcquire(
                installationId,
                qKey,
                listOf(DeclaredArtifactFile("weights", TestDigests.blob(), 10L)),
                1000L,
            ),
        )
        assertOk(
            manager.materializeComplete(
                installationId,
                listOf(
                    QuarantineFileRecord(
                        role = "weights",
                        expectedBlobId = TestDigests.blob(),
                        expectedByteLength = 10L,
                        materializeHandle = "q",
                    ),
                ),
            ),
        )
        assertOk(manager.beginVerify(installationId))
        assertOk(manager.completeIdentityVerify(installationId))
        assertOk(manager.promoteToReady(installationId))
    }

    private fun <T> assertOk(result: OmniResult<T>): T {
        assertTrue("expected Ok but was $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }
}
