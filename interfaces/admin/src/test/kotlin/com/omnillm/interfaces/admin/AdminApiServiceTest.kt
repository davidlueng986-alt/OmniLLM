package com.omnillm.interfaces.admin

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.policy.SettingValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Admin API conformance (FEAT-ADMIN, CORE-INTERFACE, ADR-004/005).
 */
class AdminApiServiceTest {

    private lateinit var api: AdminApiService
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    @Before
    fun setUp() {
        val (_, commands, _) = RequestRegistryModule.createInMemory(
            clock = { "2026-08-03T12:00:00Z" },
        )
        api = AdminModule.createService(
            commandLedger = commands,
            jobManager = JobManagerModule.createManager(),
            policyManager = PolicyModule.createManager(),
            runtimeStateProvider = { "READY" },
        )
    }

    @Test
    fun snapshot_includesRuntimeJobsSettingsAndHighWatermark() {
        val snap = api.getSnapshot()
        assertEquals("READY", snap.runtimeState)
        assertEquals("DISABLED", snap.lanState)
        assertTrue(snap.snapshotVersion >= 1L)
        assertEquals(0L, snap.highWatermark)
        assertTrue(snap.settings.values.containsKey("server.loopbackEnabled"))
    }

    @Test
    fun applySettings_cas_and_queryCommand_afterReplyLoss() {
        val cmd = AdminCommandRequest(
            commandId = "11111111-1111-1111-1111-111111111111",
            idempotencyKey = "settings-patch-1",
            canonicalInputDigest = digestA,
            expectedVersion = 0L,
        )
        val result = api.applySettings(
            principal = LocalUiPrincipal.ID,
            command = cmd,
            changes = mapOf("server.loopbackEnabled" to SettingValue.BoolValue(true)),
        )
        assertEquals("SUCCEEDED", result.state)
        assertTrue(result.resourceVersion >= 1L)
        assertEquals("settings", result.affectedResourceId)

        val settings = api.getSettings()
        assertEquals(SettingValue.BoolValue(true), settings.values["server.loopbackEnabled"])
        assertEquals(result.resourceVersion, settings.resourceVersion)

        // Reply loss: query same commandId.
        val queried = api.queryCommand(LocalUiPrincipal.ID, cmd.commandId)
        assertEquals("SUCCEEDED", queried.state)
        assertEquals(result.resourceVersion, queried.resourceVersion)

        // Same key + digest returns durable original without re-apply.
        val again = api.applySettings(
            principal = LocalUiPrincipal.ID,
            command = cmd,
            changes = mapOf("server.loopbackEnabled" to SettingValue.BoolValue(true)),
        )
        assertEquals("SUCCEEDED", again.state)
        assertEquals(result.resourceVersion, again.resourceVersion)
    }

    @Test
    fun applySettings_staleVersion_isStateConflict_commandResult() {
        api.applySettings(
            principal = LocalUiPrincipal.ID,
            command = AdminCommandRequest(
                commandId = "22222222-2222-2222-2222-222222222222",
                idempotencyKey = "settings-ok",
                canonicalInputDigest = digestA,
                expectedVersion = 0L,
            ),
            changes = mapOf("privacy.telemetryMode" to SettingValue.EnumValue("OFF")),
        )
        val stale = api.applySettings(
            principal = LocalUiPrincipal.ID,
            command = AdminCommandRequest(
                commandId = "33333333-3333-3333-3333-333333333333",
                idempotencyKey = "settings-stale",
                canonicalInputDigest = digestB,
                expectedVersion = 0L,
            ),
            changes = mapOf("privacy.telemetryMode" to SettingValue.EnumValue("LOCAL_ONLY")),
        )
        assertEquals("FAILED", stale.state)
        assertEquals(OmniErrorCode.STATE_CONFLICT, stale.error!!.code)
    }

