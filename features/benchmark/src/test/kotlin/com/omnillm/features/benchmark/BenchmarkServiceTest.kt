package com.omnillm.features.benchmark

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.benchmark.TestFixtures.assertOk
import com.omnillm.features.benchmark.TestFixtures.baseProfile
import com.omnillm.features.benchmark.TestFixtures.command
import com.omnillm.features.benchmark.TestFixtures.sampleMetrics
import com.omnillm.features.benchmark.api.CancelBenchmarkSpec
import com.omnillm.features.benchmark.api.ExportReportSpec
import com.omnillm.features.benchmark.api.PlanBenchmarkSpec
import com.omnillm.features.benchmark.api.StartBenchmarkSpec
import com.omnillm.features.benchmark.domain.BenchmarkUiPhases
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.features.benchmark.export.BenchmarkReportBuilder
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.observability.MetricClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-BENCHMARK happy path: plan → start → complete, multi-run history, export.
 */
class BenchmarkServiceTest {

    private lateinit var api: com.omnillm.features.benchmark.api.BenchmarkApi

    @Before
    fun setUp() {
        api = TestFixtures.api()
    }

    @Test
    fun snapshot_startsEmpty() = runBlocking {
        val snap = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(BenchmarkUiPhases.EMPTY, snap.uiPhase)
        assertTrue(snap.isEmpty)
        assertTrue(snap.runs.isEmpty())
    }

