package com.omnillm.ui.admin

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.modelhub.api.ModelHubAction
import com.omnillm.features.modelhub.api.ModelHubCommandIdentity
import com.omnillm.features.modelhub.api.SetPinSpec
import com.omnillm.features.modelhub.api.StartDeleteSpec
import com.omnillm.features.modelhub.api.StartLoadSpec
import com.omnillm.features.modelhub.api.StartUnloadSpec
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CODE-01: AdminProjectedModelHubApi must project live AIDL fields
 * (installationId, allowedActions, pin/load/delete) instead of a shadow
 * CAPABILITY_UNSUPPORTED API with installationId=null.
 */
class AdminProjectedModelHubApiTest {

    private val revisionHex = "a".repeat(64)
    private val installationId = "inst-live-1"
    private val digest = "c".repeat(64)

    private fun command(): ModelHubCommandIdentity =
        ModelHubCommandIdentity(
            commandId = "cmd-1",
            idempotencyKey = "idem-1",
            canonicalInputDigest = digest,
        )

    private fun fakeAdmin(): FakeOmniAdmin {
        val model = ai.omnillm.api.OmniModelInfo().apply {
            modelRevisionId = revisionHex
            displayName = "Imported GGUF"
            installationState = "READY"
            this.installationId = this@AdminProjectedModelHubApiTest.installationId
            artifactPackageId = "d".repeat(64)
            licenseStatus = "ACCEPTED"
            pinned = false
            loadedModelState = null
            allowedActions = arrayOf(ModelHubAction.LOAD, ModelHubAction.DELETE, ModelHubAction.PIN)
            liveReferenceCount = 0
            compatibilityStatus = "NOT_CHECKED"
            acquisitionChannel = "LOCAL_IMPORT"
            hasResourceVersion = true
            resourceVersion = 3L
        }
        val snapshot = ai.omnillm.api.OmniAdminSnapshot().apply {
            snapshotVersion = 1L
            runtimeState = "READY"
            lanState = "DISABLED"
            models = arrayOf(model)
            activeJobs = emptyArray()
            settings = ai.omnillm.api.OmniSettingsSnapshot().apply { resourceVersion = 1L }
        }
        return FakeOmniAdmin(snapshot)
    }

    @Test
    fun snapshot_projectsLiveInstallationIdAndActions() = runBlocking {
        val api = AdminProjectedModelHubApi(fakeAdmin())
        val snap = api.getSnapshot(LocalUiPrincipal.ID)
        assertTrue(snap is OmniResult.Ok)
        val installed = (snap as OmniResult.Ok).value.installed.single()
        assertEquals(installationId, installed.installationId)
        assertEquals("READY", installed.installationState)
        assertEquals("ACCEPTED", installed.licenseStatus)
        assertTrue(installed.allowedActions.contains(ModelHubAction.LOAD))
        assertTrue(installed.allowedActions.contains(ModelHubAction.DELETE))
    }

    @Test
    fun getModelCard_looksUpByInstallationId() = runBlocking {
        val api = AdminProjectedModelHubApi(fakeAdmin())
        val card = api.getModelCard(LocalUiPrincipal.ID, installationId = installationId)
        assertTrue(card is OmniResult.Ok)
        assertEquals(revisionHex, (card as OmniResult.Ok).value.modelRevisionId)
    }

    @Test
    fun startLoad_forwardsToAdminBinder() = runBlocking {
        val admin = fakeAdmin()
        val api = AdminProjectedModelHubApi(admin)
        val result = api.startLoad(
            LocalUiPrincipal.ID,
            StartLoadSpec(installationId = installationId, command = command()),
        )
        assertTrue(result is OmniResult.Ok)
        assertEquals(listOf(installationId), admin.loadCalls.toList())
        assertEquals("READY", (result as OmniResult.Ok).value.state)
        assertEquals(installationId, result.value.installationId)
    }

    @Test
    fun startUnloadAndPinAndDelete_forwardToAdminBinder() = runBlocking {
        val admin = fakeAdmin()
        val api = AdminProjectedModelHubApi(admin)
        val unload = api.startUnload(
            LocalUiPrincipal.ID,
            StartUnloadSpec(installationId = installationId, command = command()),
        )
        assertTrue(unload is OmniResult.Ok)
        assertEquals(listOf(installationId), admin.unloadCalls.toList())

        val pin = api.setPinned(
            LocalUiPrincipal.ID,
            SetPinSpec(installationId = installationId, pinned = true, command = command()),
        )
        assertTrue(pin is OmniResult.Ok)
        assertEquals(listOf(installationId to true), admin.pinCalls.toList())

        val del = api.startDelete(
            LocalUiPrincipal.ID,
            StartDeleteSpec(
                jobId = "job-del-1",
                installationId = installationId,
                expectedResourceVersion = 3L,
                command = command(),
            ),
        )
        assertTrue(del is OmniResult.Ok)
        assertEquals("DELETE", admin.startJobCalls.single().kind)
        assertEquals(installationId, admin.startJobCalls.single().deleteSpec?.resourceId)
        assertNotNull((del as OmniResult.Ok).value.jobId)
    }
}
