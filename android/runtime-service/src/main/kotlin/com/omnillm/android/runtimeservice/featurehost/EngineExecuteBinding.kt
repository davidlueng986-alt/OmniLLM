package com.omnillm.android.runtimeservice.featurehost

import android.util.Log
import com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
import com.omnillm.android.runtimeservice.controlplane.EngineSelectionPolicy
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.llamacpp.LlamaCppModule
import com.omnillm.runtime.orchestrator.CapabilityLookup
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.policy.SettingValue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Binds [EnginePackAttachment] results into Orchestrator inference + capability ports.
 *
 * ## Development ship mode (`buildMode.developmentShipMode == true`)
 *
 * Bound engines project **CONDITIONAL** (never plain SUPPORTED) for
 * generation/lifecycle caps so features can be finished without qualification
 * paperwork — while staying honest to consumers (COR-10 / INV-018/019): the
 * projection is a documented dev-override, and `conditions()` lists
 * `development_ship_mode` + the unqualified/evidence reasons. Cross-build
 * mismatch still returns UNKNOWN (no silent wrong-build fallback).
 *
 * ## Compliance mode (dev mode OFF / [ProductBuildMode.FAIL_CLOSED])
 *
 * CONDITIONAL only when exploratory flag on; never SUPPORTED without PASS.
 */
