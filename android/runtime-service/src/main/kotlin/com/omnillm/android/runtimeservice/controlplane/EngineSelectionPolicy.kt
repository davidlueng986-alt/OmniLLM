package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.engines.api.EngineQualificationCell
import com.omnillm.engines.api.EngineQualificationCellStatus
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.api.EvidenceStatusLabels
import com.omnillm.engines.litertlm.LitertLmModule
import com.omnillm.engines.llamacpp.LlamaCppModule
import com.omnillm.engines.mlcllm.MlcLlmModule
import com.omnillm.engines.mllm.MllmModule
import com.omnillm.engines.ortgenai.OrtGenaiModule

/**
 * Engine selection policy for the `:runtime` control plane (ADR-010, INV-018,
 * ENGINE-QUALIFICATION-STATUS, ENGINE-STANDARD).
 *
 * ## Catalog (all registered; none claimed SUPPORTED without evidence)
 *
 * | engineId | Module | Production backend |
 * |---|---|---|
 * | `llama.cpp` | `:engines:llama-cpp` | Real [com.omnillm.engines.llamacpp.native.JniNativeBackend] when `libomnillm_llama` loads; else fail-closed (no Stub substitute) |
 * | `LiteRT-LM` | `:engines:litert-lm` | Stub / registry-only — UNKNOWN |
 * | `MLC-LLM` | `:engines:mlc-llm` | Stub / registry-only — UNKNOWN |
 * | `mllm` | `:engines:mllm` | Stub / registry-only — UNKNOWN |
 * | `ONNX-Runtime-GenAI` | `:engines:ort-genai` | Stub / registry-only — UNKNOWN |
 *
 * ## Selection rules (normative for Orchestrator / execute path)
 *
 * 1. **Registry is authority for exposure** — a candidate is executable only when
 *    [EngineRegistry.resolveCapability] projects [CapabilityState.SUPPORTED]
 *    for the phase-capability cell (QUALIFIED_WITH_ENVELOPE + PASS evidence).
 * 2. **Fail closed on UNKNOWN / missing cell** — never treat design BASELINE,
 *    stub adapter presence, or native library presence as SUPPORTED.
 * 3. **Only llama-cpp may load a real native backend** in this process attach.
 *    Peer engines remain stub/UNKNOWN until their own native/SDK wiring +
 *    device evidence packs land.
 * 4. **Native library ≠ qualification** — attaching `libomnillm_llama` enables
 *    the adapter for exploratory execute plumbing only; cells stay UNQUALIFIED
 *    until PASS evidence is recorded under the cell envelope.
 * 5. **No silent inheritance** — evidence never generalizes across backend,
 *    device, model, or workload envelopes.
 * 6. **UI / transport never selects engines** (INV-001 / ADR-011) — only the
 *    control-plane Orchestrator routes using Registry projection.
 * 7. **Exploratory CONDITIONAL execute** — when native is bound **and** product
 *    setting `runtime.exploratoryExecuteEnabled=true`, Orchestrator capability
 *    lookup may return [CapabilityState.CONDITIONAL] for TEXT_GENERATION only
 *    (never SUPPORTED). See
 *    [com.omnillm.android.runtimeservice.featurehost.EngineExecuteBinding].
 *
 * See also: `docs/architecture/engine-registry-attachment.md`.
 */
object EngineSelectionPolicy {

    /** Catalog engineIds in stable order (matches specs/engine-qualification-status.yaml). */
    val CATALOG_ENGINE_IDS: List<String> = listOf(
        LlamaCppModule.ENGINE_ID,
        LitertLmModule.ENGINE_ID,
        MlcLlmModule.ENGINE_ID,
        MllmModule.ENGINE_ID,
        OrtGenaiModule.ENGINE_ID,
    )

    /** Engine id allowed to use a real native backend in production attach. */
    const val NATIVE_ELIGIBLE_ENGINE_ID: String = LlamaCppModule.ENGINE_ID

    /**
     * True when [engineId] may attempt real native load during
     * [EnginePackAttachment.attachAfterReady]. Peers must stay stub/UNKNOWN.
     */
    fun mayUseRealNativeBackend(engineId: String): Boolean =
        engineId == NATIVE_ELIGIBLE_ENGINE_ID

    /**
     * True when any registered cell projects SUPPORTED.
     * Production attach must leave this false until real evidence exists.
     */
    fun anySupportedCell(registry: EngineRegistry): Boolean =
        registry.listCells().any { cell ->
            registry.projectRuntimeCapability(
                cell.qualificationStatus,
                cell.evidenceStatus,
            ) == CapabilityState.SUPPORTED
        }

    /**
     * Assert projection invariants used by attach tests and fail-closed gates:
     * every cell is either non-envelope or non-PASS evidence ⇒ not SUPPORTED.
     */
    fun projectsSupported(cell: EngineQualificationCell): Boolean =
        cell.qualificationStatus == EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE &&
            cell.evidenceStatus == EvidenceStatusLabels.PASS

    /** Human-readable policy summary for diagnostics / notes maps. */
    fun summaryNotes(): Map<String, String> = mapOf(
        "policy" to "EngineSelectionPolicy",
        "catalogEngines" to CATALOG_ENGINE_IDS.joinToString(","),
        "nativeEligible" to NATIVE_ELIGIBLE_ENGINE_ID,
        "supportedRequires" to "QUALIFIED_WITH_ENVELOPE+PASS",
        "defaultExposure" to "UNKNOWN",
        "peerEngines" to "stub_registry_only",
        "failClosedOnUnknown" to "true",
    )
}
