package com.omnillm.engines.litertlm

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
import com.omnillm.engines.litertlm.lock.UpstreamLock
import com.omnillm.engines.litertlm.mapping.ErrorMapper
import com.omnillm.engines.litertlm.mapping.PhaseCancellationMap
import com.omnillm.engines.litertlm.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.litertlm.sdk.SdkBackend
import com.omnillm.engines.litertlm.sdk.SdkEngineToken
import com.omnillm.engines.litertlm.sdk.SdkLoadRequest
import com.omnillm.engines.litertlm.sdk.SdkProbeRequest
import com.omnillm.engines.litertlm.sdk.SdkResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * LiteRT-LM [OmniEngine] adapter (ENGINE-LITERT, CORE-ENGINE).
 *
 * - Plan methods are pure (no domain mutation, ADR-002)
 * - Commit is one-shot / idempotent / queryable by commitId (ADR-004/005)
 * - Never writes DB / model store
 * - Unsupported params fail closed
 * - Unproven backends/ops map to CAPABILITY_UNKNOWN (not silent SUPPORTED)
 * - SDK work delegated to [SdkBackend] ([com.omnillm.engines.litertlm.sdk.StubSdkBackend]
 *   for host tests; [com.omnillm.engines.litertlm.sdk.RealSdkBackend] for production)
 */
class LitertLmEngine(
    override val engineBuildId: EngineBuildId,
    val lock: UpstreamLock,
    val sdk: SdkBackend,
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

    private val commits = ConcurrentHashMap<String, CommitRecord>()
    private val loaded = ConcurrentHashMap<String, BoundLoadedModel>()

    override suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor> {
        val backends = lock.testedBackends.ifEmpty { listOf("cpu") }
        val formats = lock.testedFormats.ifEmpty { listOf("litertlm") }
        val placement = if (lock.isComplete() &&
            !PhaseCancellationMap.anyPhaseRequiresKillableWorker(phaseCancellation)
        ) {
            listOf(
                PlacementClassLabels.PRIVILEGED_TRUSTED,
                PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
            )
        } else {
            // Incomplete lock or UNKNOWN cancellation ⇒ prefer worker / isolated paths
            listOf(
                PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
                PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
            )
        }
        return OmniResult.ok(
            EngineDescriptor(
                engineBuildId = engineBuildId,
                engineId = LitertLmModule.ENGINE_ID,
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
                    "lockEligibility" to UpstreamLock.LOCK_ELIGIBILITY,
                    "library" to sdk.libraryLabel(),
                    "sdkAvailable" to sdk.isAvailable().toString(),
                    "sdkBackendKind" to sdk.javaClass.simpleName,
                    "designStatus" to LitertLmModule.DESIGN_STATUS,
                    "qualificationStatus" to LitertLmModule.QUALIFICATION_STATUS,
                    "abis" to device.abiList.joinToString(","),
                    "integrationShape" to "official-sdk-aar",
                    "officialSdkOnClasspath" to LitertLmModule.isOfficialSdkOnClasspath().toString(),
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
                planId = PlanId.parse("litert-probe-plan-$n"),
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

    override suspend fun probe(
        plan: ProbePlan,
        reservation: Reservation,
        op: OperationContext,
    ): OmniResult<ProbeResult> {
        if (reservation.principalId.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "empty reservation principal"))
        }
        return when (
            val r = sdk.probe(
                SdkProbeRequest(
                    backend = plan.backend,
                    operationToken = op.operationId,
                ),
            )
        ) {
            is SdkResult.Ok -> OmniResult.ok(
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
            is SdkResult.Err -> OmniResult.err(ErrorMapper.toOmniError(r.error))
        }
    }

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        val backend = input.loadKey.backend
        if (backend.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "loadKey.backend required"))
        }

        val envelope = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 0L,
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                scratchBytes = 2L * 1024L * 1024L,
                compiledCacheBytes = 0L,
            ),
        )

        val placement = proposePlacement(backend)
        val n = planSeq.incrementAndGet()
        val dig = digestOf(
            "load|${input.requestId.value}|${input.installationId.value}|${input.loadKey.backend}|$n",
        )
        return OmniResult.ok(
            LoadPlan(
                planId = PlanId.parse("litert-load-plan-$n"),
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

        val loadReq = SdkLoadRequest(
            storageRootKey = "broker:${plan.installationId.value}",
            installationKey = plan.installationId.value,
            backend = plan.loadKey.backend,
            privilegedLoadTicketId = commit.privilegedLoadTicketId,
        )

        when (val sdkLoad = sdk.loadEngine(loadReq)) {
            is SdkResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(sdkLoad.error))
                commits[commit.commitId.value] = CommitRecord(
                    kind = CommitKind.LOAD,
                    state = "ABORTED",
                    payloadDigest = payload,
                    result = err,
                    loadedModelId = null,
                )
                return err
            }
            is SdkResult.Ok -> {
                val n = loadedSeq.incrementAndGet()
                val lmId = LoadedModelId("litert-lm-$n")
                val handle = LoadedModelHandle(
                    loadedModelId = lmId,
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("litert-alloc-$n"),
                    placementClass = plan.proposedPlacementClass,
                    runtimeEpoch = plan.runtimeEpoch,
                )
                val bound = BoundLoadedModel(
                    handle = handle,
                    engineToken = sdkLoad.value,
                    backend = plan.loadKey.backend,
                )
                loaded[lmId.value] = bound
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
                    message = "loaded model not bound on LiteRT-LM engine",
                    details = mapOf("loadedModelId" to handle.loadedModelId.value),
                ),
            )
        if (live.handle.engineBuildId != engineBuildId) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "engineBuildId mismatch"))
        }
        return OmniResult.ok(
            LitertLmLoadedModelPort(
                engine = this,
                bound = live,
            ),
        )
    }

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
        // Untrusted acceleration backends require companion (ADR-007) when not fully locked.
        if (backend != "cpu") {
            return PlacementClassLabels.EXTERNAL_UID_ACCELERATED
        }
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

    data class BoundLoadedModel(
        val handle: LoadedModelHandle,
        val engineToken: SdkEngineToken,
        val backend: String,
    )
}