class EngineExecuteBinding(
    val inferenceEngine: DelegatingInferenceEngine = DelegatingInferenceEngine(),
    /**
     * Variant-scoped build posture (BLD-02). Default is fail-closed
     * ([ProductBuildMode.FAIL_CLOSED]); the Android control plane passes the
     * per-buildType value from BuildConfig.
     */
    val buildMode: ProductBuildMode = ProductBuildMode.FAIL_CLOSED,
    private val exploratoryEnabled: () -> Boolean = {
        buildMode.defaultExploratoryExecuteEnabled()
    },
    /**
     * Resolves real installed model bytes for llama-cpp (privileged load).
     * Production wiring supplies [RuntimeGgufModelSourceResolver]; default
     * resolves nothing (fixture-only path, same as before).
     */
    private val modelSourceResolver: LlamaCppInferenceEngineAdapter.ModelSourceResolver =
        LlamaCppInferenceEngineAdapter.NO_MODEL_SOURCE,
    /**
     * DEV mode: when no READY installation exists for a revision, the adapter
     * falls back to the explicit EXPERIMENTAL_FIXTURE path so feature journeys
     * still work end-to-end. Compliance mode keeps this false (INV-018).
     */
    private val fallbackToFixtureOnUnresolved: Boolean =
        buildMode.allowExecuteWithoutQualification(),
) {
    private val attachmentRef = AtomicReference<EnginePackAttachment?>(null)
    private val boundOnce = AtomicBoolean(false)

    val attachment: EnginePackAttachment? get() = attachmentRef.get()

    /**
     * C-02: the llama-cpp inference adapter bound by [applyAttachment] (null when
     * unbound or a test fake is bound). Exposes aggregated engine token-delta
     * text to the control-plane ports (visible assistant text).
     */
    var boundLlamaAdapter: LlamaCppInferenceEngineAdapter? = null
        private set

    val capabilityLookup: CapabilityLookup = CapabilityLookup { cap, candidate ->
        resolveCapability(cap, candidate)
    }

    /**
     * Apply [EnginePackAttachment] from [ensureEnginePacksAttached].
     *
     * C-07 multi-engine binding: every attached engine (llama-cpp + live
     * LiteRT-LM / ONNX-Runtime-GenAI peers) gets an adapter, routed by
     * engineBuildId via [MultiEngineInferenceRouter] (model's engine → matching
     * backend; no match → llama fallback; nothing attached → fail-closed).
     * When no engine is attached → keep fail-closed with clear reason.
     */
    fun applyAttachment(pack: EnginePackAttachment): ApplyResult {
        attachmentRef.set(pack)
        val adapters = linkedMapOf<String, com.omnillm.runtime.orchestrator.InferenceEnginePort>()
        var llamaAdapter: LlamaCppInferenceEngineAdapter? = null
        pack.llamaCppEngine?.let { llama ->
            val adapter = LlamaCppInferenceEngineAdapter(
                engine = llama,
                modelSourceResolver = modelSourceResolver,
                fallbackToFixtureOnUnresolved = fallbackToFixtureOnUnresolved,
            )
            llamaAdapter = adapter
            adapters[llama.engineBuildId.value] = adapter
        }
        pack.litertLmEngine?.let { litert ->
            adapters[litert.engineBuildId.value] = PeerEngineInferenceAdapter(
                engineId = com.omnillm.engines.litertlm.LitertLmModule.ENGINE_ID,
                engine = litert,
                planningAvailable = {
                    com.omnillm.engines.litertlm.LitertLmModule.isOfficialSdkOnClasspath()
                },
                envelopeProvider = {
                    com.omnillm.engines.litertlm.resource.ResourceEnvelopeEstimator
                        .estimateInference(
                            com.omnillm.engines.litertlm.resource.ResourceEnvelopeEstimator
                                .EstimateInput(
                                    contextLength = 2048,
                                    nThreads = com.omnillm.engines.litertlm.resource
                                        .ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                                    scratchBytes = 1L * 1024L * 1024L,
                                ),
                        )
                },
            )
        }
        pack.ortGenaiEngine?.let { ort ->
            adapters[ort.engineBuildId.value] = PeerEngineInferenceAdapter(
                engineId = com.omnillm.engines.ortgenai.OrtGenaiModule.ENGINE_ID,
                engine = ort,
                planningAvailable = {
                    com.omnillm.engines.ortgenai.session.GenAiBackendFactory
                        .isOfficialApiOnClasspath()
                },
                envelopeProvider = {
                    com.omnillm.engines.ortgenai.resource.ResourceEnvelopeEstimator
                        .estimateInference(
                            com.omnillm.engines.ortgenai.resource.ResourceEnvelopeEstimator
                                .EstimateInput(
                                    contextLength = 2048,
                                    nThreads = com.omnillm.engines.ortgenai.resource
                                        .ResourceEnvelopeEstimator.DEFAULT_THREADS.toInt(),
                                ),
                        )
                },
            )
        }
        boundLlamaAdapter = llamaAdapter

        if (adapters.isEmpty()) {
            inferenceEngine.unbind()
            logW(
                "no engine attached — inference remains fail-closed " +
                    "(${pack.notes["llama.failClosed"] ?: "no adapter"})",
            )
            return ApplyResult(
                bound = false,
                nativePresent = pack.nativeLibraryPresent,
                message = pack.notes["llama.failClosed"]
                    ?: "no engine attached — execute path fail-closed",
            )
        }

        inferenceEngine.bind(
            MultiEngineInferenceRouter(
                byEngineBuildId = adapters,
                fallback = llamaAdapter,
            ),
        )
        boundOnce.set(true)
        logI(
            "inference port bound engines=${adapters.keys.joinToString()} " +
                "native=${pack.nativeLibraryPresent} " +
                "exploratoryDefault=${exploratoryEnabled()} " +
                "anyExecutable=${EngineSelectionPolicy.anyExecutableCell(pack.registry, buildMode)}",
        )
        return ApplyResult(
            bound = true,
            nativePresent = pack.nativeLibraryPresent,
            message = "bound ${adapters.size} engine(s): " +
                "${adapters.keys.joinToString()}; cells remain UNKNOWN/UNQUALIFIED",
        )
    }

    /**
     * Test / Fake bind without full [EnginePackAttachment].
     * Does not mark registry cells SUPPORTED.
     */
    fun bindTestEngine(engine: com.omnillm.runtime.orchestrator.InferenceEnginePort) {
        inferenceEngine.bind(engine)
        boundOnce.set(true)
    }

    fun isEngineBound(): Boolean = inferenceEngine.isBoundToRealEngine

    fun isExploratoryExecuteEnabled(): Boolean = exploratoryEnabled()

    /**
     * Capability negotiation for Orchestrator filter 1 (COR-10 / INV-018/019).
     *
     * Dev mode: a bound engine projects **CONDITIONAL** (never plain SUPPORTED)
     * for generation caps. CONDITIONAL is the documented dev-override state —
     * it keeps the product usable (features treat CONDITIONAL as operable) while
     * never lying to consumers about qualification. Execution paths in the same
     * build honor the same posture: generation executes through the orchestrator,
     * and not-yet-implemented caps (STRUCTURED_OUTPUT / TOOL_CALLING / EMBEDDING)
     * fail with honest CAPABILITY_UNSUPPORTED/UNKNOWN + `conditions()` entries.
     */
    fun resolveCapability(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState {
        val pack = attachmentRef.get()

        if (!inferenceEngine.isBoundToRealEngine) {
            return CapabilityState.UNKNOWN
        }

        // C-07: accept any ATTACHED engine's build (llama + live peers). Unknown
        // builds stay UNKNOWN — no silent cross-revision / cross-build fallback.
        val attachedBuilds = buildSet {
            pack?.llamaCppEngine?.engineBuildId?.value?.let(::add)
            pack?.llamaCppRegistration?.engineBuildId?.value?.let(::add)
            pack?.livePeerEngineBuildIds?.forEach(::add)
        }
        if (attachedBuilds.isNotEmpty() && candidate.engineBuildId.value !in attachedBuilds) {
            return CapabilityState.UNKNOWN
        }

        if (capability in DEV_OPEN_NOT_YET_IMPLEMENTED) {
            // COR-10: execution for these caps is still a documented TODO
            // (ControlPlaneFeaturePorts returns honest CAPABILITY_UNKNOWN/
            // UNSUPPORTED). Negotiation must not widen to CONDITIONAL just because
            // exploratory is on — the cap is not implemented. Dev mode keeps the
            // dev-override CONDITIONAL with the pending marker; release stays
            // UNKNOWN (fail-closed).
            return if (buildMode.allowExecuteWithoutQualification()) {
                CapabilityState.CONDITIONAL
            } else {
                CapabilityState.UNKNOWN
            }
        }

        val generationCaps = setOf(
            CapabilityId.TEXT_GENERATION,
            CapabilityId.EMBEDDING,
            CapabilityId.REQUEST_LIFECYCLE,
            CapabilityId.SESSION_LIFECYCLE,
            CapabilityId.STREAMING,
            CapabilityId.CANCELLATION,
            CapabilityId.RESOURCE_ACCOUNTING,
            CapabilityId.CAPABILITY_NEGOTIATION,
            CapabilityId.STRUCTURED_OUTPUT,
            CapabilityId.TOOL_CALLING,
        )
        if (capability !in generationCaps) {
            return if (buildMode.allowExecuteWithoutQualification()) {
                CapabilityState.CONDITIONAL
            } else {
                CapabilityState.UNKNOWN
            }
        }

        if (buildMode.allowExecuteWithoutQualification()) {
            // Finish-all-features mode: bound adapter is executable, but the
            // projection is CONDITIONAL (dev-override), never SUPPORTED (COR-10).
            return CapabilityState.CONDITIONAL
        }

        if (!exploratoryEnabled()) {
            return CapabilityState.UNKNOWN
        }

        return CapabilityState.CONDITIONAL
    }

    /**
     * Conditions for CONDITIONAL cells (playground / server negotiation UI).
     */
    fun conditions(capability: CapabilityId, modelRevisionId: String): List<String> {
        if (resolveCapability(
                capability,
                RoutingCandidate(
                    candidateId = "cond-probe",
                    modelRevisionId = try {
                        com.omnillm.core.canonical.generated.ModelRevisionId.parse(
                            modelRevisionId.padEnd(64, '0').take(64).let {
                                if (it.matches(Regex("^[0-9a-f]{64}$"))) it else "0".repeat(64)
                            },
                        )
                    } catch (_: Exception) {
                        com.omnillm.core.canonical.generated.ModelRevisionId.parse("0".repeat(64))
                    },
                    installationId = com.omnillm.core.identity.InstallationId.parse(
                        "550e8400-e29b-41d4-a716-446655440000",
                    ),
                    engineBuildId = attachment?.llamaCppEngine?.engineBuildId
                        ?: attachment?.llamaCppRegistration?.engineBuildId
                        ?: com.omnillm.core.contracts.EngineBuildId.parse("engine-build-unbound"),
                    backend = "cpu",
                    placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                    loadKeyDigest = com.omnillm.core.canonical.generated.Sha256Digest.parse("a".repeat(64)),
                    isPrimary = true,
                    deviceExecutionFingerprint =
                        com.omnillm.core.contracts.DeviceExecutionFingerprint.parse("device-fp-probe"),
                ),
            ) != CapabilityState.CONDITIONAL
        ) {
            return emptyList()
        }
        return buildList {
            add("exploratory_execute_enabled")
            add("engine_unqualified")
            add("no_device_evidence_pack")
            add("native=${attachment?.nativeLibraryPresent == true}")
            add("engineId=${LlamaCppModule.ENGINE_ID}")
            attachment?.livePeerEngineIds?.sorted()?.forEach { engineId ->
                // C-07: disclose every live-attached engine in the negotiation
                // conditions (attachability only — never SUPPORTED evidence).
                add("engineId=$engineId")
            }
            if (buildMode.developmentShipMode) {
                // COR-10: dev-override marker — CONDITIONAL is a development
                // posture, not evidence-backed SUPPORTED.
                add("development_ship_mode")
                if (capability in DEV_OPEN_NOT_YET_IMPLEMENTED) {
                    add("dev_path_open_implementation_pending")
                }
            }
        }
    }

    data class ApplyResult(
        val bound: Boolean,
        val nativePresent: Boolean,
        val message: String,
    )

    companion object {
        private const val TAG = "OmniEngineExecute"

        /**
         * Caps whose dev-mode execution path is still a documented TODO
         * (execution returns CAPABILITY_UNSUPPORTED with honest messages).
         * Projection stays CONDITIONAL (COR-10) and `conditions()` carries the
         * `dev_path_open_implementation_pending` marker so consumers can tell
         * "dev-override open" from "implemented and executable".
         */
        private val DEV_OPEN_NOT_YET_IMPLEMENTED: Set<CapabilityId> = setOf(
            CapabilityId.EMBEDDING,
            CapabilityId.STRUCTURED_OUTPUT,
            CapabilityId.TOOL_CALLING,
        )

        /** Host unit tests have no android.util.Log runtime — swallow. */
        private fun logI(msg: String) {
            runCatching { Log.i(TAG, msg) }
        }

        private fun logW(msg: String) {
            runCatching { Log.w(TAG, msg) }
        }

        /** Product setting key (LOCAL_ADMIN). Default false — explicit opt-in. */
        const val SETTING_EXPLORATORY_EXECUTE: String = "runtime.exploratoryExecuteEnabled"

        /**
         * Read exploratory flag from policy settings snapshot values.
         */
        fun readExploratoryEnabled(values: Map<String, SettingValue>): Boolean {
            val v = values[SETTING_EXPLORATORY_EXECUTE] ?: return false
            return v is SettingValue.BoolValue && v.value
        }

        /**
         * Recommended placement for exploratory llama-cpp (UNKNOWN cancellation /
         * incomplete lock ⇒ crash-contained, not privileged-only).
         */
        const val EXPLORATORY_PLACEMENT: String = PlacementClassLabels.CRASH_CONTAINED_TRUSTED
    }
}
