package com.omnillm.features.benchmark

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.benchmark.TestFixtures.assertOk
import com.omnillm.features.benchmark.TestFixtures.baseProfile
import com.omnillm.features.benchmark.TestFixtures.command
import com.omnillm.features.benchmark.TestFixtures.nominalEnv
import com.omnillm.features.benchmark.TestFixtures.sampleMetrics
import com.omnillm.features.benchmark.api.ExportReportSpec
import com.omnillm.features.benchmark.api.PlanBenchmarkSpec
import com.omnillm.features.benchmark.api.StartBenchmarkSpec
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.features.benchmark.policy.BenchmarkAuthPolicy
import com.omnillm.features.benchmark.policy.BenchmarkRoutingPolicy
import com.omnillm.features.benchmark.policy.ThermalDeviationPolicy
import com.omnillm.features.benchmark.ports.FixedBenchmarkCapabilityAvailabilityPort
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.observability.MetricClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-BENCHMARK policy + negative cases:
 * - capability negotiation fail closed (INV-018)
 * - LAN / report auth fail closed
 * - no long-lived secrets in QR
 * - report != telemetry
 * - never silent cross-revision fallback
 * - thermal INVALID/DEGRADED excluded from rollup
 */
class BenchmarkPolicyNegativeTest {

    // ------------------------------------------------------------------
    // Capability negotiation
    // ------------------------------------------------------------------

    @Test
    fun unsupportedRequiredCapability_failsClosedOnPlanAndStart() = runBlocking {
        val states = BenchmarkFeatureModule.REQUIRED_CAPABILITIES.associateWith {
            CapabilityState.SUPPORTED
        }.toMutableMap()
        states[CapabilityId.PERFORMANCE_MEASUREMENT] = CapabilityState.UNSUPPORTED
        val api = TestFixtures.api(
            capabilities = FixedBenchmarkCapabilityAvailabilityPort(states),
        )
        val plan = api.planBenchmark(
            LocalUiPrincipal.ID,
            PlanBenchmarkSpec(profile = baseProfile()),
        )
        assertTrue(plan is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (plan as OmniResult.Err).error.code)
        assertEquals(
            CapabilityId.PERFORMANCE_MEASUREMENT.id,
            plan.error.details["capabilityId"],
        )

        val start = api.startBenchmark(
            LocalUiPrincipal.ID,
            StartBenchmarkSpec(
                jobId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                runId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                profile = baseProfile(),
                command = command(),
            ),
        )
        assertTrue(start is OmniResult.Err)
        assertTrue((start as OmniResult.Err).error is OmniError.CAPABILITY_UNSUPPORTED)
    }

    @Test
    fun unknownRequiredCapability_failsClosedAsCapabilityUnknown() = runBlocking {
        val states = BenchmarkFeatureModule.REQUIRED_CAPABILITIES.associateWith {
            CapabilityState.SUPPORTED
        }.toMutableMap()
        states[CapabilityId.JOB_LIFECYCLE] = CapabilityState.UNKNOWN
        val api = TestFixtures.api(
            capabilities = FixedBenchmarkCapabilityAvailabilityPort(states),
        )
        val plan = api.planBenchmark(
            LocalUiPrincipal.ID,
            PlanBenchmarkSpec(profile = baseProfile()),
        )
        assertTrue(plan is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, (plan as OmniResult.Err).error.code)
        assertEquals(CapabilityId.JOB_LIFECYCLE.id, plan.error.details["capabilityId"])
    }

    @Test
    fun unknownFallbackPolicy_failsClosed() = runBlocking {
        val api = TestFixtures.api()
        val plan = api.planBenchmark(
            LocalUiPrincipal.ID,
            PlanBenchmarkSpec(
                profile = baseProfile(),
                fallbackPolicy = "NOT_A_POLICY",
            ),
        )
        assertTrue(plan is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (plan as OmniResult.Err).error.code)
        assertEquals("NOT_A_POLICY", plan.error.details["fallbackPolicy"])
    }

    // ------------------------------------------------------------------
    // LAN / report auth fail closed
    // ------------------------------------------------------------------

    @Test
    fun lanPrincipal_cannotStartBenchmark() {
        val lan = PrincipalId.parse("HTTP_LAN")
        val err = BenchmarkAuthPolicy.authorizeStart(lan, AccessProfile.LAN_CLIENT)
        assertNotNull(err)
        assertEquals(OmniErrorCode.FORBIDDEN, err!!.code)
        assertEquals("jobs.manage", err.details["scope"])
    }

    @Test
    fun appClient_cannotStartBenchmark() {
        val app = PrincipalId.parse("ANDROID_APP")
        val err = BenchmarkAuthPolicy.authorizeStart(app, AccessProfile.APP_CLIENT)
        assertNotNull(err)
        assertEquals(OmniErrorCode.FORBIDDEN, err!!.code)
    }

