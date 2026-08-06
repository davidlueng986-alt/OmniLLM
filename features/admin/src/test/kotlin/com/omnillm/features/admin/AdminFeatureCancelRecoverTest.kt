package com.omnillm.features.admin

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.admin.projection.JobUiAction
import com.omnillm.features.admin.projection.JobUiProjection
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminJobEvent
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.interfaces.admin.JobSubscriptionRegistry
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.core.state.domain.JobId
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobPauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FEAT-ADMIN failure / cancel / observer recovery paths.
 *
 * Acceptance (docs/70-features/administration-jobs.md §7):
 * - Command reply loss (query)
 * - Paused cancellation
 * - Cursor gap rebuild
 * - Failure projection
 */
class AdminFeatureCancelRecoverTest {

    private lateinit var jobManager: JobManager
    private lateinit var api: com.omnillm.features.admin.usecase.AdminFeatureApi
    private lateinit var observers: JobSubscriptionRegistry
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)
    private val digestC = "c".repeat(64)

    @Before
    fun setUp() {
        val (_, commands, _) = RequestRegistryModule.createInMemory(
            clock = { "2026-08-03T12:00:00Z" },
        )
        jobManager = JobManagerModule.createManager()
        observers = JobSubscriptionRegistry()
        val admin = AdminModule.createService(
            commandLedger = commands,
            jobManager = jobManager,
            policyManager = PolicyModule.createManager(),
            jobObservers = observers,
            runtimeStateProvider = { "READY" },
        )
        api = AdminFeatureModule.createApi(admin)
    }

    @Test
    fun cancelJob_fromQueued_reachesCancelled() {
        val jobId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val created = api.startJob(
            jobId = jobId,
            kind = "DOWNLOAD",
            command = AdminCommandRequest(
                commandId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                idempotencyKey = "cancel-create-1",
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/x"),
        ) as OmniResult.Ok

        val cancelCmd = AdminCommandRequest(
            commandId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            idempotencyKey = "cancel-1",
            canonicalInputDigest = digestB,
            expectedVersion = created.value.resourceVersion,
        )
        val cancelStatus = api.cancelJob(jobId, cancelCmd)
        assertEquals("SUCCEEDED", cancelStatus.state)
        assertEquals(jobId, cancelStatus.affectedResourceId)

        val after = api.getJob(jobId) as OmniResult.Ok
        assertEquals("CANCELLED", after.value.list.state)
        assertEquals("job.cancelled", after.value.list.labelKey)
        assertTrue(JobUiAction.CANCEL !in after.value.list.allowedActions)
    }

    @Test
    fun cancelJob_fromPausedWaitingNetwork_doesNotStick() {
        val jobId = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        api.startJob(
            jobId = jobId,
            kind = "DOWNLOAD",
            command = AdminCommandRequest(
                commandId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                idempotencyKey = "pause-create-1",
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/y"),
        )
        // Drive worker-side lifecycle via JobManager port (control plane).
        val jid = JobId(jobId)
        assertTrue(jobManager.start(jid) is OmniResult.Ok)
        val paused = jobManager.pause(jid, JobPauseReason.WAITING_NETWORK) as OmniResult.Ok
        assertEquals("PAUSED_WAITING_NETWORK", paused.value.state)

        val projected = JobUiProjection.projectListItem(paused.value)
        assertTrue(JobUiAction.CANCEL in projected.allowedActions)
        assertTrue(JobUiAction.WAIT_NETWORK in projected.allowedActions)

        val cancelStatus = api.cancelJob(
            jobId,
            AdminCommandRequest(
                commandId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                idempotencyKey = "pause-cancel-1",
                canonicalInputDigest = digestB,
                expectedVersion = paused.value.resourceVersion,
            ),
        )
        assertEquals("SUCCEEDED", cancelStatus.state)

        val after = api.getJob(jobId) as OmniResult.Ok
        assertEquals("CANCELLED", after.value.list.state)
        assertTrue(JobUiProjection.isTerminal(after.value.list.state))
    }

    @Test
    fun jobsViewModel_cancelSelected_updatesState() {
        val vm = AdminFeatureModule.createJobsViewModel(api)
        val jobId = "12121212-1212-1212-1212-121212121212"
        vm.startJob(
            jobId = jobId,
            kind = "IMPORT",
            command = AdminCommandRequest(
                commandId = "13131313-1313-1313-1313-131313131313",
                idempotencyKey = "vm-import-1",
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.Import(assetId = "asset-1"),
        )
        assertNotNull(vm.state.selected)
        assertTrue(JobUiAction.CANCEL in vm.state.selected!!.list.allowedActions)

        vm.cancelSelected(
            AdminCommandRequest(
                commandId = "14141414-1414-1414-1414-141414141414",
                idempotencyKey = "vm-cancel-1",
                canonicalInputDigest = digestB,
                expectedVersion = vm.state.selected!!.resourceVersion,
            ),
        )
        assertEquals("SUCCEEDED", vm.state.lastCommand!!.state)
        assertEquals("CANCELLED", vm.state.selected!!.list.state)
    }

    @Test
    fun startJob_idempotencyConflict_isFailureProjection() {
        val key = "conflict-key"
        api.startJob(
            jobId = "15151515-1515-1515-1515-151515151515",
            kind = "DOWNLOAD",
            command = AdminCommandRequest(
                commandId = "16161616-1616-1616-1616-161616161616",
                idempotencyKey = key,
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/a"),
        )
        val conflict = api.startJob(
            jobId = "17171717-1717-1717-1717-171717171717",
            kind = "DOWNLOAD",
            command = AdminCommandRequest(
                commandId = "18181818-1818-1818-1818-181818181818",
                idempotencyKey = key,
                canonicalInputDigest = digestB,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/b"),
        )
        assertTrue(conflict is OmniResult.Err)
        assertEquals(OmniErrorCode.IDEMPOTENCY_CONFLICT, (conflict as OmniResult.Err).error.code)
    }

    @Test
    fun observeJobs_receivesEvents_andAck() {
        val events = CopyOnWriteArrayList<AdminJobEvent>()
        val sub = api.observeJobs.subscribe(
            cursor = null,
            credit = 8,
            onEvents = { events.addAll(it) },
            onRejected = { },
        ) as OmniResult.Ok

        api.startJob(
            jobId = "19191919-1919-1919-1919-191919191919",
            kind = "DIAGNOSTIC_EXPORT",
            command = AdminCommandRequest(
                commandId = "1a1a1a1a-1a1a-1a1a-1a1a-1a1a1a1a1a1a",
                idempotencyKey = "obs-1",
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.DiagnosticExport(),
        )
        assertTrue(events.isNotEmpty())

        val lastExclusive = events.maxOf { it.eventId } + 1
        val acked = api.observeJobs.ack(sub.value.session, lastExclusive) as OmniResult.Ok
        assertEquals(lastExclusive, acked.value.lastEventExclusive)

        api.observeJobs.close(acked.value)
    }

    @Test
    fun observeJobs_cursorGone_rebuildsFromSnapshot() {
        val events = CopyOnWriteArrayList<AdminJobEvent>()
        // Seed at least one job so home snapshot has active work.
        api.startJob(
            jobId = "b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0",
            kind = "DIAGNOSTIC_EXPORT",
            command = AdminCommandRequest(
                commandId = "c0c0c0c0-c0c0-c0c0-c0c0-c0c0c0c0c0c0",
                idempotencyKey = "cursor-seed",
                canonicalInputDigest = digestC,
            ),
            parameters = JobParameters.DiagnosticExport(),
        )

        // Explicit recovery path used when observe returns CURSOR_GONE (FEAT-ADMIN §5).
        val recovered = api.observeJobs.recoverFromCursorGap(
            credit = 8,
            onEvents = { events.addAll(it) },
            onRejected = { },
        ) as OmniResult.Ok
        assertTrue(recovered.value.rebuiltFromSnapshot)
        assertNotNull(recovered.value.homeAfterRebuild)
        assertTrue(recovered.value.session.cursorGone)
        assertTrue(recovered.value.homeAfterRebuild!!.activeJobCount >= 1)
        assertTrue(recovered.value.session.subscriptionId.startsWith("sub-"))

        api.observeJobs.close(recovered.value.session)
    }

    @Test
    fun jobFailure_projectsErrorSeverity() {
        val jobId = "20202020-2020-2020-2020-202020202020"
        api.startJob(
            jobId = jobId,
            kind = "DOWNLOAD",
            command = AdminCommandRequest(
                commandId = "21212121-2121-2121-2121-212121212121",
                idempotencyKey = "fail-create",
                canonicalInputDigest = digestA,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/z"),
        )
        val jid = JobId(jobId)
        jobManager.start(jid)
        jobManager.fail(
            jid,
            com.omnillm.core.errors.generated.OmniError.INTERNAL(message = "worker failed"),
        )

        val detail = api.getJob(jobId) as OmniResult.Ok
        assertEquals("FAILED", detail.value.list.state)
        assertEquals("job.failed", detail.value.list.labelKey)
        assertEquals(
            com.omnillm.features.admin.projection.UiSeverity.ERROR,
            detail.value.list.severity,
        )
        assertEquals(OmniErrorCode.INTERNAL.code, detail.value.list.errorCode)
    }

    @Test
    fun settingsViewModel_staleVersion_failsCommand() {
        val vm = AdminFeatureModule.createSettingsViewModel(api)
        vm.refresh()
        assertNotNull(vm.state.screen)

        vm.applyPatch(
            command = AdminCommandRequest(
                commandId = "22222222-2222-2222-2222-222222222221",
                idempotencyKey = "set-ok",
                canonicalInputDigest = digestA,
                expectedVersion = 0L,
            ),
            changes = mapOf("privacy.telemetryMode" to com.omnillm.runtime.policy.SettingValue.EnumValue("OFF")),
        )
        assertTrue(vm.state.lastCommand!!.isSuccess)

        // Stale CAS
        vm.applyPatch(
            command = AdminCommandRequest(
                commandId = "22222222-2222-2222-2222-222222222222",
                idempotencyKey = "set-stale",
                canonicalInputDigest = digestB,
                expectedVersion = 0L,
            ),
            changes = mapOf(
                "privacy.telemetryMode" to
                    com.omnillm.runtime.policy.SettingValue.EnumValue("LOCAL_ONLY"),
            ),
        )
        assertEquals("FAILED", vm.state.lastCommand!!.state)
        assertEquals(OmniErrorCode.STATE_CONFLICT, vm.state.lastCommand!!.error!!.code)
    }
}
