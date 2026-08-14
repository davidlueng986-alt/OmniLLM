package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PreparedOperationId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.resource.Reservation
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.interfaces.admin.AdminModule
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.modelmanager.ModelManagerModule
import com.omnillm.runtime.orchestrator.CapabilityLookup
import com.omnillm.runtime.orchestrator.InferenceEnginePort
import com.omnillm.runtime.orchestrator.InferencePlanOutcome
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.StreamBatchOutcome
import com.omnillm.runtime.orchestrator.StreamEvent
import com.omnillm.runtime.orchestrator.StreamTerminal
import com.omnillm.runtime.orchestrator.StreamTerminalKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * C-01 production-wiring proof: WaveAWiring.Deps.commitLedger must reach the
 * Orchestrator, so a commit executed through the full Wave-A path leaves
 * durable INTENT/outcome rows that a restarted control plane can query.
 *
 * Pre-fix this file did not compile (Deps.commitLedger did not exist) — the
 * module-level RED (OrchestratorCommitLedgerDurabilityTest) demonstrated the
 * missing wiring.
 */
class WaveAWiringCommitLedgerDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = { "2026-08-10T00:00:00Z" }

    private fun wireWaveA(
        controlPlaneDb: ControlPlaneDatabase,
        engine: InferenceEnginePort,
    ): WaveAFeaturePacks {
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val ledgers = RequestRegistryModule.createWithCommits(
            claims = controlPlaneDb.claims,
            commits = controlPlaneDb.commits,
            clock = clock,
        )
        val admin = AdminModule.createService(
            commandLedger = ledgers.commandLedger,
            jobManager = jobs,
            policyManager = policy,
            runtimeStateProvider = { "READY" },
            lanStateProvider = { "DISABLED" },
            clockMs = { 1_700_000_000_000L },
        )
        return WaveAWiring.wire(
            WaveAWiring.Deps(
                adminApi = admin,
                jobManager = jobs,
                modelManager = ModelManagerModule.createInMemoryControlPlane(),
                requestRegistry = ledgers.requestRegistry,
                observability = observability,
                runtimeState = { "READY" },
                lanState = { "DISABLED" },
                runtimeEpoch = { 1L },
                bootId = { "boot-wave-a-c01" },
                inferenceEngineOverride = engine,
                capabilityLookupOverride = CapabilityLookup { _, _ -> CapabilityState.SUPPORTED },
                // C-01: production wiring must forward the durable ledger.
                commitLedger = ledgers.commitLedger,
            ),
        )
    }

    @Test
    fun waveAOrchestrator_commitRowsSurviveRestart() = runBlocking {
        val file = tmp.newFile("wave-a-commit-durability.db")
        val engine = CapturingEngine()

        // First process: Wave-A wiring over SQLite-backed ledgers.
        val db1 = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        val waveA1 = wireWaveA(db1, engine)
        val req = sampleRequest()

        val submitted = waveA1.orchestrator.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val terminal = waveA1.orchestrator.pumpAll(maxSteps = 64).lastOrNull()
        assertNotNull(terminal)
        assertTrue(terminal!! is OmniResult.Ok)

        val commitId = engine.lastCommit!!.commitId
        val ledgers1 = RequestRegistryModule.createWithCommits(
            claims = db1.claims,
            commits = db1.commits,
            clock = clock,
        )
        assertNotNull(ledgers1.commitLedger.queryCommit(commitId))
        db1.close()

        // Restart: fresh DB + fresh Wave-A wiring on the same file.
        val db2 = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        val ledgers2 = RequestRegistryModule.createWithCommits(
            claims = db2.claims,
            commits = db2.commits,
            clock = clock,
        )
        val after = ledgers2.commitLedger.queryCommit(commitId)
        assertNotNull("Wave-A orchestrator commit must survive restart", after)
        assertEquals("RESULT_RECORDED", after!!.state)
        db2.close()
    }

    private fun sampleRequest(): OrchestrationRequest {
        val revHex = "1".repeat(64)
        val candidate = RoutingCandidate(
            candidateId = "cand-c01",
            modelRevisionId = com.omnillm.core.canonical.generated.ModelRevisionId.parse(revHex),
            installationId = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            backend = "cpu",
            placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
            loadKeyDigest = Sha256Digest.parse("c".repeat(64)),
            isPrimary = true,
            deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-c01"),
        )
        return OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = PrincipalId.parse("principal-c01"),
            idempotencyKey = IdempotencyKey.parse("idem-${UUID.randomUUID()}"),
            operationKind = "CHAT",
            canonicalRequestDigest = Sha256Digest.parse("d".repeat(64)),
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = candidate.modelRevisionId,
            candidates = listOf(candidate),
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            // WaveAWiring wires the orchestrator with the default System.nanoTime()
            // clock — the deadline must be far in the future on that scale.
            deadlineMonotonic = Long.MAX_VALUE,
        )
    }

    /** Minimal happy-path engine: plans, commits (records the Commit), streams one token. */
    private class CapturingEngine : InferenceEnginePort {
        var lastCommit: Commit? = null
        private var planCount = 0

        override suspend fun planInference(
            request: OrchestrationRequest,
            candidate: RoutingCandidate,
        ): OmniResult<InferencePlanOutcome> {
            planCount++
            val envelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 1_024L, nativeThreads = 1L),
                peak = ResourceVector(cpuAnonBytes = 2_048L, nativeThreads = 2L),
            )
            val plan = Plan(
                planId = PlanId.parse("plan-$planCount"),
                requestId = request.requestId,
                principalId = request.principalId,
                modelRevisionId = candidate.modelRevisionId,
                engineBuildId = candidate.engineBuildId,
                deviceExecutionFingerprint = candidate.deviceExecutionFingerprint,
                canonicalInputDigest = Sha256Digest.parse("e".repeat(64)),
                resourceEnvelope = envelope,
                expiryMonotonic = request.deadlineMonotonic,
                runtimeEpoch = request.runtimeEpoch,
                sourceSessionEpoch = request.sourceSessionEpoch,
            )
            return OmniResult.ok(InferencePlanOutcome(plan, envelope, plan.canonicalInputDigest))
        }

        override suspend fun commitInference(
            plan: Plan,
            reservation: Reservation,
            commit: Commit,
        ): OmniResult<PreparedOperation> {
            lastCommit = commit
            return OmniResult.ok(
                PreparedOperation(
                    preparedOperationId = PreparedOperationId.parse("prep-${commit.commitId.value}"),
                    operationId = "op-${commit.commitId.value}",
                    requestId = commit.requestId,
                    commitId = commit.commitId,
                    principalId = commit.principalId,
                    reservationId = reservation.reservationId,
                    revisionLeaseId = commit.revisionLeaseId,
                    issuerBootId = commit.issuerBootId,
                    runtimeEpoch = commit.runtimeEpoch,
                    revocationEpoch = commit.revocationEpoch,
                    sourceSessionEpoch = commit.sourceSessionEpoch,
                    targetSessionId = null,
                    canonicalInputDigest = plan.canonicalInputDigest,
                ),
            )
        }

        override suspend fun start(
            prepared: PreparedOperation,
            operationId: String,
            runtimeEpoch: Long,
        ): OmniResult<Unit> = OmniResult.ok(Unit)

        override suspend fun nextEvents(
            prepared: PreparedOperation,
            fromSeq: Long,
        ): OmniResult<StreamBatchOutcome> =
            if (fromSeq == 0L) {
                OmniResult.ok(
                    StreamBatchOutcome(
                        seqFrom = 0L,
                        seqTo = 1L,
                        events = listOf(StreamEvent(seq = 0L, kind = "token")),
                        terminal = null,
                    ),
                )
            } else {
                OmniResult.ok(
                    StreamBatchOutcome(
                        seqFrom = 1L,
                        seqTo = 1L,
                        events = emptyList(),
                        terminal = StreamTerminal(
                            kind = StreamTerminalKind.SUCCESS,
                            outputDigest = Sha256Digest.parse("f".repeat(64)),
                        ),
                    ),
                )
            }

        override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> =
            OmniResult.ok(CommitQueryState(commitId = commitId, state = "COMMITTED"))
    }
}
