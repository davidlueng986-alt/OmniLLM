package com.omnillm.features.diagnostics

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.diagnostics.api.CancelExportSpec
import com.omnillm.features.diagnostics.api.DiagnosticsCommandIdentity
import com.omnillm.features.diagnostics.api.StartExportSpec
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.features.diagnostics.ports.CapabilityAvailabilityPort
import com.omnillm.features.diagnostics.ports.InMemoryDiagnosticSourcePort
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.job.JobManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-DIAGNOSTICS: capability negotiation unsupported paths + cancel
 * (INV-018 fail closed, ADR-004/005 cancel, FEAT-DIAGNOSTICS acceptance §3).
 */
class CapabilityNegotiationAndCancelTest {

    private val digest = "c".repeat(64)

    @Test
    fun unsupportedRequiredCapability_failsClosedOnPlanAndStart() = runBlocking {
        val api = DiagnosticsModule.createApi(
            jobManager = JobManagerModule.createManager(),
            observability = ObservabilityModule.createFacade(),
            sources = InMemoryDiagnosticSourcePort(),
            capabilityAvailability = FixedCapabilityPort(
                mapOf(CapabilityId.DIAGNOSTIC_REASONING to CapabilityState.UNSUPPORTED),
            ),
        )

        val plan = api.planExport(LocalUiPrincipal.ID)
        assertTrue(plan is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (plan as OmniResult.Err).error.code)
        assertEquals(
            CapabilityId.DIAGNOSTIC_REASONING.id,
            plan.error.details["capabilityId"],
        )

        val start = api.startExport(
            LocalUiPrincipal.ID,
            StartExportSpec(
                jobId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                bundleId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                categories = listOf("RUNTIME_VERSIONS"),
                command = DiagnosticsCommandIdentity(
                    commandId = "cccccccc-cccc-cccc-cccc-cccccccccccc",
                    idempotencyKey = "cap-unsup-1",
                    canonicalInputDigest = digest,
                ),
            ),
        )
        assertTrue(start is OmniResult.Err)
        assertTrue((start as OmniResult.Err).error is OmniError.CAPABILITY_UNSUPPORTED)
    }

    @Test
    fun unknownRequiredCapability_failsClosedAsCapabilityUnknown() = runBlocking {
        val api = DiagnosticsModule.createApi(
            jobManager = JobManagerModule.createManager(),
            observability = ObservabilityModule.createFacade(),
            capabilityAvailability = FixedCapabilityPort(
                mapOf(CapabilityId.REQUEST_TRACE to CapabilityState.UNKNOWN),
            ),
        )
        val plan = api.planExport(LocalUiPrincipal.ID)
        assertTrue(plan is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, (plan as OmniResult.Err).error.code)
        assertEquals(CapabilityId.REQUEST_TRACE.id, plan.error.details["capabilityId"])
    }

    @Test
    fun unknownCategory_failsClosedAsCapabilityUnsupported() = runBlocking {
        val api = DiagnosticsModule.createApi(
            jobManager = JobManagerModule.createManager(),
            observability = ObservabilityModule.createFacade(),
        )
        val plan = api.planExport(
            principal = LocalUiPrincipal.ID,
            selectedCategories = listOf("NOT_A_REAL_CATEGORY"),
        )
        assertTrue(plan is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (plan as OmniResult.Err).error.code)
        assertEquals("NOT_A_REAL_CATEGORY", plan.error.details["category"])
    }

    @Test
    fun cancel_whileCollecting_doesNotMarkPartialReady() = runBlocking {
        val jobManager = JobManagerModule.createManager()
        val api = DiagnosticsModule.createApi(
            jobManager = jobManager,
            observability = ObservabilityModule.createFacade(clockWallMs = { 5_000L }),
            sources = InMemoryDiagnosticSourcePort(
                runtimeVersions = mapOf(
                    "appVersion" to "1",
                    "runtimeBuildId" to "rb",
                    "platformOs" to "android",
                    "platformApiLevel" to "36",
                ),
            ),
            clockMs = { 5_000L },
        )

        val jobId = "12121212-1212-1212-1212-121212121212"
        val bundleId = "34343434-3434-3434-3434-343434343434"
        assertOk(
            api.startExport(
                LocalUiPrincipal.ID,
                StartExportSpec(
                    jobId = jobId,
                    bundleId = bundleId,
                    categories = listOf("RUNTIME_VERSIONS", "EVENTS_TRACES"),
                    command = DiagnosticsCommandIdentity(
                        commandId = "56565656-5656-5656-5656-565656565656",
                        idempotencyKey = "collect-cancel-1",
                        canonicalInputDigest = digest,
                    ),
                ),
            ),
        )

        // In-progress collecting snapshot is COLLECTING without seal.
        val before = assertOk(api.getBundle(LocalUiPrincipal.ID, bundleId))
        assertEquals(DiagnosticBundleStates.COLLECTING, before.state)
        assertFalse(before.isShareable)

        val cancelled = assertOk(
            api.cancelExport(
                LocalUiPrincipal.ID,
                CancelExportSpec(
                    jobId = jobId,
                    command = DiagnosticsCommandIdentity(
                        commandId = "78787878-7878-7878-7878-787878787878",
                        idempotencyKey = "collect-cancel-cmd",
                        canonicalInputDigest = "d".repeat(64),
                    ),
                ),
            ),
        )
        assertEquals("CANCELLED", cancelled.state)

        val after = assertOk(api.getBundle(LocalUiPrincipal.ID, bundleId))
        assertEquals(DiagnosticBundleStates.CANCELLED, after.state)
        assertTrue(after.files.isEmpty())
        assertTrue(after.manifestDigest == null)
        assertFalse(after.isShareable)

        // Seal after cancel must fail closed.
        val seal = api.collectAndSeal(jobId)
        assertTrue(seal is OmniResult.Err)
        assertEquals(OmniErrorCode.CANCELLED, (seal as OmniResult.Err).error.code)

        val snap = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(DiagnosticUiPhases.CANCELLED, snap.uiPhase)
    }

