package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.FilesystemModelStorePort
import com.omnillm.data.modelstore.FilesystemQuarantineStore
import com.omnillm.data.modelstore.FilesystemReadyContentPort
import com.omnillm.data.modelstore.MaterializeBounds
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * Install state machine over durable SQL + filesystem quarantine
 * (CORE-MODEL §1.1 / §4, DATA-OWNERSHIP §3, ADR-010).
 *
 * Covers discover → acquire → materialize → verify → promote → READY,
 * process-reopen catalog projection, and revision lease durability.
 * Acquisition jobs bind via durable JobStore on the control plane
 * ([JobManagerModule.createDurableManager] + ModelHubService).
 */
class DurableInstallStateMachineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val installationId = InstallationId("11111111-1111-1111-1111-111111111111")
    private val revisionId = TestDigests.rev()
    private val packageId = TestDigests.pkg()
    private val payload = ByteArray(16) { 0x42 }

    @Test
    fun installStateMachine_sqlCatalog_andFilesystemPromote_happyPath() = runBlocking {
        val dbFile = tmp.newFile("omnillm-model2.db")
        val filesRoot = tmp.newFolder("files2").toPath()
        val clock = { "2026-08-06T12:00:00Z" }
        val bounds = MaterializeBounds.DEFAULT

        val fsStore = FilesystemQuarantineStore(
            filesRoot = filesRoot,
            bounds = bounds,
            monotonicNowMs = { 1_000L },
        )
        val modelStore = FilesystemModelStorePort(fsStore, FilesystemReadyContentPort(filesRoot))
        val digestHex = sha256Hex(payload)
        val blobId = BlobId.parse(digestHex)

        withDb(dbFile, clock) { db ->
            val trust = FixedTrustEvaluationPort(
                EvaluationDimensions(
                    authenticityOk = true,
                    licenseOk = true,
                    compatibilityOk = false,
                    performanceRecorded = false,
                    placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                    trustEpoch = 0L,
                ),
            )
            val manager = ModelManagerModule.createDurableControlPlane(
                installationPorts = db.installations,
                leasePorts = db.revisionLeases,
                modelStore = modelStore,
                trustEvaluation = trust,
                references = MutableReferenceSnapshotPort(),
                clock = clock,
            )

            assertOk(manager.discoverInstallation(installationId, revisionId, packageId))
            val qKey = QuarantineKey(jobId = "job-1", attemptId = "a1")
            val declared = listOf(
                DeclaredArtifactFile(
                    role = "weights",
                    blobId = blobId,
                    byteLength = payload.size.toLong(),
                ),
            )
            assertOk(manager.beginAcquire(installationId, qKey, declared, deadlineMonotonic = 10_000L))
            assertEquals("ACQUIRING", manager.getInstallation(installationId)!!.state)

            val mat = fsStore.materializeFromStream(
                key = qKey,
                role = "weights",
                expectedBlobId = blobId,
                expectedByteLength = payload.size.toLong(),
                input = ByteArrayInputStream(payload),
            )
            assertTrue("materialize failed: $mat", mat is OmniResult.Ok)
            // Files already recorded on store by materializeFromStream; advance FSM only.
            assertOk(manager.materializeComplete(installationId, emptyList()))
            assertEquals("QUARANTINED", manager.getInstallation(installationId)!!.state)

            assertOk(manager.beginVerify(installationId))
            assertEquals("VERIFYING", manager.getInstallation(installationId)!!.state)

            assertOk(manager.completeIdentityVerify(installationId))
            val afterVerify = manager.getInstallation(installationId)!!
            assertEquals("COMPATIBILITY_CHECK", afterVerify.state)
            assertNotNull(afterVerify.evaluation)
            assertTrue(afterVerify.evaluation!!.authenticityOk)
            // Compatibility evidence must not gate authenticity (ADR-009).
            assertFalse(afterVerify.evaluation!!.compatibilityOk)

            assertOk(manager.promoteToReady(installationId))
            val readySnap = manager.getInstallation(installationId)!!
            assertEquals("READY", readySnap.state)
            assertNull(readySnap.quarantineKey)
            assertEquals("installations/${installationId.value}", readySnap.storageRootKey)

            val catalog = manager.listInstallations()
            assertEquals(1, catalog.size)
            assertEquals("READY", catalog.single().state)

            // READY is not LoadedModel
            assertTrue(readySnap.isReady())
            assertFalse(readySnap.state == "LOADED")
        }

        // Survive process reopen (new DB handle, same file).
        withDb(dbFile, clock) { db2 ->
            val manager2 = ModelManagerModule.createDurableControlPlane(
                installationPorts = db2.installations,
                leasePorts = db2.revisionLeases,
                modelStore = FilesystemModelStorePort(
                    FilesystemQuarantineStore(filesRoot),
                    FilesystemReadyContentPort(filesRoot),
                ),
                clock = clock,
            )
            val reloaded = manager2.getInstallation(installationId)
            assertNotNull(reloaded)
            assertEquals("READY", reloaded!!.state)
            assertEquals("installations/${installationId.value}", reloaded.storageRootKey)
            assertEquals(revisionId.hex, reloaded.modelRevisionId.hex)
        }
    }

    @Test
    fun revisionLease_durableActive_survivesReopen() = runBlocking {
        val dbFile = tmp.newFile("omnillm-lease.db")
        val clock = { "2026-08-06T13:00:00Z" }
        val leaseId = RevisionLeaseId("lease-durable-1")
        val requestId = RequestId("req-durable-1")

        withDb(dbFile, clock) { db ->
            val filesRoot = tmp.newFolder("files-lease").toPath()
            val manager = ModelManagerModule.createDurableControlPlane(
                installationPorts = db.installations,
                leasePorts = db.revisionLeases,
                modelStore = FilesystemModelStorePort.create(filesRoot),
                clock = clock,
            )
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
        }

        withDb(dbFile, clock) { db2 ->
            val manager2 = ModelManagerModule.createDurableControlPlane(
                installationPorts = db2.installations,
                leasePorts = db2.revisionLeases,
                modelStore = FilesystemModelStorePort.create(tmp.newFolder("files-lease2").toPath()),
                clock = clock,
            )
            val lease = manager2.getRevisionLease(leaseId)
            assertNotNull(lease)
            assertEquals("ACTIVE", lease!!.state)
            assertTrue(manager2.isRevisionPinnedByLease(revisionId))
        }
    }

    @Test
    fun identityFailure_rejectsWithoutPromote_durable() = runBlocking {
        val dbFile = tmp.newFile("omnillm-reject.db")
        val filesRoot = tmp.newFolder("files-reject").toPath()
        val clock = { "2026-08-06T14:00:00Z" }
        val fsStore = FilesystemQuarantineStore(
            filesRoot = filesRoot,
            monotonicNowMs = { 1_000L },
        )
        val modelStore = FilesystemModelStorePort(fsStore, FilesystemReadyContentPort(filesRoot))
        val wrongBlob = TestDigests.blob('e')
        val declared = listOf(
            DeclaredArtifactFile(
                role = "weights",
                blobId = wrongBlob,
                byteLength = payload.size.toLong(),
            ),
        )

        withDb(dbFile, clock) { db ->
            val manager = ModelManagerModule.createDurableControlPlane(
                installationPorts = db.installations,
                leasePorts = db.revisionLeases,
                modelStore = modelStore,
                clock = clock,
            )
            assertOk(manager.discoverInstallation(installationId, revisionId, packageId))
            val qKey = QuarantineKey("job-bad", "a1")
            assertOk(manager.beginAcquire(installationId, qKey, declared, 10_000L))

            val mat = fsStore.materializeFromStream(
                key = qKey,
                role = "weights",
                expectedBlobId = wrongBlob,
                expectedByteLength = payload.size.toLong(),
                input = ByteArrayInputStream(payload),
            )
            // Digest mismatch fails closed at materialize or at identity verify.
            if (mat is OmniResult.Ok) {
                assertOk(manager.materializeComplete(installationId, emptyList()))
                assertOk(manager.beginVerify(installationId))
                assertOk(manager.completeIdentityVerify(installationId))
                assertEquals("REJECTED", manager.getInstallation(installationId)!!.state)
            } else {
                assertTrue(mat is OmniResult.Err)
            }
        }
    }

    private fun <T> assertOk(result: OmniResult<T>): T {
        assertTrue("expected Ok, got $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    private suspend fun withDb(
        file: java.io.File,
        clock: () -> String,
        block: suspend (ControlPlaneDatabase) -> Unit,
    ) {
        val db = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        try {
            block(db)
        } finally {
            db.close()
        }
    }
}
