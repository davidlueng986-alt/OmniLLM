package com.omnillm.engines.api

/**
 * SEC-PLACEMENT / ADR-007 / ARCH-TRUST-TOPOLOGY — pure placement resolution.
 *
 * Dimensions (ADR-009) stay independent: authenticity is never promoted by
 * compatibility/performance; performance never lowers placement requirements.
 *
 * Untrusted accelerated inference **must** use different-package/UID companion.
 * Same-UID workers are crash containment only — never a security sandbox.
 * When the required boundary cannot be provided ⇒ [PlacementClassLabels.TRUST_PLACEMENT_REQUIRED].
 */
object TrustPlacementPolicy {

    /**
     * Inputs for a single load/plan placement decision.
     * All flags are evaluated without side effects (Plan phase pure — ADR-002).
     */
    data class Input(
        /** Model/artifact authenticity (source, signature, digest, revision pin). */
        val authenticityOk: Boolean,
        /** Engine adapter/native code has verifiable product trust for privileged path. */
        val engineCodeTrusted: Boolean,
        /**
         * Engine is stable enough for in-process / privileged host.
         * When false but trusted, prefer crash-contained same-UID worker.
         */
        val engineStabilitySufficient: Boolean = true,
        /**
         * Workload requires GPU/NPU (or other accelerator) — not CPU-only.
         * Untrusted + accelerator ⇒ companion (EXTERNAL_UID_ACCELERATED) or fail closed.
         */
        val requiresAccelerator: Boolean,
        /**
         * Engine supports isolated-UID CPU path with read-only PFDs
         * (ISOLATED_CPU_UNTRUSTED). When false, untrusted CPU also fails closed
         * unless a different allowed path exists.
         */
        val isolatedCpuSupported: Boolean,
        /**
         * Optional companion package present, different UID, same-signer signature
         * permission handshake possible, protocol major compatible (SEC-EXTERNAL-SANDBOX).
         */
        val companionAvailable: Boolean,
        /**
         * All required lifecycle phases are known-qualified for the chosen process
         * and workload envelope. Unknown ⇒ fail closed (INV-018 / SEC-PLACEMENT §2).
         */
        val phaseQualificationKnown: Boolean,
    )

    data class Decision(
        /** SEC-PLACEMENT class label (catalog string). */
        val placementClass: String,
        /** True when execution is allowed under [placementClass]. */
        val executable: Boolean,
        /** Human-readable reason (no secrets/paths). */
        val reason: String,
        /**
         * Catalog error code when not executable; typically TRUST_PLACEMENT_REQUIRED.
         * Null when [executable] is true.
         */
        val errorCode: String? = null,
    ) {
        init {
            require(PlacementClassLabels.isKnown(placementClass)) {
                "unknown placement class: $placementClass"
            }
            if (executable) {
                require(errorCode == null) { "executable decision must not carry errorCode" }
                require(PlacementClassLabels.isExecutable(placementClass)) {
                    "executable=true requires executable placement class"
                }
            } else {
                require(errorCode != null) { "non-executable decision requires errorCode" }
            }
        }
    }

