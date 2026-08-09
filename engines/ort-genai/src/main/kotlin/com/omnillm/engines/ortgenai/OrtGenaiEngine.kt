package com.omnillm.engines.ortgenai

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
import com.omnillm.engines.ortgenai.lock.UpstreamLock
import com.omnillm.engines.ortgenai.mapping.ErrorMapper
import com.omnillm.engines.ortgenai.mapping.PhaseCancellationMap
import com.omnillm.engines.ortgenai.resource.ResourceEnvelopeEstimator
import com.omnillm.engines.ortgenai.session.GenAiBackend
import com.omnillm.engines.ortgenai.session.GenAiLoadRequest
import com.omnillm.engines.ortgenai.session.GenAiModelToken
import com.omnillm.engines.ortgenai.session.GenAiProbeRequest
import com.omnillm.engines.ortgenai.session.GenAiResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONNX Runtime GenAI [OmniEngine] session adapter (ENGINE-ORTGENAI, CORE-ENGINE).
 *
 * - Plan methods are pure (no domain mutation, ADR-002)
 * - Commit is one-shot / idempotent / queryable by commitId (ADR-004/005)
 * - Never writes DB / model store
 * - Unproven operations → [OmniError.CAPABILITY_UNKNOWN] unless exploratory mode
 * - GenAI work delegated to [GenAiBackend] (stub or future JNI)
 * - Provider matrix remains UNKNOWN / UNQUALIFIED without cell evidence
 */
