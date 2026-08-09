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
 * ## Development ship mode ([ProductBuildMode.DEVELOPMENT_SHIP_MODE])
 *
 * Bound engines project **SUPPORTED** for generation/lifecycle caps so features
 * can be finished without qualification paperwork. Cross-build mismatch still
 * returns UNKNOWN (no silent wrong-build fallback).
 *
 * ## Compliance mode (DEVELOPMENT_SHIP_MODE = false)
 *
 * CONDITIONAL only when exploratory flag on; never SUPPORTED without PASS.
 */
class EngineExecuteBinding(
    val inferenceEngine: DelegatingInferenceEngine = DelegatingInferenceEngine(),
    private val exploratoryEnabled: () -> Boolean = {
        ProductBuildMode.defaultExploratoryExecuteEnabled()
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
        ProductBuildMode.allowExecuteWithoutQualification(),
) {
    private val attachmentRef = AtomicReference<EnginePackAttachment?>(null)
    private val boundOnce = AtomicBoolean(false)

    val attachment: EnginePackAttachment? get() = attachmentRef.get()

    val capabilityLookup: CapabilityLookup = CapabilityLookup { cap, candidate ->
        resolveCapability(cap, candidate)
    }

    /**
     * Apply [EnginePackAttachment] from [ensureEnginePacksAttached].
     * When llama-cpp adapter present → bind [LlamaCppInferenceEngineAdapter].
     * When missing → keep fail-closed with clear reason.
     */
    fun applyAttachment(pack: EnginePackAttachment): ApplyResult {
        attachmentRef.set(pack)
        if (pack.llamaCppEngine != null) {
            inferenceEngine.bind(
                LlamaCppInferenceEngineAdapter(
                    engine = pack.llamaCppEngine!!,
                    modelSourceResolver = modelSourceResolver,
                    fallbackToFixtureOnUnresolved = fallbackToFixtureOnUnresolved,
                ),
            )
            boundOnce.set(true)
            logI(
                "inference port bound to llama-cpp native=${pack.nativeLibraryPresent} " +
                    "exploratoryDefault=${exploratoryEnabled()} " +
                    "anySupported=${EngineSelectionPolicy.anySupportedCell(pack.registry)}",
            )
            return ApplyResult(
                bound = true,
                nativePresent = pack.nativeLibraryPresent,
                message = "llama-cpp adapter bound; cells remain UNKNOWN/UNQUALIFIED",
            )
        }
        inferenceEngine.unbind()
        logW(
            "llama-cpp native missing — inference remains fail-closed " +
                "(${pack.notes["llama.failClosed"] ?: "no adapter"})",
        )
        return ApplyResult(
            bound = false,
            nativePresent = pack.nativeLibraryPresent,
            message = pack.notes["llama.failClosed"]
                ?: "libomnillm_llama missing — execute path fail-closed",
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
     * Capability negotiation for Orchestrator filter 1.
     * Development ship mode: bound engine ⇒ SUPPORTED for generation caps.
     */
    fun resolveCapability(capability: CapabilityId, candidate: RoutingCandidate): CapabilityState {
        val pack = attachmentRef.get()

        if (!inferenceEngine.isBoundToRealEngine) {
            return CapabilityState.UNKNOWN
        }

        val attachedBuild = pack?.llamaCppEngine?.engineBuildId?.value
            ?: pack?.llamaCppRegistration?.engineBuildId?.value
        if (attachedBuild != null && candidate.engineBuildId.value != attachedBuild) {
            // No silent cross-revision / cross-build fallback.
            return CapabilityState.UNKNOWN
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
            return if (ProductBuildMode.allowExecuteWithoutQualification()) {
                CapabilityState.CONDITIONAL
            } else {
                CapabilityState.UNKNOWN
            }
        }

        if (ProductBuildMode.allowExecuteWithoutQualification()) {
            // Finish-all-features mode: treat bound adapter as executable.
            return CapabilityState.SUPPORTED
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
        return listOf(
            "exploratory_execute_enabled",
            "engine_unqualified",
            "no_device_evidence_pack",
            "native=${attachment?.nativeLibraryPresent == true}",
            "engineId=${LlamaCppModule.ENGINE_ID}",
        )
    }

    data class ApplyResult(
        val bound: Boolean,
        val nativePresent: Boolean,
        val message: String,
    )

    companion object {
        private const val TAG = "OmniEngineExecute"

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