    @Test
    fun applySettings_missingExpectedVersion_failsCommandResult() {
        val result = api.applySettings(
            principal = LocalUiPrincipal.ID,
            command = AdminCommandRequest(
                commandId = "44444444-4444-4444-4444-444444444444",
                idempotencyKey = "settings-no-ver",
                canonicalInputDigest = digestA,
                expectedVersion = null,
            ),
            changes = mapOf("server.loopbackEnabled" to SettingValue.BoolValue(true)),
        )
        assertEquals("FAILED", result.state)
        assertEquals(OmniErrorCode.INVALID_REQUEST, result.error!!.code)
    }

    @Test
    fun startJob_and_cancelJob_returnDurableResults() {
        val jobId = "55555555-5555-5555-5555-555555555555"
        val createCmd = AdminCommandRequest(
            commandId = "66666666-6666-6666-6666-666666666666",
            idempotencyKey = "job-create-1",
            canonicalInputDigest = digestA,
        )
        val created = api.startJob(
            principal = LocalUiPrincipal.ID,
            spec = AdminJobSpec(
                jobId = jobId,
                kind = "DOWNLOAD",
                command = createCmd,
                parameters = JobParameters.Download(sourceUrl = "https://example.com/m.gguf"),
            ),
        )
        assertTrue(created is OmniResult.Ok)
        val record = (created as OmniResult.Ok).value
        assertEquals(jobId, record.jobId.value)
        assertEquals("QUEUED", record.state)

        val cancelCmd = AdminCommandRequest(
            commandId = "77777777-7777-7777-7777-777777777777",
            idempotencyKey = "job-cancel-1",
            canonicalInputDigest = digestB,
            expectedVersion = record.resourceVersion,
        )
        val cancel = api.cancelJob(LocalUiPrincipal.ID, jobId, cancelCmd)
        assertEquals("SUCCEEDED", cancel.state)
        assertEquals(jobId, cancel.affectedResourceId)

        val after = api.getJob(LocalUiPrincipal.ID, jobId) as OmniResult.Ok
        assertEquals("CANCELLED", after.value.state)

        val cancelQuery = api.queryCommand(LocalUiPrincipal.ID, cancelCmd.commandId)
        assertEquals("SUCCEEDED", cancelQuery.state)
    }

    @Test
    fun cancelJob_missingExpectedVersion_failsCommandResult() {
        val jobId = "e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1"
        api.startJob(
            principal = LocalUiPrincipal.ID,
            spec = AdminJobSpec(
                jobId = jobId,
                kind = "DOWNLOAD",
                command = AdminCommandRequest(
                    commandId = "e2e2e2e2-e2e2-e2e2-e2e2-e2e2e2e2e2e2",
                    idempotencyKey = "job-cas-missing-ver",
                    canonicalInputDigest = digestA,
                ),
                parameters = JobParameters.Download(sourceUrl = "https://example.com/c.gguf"),
            ),
        )
        val missing = api.cancelJob(
            LocalUiPrincipal.ID,
            jobId,
            AdminCommandRequest(
                commandId = "e3e3e3e3-e3e3-e3e3-e3e3-e3e3e3e3e3e3",
                idempotencyKey = "job-cas-no-ver",
                canonicalInputDigest = digestB,
                expectedVersion = null,
            ),
        )
        assertEquals("FAILED", missing.state)
        assertEquals(OmniErrorCode.INVALID_REQUEST, missing.error!!.code)
    }

    @Test
    fun cancelJob_staleExpectedVersion_isStateConflict() {
        val jobId = "f1f1f1f1-f1f1-f1f1-f1f1-f1f1f1f1f1f1"
        val created = api.startJob(
            principal = LocalUiPrincipal.ID,
            spec = AdminJobSpec(
                jobId = jobId,
                kind = "DOWNLOAD",
                command = AdminCommandRequest(
                    commandId = "f2f2f2f2-f2f2-f2f2-f2f2-f2f2f2f2f2f2",
                    idempotencyKey = "job-cas-stale",
                    canonicalInputDigest = digestA,
                ),
                parameters = JobParameters.Download(sourceUrl = "https://example.com/d.gguf"),
            ),
        ) as OmniResult.Ok
        val stale = api.cancelJob(
            LocalUiPrincipal.ID,
            jobId,
            AdminCommandRequest(
                commandId = "f3f3f3f3-f3f3-f3f3-f3f3-f3f3f3f3f3f3",
                idempotencyKey = "job-cas-stale-key",
                canonicalInputDigest = digestB,
                expectedVersion = created.value.resourceVersion + 99L,
            ),
        )
        assertEquals("FAILED", stale.state)
        assertEquals(OmniErrorCode.STATE_CONFLICT, stale.error!!.code)
    }

