package com.omnillm.features.diagnostics

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.diagnostics.api.CancelExportSpec
import com.omnillm.features.diagnostics.api.DiagnosticsCommandIdentity
import com.omnillm.features.diagnostics.api.StartExportSpec
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases
import com.omnillm.features.diagnostics.export.DiagnosticBundleBuilder
import com.omnillm.features.diagnostics.ports.CapabilityAvailabilityPort
import com.omnillm.features.diagnostics.ports.InMemoryDiagnosticSourcePort
import com.omnillm.features.diagnostics.viewmodel.DiagnosticsViewModel
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.observability.MetricSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-DIAGNOSTICS happy path, redaction, client request ids, UX phases.
 */
class DiagnosticsServiceTest {

    private lateinit var api: com.omnillm.features.diagnostics.api.DiagnosticsApi
    private val digestA = "a".repeat(64)
    private val digestB = "b".repeat(64)

    @Before
    fun setUp() {
        val sources = InMemoryDiagnosticSourcePort(
            runtimeVersions = mapOf(
                "appVersion" to "1.0.0-test",
                "runtimeBuildId" to "rb-test",
                "platformOs" to "android",
                "platformApiLevel" to "36",
            ),
            configuration = mapOf(
                "policyVersion" to "pv-1",
                "runtimeEpoch" to "1",
                "resourceCapsSummary" to "ok",
                "featureFlagsSummary" to "diag",
            ),
            requestJobs = listOf(
                mapOf(
                    "requestId" to "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "state" to "FAILED",
                    "phase" to "execute",
                    "errorCode" to "INTERNAL",
                    "principalClass" to "LOCAL_UI",
                    "operationKind" to "chat.completions",
                ),
            ),
            metrics = listOf(
                MetricSample(
                    name = "request.error_count",
                    value = 2.0,
                    unit = "count",
                    evidenceLabel = EvidenceLabel.MEASURED,
                    sampledAtEpochMs = 100L,
                ),
            ),
        )
        api = DiagnosticsModule.createApi(
            jobManager = JobManagerModule.createManager(),
            observability = ObservabilityModule.createFacade(clockWallMs = { 1_000L }),
            sources = sources,
            clockMs = { 1_000L },
        )
    }

    @Test
    fun snapshot_startsEmpty() = runBlocking {
        val snap = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(DiagnosticUiPhases.EMPTY, snap.uiPhase)
        assertTrue(snap.isEmpty)
        assertTrue(snap.bundles.isEmpty())
    }

    @Test
    fun plan_thenStart_collectSeal_reachesReady() = runBlocking {
        val plan = assertOk(
            api.planExport(
                principal = LocalUiPrincipal.ID,
                includeDetail = false,
                selectedCategories = listOf("RUNTIME_VERSIONS", "CONFIGURATION", "REQUEST_JOB_STATE"),
            ),
        )
        assertTrue(plan.categories.isNotEmpty())
        assertTrue(plan.estimatedTotalBytes > 0L)
        assertEquals("diagnostics.share.irreversible", plan.shareIrreversibleNoticeKey)

        val jobId = "11111111-1111-1111-1111-111111111111"
        val bundleId = "22222222-2222-2222-2222-222222222222"
        val handle = assertOk(
            api.startExport(
                LocalUiPrincipal.ID,
                StartExportSpec(
                    jobId = jobId,
                    bundleId = bundleId,
                    includeDetail = false,
                    categories = listOf("RUNTIME_VERSIONS", "CONFIGURATION", "REQUEST_JOB_STATE"),
                    ttlSeconds = 3_600,
                    shareIrreversibleAcknowledged = true,
                    command = DiagnosticsCommandIdentity(
                        commandId = "33333333-3333-3333-3333-333333333333",
                        idempotencyKey = "export-1",
                        canonicalInputDigest = digestA,
                    ),
                ),
            ),
        )
        assertTrue(handle.createdNew)
        assertEquals("DIAGNOSTIC_EXPORT", handle.kind)
        assertEquals("RUNNING", handle.state)

        val mid = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertEquals(DiagnosticUiPhases.LOADING, mid.uiPhase)

        val sealed = assertOk(api.collectAndSeal(jobId))
        assertEquals(DiagnosticBundleStates.READY, sealed.state)
        assertNotNull(sealed.manifestDigest)
        assertTrue(DiagnosticBundleBuilder.verifyIntegrity(sealed))
        assertTrue(sealed.shareIrreversibleDisclosed)

        // Payload must not carry prompt material; allowlist schema may name "prompt".
        val payloadText = sealed.files
            .filter { it.pathRole != DiagnosticBundleBuilder.REDACTION_ALLOWLIST_PATH_ROLE }
            .joinToString("\n") { it.redactedUtf8.orEmpty() }
        assertFalse(payloadText.lines().any { it.startsWith("prompt=") })
        assertFalse(payloadText.contains("secret user prompt"))
        assertTrue(payloadText.contains("appVersion") || payloadText.contains("1.0.0-test"))

        val after = assertOk(api.getSnapshot(LocalUiPrincipal.ID))
        assertTrue(
            after.uiPhase == DiagnosticUiPhases.CONTENT ||
                after.uiPhase == DiagnosticUiPhases.DEGRADED,
        )
        assertTrue(after.bundles.any { it.state == DiagnosticBundleStates.READY })
    }

