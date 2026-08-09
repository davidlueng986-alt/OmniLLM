package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
import com.omnillm.android.runtimeservice.featurehost.FeaturePackHost
import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.ExploratoryInferenceSource
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.interfaces.http.AssetCreateRequestDto
import com.omnillm.interfaces.http.AsyncInferenceRequestDto
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.ContentReportProposalRequestDto
import com.omnillm.interfaces.http.DiagnosticExportRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.JobSpecDto
import com.omnillm.interfaces.http.NativeChatPayloadDto
import com.omnillm.interfaces.http.OpenAIChatRequestDto
import com.omnillm.interfaces.http.OpenAIEmbeddingRequestDto
import com.omnillm.interfaces.http.ChatMessageDto
import com.omnillm.interfaces.http.SettingsPatchDto
import com.omnillm.interfaces.http.SseHandlerResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.modelmanager.ModelManagerModule
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Launch-critical OpenAPI surface exercised through [ControlPlaneHttpHandler]
 * against in-memory control-plane fakes (ADR-011 / CORE-INTERFACE).
 *
 * TST-01: every route pins its EXACT success/failure code — no tautological
 * `Ok || Err` assertions. Covers SSE termination, STATE_CONFLICT /
 * IDEMPOTENCY_CONFLICT, resource-version conflicts and asset TTL expiry.
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

    private fun otherPrincipal(scopes: Set<String> = setOf("inference.read-own")) = HttpPrincipal(
        principalId = "other-principal",
        tokenId = "tok-other",
        scopes = scopes,
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

    private fun assertErrCode(result: HttpHandlerResult<*>, code: OmniErrorCode, context: String) {
        assertTrue(
            "$context must fail closed with $code, got $result",
            result is HttpHandlerResult.Err,
        )
        assertEquals(code, (result as HttpHandlerResult.Err).error.code)
    }

    // ----- Health / models / metrics / settings ------------------------------

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

    // ----- Inference fail-closed (no engine candidates) ----------------------

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
        assertErrCode(chat, OmniErrorCode.CAPABILITY_UNSUPPORTED, "sync chat")

        val emb = h.createEmbedding(
            principal,
            OpenAIEmbeddingRequestDto(
                model = "fixture",
                input = JsonPrimitive("hello"),
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "emb-1",
        )
        assertErrCode(emb, OmniErrorCode.CAPABILITY_UNSUPPORTED, "embeddings")
    }

    @Test
    fun streamingChat_failsClosedWithoutEngineCandidates() = runBlocking {
        val h = handler()
        val stream = h.createChatCompletionStream(
            principal,
            OpenAIChatRequestDto(
                model = "fixture",
                messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                stream = true,
            ),
            requestIdHeader = uuid(),
            idempotencyKeyHeader = "chat-stream-1",
        )
        assertTrue(stream is SseHandlerResult.PreStreamError)
        assertEquals(
            OmniErrorCode.CAPABILITY_UNSUPPORTED,
            (stream as SseHandlerResult.PreStreamError).error.code,
        )
    }

    // ----- Durable requests: honest fail-closed without orchestrator ---------

    @Test
    fun durableRequest_failClosedWithoutOrchestrator() = runBlocking {
        val h = handler()
        val requestId = uuid()
        val accepted = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = requestId,
                idempotencyKey = "async-1",
                operation = "CHAT",
                chat = NativeChatPayloadDto(
                    model = "fixture",
                    messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                ),
            ),
        )
        // API-16: claim-only-never-execute is forbidden — honest CAPABILITY_UNSUPPORTED.
        assertErrCode(accepted, OmniErrorCode.CAPABILITY_UNSUPPORTED, "durable create without orchestrator")

        val missing = h.getRequest(principal, requestId)
        assertErrCode(missing, OmniErrorCode.NOT_FOUND, "getRequest missing")

        val cancelMissing = h.cancelRequest(principal, requestId, cmd("cancel-missing", expectedVersion = 0L))
        assertErrCode(cancelMissing, OmniErrorCode.NOT_FOUND, "cancelRequest missing")
    }

    @Test
    fun requestEvents_failClosedWithoutOrchestrator() = runBlocking {
        val h = handler()
        val result = h.streamRequestEvents(principal, uuid(), 0L)
        assertTrue(result is SseHandlerResult.PreStreamError)
        assertEquals(
            OmniErrorCode.NOT_FOUND,
            (result as SseHandlerResult.PreStreamError).error.code,
        )
    }

    // ----- Durable request execution with a real orchestrator (API-16) --------

    @Test
    fun durableRequest_claimExecuteAndStreamEvents_withOrchestrator() = runBlocking {
        // Real stub llama-cpp engine through the binding (same path as the
        // control plane attach); harness orchestrator drives execution.
        // Dev-mode posture: the stub has no READY installation, so the fixture
        // fallback (fallbackToFixtureOnUnresolved) is what lets it execute
        // end-to-end — the same posture EngineExecuteBindingTest uses.
        val pack = com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
            .attachForTest(includeStubEngine = true)
        val binding = EngineExecuteBinding(
            buildMode = com.omnillm.core.contracts.ProductBuildMode(developmentShipMode = true),
            exploratoryEnabled = { true },
        )
        binding.applyAttachment(pack)
        // Governor capacity must fit the llama-cpp resource envelope (~MBs), the
        // harness default (1MB) rejects the reservation (ADMISSION_REJECTED).
        val harness = OrchestratorModule.createInMemoryHarness(
            capacity = com.omnillm.core.canonical.generated.ResourceVector(
                cpuAnonBytes = 512L * 1024L * 1024L,
                nativeThreads = 64L,
                fileDescriptors = 256L,
            ),
            safetyMargin = com.omnillm.core.canonical.generated.ResourceVector(
                cpuAnonBytes = 1_000L,
                nativeThreads = 1L,
                fileDescriptors = 4L,
            ),
            engine = binding.inferenceEngine,
            capabilities = binding.capabilityLookup,
        )
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = harness.registry,
            commandLedger = RequestRegistryModule.createInMemoryWithCommits().commandLedger,
            tokenService = LoopbackTokenService(),
            orchestrator = harness.orchestrator,
            exploratorySource = {
                // ARC-06: durable paths fail closed unless the requested revision
                // maps to a REAL installation — seed one for the fixture model
                // ("fixture" → sha256("model|fixture")).
                val installs = com.omnillm.runtime.modelmanager.memory
                    .InMemoryInstallationRepository()
                val rev = com.omnillm.core.canonical.generated.ModelRevisionId.parse(
                    com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|fixture"),
                )
                runBlocking {
                    installs.save(
                        com.omnillm.runtime.modelmanager.domain.InstallationSnapshot.discovered(
                            installationId = com.omnillm.core.state.domain.InstallationId(
                                "550e8400-e29b-41d4-a716-446655440000",
                            ),
                            modelRevisionId = rev,
                            artifactPackageId = com.omnillm.core.canonical.generated
                                .ArtifactPackageId.parse("d".repeat(64)),
                        ),
                    )
                }
                ExploratoryInferenceSource(
                    binding = binding,
                    modelManager = ModelManagerModule.createInMemoryControlPlane(
                        installations = installs,
                    ),
                )
            },
        )
        val requestId = uuid()
        val accepted = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = requestId,
                idempotencyKey = "durable-1",
                operation = "CHAT",
                chat = NativeChatPayloadDto(
                    model = "fixture",
                    messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                ),
            ),
        )
        assertTrue("durable create must accept: $accepted", accepted is HttpHandlerResult.Ok)
        assertEquals(202, (accepted as HttpHandlerResult.Ok).status)

        // The background pump drives execution to a terminal — poll briefly.
        var state = ""
        for (attempt in 1..100) {
            val q = h.getRequest(principal, requestId)
            if (q is HttpHandlerResult.Ok) {
                state = q.body.state
                if (q.body.state == "COMPLETED") break
            }
            kotlinx.coroutines.delay(20)
        }
        assertEquals("durable request must execute to COMPLETED", "COMPLETED", state)

        // Idempotent identical replay returns the durable state (202).
        val replay = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = requestId,
                idempotencyKey = "durable-1",
                operation = "CHAT",
                chat = NativeChatPayloadDto(
                    model = "fixture",
                    messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                ),
            ),
        )
        assertTrue("identical replay must be accepted: $replay", replay is HttpHandlerResult.Ok)

        // Same claim key + different payload ⇒ IDEMPOTENCY_CONFLICT.
        val conflict = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = uuid(),
                idempotencyKey = "durable-1",
                operation = "CHAT",
                chat = NativeChatPayloadDto(
                    model = "fixture",
                    messages = listOf(ChatMessageDto(role = "user", content = "DIFFERENT")),
                ),
            ),
        )
        assertErrCode(conflict, OmniErrorCode.IDEMPOTENCY_CONFLICT, "durable idempotency conflict")

        // Events stream reflects REAL terminal state (state event + terminal event).
        val events = h.streamRequestEvents(principal, requestId, 0L)
        assertTrue("events must stream: $events", events is SseHandlerResult.Stream)
        val frames = (events as SseHandlerResult.Stream).events.toList()
        assertTrue("events must include the state snapshot", frames.any { it.id == "0" })
        assertTrue("events must terminate", frames.any { it.isTerminal })
        assertTrue(
            "terminal event must carry COMPLETED",
            frames.filter { it.isTerminal }.any { it.data.contains("COMPLETED") },
        )

        // getRequest exposes the actual routing after execution.
        val queried = h.getRequest(principal, requestId) as HttpHandlerResult.Ok
        assertEquals("COMPLETED", queried.body.state)

        // Owner semantics: a different principal without read-all gets FORBIDDEN.
        val forbidden = h.getRequest(otherPrincipal(), requestId)
        assertErrCode(forbidden, OmniErrorCode.FORBIDDEN, "getRequest non-owner")
    }

    // ----- Cancel ownership (COR-05 / COR-22) --------------------------------

    @Test
    fun cancelRequest_ownerCheckedBeforeCancel() = runBlocking {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
        val requestId = uuid()
        ledgers.requestRegistry.claim(
            principal = PrincipalId.parse(principal.principalId),
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("owner-1"),
            canonicalHash = Sha256Digest.parse(digest64),
            requestId = RequestId.parse(requestId),
        )

        val cancelOther = h.cancelRequest(
            otherPrincipal(),
            requestId,
            cmd("cancel-other", expectedVersion = 0L),
        )
        assertErrCode(cancelOther, OmniErrorCode.FORBIDDEN, "cancelRequest non-owner")
        // Side-effect must NOT have happened: no terminal row.
        assertEquals(
            null,
            ledgers.requestRegistry.queryRequestTerminal(RequestId.parse(requestId)),
        )

        val cancelOwner = h.cancelRequest(
            principal,
            requestId,
            cmd("cancel-owner", expectedVersion = 0L),
        )
        assertTrue("owner cancel must succeed: $cancelOwner", cancelOwner is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (cancelOwner as HttpHandlerResult.Ok).body.state)

        val cmdResult = h.getCommand(principal, cancelOwner.body.commandId)
        assertTrue(cmdResult is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (cmdResult as HttpHandlerResult.Ok).body.state)
    }

    @Test
    fun cancelRequest_readAllScopeBypassesOwnerCheck() = runBlocking {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
        val requestId = uuid()
        ledgers.requestRegistry.claim(
            principal = PrincipalId.parse(principal.principalId),
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("readall-1"),
            canonicalHash = Sha256Digest.parse(digest64),
            requestId = RequestId.parse(requestId),
        )
        val cancel = h.cancelRequest(
            otherPrincipal(scopes = setOf("inference.cancel", "jobs.read-all")),
            requestId,
            cmd("cancel-readall", expectedVersion = 0L),
        )
        assertTrue("jobs.read-all must permit cross-owner cancel: $cancel", cancel is HttpHandlerResult.Ok)
    }

    @Test
    fun cancelRequest_sameKeyDifferentDigest_idempotencyConflict() = runBlocking {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
        val requestId = uuid()
        ledgers.requestRegistry.claim(
            principal = PrincipalId.parse(principal.principalId),
            operationKind = "CHAT",
            idempotencyKey = IdempotencyKey.parse("idem-1"),
            canonicalHash = Sha256Digest.parse(digest64),
            requestId = RequestId.parse(requestId),
        )
        val first = h.cancelRequest(principal, requestId, cmd("idem-1", expectedVersion = 0L))
        assertTrue(first is HttpHandlerResult.Ok)

        val conflicting = CommandRequestDto(
            commandId = uuid(),
            idempotencyKey = "idem-1",
            canonicalInputDigest = "b".repeat(64),
            expectedVersion = 0L,
        )
        val second = h.cancelRequest(principal, requestId, conflicting)
        assertErrCode(second, OmniErrorCode.IDEMPOTENCY_CONFLICT, "cancelRequest digest conflict")
    }

    // ----- Assets (COR-17 TTL / API-05 delete / API-06 sha256) ---------------

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

        val uploaded = h.uploadAsset(principal, assetId, byteArrayOf(1, 2, 3), 3, null)
        assertTrue("upload must succeed: $uploaded", uploaded is HttpHandlerResult.Ok)

        val committed = h.commitAsset(principal, assetId, cmd("asset-commit"))
        assertTrue("commit must succeed: $committed", committed is HttpHandlerResult.Ok)

        val deleted = h.deleteAsset(principal, assetId, cmd("asset-delete"))
        assertTrue("delete must succeed: $deleted", deleted is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (deleted as HttpHandlerResult.Ok).body.state)
        // API-05: the delete command is recorded durably in the CommandLedger.
        val cmdResult = h.getCommand(principal, deleted.body.commandId)
        assertTrue(cmdResult is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (cmdResult as HttpHandlerResult.Ok).body.state)
    }

    @Test
    fun asset_ttlOneSecond_expiresAfterTwoSeconds() = runBlocking {
        var now = Instant.parse("2026-08-04T00:00:00Z")
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            clock = { now },
        )
        val created = h.createAsset(
            principal,
            AssetCreateRequestDto(
                command = cmd("asset-ttl"),
                purpose = "image",
                maxBytes = 1024,
                ttlSeconds = 1,
            ),
        ) as HttpHandlerResult.Ok
        val assetId = created.body.assetId

        // TTL has not elapsed yet — readable.
        assertTrue(h.getAsset(principal, assetId) is HttpHandlerResult.Ok)

        now = now.plusSeconds(2)
        assertErrCode(h.getAsset(principal, assetId), OmniErrorCode.ASSET_EXPIRED, "getAsset expired")
        assertErrCode(
            h.uploadAsset(principal, assetId, byteArrayOf(1), 1, null),
            OmniErrorCode.ASSET_EXPIRED,
            "uploadAsset expired",
        )
        assertErrCode(
            h.commitAsset(principal, assetId, cmd("asset-commit-expired")),
            OmniErrorCode.ASSET_EXPIRED,
            "commitAsset expired",
        )
        assertErrCode(
            h.deleteAsset(principal, assetId, cmd("asset-delete-expired")),
            OmniErrorCode.ASSET_EXPIRED,
            "deleteAsset expired",
        )
    }

    @Test
    fun asset_upload_enforcesExpectedSha256() = runBlocking {
        val h = handler()
        val created = h.createAsset(
            principal,
            AssetCreateRequestDto(
                command = cmd("asset-sha"),
                purpose = "image",
                maxBytes = 1024,
                ttlSeconds = 3600,
            ),
        ) as HttpHandlerResult.Ok
        val assetId = created.body.assetId
        val body = "hello world".toByteArray()
        val sha = sha256(body)

        val wrong = h.uploadAsset(principal, assetId, body, body.size.toLong(), null, "0".repeat(64))
        assertErrCode(wrong, OmniErrorCode.INVALID_REQUEST, "upload sha256 mismatch")

        val ok = h.uploadAsset(principal, assetId, body, body.size.toLong(), null, sha)
        assertTrue("upload with matching sha256 must succeed: $ok", ok is HttpHandlerResult.Ok)
    }

    // ----- Jobs (COR-04 owner check first) -----------------------------------

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
        assertTrue("job create must succeed: $created", created is HttpHandlerResult.Ok)
        assertEquals(jobId, (created as HttpHandlerResult.Ok).body.jobId)

        val listed = h.listOwnJobs(principal, null) as HttpHandlerResult.Ok
        assertTrue(listed.body.items.any { it.jobId == jobId })

        val got = h.getJob(principal, jobId) as HttpHandlerResult.Ok
        assertEquals(jobId, got.body.jobId)

        val cancel = h.cancelJob(principal, jobId, cmd("job-cancel"))
        assertTrue("job cancel must succeed: $cancel", cancel is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (cancel as HttpHandlerResult.Ok).body.state)

        val after = h.getJob(principal, jobId) as HttpHandlerResult.Ok
        assertEquals("CANCELLED", after.body.state)
    }

    @Test
    fun job_cancel_forbiddenBeforeSideEffect() = runBlocking {
        val h = handler()
        val jobId = uuid()
        h.createJob(
            principal,
            JobSpecDto(
                command = cmd("job-create-2"),
                jobId = jobId,
                kind = "IMPORT",
                parameters = JsonObject(mapOf("asset_id" to JsonPrimitive("asset-fixture"))),
            ),
        )
        val cancel = h.cancelJob(otherPrincipal(), jobId, cmd("job-cancel-other"))
        assertErrCode(cancel, OmniErrorCode.FORBIDDEN, "cancelJob non-owner")
        // COR-04: the job must NOT have been cancelled by the rejected caller.
        val after = h.getJob(principal, jobId) as HttpHandlerResult.Ok
        assertFalse(
            "job must not be cancelled by a 403'd caller, state=${after.body.state}",
            after.body.state == "CANCELLED",
        )
    }

    // ----- Settings (API-04 CommandResult + resource-version conflict) --------

    @Test
    fun settings_patch_returnsCommandResult_andConflictsOnStaleVersion() = runBlocking {
        val h = handler()
        val snap = h.getSettings(principal) as HttpHandlerResult.Ok
        val base = snap.body.resourceVersion

        val patched = h.patchSettings(
            principal,
            SettingsPatchDto(
                command = cmd("settings-1", expectedVersion = base),
                changes = mapOf(
                    "runtime.exploratoryExecuteEnabled" to JsonPrimitive(true),
                ),
            ),
        )
        assertTrue("settings patch must succeed: $patched", patched is HttpHandlerResult.Ok)
        val result = patched as HttpHandlerResult.Ok
        // API-04: the 200 body is a CommandResult, not a SettingsSnapshot.
        assertEquals("SUCCEEDED", result.body.state)
        assertEquals("settings", result.body.affectedResourceId)
        assertNotNull(result.body.commandId)

        val cmdResult = h.getCommand(principal, result.body.commandId)
        assertTrue(cmdResult is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (cmdResult as HttpHandlerResult.Ok).body.state)

        // Resource-version conflict: patching with the STALE base version.
        val stale = h.patchSettings(
            principal,
            SettingsPatchDto(
                command = cmd("settings-2", expectedVersion = base),
                changes = mapOf(
                    "runtime.exploratoryExecuteEnabled" to JsonPrimitive(false),
                ),
            ),
        )
        assertErrCode(stale, OmniErrorCode.STATE_CONFLICT, "settings stale version")
    }

    // ----- Diagnostics / content reports --------------------------------------

    @Test
    fun diagnosticsExport_andContentReportProposal() = runBlocking {
        val h = handler()
        val diag = h.createDiagnosticExport(
            principal,
            DiagnosticExportRequestDto(command = cmd("diag-1"), includeDetail = false),
        )
        assertTrue("diagnostic export must succeed: $diag", diag is HttpHandlerResult.Ok)

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
        assertTrue("content report proposal must succeed: $report", report is HttpHandlerResult.Ok)
        assertEquals(reportId, (report as HttpHandlerResult.Ok).body.reportId)

        val got = h.getContentReport(principal, reportId) as HttpHandlerResult.Ok
        assertEquals(reportId, got.body.reportId)
    }

    // ----- LAN pairing fail-closed --------------------------------------------

    @Test
    fun lanPairingExchange_failsClosedWhenTlsNotReady() = runBlocking {
        val h = handler()
        // completeLanPairing without ACTIVE TLS must fail closed (STATE_CONFLICT / CAPABILITY).
        val result = h.completeLanPairing(
            """{"command":{"command_id":"${uuid()}","idempotency_key":"pair-1"},"exchange_id":"${uuid()}","challenge_id":"${uuid()}","client_public_key":"${"x".repeat(32)}","requested_scopes":["inference.create"],"proof_base64url":"${"y".repeat(43)}"}""",
        )
        assertTrue("LAN pairing must fail closed: $result", result is HttpHandlerResult.Err)
    }

    @Test
    fun featurePackMarkers_includeLanDiagnosticsContentReport() {
        val markers = handler().attachedFeaturePackMarkers()
        assertEquals(true, markers["lan"])
        assertEquals(true, markers["diagnostics"])
        assertEquals(true, markers["ai-content-report"])
    }

    // ----- helpers ------------------------------------------------------------

    private fun sha256(bytes: ByteArray): String {
        val dig = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { b -> "%02x".format(b) }
    }
}