    @Test
    fun lanPrincipal_cannotExportReport() {
        val lan = PrincipalId.parse("HTTP_LAN")
        val err = BenchmarkAuthPolicy.authorizeReportExport(lan, AccessProfile.LAN_CLIENT)
        assertNotNull(err)
        assertEquals(OmniErrorCode.FORBIDDEN, err!!.code)
        assertEquals("report_not_for_lan_or_external", err.details["reason"])
    }

    @Test
    fun localUi_canStartAndExport() {
        assertNull(BenchmarkAuthPolicy.authorizeStart(LocalUiPrincipal.ID))
        assertNull(BenchmarkAuthPolicy.authorizeReportExport(LocalUiPrincipal.ID))
    }

    @Test
    fun exportReport_viaService_rejectsLanPrincipal() = runBlocking {
        val api = TestFixtures.api()
        val profile = baseProfile()
        // Seed a profile via plan as LOCAL_UI first.
        assertOk(api.planBenchmark(LocalUiPrincipal.ID, PlanBenchmarkSpec(profile = profile)))
        val lan = PrincipalId.parse("HTTP_LAN")
        val r = api.exportReport(
            lan,
            ExportReportSpec(
                reportId = "r1",
                profileId = profile.profileId(),
                command = command(idempotencyKey = "lan-export"),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as OmniResult.Err).error.code)
    }

    // ------------------------------------------------------------------
    // QR: no long-lived secrets
    // ------------------------------------------------------------------

    @Test
    fun qrPayload_rejectsTokenField() {
        val err = BenchmarkAuthPolicy.validateQrPayload(
            mapOf(
                "reportId" to "r1",
                "access_token" to "a".repeat(64),
            ),
        )
        assertNotNull(err)
        assertEquals(OmniErrorCode.FORBIDDEN, err!!.code)
        assertEquals("no_long_lived_secrets_in_qr", err.details["reason"])
    }

    @Test
    fun qrPayload_rejectsVerifierSecret() {
        val err = BenchmarkAuthPolicy.validateQrPayload(
            mapOf("verifier" to "secret-value", "challengeId" to "ch-1"),
        )
        assertNotNull(err)
        assertEquals("verifier", err!!.details["field"])
    }

    @Test
    fun safeReportQrFields_passValidation() {
        val fields = BenchmarkAuthPolicy.safeReportQrFields(
            reportId = "report-xyz",
            serverSpkiSha256 = "a".repeat(64),
            expiresAtEpochMs = 99_000L,
            connectionEpoch = 3L,
        )
        assertNull(BenchmarkAuthPolicy.validateQrPayload(fields))
        assertFalse(fields.keys.any { it.contains("token", ignoreCase = true) })
        assertFalse(fields.keys.any { it.contains("secret", ignoreCase = true) })
    }

    // ------------------------------------------------------------------
    // report != telemetry
    // ------------------------------------------------------------------

    @Test
    fun measurementMetrics_areMeasurementClass() {
        val m = sampleMetrics()
        assertEquals(MetricClass.MEASUREMENT, m.metricClass())
        assertFalse(m.metricClass() == MetricClass.OPERATIONAL)
    }

    // ------------------------------------------------------------------
    // Never silent cross-revision fallback
    // ------------------------------------------------------------------

    @Test
    fun crossRevision_underNone_failsClosed() {
        val profile = baseProfile(modelRevisionId = TestFixtures.REV_A)
        val env = nominalEnv(revision = TestFixtures.REV_B)
        val check = BenchmarkRoutingPolicy.validateActualAgainstProfile(
            profile = profile,
            actual = env,
            fallbackPolicy = com.omnillm.core.canonical.generated.FallbackPolicy.NONE,
        )
        assertTrue(check is BenchmarkRoutingPolicy.RoutingCheck.Fail)
        val err = (check as BenchmarkRoutingPolicy.RoutingCheck.Fail).error
        assertEquals(OmniErrorCode.INVALID_REQUEST, err.code)
        assertEquals("no_silent_cross_revision_fallback", err.details["reason"])
    }

    @Test
    fun crossRevision_allowList_notOnList_failsClosed() {
        val profile = baseProfile(modelRevisionId = TestFixtures.REV_A)
        val env = nominalEnv(revision = TestFixtures.REV_B)
        val check = BenchmarkRoutingPolicy.validateActualAgainstProfile(
            profile = profile,
            actual = env,
            fallbackPolicy = com.omnillm.core.canonical.generated.FallbackPolicy.ALLOW_LIST,
            allowedRevisionIds = setOf("c".repeat(64)),
        )
        assertTrue(check is BenchmarkRoutingPolicy.RoutingCheck.Fail)
        assertEquals(
            "revision_not_on_allowlist",
            (check as BenchmarkRoutingPolicy.RoutingCheck.Fail).error.details["reason"],
        )
    }

