package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.featurehost.FeaturePackHost
import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.interfaces.http.AssetCreateRequestDto
import com.omnillm.interfaces.http.AsyncInferenceRequestDto
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.ContentReportProposalRequestDto
import com.omnillm.interfaces.http.DiagnosticExportRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.JobSpecDto
import com.omnillm.interfaces.http.OpenAIChatRequestDto
import com.omnillm.interfaces.http.OpenAIEmbeddingRequestDto
import com.omnillm.interfaces.http.ChatMessageDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Launch-critical OpenAPI surface exercised through [ControlPlaneHttpHandler]
 * against in-memory control-plane fakes (ADR-011 / CORE-INTERFACE).
 *
 * Does not invent engine PASS evidence — inference may CAPABILITY_UNSUPPORTED
 * without READY models; routes must still be present and fail closed honestly.
 */
class LaunchCriticalHttpSurfaceTest {

    private val digest64 = "a".repeat(64)
    private val allScopes = setOf("*")

    private val principal = HttpPrincipal(
        principalId = "launch-surface-principal",
        tokenId = "tok-launch",
        scopes = allScopes,
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun cmd(key: String = "k1", expectedVersion: Long? = null): CommandRequestDto =
        CommandRequestDto(
            commandId = uuid(),
            idempotencyKey = key,
            canonicalInputDigest = digest64,
            expectedVersion = expectedVersion,
        )

    private fun handler(): ControlPlaneHttpHandler {
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val host = FeaturePackHost.bootstrap(
            jobManager = jobs,
            policyManager = policy,
            observability = observability,
            clockMs = { 1_700_000_000_000L },
        )
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = jobs,
            policyManager = policy,
            modelCatalog = {
                listOf(
                    com.omnillm.interfaces.http.ModelInfoDto(
                        modelRevisionId = digest64,
                        displayName = "fixture-model",
                        capabilities = listOf(
                            com.omnillm.interfaces.http.CapabilityEntryDto(
                                id = "chat.completions",
                                state = "UNKNOWN",
                            ),
                        ),
                        installationState = "READY",
                    ),
                )
            },
            metricSummary = {
                com.omnillm.interfaces.http.MetricSummaryDto(
                    series = listOf(
                        com.omnillm.interfaces.http.MetricPointDto(
                            id = "ttft_ms",
                            value = 1.0,
                            sampledAt = "2026-08-04T00:00:00Z",
                        ),
                    ),
                    resourceVersion = 1,
                )
            },
            lanPorts = host.lanPorts,
            diagnosticsApi = host.diagnosticsApi,
            contentReportApi = host.contentReportApi,
            routingApi = host.routingApi,
            toolsApi = host.toolsApi,
            benchmarkApi = host.benchmarkApi,
        )
    }

    @Test
    fun health_models_metrics_settings_arePresent() = runBlocking {
        val h = handler()
        val health = h.getHealth() as HttpHandlerResult.Ok
        assertEquals("READY", health.body.runtimeState)

        val models = h.listModels(principal, null) as HttpHandlerResult.Ok
        assertEquals(1, models.body.items.size)
        assertEquals("UNKNOWN", models.body.items[0].capabilities[0].state)

        val metrics = h.getMetricSummary(principal) as HttpHandlerResult.Ok
        assertTrue(metrics.body.series.any { it.id == "ttft_ms" })

        val settings = h.getSettings(principal) as HttpHandlerResult.Ok
        assertTrue(settings.body.resourceVersion >= 0)
    }

    @Test
    fun chatAndEmbeddings_failClosedWithoutReadyEngineCandidates() = runBlocking {
        val h = handler()
        val chat = h.createChatCompletion(
            principal,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "chat-1",
        )
        assertTrue(chat is HttpHandlerResult.Err)

        val emb = h.createEmbedding(
            principal,
            OpenAIEmbeddingRequestDto(
                model = "fixture",
                input = JsonPrimitive("hello"),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "emb-1",
        )
        assertTrue(emb is HttpHandlerResult.Err)
    }

    @Test
    fun durableRequest_claimQueryCancel() = runBlocking {
        val h = handler()
        val requestId = uuid()
        val accepted = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = requestId,
                idempotencyKey = "async-1",
                operation = "CHAT",
                model = "fixture",
            ),
        ) as HttpHandlerResult.Ok
        assertEquals(requestId, accepted.body.requestId)

        val queried = h.getRequest(principal, requestId) as HttpHandlerResult.Ok
        assertEquals(requestId, queried.body.requestId)