    /**
     * Resolve effective placement. Never selects same-UID as a security boundary
     * for untrusted accelerated work.
     */
    fun resolve(input: Input): Decision {
        if (!input.phaseQualificationKnown) {
            return fail(
                reason = "phase qualification unknown; fail closed",
                detail = "phaseQualificationKnown=false",
            )
        }

        val modelTrusted = input.authenticityOk
        val engineTrusted = input.engineCodeTrusted

        // --- Trusted model + trusted engine ---
        if (modelTrusted && engineTrusted) {
            return if (input.engineStabilitySufficient) {
                Decision(
                    placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
                    executable = true,
                    reason = "trusted model and engine; privileged/runtime placement",
                )
            } else {
                Decision(
                    placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
                    executable = true,
                    reason = "trusted artifacts; same-UID worker for crash containment only",
                )
            }
        }

        // --- Untrusted model (or untrusted engine code treated as untrusted workload) ---
        if (input.requiresAccelerator) {
            // ADR-007: untrusted accelerated path requires different package/UID companion.
            // Never fall back to same-UID "high-performance sandbox".
            return if (input.companionAvailable) {
                Decision(
                    placementClass = PlacementClassLabels.EXTERNAL_UID_ACCELERATED,
                    executable = true,
                    reason = "untrusted accelerated path; companion UID available",
                )
            } else {
                fail(
                    reason = "untrusted accelerator requires companion; unavailable or unsupported",
                    detail = "companionAvailable=false requiresAccelerator=true",
                )
            }
        }

        // CPU-only untrusted path: isolated UID when engine supports PFD isolation.
        if (input.isolatedCpuSupported) {
            return Decision(
                placementClass = PlacementClassLabels.ISOLATED_CPU_UNTRUSTED,
                executable = true,
                reason = "untrusted CPU path; isolated UID with read-only PFDs",
            )
        }

        return fail(
            reason = "no secure placement boundary available for untrusted workload",
            detail = "isolatedCpuSupported=false companionAvailable=${input.companionAvailable}",
        )
    }

    /**
     * Host-side gate: given a proposed class (e.g. from engine planLoad), ensure
     * it is still admissible under live companion/isolation availability.
     * Unknown classes fail closed.
     */
    fun gateProposed(
        proposedPlacementClass: String,
        companionAvailable: Boolean,
        isolatedCpuAvailable: Boolean,
    ): Decision {
        if (!PlacementClassLabels.isKnown(proposedPlacementClass)) {
            return fail(
                reason = "unknown placement class",
                detail = "proposed=$proposedPlacementClass",
            )
        }
        if (!PlacementClassLabels.isExecutable(proposedPlacementClass)) {
            return fail(
                reason = "proposed placement is non-executable",
                detail = "proposed=$proposedPlacementClass",
            )
        }
        when (proposedPlacementClass) {
            PlacementClassLabels.EXTERNAL_UID_ACCELERATED -> {
                if (!companionAvailable) {
                    return fail(
                        reason = "EXTERNAL_UID_ACCELERATED requires companion; not available",
                        detail = "never same-UID fallback",
                    )
                }
                return Decision(
                    placementClass = proposedPlacementClass,
                    executable = true,
                    reason = "companion available for untrusted acceleration",
                )
            }
            PlacementClassLabels.ISOLATED_CPU_UNTRUSTED -> {
                if (!isolatedCpuAvailable) {
                    return fail(
                        reason = "ISOLATED_CPU_UNTRUSTED requires isolated CPU path",
                        detail = "isolatedCpuAvailable=false",
                    )
                }
                return Decision(
                    placementClass = proposedPlacementClass,
                    executable = true,
                    reason = "isolated CPU path available",
                )
            }
            PlacementClassLabels.PRIVILEGED_TRUSTED,
            PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            -> {
                return Decision(
                    placementClass = proposedPlacementClass,
                    executable = true,
                    reason = "trusted same-UID placement (not a security sandbox claim)",
                )
            }
            else -> {
                return fail(
                    reason = "placement not admitted",
                    detail = "proposed=$proposedPlacementClass",
                )
            }
        }
    }

    /**
     * True when [placementClass] may run inside same-UID engine worker.
     * Untrusted / external / isolated classes are never same-UID security sandboxes.
     */
    fun allowedInSameUidWorker(placementClass: String): Boolean =
        placementClass == PlacementClassLabels.PRIVILEGED_TRUSTED ||
            placementClass == PlacementClassLabels.CRASH_CONTAINED_TRUSTED

    private fun fail(reason: String, detail: String): Decision =
        Decision(
            placementClass = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
            executable = false,
            reason = if (detail.isEmpty()) reason else "$reason ($detail)",
            errorCode = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
        )
}