    @Test
    fun cancel_requestOnly_onRunning_marksCancelRequestedWithoutTerminal() = runBlocking {
        val jobManager: JobManager = JobManagerModule.createManager()
        val api = DiagnosticsModule.createApi(
            jobManager = jobManager,
            observability = ObservabilityModule.createFacade(),
            clockMs = { 9_000L },
        )
        val jobId = "9a9a9a9a-9a9a-9a9a-9a9a-9a9a9a9a9a9a"
        val bundleId = "8b8b8b8b-8b8b-8b8b-8b8b-8b8b8b8b8b8b"
        assertOk(
            api.startExport(
                LocalUiPrincipal.ID,
                StartExportSpec(
                    jobId = jobId,
                    bundleId = bundleId,
                    categories = listOf("RUNTIME_VERSIONS"),
                    command = DiagnosticsCommandIdentity(
                        commandId = "7c7c7c7c-7c7c-7c7c-7c7c-7c7c7c7c7c7c",
                        idempotencyKey = "req-only-1",
                        canonicalInputDigest = digest,
                    ),
                ),
            ),
        )
        val soft = assertOk(
            api.cancelExport(
                LocalUiPrincipal.ID,
                CancelExportSpec(
                    jobId = jobId,
                    requestOnly = true,
                    command = DiagnosticsCommandIdentity(
                        commandId = "6d6d6d6d-6d6d-6d6d-6d6d-6d6d6d6d6d6d",
                        idempotencyKey = "req-only-cmd",
                        canonicalInputDigest = "e".repeat(64),
                    ),
                ),
            ),
        )
        assertTrue(soft.cancelRequested)
        assertEquals("RUNNING", soft.state)

        // Hard cancel finishes the job.
        val hard = assertOk(
            api.cancelExport(
                LocalUiPrincipal.ID,
                CancelExportSpec(
                    jobId = jobId,
                    requestOnly = false,
                    command = DiagnosticsCommandIdentity(
                        commandId = "5e5e5e5e-5e5e-5e5e-5e5e-5e5e5e5e5e5e",
                        idempotencyKey = "hard-cancel-cmd",
                        canonicalInputDigest = "f".repeat(64),
                    ),
                ),
            ),
        )
        assertEquals("CANCELLED", hard.state)
    }

    @Test
    fun temporarilyUnavailable_capability_mapsToCapabilityUnknown() = runBlocking {
        val api = DiagnosticsModule.createApi(
            jobManager = JobManagerModule.createManager(),
            observability = ObservabilityModule.createFacade(),
            capabilityAvailability = FixedCapabilityPort(
                mapOf(CapabilityId.EVIDENCE_LABELING to CapabilityState.TEMPORARILY_UNAVAILABLE),
            ),
        )
        val start = api.startExport(
            LocalUiPrincipal.ID,
            StartExportSpec(
                jobId = "1f1f1f1f-1f1f-1f1f-1f1f-1f1f1f1f1f1f",
                bundleId = "2e2e2e2e-2e2e-2e2e-2e2e-2e2e2e2e2e2e",
                command = DiagnosticsCommandIdentity(
                    commandId = "3d3d3d3d-3d3d-3d3d-3d3d-3d3d3d3d3d3d",
                    idempotencyKey = "temp-unavail",
                    canonicalInputDigest = digest,
                ),
            ),
        )
        assertTrue(start is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, (start as OmniResult.Err).error.code)
    }

    private class FixedCapabilityPort(
        private val overrides: Map<CapabilityId, CapabilityState>,
    ) : CapabilityAvailabilityPort {
        override fun resolve(capabilityId: CapabilityId): CapabilityState {
            overrides[capabilityId]?.let { return it }
            return if (capabilityId in DiagnosticsModule.REQUIRED_CAPABILITIES) {
                CapabilityState.SUPPORTED
            } else {
                CapabilityState.UNKNOWN
            }
        }
    }

    private fun <T> assertOk(result: OmniResult<T>): T {
        assertTrue("expected Ok, got $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }
}