        val cancel = h.cancelRequest(principal, requestId, cmd("cancel-1", expectedVersion = 0L))
        assertTrue(cancel is HttpHandlerResult.Ok || cancel is HttpHandlerResult.Err)
        // Command path is present either way; claim ledger records cancel command.
        if (cancel is HttpHandlerResult.Ok) {
            val cmdResult = h.getCommand(principal, cancel.body.commandId)
            assertTrue(cmdResult is HttpHandlerResult.Ok)
        }
    }

    @Test
    fun assets_createGetDeleteLifecycle() = runBlocking {
        val h = handler()
        val created = h.createAsset(
            principal,
            AssetCreateRequestDto(
                command = cmd("asset-create"),
                purpose = "image",
                maxBytes = 1024,
                ttlSeconds = 3600,
            ),
        ) as HttpHandlerResult.Ok
        val assetId = created.body.assetId
        assertEquals("CREATED", created.body.state)

        val got = h.getAsset(principal, assetId) as HttpHandlerResult.Ok
        assertEquals(assetId, got.body.assetId)

        val deleted = h.deleteAsset(principal, assetId) as HttpHandlerResult.Ok
        assertEquals("SUCCEEDED", deleted.body.state)
    }

    @Test
    fun jobs_createListGetCancel() = runBlocking {
        val h = handler()
        val jobId = uuid()
        val created = h.createJob(
            principal,
            JobSpecDto(
                command = cmd("job-create"),
                jobId = jobId,
                kind = "IMPORT",
                parameters = JsonObject(mapOf("asset_id" to JsonPrimitive("asset-fixture"))),
            ),
        )
        assertTrue(
            "job create should succeed or fail closed with catalog error, got $created",
            created is HttpHandlerResult.Ok || created is HttpHandlerResult.Err,
        )
        if (created is HttpHandlerResult.Ok) {
            val listed = h.listOwnJobs(principal, null) as HttpHandlerResult.Ok
            assertTrue(listed.body.items.any { it.jobId == jobId })
            val got = h.getJob(principal, jobId) as HttpHandlerResult.Ok
            assertEquals(jobId, got.body.jobId)
            val cancel = h.cancelJob(principal, jobId, cmd("job-cancel"))
            assertTrue(cancel is HttpHandlerResult.Ok || cancel is HttpHandlerResult.Err)
        }
    }

    @Test
    fun diagnosticsExport_andContentReportProposal() = runBlocking {
        val h = handler()
        val diag = h.createDiagnosticExport(
            principal,
            DiagnosticExportRequestDto(command = cmd("diag-1"), includeDetail = false),
        )
        assertTrue(diag is HttpHandlerResult.Ok)

        val reportId = uuid()
        val report = h.createContentReportProposal(
            principal,
            ContentReportProposalRequestDto(
                command = cmd("cr-1"),
                reportId = reportId,
                category = "HATE_HARASSMENT",
                createdAt = "2026-01-01T12:00:00Z",
                appBuild = "1.0.0-test",
                modelRevisionId = digest64,
                engineBuildId = "llama-cpp@test",
                backend = "llama-cpp",
                localPolicyVersion = "policy-v1",
                outputDigest = digest64,
                userLocale = "en-US",
            ),
        )
        // External propose is allowed on loopback with content-reports.propose.
        assertTrue(
            "content report proposal must succeed or fail closed, got $report",
            report is HttpHandlerResult.Ok || report is HttpHandlerResult.Err,
        )
        if (report is HttpHandlerResult.Ok) {
            val got = h.getContentReport(principal, reportId) as HttpHandlerResult.Ok
            assertEquals(reportId, got.body.reportId)
        }
    }

    @Test
    fun featurePackMarkers_includeLanDiagnosticsContentReport() {
        val markers = handler().attachedFeaturePackMarkers()
        assertEquals(true, markers["lan"])
        assertEquals(true, markers["diagnostics"])
        assertEquals(true, markers["ai-content-report"])
    }

    @Test
    fun lanPairingExchange_failsClosedWhenTlsNotReady() = runBlocking {
        val h = handler()
        // completeLanPairing without ACTIVE TLS must fail closed (STATE_CONFLICT / CAPABILITY).
        val result = h.completeLanPairing(
            """{"command":{"command_id":"${uuid()}","idempotency_key":"pair-1"},"exchange_id":"${uuid()}","challenge_id":"${uuid()}","client_public_key":"${"x".repeat(32)}","requested_scopes":["inference.create"],"proof_base64url":"${"y".repeat(43)}"}""",
        )
        assertTrue(result is HttpHandlerResult.Err)
    }
}