class OrtGenaiEngine(
    override val engineBuildId: EngineBuildId,
    val lock: UpstreamLock,
    val backend: GenAiBackend,
    /**
     * When false (default scaffold), probe/commitLoad refuse with CAPABILITY_UNKNOWN
     * because no qualification evidence exists for this pack.
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

    private val commits = ConcurrentHashMap<String, CommitRecord>()
    private val loaded = ConcurrentHashMap<String, BoundLoadedModel>()

    override suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor> {
        val backends = lock.testedBackends.ifEmpty { listOf("cpu") }
        val formats = lock.testedFormats.ifEmpty { listOf("ONNX-GENAI") }
        // Incomplete lock or UNKNOWN cancellation ⇒ prefer worker / isolated / companion paths
        val placement = listOf(
            PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
            PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
        )
        return OmniResult.ok(
            EngineDescriptor(
                engineBuildId = engineBuildId,
                engineId = OrtGenaiModule.ENGINE_ID,
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
                    "designStatus" to OrtGenaiModule.DESIGN_STATUS,
                    "qualificationStatus" to OrtGenaiModule.QUALIFICATION_STATUS,
                    "registryExposure" to OrtGenaiModule.REGISTRY_EXPOSURE,
                    "library" to backend.libraryLabel(),
                    "backendAvailable" to backend.isAvailable().toString(),
                    "integrationShape" to "genai-runtime-api-plus-ep-adapter",
                    "nativeWired" to backend.isAvailable().toString(),
                    "abis" to device.abiList.joinToString(","),
                    "ortRuntimeDigest" to (lock.ortRuntimeArtifactDigest.orEmpty()),
                    "configSchemaDigest" to (lock.configSchemaDigest.orEmpty()),
                    "knownLimitation" to "NOT_QUALIFIED_UNTIL_UPSTREAM_LOCK",
                ),
            ),
        )
    }

    override suspend fun planProbe(input: ProbeInput): OmniResult<ProbePlan> {
        if (input.backend.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "backend required"))
        }
        val n = planSeq.incrementAndGet()
        val dig = digestOf("ort-probe|${input.requestId.value}|${input.backend}|$n")
        return OmniResult.ok(
            ProbePlan(
                planId = PlanId.parse("ort-probe-plan-$n"),
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
        if (!allowUnprovenExecution || !lock.isComplete()) {
            // Design-complete but UNQUALIFIED: do not claim provider availability.
            return OmniResult.err(
                ErrorMapper.capabilityUnknown(
                    capability = "PROBE.${plan.backend}",
                    reason = "ONNX Runtime GenAI probe unproven: lock incomplete or no " +
                        "qualification cell PASS (ENGINE-ORTGENAI §11)",
                ),
            )
        }
        return when (
            val r = backend.probe(
                GenAiProbeRequest(
                    backend = plan.backend,
                    operationToken = op.operationId,
                ),
            )
        ) {
            is GenAiResult.Ok -> OmniResult.ok(
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
            is GenAiResult.Err -> OmniResult.err(ErrorMapper.toOmniError(r.error))
        }
    }

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        val backendId = input.loadKey.backend
        if (backendId.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "loadKey.backend required"))
        }

        val envelope = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                weightFileBytes = 0L,
                externalDataFileBytes = 0L,
                contextLength = 2048,
                nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                kvBytesPerToken = 0L,
                // Conservative graph-opt / compile-cache placeholders (advisory only).
                graphOptScratchBytes = 4L * 1024L * 1024L,
                providerCompileCacheBytes = 0L,
            ),
        )

        val placement = proposePlacement(backendId)
        val n = planSeq.incrementAndGet()
        val dig = digestOf(
            "ort-load|${input.requestId.value}|${input.installationId.value}|${input.loadKey.backend}|$n",
        )
        return OmniResult.ok(
            LoadPlan(
                planId = PlanId.parse("ort-load-plan-$n"),
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

        // Scaffold default: unproven load stays CAPABILITY_UNKNOWN (no SUPPORTED claim).
        // Missing natives must never be reported as success.
        if (!allowUnprovenExecution || !lock.isComplete()) {
            val err = OmniResult.err(
                ErrorMapper.capabilityUnknown(
                    capability = "LOAD.${plan.loadKey.backend}",
                    reason = "ONNX Runtime GenAI load unproven: incomplete UPSTREAM.lock and/or " +
                        "no QUALIFIED_WITH_ENVELOPE+PASS cell (ENGINE-ORTGENAI §11)",
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

        val providerDigest = when (plan.loadKey.backend) {
            "cpu" -> lock.providerCpuDigest.orEmpty()
            "nnapi" -> lock.providerNnapiDigest.orEmpty()
            "qnn" -> lock.providerQnnDigest.orEmpty()
            else -> ""
        }

        val loadReq = GenAiLoadRequest(
            storageRootKey = "broker:${plan.installationId.value}",
            installationKey = plan.installationId.value,
            backend = plan.loadKey.backend,
            privilegedLoadTicketId = commit.privilegedLoadTicketId,
            genAiArtifactDigest = lock.artifactDigest.orEmpty(),
            ortRuntimeDigest = lock.ortRuntimeArtifactDigest.orEmpty(),
            providerLibraryDigest = providerDigest,
            configSchemaDigest = lock.configSchemaDigest.orEmpty(),
        )

        when (val genLoad = backend.loadModel(loadReq)) {
            is GenAiResult.Err -> {
                val err = OmniResult.err(ErrorMapper.toOmniError(genLoad.error))
                commits[commit.commitId.value] = CommitRecord(
                    kind = CommitKind.LOAD,
                    state = "ABORTED",
                    payloadDigest = payload,
                    result = err,
                    loadedModelId = null,
                )
                return err
            }
            is GenAiResult.Ok -> {
                val n = loadedSeq.incrementAndGet()
                val lmId = LoadedModelId("ort-genai-lm-$n")
                val handle = LoadedModelHandle(
                    loadedModelId = lmId,
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("ort-alloc-$n"),
                    placementClass = plan.proposedPlacementClass,
                    runtimeEpoch = plan.runtimeEpoch,
                )
                val bound = BoundLoadedModel(
                    handle = handle,
                    modelToken = genLoad.value,
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
                    message = "loaded model not bound on ONNX Runtime GenAI engine",
                    details = mapOf("loadedModelId" to handle.loadedModelId.value),
                ),
            )
        if (live.handle.engineBuildId != engineBuildId) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "engineBuildId mismatch"))
        }
        return OmniResult.ok(
            OrtGenaiLoadedModelPort(
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
        // Non-CPU EPs require companion UID acceleration when untrusted (ADR-007).
        if (backend != "cpu") {
            return PlacementClassLabels.EXTERNAL_UID_ACCELERATED
        }
        // UNKNOWN cancel always prefers crash-contained / worker path.
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
        val modelToken: GenAiModelToken,
        val backend: String,
    )
}
