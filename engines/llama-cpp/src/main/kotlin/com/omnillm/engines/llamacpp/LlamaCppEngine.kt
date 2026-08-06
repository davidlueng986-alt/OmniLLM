package com.omnillm.engines.llamacpp

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
import com.omnillm.engines.llamacpp.lock.UpstreamLock
import com.omnillm.engines.llamacpp.mapping.ErrorMapper
import com.omnillm.engines.llamacpp.mapping.PhaseCancellationMap
import com.omnillm.engines.llamacpp.native.JniNativeMapping
import com.omnillm.engines.llamacpp.native.NativeBackend
import com.omnillm.engines.llamacpp.native.NativeLoadRequest
import com.omnillm.engines.llamacpp.native.NativeModelToken
import com.omnillm.engines.llamacpp.native.NativeProbeRequest
import com.omnillm.engines.llamacpp.native.NativeResult
import com.omnillm.engines.llamacpp.resource.ResourceEnvelopeEstimator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * llama.cpp [OmniEngine] adapter (ENGINE-LLAMACPP, CORE-ENGINE).
 *
 * - Plan methods are pure (no domain mutation, ADR-002)
 * - Commit is one-shot / idempotent / queryable by commitId (ADR-004/005)
 * - Never writes DB / model store
 * - Unsupported params fail closed
 * - Native work delegated to [NativeBackend] (stub or JNI)
 * - Load requires explicit EXPERIMENTAL_FIXTURE markers **or** resolved path/FD
 *   (no silent fixture substitution for real installs — INV-018)
 */
