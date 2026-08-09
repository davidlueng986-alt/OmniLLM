package com.omnillm.android.runtimeservice.controlplane

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.contracts.ProductBuildMode
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
 * Engine selection policy for the `:runtime` control plane.
 *
 * ## Development ship mode ([ProductBuildMode.DEVELOPMENT_SHIP_MODE] = true)
 *
 * Goal: **finish all engines + features**. Selection does **not** require
 * formal QUALIFIED_WITH_ENVELOPE + lab PASS packs before execute.
 * Any catalog engine may use a real native/SDK backend when wired.
 *
 * ## Compliance mode (set DEVELOPMENT_SHIP_MODE = false)
 *
 * Restores document-style honesty: only QUALIFIED_WITH_ENVELOPE + PASS ⇒ SUPPORTED;
 * only llama-cpp native-eligible; peers stub-only.
 */
object EngineSelectionPolicy {

    val CATALOG_ENGINE_IDS: List<String> = listOf(
        LlamaCppModule.ENGINE_ID,
        LitertLmModule.ENGINE_ID,
        MlcLlmModule.ENGINE_ID,
        MllmModule.ENGINE_ID,
        OrtGenaiModule.ENGINE_ID,
    )

    /** Preferred default engine for first-path execute (still one of the catalog). */
    const val PRIMARY_ENGINE_ID: String = LlamaCppModule.ENGINE_ID

    /**
     * True when [engineId] may attempt real native/SDK load during attach.
     * Development ship mode: **all** catalog engines.
     */
    fun mayUseRealNativeBackend(engineId: String): Boolean {
        if (ProductBuildMode.allowAllEnginesNative()) return engineId in CATALOG_ENGINE_IDS
        return engineId == PRIMARY_ENGINE_ID
    }

    /**
     * True when registry has at least one executable cell.
     * Development ship mode: true if any registration exists (adapter catalog present).
     */
    fun anySupportedCell(registry: EngineRegistry): Boolean {
        if (ProductBuildMode.allowExecuteWithoutQualification()) {
            return registry.listRegistrations().isNotEmpty()
        }
        return registry.listCells().any { cell ->
            registry.projectRuntimeCapability(
                cell.qualificationStatus,
                cell.evidenceStatus,
            ) == CapabilityState.SUPPORTED
        }
    }

    /**
     * Formal qualification ⇒ SUPPORTED projection (compliance path).
     * Development path does not require this for execute (see [EngineExecuteBinding]).
     */
    fun projectsSupported(cell: EngineQualificationCell): Boolean =
        cell.qualificationStatus == EngineQualificationCellStatus.QUALIFIED_WITH_ENVELOPE &&
            cell.evidenceStatus == EvidenceStatusLabels.PASS

    fun summaryNotes(): Map<String, String> = mapOf(
        "policy" to "EngineSelectionPolicy",
        "productBuildMode" to if (ProductBuildMode.DEVELOPMENT_SHIP_MODE) {
            "DEVELOPMENT_SHIP_MODE"
        } else {
            "COMPLIANCE_HONESTY_MODE"
        },
        "catalogEngines" to CATALOG_ENGINE_IDS.joinToString(","),
        "nativeEligible" to if (ProductBuildMode.allowAllEnginesNative()) {
            "ALL_CATALOG"
        } else {
            PRIMARY_ENGINE_ID
        },
        "executeRequiresQualificationPass" to
            (!ProductBuildMode.allowExecuteWithoutQualification()).toString(),
        "peerEngines" to if (ProductBuildMode.allowAllEnginesNative()) {
            "native_sdk_allowed"
        } else {
            "stub_registry_only"
        },
        "failClosedOnUnknown" to (!ProductBuildMode.allowExecuteWithoutQualification()).toString(),
    )
}
