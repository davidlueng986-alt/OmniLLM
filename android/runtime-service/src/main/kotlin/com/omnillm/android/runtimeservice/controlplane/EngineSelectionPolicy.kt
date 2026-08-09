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
 * ## Development ship mode (`buildMode.developmentShipMode == true`)
 *
 * Goal: **finish all engines + features**. Selection does **not** require
 * formal QUALIFIED_WITH_ENVELOPE + lab PASS packs before execute.
 * Any catalog engine may use a real native/SDK backend when wired.
 * Projections stay honest (COR-10): the policy never claims `SUPPORTED`
 * without evidence — dev mode only widens **executability**, see
 * [anyExecutableCell].
 *
 * ## Compliance mode (dev mode OFF / [ProductBuildMode.FAIL_CLOSED])
 *
 * Restores document-style honesty: only QUALIFIED_WITH_ENVELOPE + PASS ⇒ SUPPORTED;
 * only llama-cpp native-eligible; peers stub-only.
 *
 * The mode travels as an explicit [ProductBuildMode] parameter (manual DI);
 * anything not wired defaults to fail-closed.
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
     * Development ship mode: **all** catalog engines; otherwise llama-cpp only.
     */
    fun mayUseRealNativeBackend(
        engineId: String,
        buildMode: ProductBuildMode = ProductBuildMode.FAIL_CLOSED,
    ): Boolean {
        if (buildMode.allowAllEnginesNative()) return engineId in CATALOG_ENGINE_IDS
        return engineId == PRIMARY_ENGINE_ID
    }

    /**
     * True when the registry contains at least one **executable** path.
     *
     * Naming is deliberate (COR-10): this must never be read as "projects
     * SUPPORTED". Dev mode: any registration present (attach shortcut, no
     * qualification required). Compliance mode: only cells that project
     * [CapabilityState.SUPPORTED] from evidence (QUALIFIED_WITH_ENVELOPE + PASS).
     */
    fun anyExecutableCell(
        registry: EngineRegistry,
        buildMode: ProductBuildMode = ProductBuildMode.FAIL_CLOSED,
    ): Boolean {
        if (buildMode.allowExecuteWithoutQualification()) {
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

    fun summaryNotes(buildMode: ProductBuildMode = ProductBuildMode.FAIL_CLOSED): Map<String, String> = mapOf(
        "policy" to "EngineSelectionPolicy",
        "productBuildMode" to if (buildMode.developmentShipMode) {
            "DEVELOPMENT_SHIP_MODE"
        } else {
            "COMPLIANCE_HONESTY_MODE"
        },
        "catalogEngines" to CATALOG_ENGINE_IDS.joinToString(","),
        "nativeEligible" to if (buildMode.allowAllEnginesNative()) {
            "ALL_CATALOG"
        } else {
            PRIMARY_ENGINE_ID
        },
        "executeRequiresQualificationPass" to
            (!buildMode.allowExecuteWithoutQualification()).toString(),
        "peerEngines" to if (buildMode.allowAllEnginesNative()) {
            "native_sdk_allowed"
        } else {
            "stub_registry_only"
        },
        "failClosedOnUnknown" to (!buildMode.allowExecuteWithoutQualification()).toString(),
    )
}