    @Test
    fun claimOrReturn_sameIdempotency_returnsExisting() = runBlocking {
        val jobId = "44444444-4444-4444-4444-444444444444"
        val bundleId = "55555555-5555-5555-5555-555555555555"
        val spec = StartExportSpec(
            jobId = jobId,
            bundleId = bundleId,
            categories = listOf("RUNTIME_VERSIONS"),
            command = DiagnosticsCommandIdentity(
                commandId = "66666666-6666-6666-6666-666666666666",
                idempotencyKey = "export-idem-1",
                canonicalInputDigest = digestB,
            ),
        )
        val first = assertOk(api.startExport(LocalUiPrincipal.ID, spec))
        assertTrue(first.createdNew)
        val second = assertOk(api.startExport(LocalUiPrincipal.ID, spec))
        assertFalse(second.createdNew)
        assertEquals(first.jobId, second.jobId)
    }

    @Test
    fun clientInferenceIdentity_isUuidAndUnique() {
        val a = api.newClientInferenceIdentity("inf-key-1")
        val b = api.newClientInferenceIdentity("inf-key-2")
        assertNotEquals(a.requestId, b.requestId)
        assertTrue(a.requestId.matches(Regex("^[0-9a-f-]{36}$")))
        assertEquals("inf-key-1", a.idempotencyKey)
    }

    @Test
    fun metrics_carryEvidenceLabels() {
        val metrics = assertOk(api.listEvidencedMetrics(LocalUiPrincipal.ID))
        assertTrue(metrics.isNotEmpty())
        assertTrue(metrics.all { it.evidenceLabel in EvidenceLabel.entries.toSet() })
        val measured = metrics.first { it.name == "request.error_count" }
        assertEquals(EvidenceLabel.MEASURED, measured.evidenceLabel)
        assertEquals(2.0, measured.value!!, 0.0)
    }

    @Test
    fun viewModel_emptyLoadingContentActions() = runBlocking {
        val vm = DiagnosticsViewModel(api)
        assertTrue(vm.uiState().isEmpty)
        assertTrue("plan-export" in vm.uiState().actions)

        vm.planExport(selectedCategories = listOf("RUNTIME_VERSIONS"))
        assertEquals(DiagnosticUiPhases.PREVIEW, vm.uiState().uiPhase)

        val jobId = "77777777-7777-7777-7777-777777777777"
        val bundleId = "88888888-8888-8888-8888-888888888888"
        assertOk(
            vm.startExport(
                StartExportSpec(
                    jobId = jobId,
                    bundleId = bundleId,
                    categories = listOf("RUNTIME_VERSIONS"),
                    shareIrreversibleAcknowledged = true,
                    command = DiagnosticsCommandIdentity(
                        commandId = "99999999-9999-9999-9999-999999999999",
                        idempotencyKey = "vm-export-1",
                        canonicalInputDigest = digestA,
                    ),
                ),
            ),
        )
        assertTrue(vm.uiState().isLoading)
        assertTrue("cancel" in vm.uiState().actions)

        assertOk(vm.collectAndSeal(jobId))
        assertTrue(
            vm.uiState().uiPhase == DiagnosticUiPhases.CONTENT ||
                vm.uiState().uiPhase == DiagnosticUiPhases.DEGRADED,
        )
        assertTrue("share" in vm.uiState().actions)
    }

    @Test
    fun cancel_fromQueuedOrRunning_leavesNonReadyBundle() = runBlocking {
        val jobId = "abababab-abab-abab-abab-abababababab"
        val bundleId = "cdcdcdcd-cdcd-cdcd-cdcd-cdcdcdcdcdcd"
        assertOk(
            api.startExport(
                LocalUiPrincipal.ID,
                StartExportSpec(
                    jobId = jobId,
                    bundleId = bundleId,
                    categories = listOf("RUNTIME_VERSIONS"),
                    command = DiagnosticsCommandIdentity(
                        commandId = "efefefef-efef-efef-efef-efefefefefef",
                        idempotencyKey = "cancel-export-1",
                        canonicalInputDigest = digestA,
                    ),
                ),
            ),
        )
        val cancelled = assertOk(
            api.cancelExport(
                LocalUiPrincipal.ID,
                CancelExportSpec(
                    jobId = jobId,
                    command = DiagnosticsCommandIdentity(
                        commandId = "01010101-0101-0101-0101-010101010101",
                        idempotencyKey = "cancel-cmd-1",
                        canonicalInputDigest = digestB,
                    ),
                ),
            ),
        )
        assertEquals("CANCELLED", cancelled.state)

        val bundle = assertOk(api.getBundle(LocalUiPrincipal.ID, bundleId))
        assertEquals(DiagnosticBundleStates.CANCELLED, bundle.state)
        assertNullSafeManifest(bundle.manifestDigest)
        assertFalse(bundle.isShareable)
    }

    private fun assertNullSafeManifest(digest: String?) {
        // Partial/cancelled must not carry a sealed manifest.
        assertTrue(digest == null)
    }

    private fun <T> assertOk(result: OmniResult<T>): T {
        assertTrue("expected Ok, got $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }
}
