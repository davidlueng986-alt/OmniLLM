package com.omnillm.engines.mllm

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.CommitQueryState
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.api.EngineDescriptor
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.engines.api.LoadedModelHandle
import com.omnillm.engines.api.LoadedModelPort
import com.omnillm.engines.api.OmniEngine
import com.omnillm.engines.api.OperationContext
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.api.ProbeInput
import com.omnillm.engines.api.ProbePlan
import com.omnillm.engines.api.ProbeResult
import com.omnillm.engines.mllm.lock.UpstreamLock
import com.omnillm.engines.mllm.mapping.ErrorMapper
import com.omnillm.engines.mllm.mapping.PhaseCancellationMap
import com.omnillm.engines.mllm.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.mllm.server.PrivateChannelProtocol
import com.omnillm.engines.mllm.server.ServerBackend
import com.omnillm.engines.mllm.server.ServerLoadRequest
import com.omnillm.engines.mllm.server.ServerModelToken
import com.omnillm.engines.mllm.server.ServerProbeRequest
import com.omnillm.engines.mllm.server.ServerResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * mllm [OmniEngine] adapter (ENGINE-MLLM, CORE-ENGINE).
 *
 * Integration shape: device-local client-server / Go `mllm_server.aar`.
 * The embedded server is engine-private; Gateway remains the sole public entry.
 *
 * - Plan methods are pure (no domain mutation, ADR-002)
 * - Commit is one-shot / idempotent / queryable by commitId (ADR-004/005)
 * - Never writes DB / model store
 * - Unproven operations → [OmniError.CAPABILITY_UNKNOWN] unless exploratory mode
 * - Server work delegated to [ServerBackend] (stub until AAR wired)
 * - Capability matrix / qualification cells stay UNQUALIFIED without evidence
 */