    @Test
    fun crossRevision_allowList_onList_degradesWithDisclosure() {
        val profile = baseProfile(modelRevisionId = TestFixtures.REV_A)
        val env = nominalEnv(revision = TestFixtures.REV_B)
        val check = BenchmarkRoutingPolicy.validateActualAgainstProfile(
            profile = profile,
            actual = env,
            fallbackPolicy = com.omnillm.core.canonical.generated.FallbackPolicy.ALLOW_LIST,
            allowedRevisionIds = setOf(TestFixtures.REV_B),
        )
        assertTrue(check is BenchmarkRoutingPolicy.RoutingCheck.Degraded)
        val dev = (check as BenchmarkRoutingPolicy.RoutingCheck.Degraded).deviations
        assertTrue(dev.any { it.startsWith("cross_revision_allowlist:") })
    }

    @Test
    fun completeRun_crossRevision_failsJob() = runBlocking {
        val env = nominalEnv(revision = TestFixtures.REV_B)
        val api = TestFixtures.api(env = env)
        val profile = baseProfile(modelRevisionId = TestFixtures.REV_A)
        val jobId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01"
        val runId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbb01"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = jobId,
                    runId = runId,
                    profile = profile,
                    fallbackPolicy = "NONE",
                    command = command(idempotencyKey = "xr-1", digest = "a".repeat(64)),
                ),
            ),
        )
        val r = api.completeRun(jobId, sampleMetrics())
        assertTrue(r is OmniResult.Err)
        assertEquals(
            "no_silent_cross_revision_fallback",
            (r as OmniResult.Err).error.details["reason"],
        )
        val runs = assertOk(api.listRuns(LocalUiPrincipal.ID, profile.profileId()))
        assertTrue(runs.any { it.outcome == MeasurementRunOutcomes.FAILED })
    }

    // ------------------------------------------------------------------
    // Thermal deviation
    // ------------------------------------------------------------------

    @Test
    fun thermalHardBreach_marksInvalid_notRollupEligible() {
        val profile = baseProfile(thermalCeilingCelsius = 40)
        val env = nominalEnv(thermalCelsius = 50, thermalState = "NOMINAL")
        val decision = ThermalDeviationPolicy.evaluate(profile, env)
        assertEquals(MeasurementRunOutcomes.INVALID, decision.outcome)
        assertTrue(decision.reasons.any { it.startsWith("thermal_hard_breach") })
        assertFalse(ThermalDeviationPolicy.mayRollup(decision.outcome))
    }

    @Test
    fun thermalSoftBreach_marksDegraded_notRollupEligible() {
        val profile = baseProfile(thermalCeilingCelsius = 40)
        val env = nominalEnv(thermalCelsius = 42, thermalState = "WARNING")
        val decision = ThermalDeviationPolicy.evaluate(profile, env)
        assertEquals(MeasurementRunOutcomes.DEGRADED, decision.outcome)
        assertFalse(MeasurementRunOutcomes.isRollupEligible(decision.outcome))
    }

    @Test
    fun thermalNominal_isValidAndRollupEligible() {
        val profile = baseProfile(thermalCeilingCelsius = 45)
        val env = nominalEnv(thermalCelsius = 35, thermalState = "NOMINAL")
        val decision = ThermalDeviationPolicy.evaluate(profile, env)
        assertEquals(MeasurementRunOutcomes.VALID, decision.outcome)
        assertTrue(ThermalDeviationPolicy.mayRollup(decision.outcome))
    }

    @Test
    fun completeRun_thermalHardBreach_outcomeInvalid() = runBlocking {
        val env = nominalEnv(thermalCelsius = 60, thermalState = "SEVERE")
        val api = TestFixtures.api(env = env)
        val profile = baseProfile(thermalCeilingCelsius = 40)
        val jobId = "cccccccc-cccc-cccc-cccc-cccccccccc01"
        val runId = "dddddddd-dddd-dddd-dddd-dddddddddd01"
        assertOk(
            api.startBenchmark(
                LocalUiPrincipal.ID,
                StartBenchmarkSpec(
                    jobId = jobId,
                    runId = runId,
                    profile = profile,
                    command = command(idempotencyKey = "th-1", digest = "b".repeat(64)),
                ),
            ),
        )
        val sealed = assertOk(api.completeRun(jobId, sampleMetrics()))
        assertEquals(MeasurementRunOutcomes.INVALID, sealed.outcome)
        assertTrue(sealed.deviationReasons.isNotEmpty())
        assertFalse(MeasurementRunOutcomes.isRollupEligible(sealed.outcome))
    }

    @Test
    fun backendFallback_underNone_failsClosed() {
        val profile = baseProfile(backend = "cpu")
        val env = nominalEnv(backend = "gpu")
        val check = BenchmarkRoutingPolicy.validateActualAgainstProfile(
            profile = profile,
            actual = env,
            fallbackPolicy = com.omnillm.core.canonical.generated.FallbackPolicy.NONE,
        )
        assertTrue(check is BenchmarkRoutingPolicy.RoutingCheck.Fail)
    }
}
