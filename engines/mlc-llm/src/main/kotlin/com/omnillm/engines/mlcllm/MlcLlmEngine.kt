package com.omnillm.engines.mlcllm

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
import com.omnillm.engines.mlcllm.lock.UpstreamLock
import com.omnillm.engines.mlcllm.mapping.ErrorMapper
import com.omnillm.engines.mlcllm.mapping.PhaseCancellationMap
import com.omnillm.engines.mlcllm.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.mlcllm.runtime.NativeLoadRequest
import com.omnillm.engines.mlcllm.runtime.NativeModelToken
import com.omnillm.engines.mlcllm.runtime.NativeProbeRequest
import com.omnillm.engines.mlcllm.runtime.NativeResult
import com.omnillm.engines.mlcllm.runtime.RuntimeBackend
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MLC-LLM [OmniEngine] adapter (ENGINE-MLC, CORE-ENGINE).
 *
 * Compiler + generated-model-library + runtime mapping:
 * - Plan methods are pure (no domain mutation, ADR-002)
 * - Commit is one-shot / idempotent / queryable by commitId (ADR-004/005)
 * - Never writes DB / model store
 * - Unproven operations → [OmniError.CAPABILITY_UNKNOWN] via [RuntimeBackend]
 * - Capability matrix / Registry stay UNQUALIFIED without evidence
 * - Runtime work delegated to [RuntimeBackend] (stub or future JNI)
 */
class MlcLlmEngine(
    override val engineBuildId: EngineBuildId,
    val lock: UpstreamLock,
    val runtime: RuntimeBackend,
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
        val backends = lock.testedBackends.ifEmpty { listOf("cpu", "opencl", "vulkan") }
        val formats = lock.testedFormats.ifEmpty { listOf("mlc-model-lib") }
        // Incomplete lock or UNKNOWN cancellation ⇒ prefer worker / companion paths.
        // Untrusted generated code prefers EXTERNAL_UID (ENGINE-MLC §7 / ADR-007).
        val placement = listOf(
            PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
            PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
        )
        return OmniResult.ok(
            EngineDescriptor(
                engineBuildId = engineBuildId,
                engineId = MlcLlmModule.ENGINE_ID,
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
                    "designStatus" to MlcLlmModule.DESIGN_STATUS,
                    "qualificationStatus" to MlcLlmModule.QUALIFICATION_STATUS,
                    "registryExposure" to "UNKNOWN",
                    "library" to runtime.libraryLabel(),
                    "runtimeAvailable" to runtime.isAvailable().toString(),
                    "integrationShape" to "compiler+generated-lib+runtime",
                    "abis" to device.abiList.joinToString(","),
                    "tvmCommit" to (lock.tvmCommit.orEmpty()),
                    "knownLimitation" to
                        "unpinned backends UNKNOWN; generated code needs companion UID",
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
                planId = PlanId.parse("mlc-probe-plan-$n"),
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
        // Execute path always goes through RuntimeBackend — default stub is UNKNOWN.
        return when (
            val r = runtime.probe(
                NativeProbeRequest(
                    backend = plan.backend,
                    operationToken = op.operationId,
                ),
            )
        ) {
            is NativeResult.Ok -> OmniResult.ok(
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
            is NativeResult.Err -> OmniResult.err(ErrorMapper.toOmniError(r.error))
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
                generatedModuleBytes = 0L,
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                // Conservative CPU placeholder; GPU dims stay 0 until profile known
                scratchBytes = 4L * 1024L * 1024L,
                temporaryDiskBytes = 1L * 1024L * 1024L,
            ),
        )

        val placement = proposePlacement(backend)
        val n = planSeq.incrementAndGet()
        val dig = digestOf(
            "load|${input.requestId.value}|${input.installationId.value}|${input.loadKey.backend}|$n",
        )
        return OmniResult.ok(
            LoadPlan(
                planId = PlanId.parse("mlc-load-plan-$n"),
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

        val loadReq = NativeLoadRequest(
            storageRootKey = "broker:${plan.installationId.value}",
            installationKey = plan.installationId.value,
            backend = plan.loadKey.backend,
            privilegedLoadTicketId = commit.privilegedLoadTicketId,
            generatedLibraryDigest = lock.generatedLibraryDigest.orEmpty(),
            runtimeArtifactDigest = lock.runtimeArtifactDigest.orEmpty(),
        )

        when (val nativeLoad = runtime.loadModel(loadReq)) {
            is NativeResult.Err -> {
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
            is NativeResult.Ok -> {
                val n = loadedSeq.incrementAndGet()
                val lmId = LoadedModelId("mlc-lm-$n")
                val handle = LoadedModelHandle(
                    loadedModelId = lmId,
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("mlc-alloc-$n"),
                    placementClass = plan.proposedPlacementClass,
                    runtimeEpoch = plan.runtimeEpoch,
                )
                val bound = BoundLoadedModel(
                    handle = handle,
                    modelToken = nativeLoad.value,
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
                    message = "loaded model not bound on MLC-LLM engine",
                    details = mapOf("loadedModelId" to handle.loadedModelId.value),
                ),
            )
        if (live.handle.engineBuildId != engineBuildId) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "engineBuildId mismatch"))
        }
        return OmniResult.ok(
            MlcLlmLoadedModelPort(
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
        // Accelerators + generated code default to companion UID (ADR-007 / ENGINE-MLC §7)
        if (backend != "cpu") {
            return PlacementClassLabels.EXTERNAL_UID_ACCELERATED
        }
        // UNKNOWN cancellation ⇒ killable worker only
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
        val modelToken: NativeModelToken,
        val backend: String,
    )
}