class MllmEngine(
    override val engineBuildId: EngineBuildId,
    val lock: UpstreamLock,
    val server: ServerBackend,
    /**
     * When false (default scaffold), probe/commitLoad refuse with CAPABILITY_UNKNOWN
     * because no qualification evidence exists for this pack.
     * Tests may set true **and** use exploratory stub for Plan→Commit plumbing only.
     */
    val allowUnprovenExecution: Boolean = false,
    /**
     * Measured phase cancellation overrides. Default all UNKNOWN.
     * Control plane may inject evidence-backed modes without inventing SUPPORTED.
     */
    measuredPhaseCancellation: Map<String, String> = emptyMap(),
) : OmniEngine {

    private val phaseCancellation: Map<String, String> =
        PhaseCancellationMap.measuredModes(measuredPhaseCancellation)

    private val planSeq = AtomicInteger(0)
    private val loadedSeq = AtomicInteger(0)

    /** commitId → durable query state for reply-loss recovery (ADR-004/005). */
    private val commits = ConcurrentHashMap<String, CommitRecord>()
    private val loaded = ConcurrentHashMap<String, BoundLoadedModel>()

    /** Captured at planLoad for commitLoad — not durable domain state (sideband only). */
    private val pendingLoads = ConcurrentHashMap<String, PendingLoad>()

    override suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor> {
        // Backends/formats empty or design-candidate only until locked evidence.
        val backends = lock.testedBackends.ifEmpty { listOf("cpu") }
        val formats = lock.testedFormats // may be empty — do not invent formats
        // Incomplete lock or UNKNOWN cancellation ⇒ prefer worker / isolated / companion
        val placement = listOf(
            PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
            PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
        )
        return OmniResult.ok(
            EngineDescriptor(
                engineBuildId = engineBuildId,
                engineId = MllmModule.ENGINE_ID,
                backends = backends,
                supportedFormats = formats,
                placementClasses = placement,
                phaseCancellation = phaseCancellation,
                notes = mapOf(
                    "deviceFingerprint" to device.deviceExecutionFingerprint.value,
                    "lockState" to if (lock.isComplete()) {
                        UpstreamLock.LOCK_STATE_LOCKED
                    } else {
                        UpstreamLock.LOCK_STATE_NOT_LOCKED
                    },
                    "designStatus" to MllmModule.DESIGN_STATUS,
                    "qualificationStatus" to MllmModule.QUALIFICATION_STATUS,
                    "registryExposure" to MllmModule.REGISTRY_EXPOSURE,
                    "runtimeCapabilityDefault" to "UNKNOWN",
                    "integrationShape" to "embedded-server-aar",
                    "library" to server.libraryLabel(),
                    "serverAvailable" to server.isAvailable().toString(),
                    "serverLifecycle" to server.lifecycleState().name,
                    "protocolId" to PrivateChannelProtocol.PROTOCOL_ID,
                    "protocolVersion" to PrivateChannelProtocol.PROTOCOL_VERSION.toString(),
                    "abis" to device.abiList.joinToString(","),
                    "knownLimitation" to "unproven-ops-map-to-CAPABILITY_UNKNOWN",
                ),
            ),
        )
    }

    override suspend fun planProbe(input: ProbeInput): OmniResult<ProbePlan> {
        if (input.backend.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "backend required"))
        }
        val n = planSeq.incrementAndGet()
        val dig = digestOf("probe|${input.requestId.value}|${input.backend}|$n")
        return OmniResult.ok(
            ProbePlan(
                planId = PlanId.parse("mllm-probe-plan-$n"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                backend = input.backend,
                resourceEnvelope = ResourceEnvelopeEstimator.estimateProbe(),
                phaseCapabilityDigest = dig,
                canonicalInputDigest = dig,
                expiryMonotonic = Long.MAX_VALUE / 4,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    /**
     * Execute probe under reservation. Unproven path fails closed with
     * CAPABILITY_UNKNOWN (or NOT_LOCKED mapping) — never elevates trust.
     */
    override suspend fun probe(
        plan: ProbePlan,
        reservation: Reservation,
        op: OperationContext,
    ): OmniResult<ProbeResult> {
        if (reservation.principalId.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "empty reservation principal"))
        }
        if (!allowUnprovenExecution || !lock.isComplete()) {
            return OmniResult.err(
                ErrorMapper.capabilityUnknown(
                    capability = "PROBE.${plan.backend}",
                    reason = "mllm probe unproven: lock incomplete or no qualification cell PASS",
                ),
            )
        }
        return when (
            val r = server.probe(
                ServerProbeRequest(
                    backend = plan.backend,
                    operationToken = op.operationId,
                ),
            )
        ) {
            is ServerResult.Ok -> OmniResult.ok(
                ProbeResult(
                    planId = plan.planId,
                    success = r.value.available,
                    observedEnvelope = plan.resourceEnvelope,
                    attributes = ErrorMapper.sanitize(
                        r.value.attributes + mapOf(
                            "backend" to r.value.backend,
                            "reservationId" to reservation.reservationId.value,
                        ),
                    ),
                ),
            )
            is ServerResult.Err -> OmniResult.err(ErrorMapper.toOmniError(r.error))
        }
    }

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        val backend = input.loadKey.backend
        if (backend.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "loadKey.backend required"))
        }

        // Load envelope includes Go/server fixed overhead (ENGINE-MLLM §6).
        val envelope = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 0L,
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                scratchBytes = 2L * 1024L * 1024L,
            ),
        )

        val placement = proposePlacement(backend)
        val n = planSeq.incrementAndGet()
        val dig = digestOf(
            "load|${input.requestId.value}|${input.installationId.value}|${input.loadKey.backend}|$n",
        )
        // Sideband only — no weight open / DB write (ADR-002).
        pendingLoads[PlanId.parse("mllm-load-plan-$n").value] = PendingLoad(
            storageRootKey = input.storageRootKey,
            resolvedModelPath = input.resolvedModelPath,
        )
        return OmniResult.ok(
            LoadPlan(
                planId = PlanId.parse("mllm-load-plan-$n"),
                requestId = input.requestId,
                principalId = input.principalId,
                engineBuildId = engineBuildId,
                loadKey = input.loadKey,
                installationId = input.installationId,
                modelRevisionId = input.modelRevisionId,
                resourceEnvelope = envelope,
                proposedPlacementClass = placement,
                phaseCapabilityDigest = dig,
                canonicalInputDigest = dig,
                expiryMonotonic = Long.MAX_VALUE / 4,
                runtimeEpoch = input.runtimeEpoch,
            ),
        )
    }

    /**
     * Commit load is an execute/mutation path. Until lock + cell evidence exist,
     * fail closed with CAPABILITY_UNKNOWN and record ABORTED for query recovery.
     */
    override suspend fun commitLoad(
        plan: LoadPlan,
        reservation: Reservation,
        commit: CommitContext,
    ): OmniResult<LoadedModelHandle> {
        val payload = loadPayload(plan, commit)
        val existing = commits[commit.commitId.value]
        if (existing != null) {
            if (existing.payloadDigest != payload) {
                return OmniResult.err(
                    OmniError.IDEMPOTENCY_CONFLICT(message = "commitLoad payload mismatch"),
                )
            }
            @Suppress("UNCHECKED_CAST")
            return when (val r = existing.result) {
                is OmniResult.Ok<*> -> OmniResult.ok(r.value as LoadedModelHandle)
                is OmniResult.Err -> r
            }
        }

        if (plan.engineBuildId != engineBuildId) {
            val err = OmniResult.err(
                OmniError.INVALID_REQUEST(message = "engineBuildId mismatch on commitLoad"),
            )
            commits[commit.commitId.value] = CommitRecord(
                kind = CommitKind.LOAD,
                state = "ABORTED",
                payloadDigest = payload,
                result = err,
                loadedModelId = null,
            )
            return err
        }

        // Scaffold default: unproven load stays CAPABILITY_UNKNOWN (no SUPPORTED claim).
        if (!allowUnprovenExecution || !lock.isComplete()) {
            val err = OmniResult.err(
                ErrorMapper.capabilityUnknown(
                    capability = "LOAD.${plan.loadKey.backend}",
                    reason = "mllm load unproven: incomplete UPSTREAM.lock and/or " +
                        "no QUALIFIED_WITH_ENVELOPE+PASS cell (ENGINE-MLLM §10)",
                ),
            )
            commits[commit.commitId.value] = CommitRecord(
                kind = CommitKind.LOAD,
                state = "ABORTED",
                payloadDigest = payload,
                result = err,
                loadedModelId = null,
            )
            return err
        }

        val pending = pendingLoads.remove(plan.planId.value)
        val loadReq = ServerLoadRequest(
            storageRootKey = pending?.storageRootKey ?: "broker:${plan.installationId.value}",
            installationKey = plan.installationId.value,
            backend = plan.loadKey.backend,
            privilegedLoadTicketId = commit.privilegedLoadTicketId,
            resolvedModelPath = pending?.resolvedModelPath,
        )

        when (val nativeLoad = server.loadModel(loadReq)) {
            is ServerResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(nativeLoad.error))
                commits[commit.commitId.value] = CommitRecord(
                    kind = CommitKind.LOAD,
                    state = "ABORTED",
                    payloadDigest = payload,
                    result = err,
                    loadedModelId = null,
                )
                return err
            }
            is ServerResult.Ok -> {
                val n = loadedSeq.incrementAndGet()
                val lmId = LoadedModelId("mllm-lm-$n")
                val handle = LoadedModelHandle(
                    loadedModelId = lmId,
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("mllm-alloc-$n"),
                    placementClass = plan.proposedPlacementClass,
                    runtimeEpoch = plan.runtimeEpoch,
                )
                loaded[lmId.value] = BoundLoadedModel(
                    handle = handle,
                    modelToken = nativeLoad.value,
                    backend = plan.loadKey.backend,
                )
                val ok = OmniResult.ok(handle)
                commits[commit.commitId.value] = CommitRecord(
                    kind = CommitKind.LOAD,
                    state = "COMMITTED",
                    payloadDigest = payload,
                    result = ok,
                    loadedModelId = lmId,
                )
                return ok
            }
        }
    }

    override suspend fun queryCommit(commitId: CommitId): OmniResult<CommitQueryState> {
        val rec = commits[commitId.value]
            // Fail closed: missing journal must not invent INTENT_RECORDED (REL-RECOVERY).
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "commit journal entry not found",
                    details = mapOf("commitId" to commitId.value),
                ),
            )
        return OmniResult.ok(
            CommitQueryState(
                commitId = commitId,
                state = rec.state,
                loadedModelId = rec.loadedModelId,
            ),
        )
    }

    override fun bindLoadedModel(handle: LoadedModelHandle): OmniResult<LoadedModelPort> {
        val live = loaded[handle.loadedModelId.value]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "loaded model not bound on mllm engine",
                    details = mapOf("loadedModelId" to handle.loadedModelId.value),
                ),
            )
        if (live.handle.engineBuildId != engineBuildId) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "engineBuildId mismatch"))
        }
        return OmniResult.ok(
            MllmLoadedModelPort(
                engine = this,
                bound = live,
            ),
        )
    }

    // --- internal helpers used by LoadedModelPort ---

    internal fun recordCommit(
        commit: CommitContext,
        kind: CommitKind,
        payloadDigest: String,
        loadedModelId: LoadedModelId?,
        result: OmniResult<*>,
        state: String,
    ) {
        commits[commit.commitId.value] = CommitRecord(
            kind = kind,
            state = state,
            payloadDigest = payloadDigest,
            result = result,
            loadedModelId = loadedModelId,
        )
    }

    internal fun findCommit(commitId: CommitId): CommitRecord? = commits[commitId.value]

    internal fun removeLoaded(id: LoadedModelId) {
        loaded.remove(id.value)
    }

    internal fun nextPlanId(prefix: String): PlanId =
        PlanId.parse("$prefix-${planSeq.incrementAndGet()}")

    private fun proposePlacement(backend: String): String {
        // Untrusted acceleration backends require companion (ADR-007).
        if (backend != "cpu") {
            return PlacementClassLabels.EXTERNAL_UID_ACCELERATED
        }
        // UNKNOWN cancellation ⇒ killable worker only (ENGINE-MLLM §6).
        return if (PhaseCancellationMap.anyPhaseRequiresKillableWorker(phaseCancellation)) {
            PlacementClassLabels.CRASH_CONTAINED_TRUSTED
        } else {
            PlacementClassLabels.PRIVILEGED_TRUSTED
        }
    }

    private fun loadPayload(plan: LoadPlan, commit: CommitContext): String =
        "load|${plan.planId.value}|${commit.oneShotNonce}|${commit.privilegedLoadTicketId}"

    companion object {
        fun digestOf(text: String): Sha256Digest =
            Sha256Digest.parse(IdentityHashing.sha256Hex(text))
    }

    internal enum class CommitKind { LOAD, INFERENCE, EMBEDDING }

    internal data class CommitRecord(
        val kind: CommitKind,
        val state: String,
        val payloadDigest: String,
        val result: OmniResult<*>,
        val loadedModelId: LoadedModelId?,
    )

    internal data class PendingLoad(
        val storageRootKey: String,
        val resolvedModelPath: String?,
    )

    data class BoundLoadedModel(
        val handle: LoadedModelHandle,
        val modelToken: ServerModelToken,
        val backend: String,
    )
}