class LlamaCppEngine(
    override val engineBuildId: EngineBuildId,
    val lock: UpstreamLock,
    val native: NativeBackend,
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
    /** Plan-sideband for commitLoad (storage / path / FD). Not domain mutation. */
    private val pendingLoads = ConcurrentHashMap<String, PendingLoad>()

    override suspend fun describe(device: DeviceDescriptor): OmniResult<EngineDescriptor> {
        val backends = lock.testedBackends.ifEmpty { listOf("cpu") }
        val formats = lock.testedFormats.ifEmpty { listOf("GGUF") }
        val placement = if (lock.isComplete() && !PhaseCancellationMap.anyPhaseRequiresKillableWorker(phaseCancellation)) {
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
                engineId = LlamaCppModule.ENGINE_ID,
                backends = backends,
                supportedFormats = formats,
                placementClasses = placement,
                phaseCancellation = phaseCancellation,
                notes = mapOf(
                    "deviceFingerprint" to device.deviceExecutionFingerprint.value,
                    "lockState" to if (lock.isComplete()) UpstreamLock.LOCK_STATE_LOCKED else UpstreamLock.LOCK_STATE_NOT_LOCKED,
                    "library" to native.libraryLabel(),
                    "nativeAvailable" to native.isAvailable().toString(),
                    "abis" to device.abiList.joinToString(","),
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
                planId = PlanId.parse("llama-probe-plan-$n"),
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
        when (val r = native.probe(
            NativeProbeRequest(
                backend = plan.backend,
                operationToken = op.operationId,
            ),
        )) {
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
        }.let { return it }
    }

    override suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        // Attributes may be empty on LoadInput; backend comes from loadKey.
        val backend = input.loadKey.backend
        if (backend.isEmpty()) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "loadKey.backend required"))
        }

        val nCtx = 2048
        val nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt()
        val envelope = ResourceEnvelopeEstimator.estimateLoad(
            ResourceEnvelopeEstimator.EstimateInput(
                // Weight size unknown at pure plan without metadata — conservative placeholder.
                weightFileBytes = 0L,
                contextLength = nCtx,
                nThreads = nThreads,
                kvBytesPerToken = 0L,
                scratchBytes = 2L * 1024L * 1024L,
            ),
        )

        val placement = proposePlacement(backend)
        val n = planSeq.incrementAndGet()
        val dig = digestOf(
            "load|${input.requestId.value}|${input.installationId.value}|${input.loadKey.backend}|$n",
        )
        val planId = PlanId.parse("llama-load-plan-$n")
        // Sideband only — no weight open / DB write (ADR-002).
        pendingLoads[planId.value] = PendingLoad(
            storageRootKey = input.storageRootKey,
            resolvedModelPath = input.resolvedModelPath,
            modelFd = input.modelFd,
        )
        return OmniResult.ok(
            LoadPlan(
                planId = planId,
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

        val nCtx = 2048
        val nThreads = ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt()
        val pending = pendingLoads[plan.planId.value]
            ?: PendingLoad(
                storageRootKey = "broker:${plan.installationId.value}",
                resolvedModelPath = null,
                modelFd = -1,
            )
        val loadReq = resolveNativeLoadRequest(
            plan = plan,
            pending = pending,
            nCtx = nCtx,
            nThreads = nThreads,
            privilegedLoadTicketId = commit.privilegedLoadTicketId,
        )
        if (loadReq == null) {
            val err = OmniResult.err(
                OmniError.CAPABILITY_UNSUPPORTED(
                    message = "llama-cpp load requires explicit EXPERIMENTAL_FIXTURE markers " +
                        "or privileged path/FD — no silent fixture for real installs",
                    details = mapOf(
                        "storageRootKeyKind" to storageRootKind(pending.storageRootKey),
                        "hasPath" to (!pending.resolvedModelPath.isNullOrEmpty()).toString(),
                        "hasFd" to (pending.modelFd >= 0).toString(),
                    ),
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

        when (val nativeLoad = native.loadModel(loadReq)) {
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
                pendingLoads.remove(plan.planId.value)
                val n = loadedSeq.incrementAndGet()
                val lmId = LoadedModelId("llama-lm-$n")
                val handle = LoadedModelHandle(
                    loadedModelId = lmId,
                    installationId = plan.installationId,
                    engineBuildId = plan.engineBuildId,
                    loadKey = plan.loadKey,
                    allocationHandleId = AllocationHandleId.parse("llama-alloc-$n"),
                    placementClass = plan.proposedPlacementClass,
                    runtimeEpoch = plan.runtimeEpoch,
                )
                val bound = BoundLoadedModel(
                    handle = handle,
                    modelToken = nativeLoad.value,
                    backend = plan.loadKey.backend,
                    loadMode = if (isExplicitFixture(pending)) {
                        LOAD_MODE_EXPERIMENTAL_FIXTURE
                    } else {
                        LOAD_MODE_UPSTREAM_GGUF
                    },
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
                    message = "loaded model not bound on llama.cpp engine",
                    details = mapOf("loadedModelId" to handle.loadedModelId.value),
                ),
            )
        if (live.handle.engineBuildId != engineBuildId) {
            return OmniResult.err(OmniError.INVALID_REQUEST(message = "engineBuildId mismatch"))
        }
        return OmniResult.ok(
            LlamaCppLoadedModelPort(
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

    /**
     * Build native load request with honest mode selection.
     * Returns null when neither explicit fixture nor path/FD is present (fail closed).
     */
    private fun resolveNativeLoadRequest(
        plan: LoadPlan,
        pending: PendingLoad,
        nCtx: Int,
        nThreads: Int,
        privilegedLoadTicketId: String,
    ): NativeLoadRequest? {
        if (isExplicitFixture(pending)) {
            return NativeLoadRequest(
                storageRootKey = pending.storageRootKey.ifEmpty {
                    "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}"
                },
                installationKey = JniNativeMapping.EXPERIMENTAL_FIXTURE,
                backend = plan.loadKey.backend,
                nCtx = nCtx,
                nThreads = nThreads,
                privilegedLoadTicketId = privilegedLoadTicketId,
                resolvedModelPath = "fixture:${JniNativeMapping.EXPERIMENTAL_FIXTURE}",
                modelFd = -1,
            )
        }
        val path = pending.resolvedModelPath?.takeIf { it.isNotEmpty() }
        val fd = pending.modelFd
        if (path == null && fd < 0) {
            return null
        }
        return NativeLoadRequest(
            storageRootKey = pending.storageRootKey,
            installationKey = plan.installationId.value,
            backend = plan.loadKey.backend,
            nCtx = nCtx,
            nThreads = nThreads,
            privilegedLoadTicketId = privilegedLoadTicketId,
            resolvedModelPath = path,
            modelFd = fd,
        )
    }

    private fun isExplicitFixture(pending: PendingLoad): Boolean {
        val keys = listOfNotNull(
            pending.storageRootKey,
            pending.resolvedModelPath,
        )
        return keys.any { key ->
            key == JniNativeMapping.EXPERIMENTAL_FIXTURE || key.startsWith("fixture:")
        }
    }

    private fun storageRootKind(key: String): String = when {
        key == JniNativeMapping.EXPERIMENTAL_FIXTURE || key.startsWith("fixture:") -> "fixture"
        key.startsWith("broker:") -> "broker"
        else -> "other"
    }

    companion object {
        const val LOAD_MODE_EXPERIMENTAL_FIXTURE: String = "EXPERIMENTAL_FIXTURE"
        const val LOAD_MODE_UPSTREAM_GGUF: String = "UPSTREAM_GGUF"

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

    /** Captured at planLoad for commitLoad — not durable domain state. */
    internal data class PendingLoad(
        val storageRootKey: String,
        val resolvedModelPath: String?,
        val modelFd: Int,
    )

    data class BoundLoadedModel(
        val handle: LoadedModelHandle,
        val modelToken: NativeModelToken,
        val backend: String,
        /** Honest load mode label (fixture vs GGUF); never elevates qualification. */
        val loadMode: String = LOAD_MODE_EXPERIMENTAL_FIXTURE,
    )
}
