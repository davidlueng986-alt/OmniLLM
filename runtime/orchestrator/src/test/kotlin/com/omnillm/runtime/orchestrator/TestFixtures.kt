package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
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
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.PlacementClassLabels
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** [seed] must be a single lower-case hex digit (0-9a-f). */
internal fun digest(seed: Char = 'a'): Sha256Digest {
    require(seed in '0'..'9' || seed in 'a'..'f') { "digest seed must be hex digit, got $seed" }
    return Sha256Digest.parse(seed.toString().repeat(64))
}

/** [seed] must be a single lower-case hex digit (0-9a-f). */
internal fun revision(seed: Char = '1'): ModelRevisionId {
    require(seed in '0'..'9' || seed in 'a'..'f') { "revision seed must be hex digit, got $seed" }
    return ModelRevisionId.parse(seed.toString().repeat(64))
}

internal fun uuid(): String = UUID.randomUUID().toString()

internal fun requestId(): RequestId = RequestId.parse(uuid())

internal fun principal(name: String = "principal-a"): PrincipalId = PrincipalId.parse(name)

internal fun candidate(
    id: String = "cand-primary",
    rev: ModelRevisionId = revision('1'),
    backend: String = "cpu",
    primary: Boolean = true,
    placement: String = PlacementClassLabels.PRIVILEGED_TRUSTED,
    engine: String = "engine-build-1",
    installation: String = "550e8400-e29b-41d4-a716-446655440000",
): RoutingCandidate =
    RoutingCandidate(
        candidateId = id,
        modelRevisionId = rev,
        installationId = InstallationId.parse(installation),
        engineBuildId = EngineBuildId.parse(engine),
        backend = backend,
        placementClass = placement,
        loadKeyDigest = digest('c'),
        isPrimary = primary,
        deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("device-fp-test-1"),
    )

internal fun orchestrationRequest(
    requestId: RequestId = requestId(),
    principalId: PrincipalId = principal(),
    idempotencyKey: String = "idem-${uuid()}",
    revision: ModelRevisionId = revision('1'),
    candidates: List<RoutingCandidate> = listOf(candidate(rev = revision)),
    routing: RoutingPreference = RoutingPreference(),
    costClass: String = CostClassLabels.GENERATION,
    costUnits: Long? = null,
    deadline: Long = 1_000_000L,
    capabilities: Set<CapabilityId> = setOf(CapabilityId.TEXT_GENERATION),
): OrchestrationRequest =
    OrchestrationRequest(
        requestId = requestId,
        principalId = principalId,
        idempotencyKey = IdempotencyKey.parse(idempotencyKey),
        operationKind = "CHAT",
        canonicalRequestDigest = digest('d'), // hex digit
        requiredCapabilities = capabilities,
        requestedRevisionId = revision,
        candidates = candidates,
        routing = routing,
        costClass = costClass,
        costUnits = costUnits,
        runtimeEpoch = 1L,
        revocationEpoch = 0L,
        deadlineMonotonic = deadline,
    )

/**
 * Fake inference engine for pipeline integration tests.
 * Plan is pure; commit/start/stream mutate only fake in-memory state.
 */
internal class FakeInferenceEngine(
    var peakBytes: Long = 2_048L,
    var steadyBytes: Long = 1_024L,
    var failPlan: Boolean = false,
    var failCommit: Boolean = false,
    var failStart: Boolean = false,
    var streamTokens: Int = 2,
    var terminalKind: StreamTerminalKind = StreamTerminalKind.SUCCESS,
) : InferenceEnginePort {
    val planCount = AtomicInteger(0)
    val commitCount = AtomicInteger(0)
    val startCount = AtomicInteger(0)
    var lastCommit: Commit? = null
    private val commits = linkedMapOf<String, String>()

    override suspend fun planInference(
        request: OrchestrationRequest,
        candidate: RoutingCandidate,
    ): OmniResult<InferencePlanOutcome> {
        planCount.incrementAndGet()
        if (failPlan) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.INTERNAL(message = "plan failed"),
            )
        }
        val dig = digest('e')
        val envelope = ResourceEnvelope(
            steady = ResourceVector(cpuAnonBytes = steadyBytes, nativeThreads = 1L),
            peak = ResourceVector(cpuAnonBytes = peakBytes, nativeThreads = 2L),
        )
        val plan = Plan(
            planId = PlanId.parse("plan-${planCount.get()}"),
            requestId = request.requestId,
            principalId = request.principalId,
            modelRevisionId = candidate.modelRevisionId,
            engineBuildId = candidate.engineBuildId,
            deviceExecutionFingerprint = candidate.deviceExecutionFingerprint,
            canonicalInputDigest = dig,
            resourceEnvelope = envelope,
            expiryMonotonic = request.deadlineMonotonic,
            runtimeEpoch = request.runtimeEpoch,
            sourceSessionEpoch = request.sourceSessionEpoch,
        )
        return OmniResult.ok(InferencePlanOutcome(plan, envelope, dig))
    }

    override suspend fun commitInference(
        plan: Plan,
        reservation: Reservation,
        commit: Commit,
    ): OmniResult<PreparedOperation> {
        commitCount.incrementAndGet()
        lastCommit = commit
        if (failCommit) {
            commits[commit.commitId.value] = "ABORTED"
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.STATE_CONFLICT(message = "commit rejected"),
            )
        }
        commits[commit.commitId.value] = "COMMITTED"
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
    ): OmniResult<Unit> {
        startCount.incrementAndGet()
        if (failStart) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.WORKER_DIED(message = "start failed"),
            )
        }
        return OmniResult.ok(Unit)
    }

    override suspend fun nextEvents(
        prepared: PreparedOperation,
        fromSeq: Long,
    ): OmniResult<StreamBatchOutcome> {
        if (fromSeq >= streamTokens) {
            return OmniResult.ok(
                StreamBatchOutcome(
                    seqFrom = fromSeq,
                    seqTo = fromSeq,
                    events = emptyList(),
                    terminal = StreamTerminal(
                        kind = terminalKind,
                        outputDigest = digest('f'),
                        errorCode = if (terminalKind == StreamTerminalKind.FAILURE) "INTERNAL" else null,
                    ),
                ),
            )
        }
        val next = fromSeq + 1
        return OmniResult.ok(
            StreamBatchOutcome(
                seqFrom = fromSeq,
                seqTo = next,
                events = listOf(
                    StreamEvent(seq = fromSeq, kind = "token", payloadDigest = digest('b')),
                ),
                terminal = null,
            ),
        )
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> {
        val state = commits[commitId.value] ?: "UNKNOWN"
        return OmniResult.ok(CommitQueryState(commitId = commitId, state = state))
    }
}

internal class AllSupportedCapabilities : CapabilityLookup {
    override fun state(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState =
        CapabilityState.SUPPORTED
}

internal class SelectiveCapabilities(
    private val unsupported: Set<String> = emptySet(),
) : CapabilityLookup {
    override fun state(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState =
        if (candidate.candidateId in unsupported) {
            CapabilityState.UNSUPPORTED
        } else {
            CapabilityState.SUPPORTED
        }
}
