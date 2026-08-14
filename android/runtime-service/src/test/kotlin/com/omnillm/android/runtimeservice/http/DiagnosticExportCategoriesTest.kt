package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.diagnostics.api.CancelExportSpec
import com.omnillm.features.diagnostics.api.ClientInferenceIdentity
import com.omnillm.features.diagnostics.api.DeleteBundleSpec
import com.omnillm.features.diagnostics.api.DiagnosticsApi
import com.omnillm.features.diagnostics.api.DiagnosticsSnapshot
import com.omnillm.features.diagnostics.api.StartExportSpec
import com.omnillm.features.diagnostics.api.DiagnosticJobHandle
import com.omnillm.features.diagnostics.api.EvidencedMetricView
import com.omnillm.features.diagnostics.api.RedactionAllowlistExport
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.HttpJson
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.job.JobParameters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D23g wire-contract drift: DiagnosticExportRequest (spec :2600-2614) has an
 * optional `categories` array — the DTO never modeled it, so the wire field
 * was silently dropped and the export job never received the requested
 * categories.
 */
class DiagnosticExportCategoriesTest {

    private val principal = HttpPrincipal(
        principalId = "d23g-principal",
        tokenId = "tok-d23g",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val digest64 = "a".repeat(64)

    private fun uuid(): String = UUID.randomUUID().toString()

    private val diagnosticsStub = object : DiagnosticsApi {
        override suspend fun getSnapshot(principal: PrincipalId): OmniResult<DiagnosticsSnapshot> =
            OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override fun planExport(
            principal: PrincipalId,
            includeDetail: Boolean,
            selectedCategories: List<String>,
        ): OmniResult<DiagnosticExportPlan> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun startExport(
            principal: PrincipalId,
            spec: StartExportSpec,
        ): OmniResult<DiagnosticJobHandle> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun cancelExport(
            principal: PrincipalId,
            spec: CancelExportSpec,
        ): OmniResult<DiagnosticJobHandle> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun queryExportJob(
            principal: PrincipalId,
            jobId: String,
        ): OmniResult<DiagnosticJobHandle> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun getBundle(
            principal: PrincipalId,
            bundleId: String,
        ): OmniResult<DiagnosticBundleSnapshot> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun listBundles(principal: PrincipalId): OmniResult<List<DiagnosticBundleSnapshot>> =
            OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun collectAndSeal(
            jobId: String,
            forceFailClosed: Boolean,
        ): OmniResult<DiagnosticBundleSnapshot> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override suspend fun deleteBundle(
            principal: PrincipalId,
            spec: DeleteBundleSpec,
        ): OmniResult<DiagnosticBundleSnapshot> = OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override fun newClientInferenceIdentity(idempotencyKey: String?): ClientInferenceIdentity =
            throw UnsupportedOperationException("stub")
        override fun listEvidencedMetrics(principal: PrincipalId): OmniResult<List<EvidencedMetricView>> =
            OmniResult.err(OmniError.INTERNAL(message = "stub"))
        override fun exportRedactionAllowlist(principal: PrincipalId): OmniResult<RedactionAllowlistExport> =
            OmniResult.err(OmniError.INTERNAL(message = "stub"))
    }

    @Test
    fun diagnosticExport_categoriesInWire_passedToJobParameters() = runBlocking {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val jobs = JobManagerModule.createManager()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = jobs,
            diagnosticsApi = diagnosticsStub,
        )
        // Wire-shaped request: categories carried on the wire.
        val wire =
            """{"command":{"command_id":"${uuid()}","idempotency_key":"d23g-${uuid()}","canonical_input_digest":"$digest64"},"include_detail":true,"categories":["network","app_state","engine_logs"]}"""
        val req = HttpJson.codec.decodeFromString(
            com.omnillm.interfaces.http.DiagnosticExportRequestDto.serializer(),
            wire,
        )
        val r = h.createDiagnosticExport(principal, req)
        assertTrue("export job must be created: $r", r is HttpHandlerResult.Ok)
        val jobId = (r as HttpHandlerResult.Ok).body.jobId
        val record = jobs.query(com.omnillm.core.state.domain.JobId(jobId)) as OmniResult.Ok
        val params = record.value.parameters as JobParameters.DiagnosticExport
        assertEquals(
            "wire categories must reach the export job parameters",
            listOf("network", "app_state", "engine_logs"),
            params.categories,
        )
        assertTrue("include_detail must still pass through", params.includeDetail)
    }

    @Test
    fun diagnosticExport_withoutCategories_defaultsEmpty() = runBlocking {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val jobs = JobManagerModule.createManager()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = jobs,
            diagnosticsApi = diagnosticsStub,
        )
        val wire =
            """{"command":{"command_id":"${uuid()}","idempotency_key":"d23g-${uuid()}","canonical_input_digest":"$digest64"}}"""
        val req = HttpJson.codec.decodeFromString(
            com.omnillm.interfaces.http.DiagnosticExportRequestDto.serializer(),
            wire,
        )
        val r = h.createDiagnosticExport(principal, req) as HttpHandlerResult.Ok
        val record = jobs.query(com.omnillm.core.state.domain.JobId(r.body.jobId)) as OmniResult.Ok
        val params = record.value.parameters as JobParameters.DiagnosticExport
        assertEquals("categories is optional — absent means empty", emptyList<String>(), params.categories)
    }
}