    @Test
    fun plan_thenStart_complete_reachesValid() = runBlocking {
        val profile = baseProfile()
        val plan = assertOk(
            api.planBenchmark(
                LocalUiPrincipal.ID,
                PlanBenchmarkSpec(profile = profile, templateId = "quick_smoke"),
            ),
        )
        assertEquals(profile.profileId(), plan.profileId)
        assertTrue(plan.dimensions.isNotEmpty())
        assertEquals("benchmark.fairness.interactive_share", plan.fairnessNoticeKey)

        val jobId = "11111111-1111-1111-1111-111111111111"
        val runId = "22222222-2222-2222-2222-222222222222"
        val handle = assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = jobId,
                    runId = runId,
                    profile = profile,
                    command = command(idempotencyKey = "run-1"),
                ),
            ),
        )
        assertTrue(handle.createdNew)
        assertEquals("BENCHMARK", handle.kind)
        assertEquals("RUNNING", handle.state)

        val mid = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(BenchmarkUiPhases.LOADING, mid.uiPhase)

        val sealed = assertOk(api.completeRun(jobId, sampleMetrics()))
        assertEquals(MeasurementRunOutcomes.VALID, sealed.outcome)
        assertEquals(1L, sealed.runSeq)
        assertNotNull(sealed.metrics)
        assertEquals(MetricClass.MEASUREMENT, sealed.metrics!!.metricClass())

        val after = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(BenchmarkUiPhases.CONTENT, after.uiPhase)
        assertTrue(after.runs.any { it.outcome == MeasurementRunOutcomes.VALID })
    }

    @Test
    fun claimOrReturn_sameIdempotency_returnsExisting() = runBlocking {
        val profile = baseProfile()
        val jobId = "33333333-3333-3333-3333-333333333333"
        val runId = "44444444-4444-4444-4444-444444444444"
        val spec = StartBenchmarkSpec(
            jobId = jobId,
            runId = runId,
            profile = profile,
            command = command(idempotencyKey = "idem-same", digest = "1".repeat(64)),
        )
        val first = assertOk(api.startBenchmark(LocalUiPrincipal.ID, spec))
        assertTrue(first.createdNew)
        val second = assertOk(api.startBenchmark(LocalUiPrincipal.ID, spec))
        assertFalse(second.createdNew)
        assertEquals(first.jobId, second.jobId)
    }

    @Test
    fun multiRun_currentSelectedByRunSeqNotTimestamp() = runBlocking {
        val profile = baseProfile()
        val profileId = profile.profileId()

        // Fixed clock so timestamps collide — selection must use runSeq.
        var clock = 5_000L
        api = TestFixtures.api(clockMs = { clock })

        val job1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val run1 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = job1,
                    runId = run1,
                    profile = profile,
                    command = command(
                        commandId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                        idempotencyKey = "mr-1",
                        digest = "2".repeat(64),
                    ),
                ),
            ),
        )
        assertOk(api.completeRun(job1, sampleMetrics()))

        val job2 = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        val run2 = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = job2,
                    runId = run2,
                    profile = profile,
                    command = command(
                        commandId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                        idempotencyKey = "mr-2",
                        digest = "3".repeat(64),
                    ),
                ),
            ),
        )
        assertOk(api.completeRun(job2, sampleMetrics()))

        val current = assertOk(api.currentRun(LocalUiPrincipal.ID, profileId))
        assertNotNull(current)
        assertEquals(2L, current!!.runSeq)
        assertEquals(run2, current.runId)

        val listed = assertOk(api.listRuns(LocalUiPrincipal.ID, profileId))
        assertEquals(2, listed.size)
        assertTrue(listed[0].runSeq > listed[1].runSeq)
    }

    @Test
    fun profileIdentity_changesWhenDimensionChanges() {
        val a = baseProfile(contextLength = 1024)
        val b = baseProfile(contextLength = 2048)
        assertNotEquals(a.profileId(), b.profileId())
        assertEquals(a.profileId(), a.profileId()) // stable re-hash
    }

    @Test
    fun compare_incompatible_forbidsSingleRanking() = runBlocking {
        val left = baseProfile(engineBuildId = "eng-a")
        val right = baseProfile(engineBuildId = "eng-b", threads = 8)
        assertOk(
            api.planBenchmark(LocalUiPrincipal.ID, PlanBenchmarkSpec(profile = left)),
        )
        assertOk(
            api.planBenchmark(LocalUiPrincipal.ID, PlanBenchmarkSpec(profile = right)),
        )
        val cmp = assertOk(
            api.compareProfiles(
                LocalUiPrincipal.ID,
                left.profileId(),
                right.profileId(),
            ),
        )
        assertFalse(cmp.compatible)
        assertTrue(cmp.forbidSingleRanking)
        assertTrue("engineBuildId" in cmp.differingDimensions)
        assertTrue("threads" in cmp.differingDimensions)
    }

    @Test
    fun exportReport_isMeasurementNotTelemetry() = runBlocking {
        val profile = baseProfile()
        val jobId = "12121212-1212-1212-1212-121212121212"
        val runId = "34343434-3434-3434-3434-343434343434"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = jobId,
                    runId = runId,
                    profile = profile,
                    command = command(idempotencyKey = "exp-1", digest = "4".repeat(64)),
                ),
            ),
        )
        assertOk(api.completeRun(jobId, sampleMetrics()))

        val report = assertOk(
            api.exportReport(
                LocalUiPrincipal.ID,
                ExportReportSpec(
                    reportId = "report-1",
                    profileId = profile.profileId(),
                    command = command(
                        commandId = "56565656-5656-5656-5656-565656565656",
                        idempotencyKey = "exp-report",
                        digest = "5".repeat(64),
                    ),
                ),
            ),
        )
        assertEquals(MetricClass.MEASUREMENT, report.metricClass)
        assertFalse(report.includePromptOutput)
        BenchmarkReportBuilder.assertNotTelemetry(report)
        assertTrue(report.qrShareFields.containsKey("reportId"))
        assertFalse(report.qrShareFields.keys.any { it.contains("token", ignoreCase = true) })
    }

    @Test
    fun cancel_whileRunning_marksCancelled() = runBlocking {
        val profile = baseProfile()
        val jobId = "77777777-7777-7777-7777-777777777777"
        val runId = "88888888-8888-8888-8888-888888888888"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = jobId,
                    runId = runId,
                    profile = profile,
                    command = command(idempotencyKey = "cancel-1", digest = "6".repeat(64)),
                ),
            ),
        )
        val cancelled = assertOk(
            api.cancelBenchmark(
                LocalUiPrincipal.ID,
                CancelBenchmarkSpec(
                    jobId = jobId,
                    command = command(
                        commandId = "99999999-9999-9999-9999-999999999999",
                        idempotencyKey = "cancel-cmd",
                        digest = "7".repeat(64),
                    ),
                ),
            ),
        )
        assertEquals("CANCELLED", cancelled.state)

        val runs = assertOk(api.listRuns(LocalUiPrincipal.ID, profile.profileId()))
        assertTrue(runs.any { it.outcome == MeasurementRunOutcomes.CANCELLED })
    }

    @Test
    fun complete_afterCancel_fails() = runBlocking {
        val profile = baseProfile()
        val jobId = "10101010-1010-1010-1010-101010101010"
        val runId = "20202020-2020-2020-2020-202020202020"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = jobId,
                    runId = runId,
                    profile = profile,
                    command = command(idempotencyKey = "c2", digest = "8".repeat(64)),
                ),
            ),
        )
        assertOk(
            api.cancelBenchmark(
                LocalUiPrincipal.ID,
                CancelBenchmarkSpec(
                    jobId = jobId,
                    command = command(
                        commandId = "30303030-3030-3030-3030-303030303030",
                        idempotencyKey = "c2-cancel",
                        digest = "9".repeat(64),
                    ),
                ),
            ),
        )
        val r = api.completeRun(jobId, sampleMetrics())
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CANCELLED, (r as OmniResult.Err).error.code)
    }
}
