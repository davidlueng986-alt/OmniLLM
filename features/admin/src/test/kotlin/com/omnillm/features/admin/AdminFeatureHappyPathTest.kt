package com.omnillm.features.admin

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.admin.projection.CommandUiAction
import com.omnillm.features.admin.projection.JobUiAction
import com.omnillm.features.admin.projection.JobUiProjection
import com.omnillm.features.admin.projection.UiSeverity
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.policy.SettingValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-ADMIN happy path: snapshot, settings CAS, start job, observe, query command.
 */
class AdminFeatureHappyPathTest {

    private lateinit var api: com.omnillm.features.admin.usecase.AdminFeatureApi
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    @Before
    fun setUp() {
        val (_, commands, _) = RequestRegistryModule.createInMemory(
            clock = { "2026-08-03T12:00:00Z" },
        )
        val admin = AdminModule.createService(
            commandLedger = commands,
            jobManager = JobManagerModule.createManager(),
            policyManager = PolicyModule.createManager(),
            runtimeStateProvider = { "READY" },
        )
        api = AdminFeatureModule.createApi(admin)
    }

    @Test
    fun home_projectsRuntimeJobsAndSettings() {
        val home = api.getHome()
        assertEquals("READY", home.runtimeState)
        assertEquals("DISABLED", home.lanState)
        assertTrue(home.snapshotVersion >= 1L)
        assertEquals(0, home.activeJobCount)
        assertTrue(home.settingsResourceVersion >= 0L)
    }

    @Test
    fun settings_patch_and_replyLoss_queryCommand() {
        val cmd = AdminCommandRequest(
            commandId = "11111111-1111-1111-1111-111111111111",
            idempotencyKey = "feat-settings-1",
            canonicalInputDigest = digestA,
            expectedVersion = 0L,
        )
        val status = api.patchSettings(
            command = cmd,
            changes = mapOf("server.loopbackEnabled" to SettingValue.BoolValue(true)),
        )
        assertTrue(status.isSuccess)
        assertEquals("SUCCEEDED", status.state)
        assertEquals("settings", status.affectedResourceId)
        assertTrue(CommandUiAction.VIEW_DETAILS in status.allowedActions)

        val screen = api.getSettings()
        assertEquals(
            "true",
            screen.fields.first { it.key == "server.loopbackEnabled" }.displayValue,
        )

        // Reply loss: query same commandId, no re-apply.
        val queried = api.queryCommand(cmd.commandId)
        assertEquals("SUCCEEDED", queried.state)
        assertEquals(status.resourceVersion, queried.resourceVersion)
    }

    @Test
    fun startJob_projectsQueuedWithCancelAction() {
        val jobId = "22222222-2222-2222-2222-222222222222"
        val created = api.startJob(
            jobId = jobId,
            kind = "DOWNLOAD",
            command = AdminCommandRequest(
                commandId = "33333333-3333-3333-3333-333333333333",
                idempotencyKey = "feat-job-1",
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/m.gguf"),
        )
        assertTrue(created is OmniResult.Ok)
        val detail = (created as OmniResult.Ok).value
        assertEquals(jobId, detail.list.jobId)
        assertEquals("QUEUED", detail.list.state)
        assertEquals("job.queued", detail.list.labelKey)
        assertEquals(UiSeverity.INFO, detail.list.severity)
        assertTrue(JobUiAction.CANCEL in detail.list.allowedActions)
        assertNull(detail.list.progressRatio) // unknown total — no invented percent

        val listed = api.listActiveJobs()
        assertEquals(1, listed.size)
        assertEquals(jobId, listed.single().jobId)
    }

    @Test
    fun homeViewModel_refresh_loadsSnapshot() {
        val vm = AdminFeatureModule.createHomeViewModel(api)
        vm.refresh()
        assertFalse(vm.state.loading)
        assertNotNull(vm.state.home)
        assertEquals("READY", vm.state.home!!.runtimeState)
        assertNull(vm.state.error)
    }

    @Test
    fun jobsViewModel_startAndList() {
        val vm = AdminFeatureModule.createJobsViewModel(api)
        vm.startJob(
            jobId = "44444444-4444-4444-4444-444444444444",
            kind = "DIAGNOSTIC_EXPORT",
            command = AdminCommandRequest(
                commandId = "55555555-5555-5555-5555-555555555555",
                idempotencyKey = "feat-diag-1",
                canonicalInputDigest = digestB,
            ),
            parameters = JobParameters.DiagnosticExport(includeDetail = false),
        )
        assertNotNull(vm.state.selected)
        assertEquals("QUEUED", vm.state.selected!!.list.state)
        assertTrue(vm.state.jobs.any { it.jobId == "44444444-4444-4444-4444-444444444444" })
    }

    @Test
    fun jobUiProjection_unknownState_failClosed() {
        // Projection helper: only catalog states get actions.
        assertFalse(JobUiProjection.isCancellable("NOT_A_REAL_STATE", false))
        assertTrue(JobUiProjection.allowedActions("NOT_A_REAL_STATE", false).isEmpty())
    }
}