    @Test
    fun createJob_sameKeyDifferentDigest_isIdempotencyConflict() {
        val jobId = "88888888-8888-8888-8888-888888888888"
        api.startJob(
            principal = LocalUiPrincipal.ID,
            spec = AdminJobSpec(
                jobId = jobId,
                kind = "DOWNLOAD",
                command = AdminCommandRequest(
                    commandId = "99999999-9999-9999-9999-999999999999",
                    idempotencyKey = "job-conflict",
                    canonicalInputDigest = digestA,
                ),
                parameters = JobParameters.Download(sourceUrl = "https://example.com/a"),
            ),
        )
        val conflict = api.startJob(
            principal = LocalUiPrincipal.ID,
            spec = AdminJobSpec(
                jobId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                kind = "DOWNLOAD",
                command = AdminCommandRequest(
                    commandId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    idempotencyKey = "job-conflict",
                    canonicalInputDigest = digestB,
                ),
                parameters = JobParameters.Download(sourceUrl = "https://example.com/b"),
            ),
        )
        assertTrue(conflict is OmniResult.Err)
        assertEquals(
            OmniErrorCode.IDEMPOTENCY_CONFLICT,
            (conflict as OmniResult.Err).error.code,
        )
    }

    @Test
    fun observeJobs_subscriptionHandle_ack_and_close() {
        val events = CopyOnWriteArrayList<AdminJobEventBatch>()
        val rejected = AtomicReference<com.omnillm.core.errors.generated.OmniError?>(null)
        val sink = object : AdminJobEventSink {
            override fun onEvents(batch: AdminJobEventBatch) {
                events.add(batch)
            }

            override fun onRejected(error: com.omnillm.core.errors.generated.OmniError) {
                rejected.set(error)
            }
        }

        val sub = api.observeJobs(
            principal = LocalUiPrincipal.ID,
            cursor = null,
            credit = 8,
            sink = sink,
        ) as OmniResult.Ok
        assertTrue(sub.value.startsWith("sub-"))

        // Produce a job event.
        api.startJob(
            principal = LocalUiPrincipal.ID,
            spec = AdminJobSpec(
                jobId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                kind = "DIAGNOSTIC_EXPORT",
                command = AdminCommandRequest(
                    commandId = "dddddddd-dddd-dddd-dddd-dddddddddddd",
                    idempotencyKey = "obs-job-1",
                    canonicalInputDigest = digestA,
                ),
                parameters = JobParameters.DiagnosticExport(includeDetail = false),
            ),
        )

        assertTrue(events.isNotEmpty())
        val batch = events.first()
        assertEquals(sub.value, batch.subscriptionId)
        assertTrue(batch.events.isNotEmpty())

        val ack = api.ackJobEvents(
            principal = LocalUiPrincipal.ID,
            subscriptionId = sub.value,
            streamEpoch = batch.streamEpoch,
            eventToExclusive = batch.eventTo,
        )
        assertTrue(ack is OmniResult.Ok)

        api.closeSubscription(LocalUiPrincipal.ID, sub.value)
        assertEquals(0, api.jobObservers.subscriptionCount())
    }

    @Test
    fun queryCommand_unknown_returnsFailedCommandResultNotThrow() {
        val result = api.queryCommand(
            LocalUiPrincipal.ID,
            "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
        )
        assertEquals("FAILED", result.state)
        assertEquals(OmniErrorCode.NOT_FOUND, result.error!!.code)
        assertNotNull(result.commandId)
    }
}
