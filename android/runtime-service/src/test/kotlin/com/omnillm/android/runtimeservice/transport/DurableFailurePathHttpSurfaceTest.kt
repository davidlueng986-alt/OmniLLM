package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding
import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.ExploratoryInferenceSource
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.resource.Reservation
import com.omnillm.interfaces.http.AsyncInferenceRequestDto
import com.omnillm.interfaces.http.ChatMessageDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.NativeChatPayloadDto
import com.omnillm.interfaces.http.SseHandlerResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.StreamBatchOutcome
import com.omnillm.engines.api.CommitQueryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D14c: API-16 durable request whose engine execution FAILS must end in a real
 * terminal FAILED state (never stuck), with a terminal SSE event carrying the
 * error code. The COMPLETED happy path is covered by
 * LaunchCriticalHttpSurfaceTest; this pins the failure path end-to-end through
 * [ControlPlaneHttpHandler] with a REAL orchestrator + engine binding whose
 * start() honestly fails (START_FAILED terminal).
 */
class DurableFailurePathHttpSurfaceTest {

    private val digest64 = "a".repeat(64)

    private val principal = HttpPrincipal(
        principalId = "durable-failure-principal",
        tokenId = "tok-durable-failure",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    /** Delegate engine that fails at start() — the honest engine-failure path. */
    private class FailingStartEngine(
        private val delegate: InferenceEnginePort,
    ) : InferenceEnginePort {
        override suspend fun planInference(
            request: OrchestrationRequest,
            candidate: RoutingCandidate,
        ): OmniResult<InferencePlanOutcome> = delegate.planInference(request, candidate)

        override suspend fun commitInference(
            plan: Plan,
            reservation: Reservation,
            commit: Commit,
        ): OmniResult<PreparedOperation> = delegate.commitInference(plan, reservation, commit)

        override suspend fun start(
            prepared: PreparedOperation,
            operationId: String,
            runtimeEpoch: Long,
        ): OmniResult<Unit> = OmniResult.err(
            OmniError.INTERNAL(message = "engine start failed (test fixture)"),
        )

        override suspend fun nextEvents(
            prepared: PreparedOperation,
            fromSeq: Long,
        ): OmniResult<StreamBatchOutcome> = delegate.nextEvents(prepared, fromSeq)

        override suspend fun queryCommit(commitId: com.omnillm.core.contracts.CommitId): OmniResult<CommitQueryState> =
            delegate.queryCommit(commitId)
    }

    private fun buildHandler(): ControlPlaneHttpHandler {
        val pack = com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
            .attachForTest(includeStubEngine = true)
        val binding = EngineExecuteBinding(
            buildMode = ProductBuildMode(developmentShipMode = true),
            exploratoryEnabled = { true },
        )
        binding.applyAttachment(pack)
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
            engine = FailingStartEngine(binding.inferenceEngine),
            capabilities = binding.capabilityLookup,
        )
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = harness.registry,
            commandLedger = RequestRegistryModule.createInMemoryWithCommits().commandLedger,
            tokenService = LoopbackTokenService(),
            orchestrator = harness.orchestrator,
            exploratorySource = {
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
    }

    private suspend fun submitDurable(h: ControlPlaneHttpHandler, key: String): String {
        val requestId = uuid()
        val accepted = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = requestId,
                idempotencyKey = key,
                operation = "CHAT",
                chat = NativeChatPayloadDto(
                    model = "fixture",
                    messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                ),
            ),
        )
        assertTrue("durable create must accept: $accepted", accepted is HttpHandlerResult.Ok)
        assertEquals(202, (accepted as HttpHandlerResult.Ok).status)
        return requestId
    }

    @Test
    fun durableRequest_engineStartFailure_endsFailed_notStuck() = runBlocking {
        val h = buildHandler()
        val requestId = submitDurable(h, "durable-fail-1")

        // The background pump drives execution; poll until a terminal state.
        var terminalState: String? = null
        var terminalErrorCode: String? = null
        for (attempt in 1..200) {
            val q = h.getRequest(principal, requestId)
            if (q is HttpHandlerResult.Ok) {
                terminalState = q.body.state
                terminalErrorCode = q.body.terminalError?.code
                if (terminalState in DURABLE_TERMINAL_STATES) break
            }
            delay(20)
        }
        assertEquals(
            "engine-start failure must end in FAILED (not stuck), got state=$terminalState",
            "FAILED",
            terminalState,
        )
        assertEquals("terminal error code must surface INTERNAL", "INTERNAL", terminalErrorCode)

        // Terminal SSE event must carry the failure + error code.
        val events = h.streamRequestEvents(principal, requestId, 0L)
        assertTrue("events must stream: $events", events is SseHandlerResult.Stream)
        val frames = (events as SseHandlerResult.Stream).events.toList()
        assertTrue("events must include the state snapshot", frames.any { it.id == "0" })
        assertTrue("events must terminate", frames.any { it.isTerminal })
        val terminal = frames.filter { it.isTerminal }
        assertTrue("terminal frame must carry FAILED: $terminal", terminal.any { it.data.contains("FAILED") })
        assertTrue("terminal frame must carry error code: $terminal", terminal.any { it.data.contains("INTERNAL") })

        // Honest terminal row: the request registry records the error durably.
        val row = h.getRequest(principal, requestId) as HttpHandlerResult.Ok
        assertEquals("FAILED", row.body.state)
    }

    @Test
    fun durableRequest_engineStartFailure_terminalRecordedInRegistry() = runBlocking {
        val h = buildHandler()
        val requestId = submitDurable(h, "durable-fail-2")
        for (attempt in 1..200) {
            val q = h.getRequest(principal, requestId)
            if (q is HttpHandlerResult.Ok && q.body.state == "FAILED") break
            delay(20)
        }
        val final = h.getRequest(principal, requestId) as HttpHandlerResult.Ok
        assertEquals("FAILED", final.body.state)
        // Replay with the same idempotency key + identical payload (incl. the
        // same requestId) stays accepted (202) — replay of a failed durable
        // request must not invent a new execution.
        val replay = h.createAsyncInferenceRequest(
            principal,
            AsyncInferenceRequestDto(
                requestId = requestId,
                idempotencyKey = "durable-fail-2",
                operation = "CHAT",
                chat = NativeChatPayloadDto(
                    model = "fixture",
                    messages = listOf(ChatMessageDto(role = "user", content = "hi")),
                ),
            ),
        )
        assertTrue("identical replay must be accepted: $replay", replay is HttpHandlerResult.Ok)
    }

    private companion object {
        val DURABLE_TERMINAL_STATES: Set<String> = setOf(
            "COMPLETED",
            "FAILED",
            "CANCELLED",
            "ABORTED_UNCERTAIN",
        )
    }
}
