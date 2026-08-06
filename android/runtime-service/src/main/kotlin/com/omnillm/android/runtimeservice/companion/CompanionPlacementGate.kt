package com.omnillm.android.runtimeservice.companion

import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.engines.api.TrustPlacementPolicy

/**
 * Host control-plane gate combining [TrustPlacementPolicy] with live
 * [CompanionAvailability] (SEC-PLACEMENT, SEC-EXTERNAL-SANDBOX, ADR-007).
 *
 * Rules:
 * - Untrusted accelerator without companion ⇒ TRUST_PLACEMENT_REQUIRED
 * - Never rewrite EXTERNAL_UID_ACCELERATED to same-UID worker placement
 * - Proposed same-UID classes never claim security sandbox semantics
 */
object CompanionPlacementGate {

    data class Result(
        val placementClass: String,
        val executable: Boolean,
        val errorCode: String?,
        val reason: String,
    )

    /**
     * Resolve placement from trust dimensions + companion/isolation availability.
     */
    fun resolve(
        authenticityOk: Boolean,
        engineCodeTrusted: Boolean,
        engineStabilitySufficient: Boolean,
        requiresAccelerator: Boolean,
        isolatedCpuSupported: Boolean,
        companion: CompanionAvailability,
        phaseQualificationKnown: Boolean = true,
    ): Result {
        val decision = TrustPlacementPolicy.resolve(
            TrustPlacementPolicy.Input(
                authenticityOk = authenticityOk,
                engineCodeTrusted = engineCodeTrusted,
                engineStabilitySufficient = engineStabilitySufficient,
                requiresAccelerator = requiresAccelerator,
                isolatedCpuSupported = isolatedCpuSupported,
                companionAvailable = companion.isAvailable,
                phaseQualificationKnown = phaseQualificationKnown,
            ),
        )
        return Result(
            placementClass = decision.placementClass,
            executable = decision.executable,
            errorCode = decision.errorCode,
            reason = decision.reason + companionDetail(companion),
        )
    }

    /**
     * Gate an engine-proposed placement class under live availability.
     * EXTERNAL_UID_ACCELERATED without companion never falls back to same-UID.
     */
    fun gateProposed(
        proposedPlacementClass: String,
        companion: CompanionAvailability,
        isolatedCpuAvailable: Boolean,
    ): Result {
        // Explicit hard rule: never map untrusted accel → same-UID.
        if (proposedPlacementClass == PlacementClassLabels.EXTERNAL_UID_ACCELERATED &&
            !companion.isAvailable
        ) {
            return Result(
                placementClass = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
                executable = false,
                errorCode = CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                reason = "companion unavailable for untrusted acceleration; " +
                    "never same-UID security sandbox fallback" +
                    companionDetail(companion),
            )
        }
        val decision = TrustPlacementPolicy.gateProposed(
            proposedPlacementClass = proposedPlacementClass,
            companionAvailable = companion.isAvailable,
            isolatedCpuAvailable = isolatedCpuAvailable,
        )
        return Result(
            placementClass = decision.placementClass,
            executable = decision.executable,
            errorCode = decision.errorCode,
            reason = decision.reason + companionDetail(companion),
        )
    }

    /**
     * True only for crash-containment / privileged same-UID worker classes.
     * EXTERNAL_UID / ISOLATED_CPU must not be admitted to `:engine_worker`.
     */
    fun allowedInSameUidWorker(placementClass: String): Boolean =
        TrustPlacementPolicy.allowedInSameUidWorker(placementClass)

    private fun companionDetail(companion: CompanionAvailability): String {
        if (companion.detail.isEmpty() && companion.isAvailable) return ""
        val extra = if (companion.detail.isNotEmpty()) " detail=${companion.detail}" else ""
        return " [companion installed=${companion.installed} differentUid=${companion.differentUid} " +
            "sameSigner=${companion.sameSigner} protocol=${companion.protocolCompatible}$extra]"
    }
}
