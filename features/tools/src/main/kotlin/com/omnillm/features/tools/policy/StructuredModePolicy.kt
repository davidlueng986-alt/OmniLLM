package com.omnillm.features.tools.policy

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.tools.domain.StructuredCallerPolicy
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ValidationStatusCode

/**
 * Resolves actual structured mode from engine cell + caller policy (FEAT-TOOLS §2).
 *
 * Invariants:
 * - Never silently demote NATIVE_CONSTRAINED to free text / omitted mode.
 * - When engine lacks native grammar and caller disallows post-validation ⇒ reject.
 * - When allowed, disclose mode and attempt caps.
 */
object StructuredModePolicy {

    data class EngineStructuredCell(
        /** Highest mode the engine/model cell can offer for this workload. */
        val offeredMode: StructuredMode,
        val capabilityState: CapabilityState = CapabilityState.SUPPORTED,
        val engineBuildId: String = "",
        val workloadEnvelope: String = "default",
    )

    data class ModeDecision(
        val actualMode: StructuredMode,
        val maxAttempts: Int,
        val maxRepairAttempts: Int,
        val disclosed: Boolean = true,
        val validationCode: ValidationStatusCode = ValidationStatusCode.OK,
    )

    data class ModeDenial(
        val error: OmniError,
        val validationCode: ValidationStatusCode,
        val offeredMode: StructuredMode,
    )

    sealed class ResolveResult {
        data class Allow(val decision: ModeDecision) : ResolveResult()
        data class Deny(val denial: ModeDenial) : ResolveResult()
    }

    fun resolve(
        cell: EngineStructuredCell,
        caller: StructuredCallerPolicy,
    ): ResolveResult {
        when (cell.capabilityState) {
            CapabilityState.UNSUPPORTED -> {
                return deny(
                    offered = StructuredMode.UNSUPPORTED,
                    code = ValidationStatusCode.MODE_UNSUPPORTED,
                    error = OmniError.CAPABILITY_UNSUPPORTED(
                        message = "STRUCTURED_OUTPUT unsupported for cell",
                        details = mapOf(
                            "state" to CapabilityState.UNSUPPORTED.name,
                            "offeredMode" to StructuredMode.UNSUPPORTED.name,
                        ),
                    ),
                )
            }
            CapabilityState.UNKNOWN -> {
                return deny(
                    offered = StructuredMode.UNSUPPORTED,
                    code = ValidationStatusCode.CAPABILITY_BLOCKED,
                    error = OmniError.CAPABILITY_UNKNOWN(
                        message = "STRUCTURED_OUTPUT cell unknown (fail closed)",
                        details = mapOf(
                            "state" to CapabilityState.UNKNOWN.name,
                        ),
                    ),
                )
            }
            CapabilityState.TEMPORARILY_UNAVAILABLE -> {
                return deny(
                    offered = cell.offeredMode,
                    code = ValidationStatusCode.CAPABILITY_BLOCKED,
                    error = OmniError.CAPABILITY_UNSUPPORTED(
                        message = "STRUCTURED_OUTPUT temporarily unavailable",
                        details = mapOf(
                            "state" to CapabilityState.TEMPORARILY_UNAVAILABLE.name,
                        ),
                    ),
                )
            }
            CapabilityState.CONDITIONAL,
            CapabilityState.SUPPORTED,
            -> Unit
        }

        if (cell.offeredMode == StructuredMode.UNSUPPORTED) {
            return deny(
                offered = StructuredMode.UNSUPPORTED,
                code = ValidationStatusCode.MODE_UNSUPPORTED,
                error = OmniError.CAPABILITY_UNSUPPORTED(
                    message = "engine offers no structured mode",
                    details = mapOf("offeredMode" to StructuredMode.UNSUPPORTED.name),
                ),
            )
        }

        // Prefer native when offered.
        if (cell.offeredMode == StructuredMode.NATIVE_CONSTRAINED) {
            return ResolveResult.Allow(
                ModeDecision(
                    actualMode = StructuredMode.NATIVE_CONSTRAINED,
                    maxAttempts = 1,
                    maxRepairAttempts = 0,
                    validationCode = ValidationStatusCode.OK,
                ),
            )
        }

        // Caller requires native but engine cannot provide it.
        if (caller.requireNativeConstrained ||
            (!caller.allowPostValidate && !caller.allowRepairRetry)
        ) {
            return deny(
                offered = cell.offeredMode,
                code = ValidationStatusCode.MODE_POLICY_DENIED,
                error = OmniError.CAPABILITY_UNSUPPORTED(
                    message = "native constrained required; engine lacks grammar " +
                        "and caller forbids post-validation (no silent fallback)",
                    details = mapOf(
                        "offeredMode" to cell.offeredMode.name,
                        "requireNativeConstrained" to caller.requireNativeConstrained.toString(),
                        "allowPostValidate" to caller.allowPostValidate.toString(),
                        "allowRepairRetry" to caller.allowRepairRetry.toString(),
                        "silentFallback" to "false",
                    ),
                ),
            )
        }

        // Explicit non-native path — always disclose.
        return when (cell.offeredMode) {
            StructuredMode.REPAIR_RETRY -> {
                if (!caller.allowRepairRetry) {
                    // May still allow POST_VALIDATE if offered can degrade *explicitly*.
                    if (caller.allowPostValidate) {
                        return ResolveResult.Allow(
                            ModeDecision(
                                actualMode = StructuredMode.POST_VALIDATE,
                                maxAttempts = caller.maxValidationAttempts,
                                maxRepairAttempts = 0,
                                validationCode = ValidationStatusCode.OK,
                            ),
                        )
                    }
                    return deny(
                        offered = cell.offeredMode,
                        code = ValidationStatusCode.MODE_POLICY_DENIED,
                        error = OmniError.CAPABILITY_UNSUPPORTED(
                            message = "repair/post-validate not allowed by caller policy",
                            details = mapOf(
                                "offeredMode" to cell.offeredMode.name,
                                "silentFallback" to "false",
                            ),
                        ),
                    )
                }
                ResolveResult.Allow(
                    ModeDecision(
                        actualMode = StructuredMode.REPAIR_RETRY,
                        maxAttempts = caller.maxValidationAttempts,
                        maxRepairAttempts = caller.maxRepairAttempts,
                        validationCode = ValidationStatusCode.OK,
                    ),
                )
            }
            StructuredMode.POST_VALIDATE -> {
                if (!caller.allowPostValidate) {
                    return deny(
                        offered = cell.offeredMode,
                        code = ValidationStatusCode.MODE_POLICY_DENIED,
                        error = OmniError.CAPABILITY_UNSUPPORTED(
                            message = "post-validation not allowed by caller policy",
                            details = mapOf(
                                "offeredMode" to cell.offeredMode.name,
                                "silentFallback" to "false",
                            ),
                        ),
                    )
                }
                ResolveResult.Allow(
                    ModeDecision(
                        actualMode = StructuredMode.POST_VALIDATE,
                        maxAttempts = caller.maxValidationAttempts,
                        maxRepairAttempts = 0,
                        validationCode = ValidationStatusCode.OK,
                    ),
                )
            }
            StructuredMode.NATIVE_CONSTRAINED,
            StructuredMode.UNSUPPORTED,
            -> error("unreachable after guards")
        }
    }

    /**
     * Guard: actual mode in a response/plan must never be omitted or rewritten
     * to free-text without disclosure. Returns error if a silent demotion is detected.
     */
    fun assertDisclosedMode(
        plannedMode: StructuredMode,
        actualMode: StructuredMode?,
        freeTextWithoutSchema: Boolean,
    ): OmniError? {
        if (actualMode == null) {
            return OmniError.INTERNAL(
                message = "actual structured mode must be disclosed",
                details = mapOf("plannedMode" to plannedMode.name),
            )
        }
        if (freeTextWithoutSchema && plannedMode != StructuredMode.UNSUPPORTED) {
            return OmniError.INTERNAL(
                message = "silent fallback from structured mode to free text is forbidden",
                details = mapOf(
                    "plannedMode" to plannedMode.name,
                    "actualMode" to actualMode.name,
                    "silentFallback" to "true",
                ),
            )
        }
        // Native planned but actual non-native without prior policy allow is a bug.
        if (plannedMode == StructuredMode.NATIVE_CONSTRAINED &&
            actualMode != StructuredMode.NATIVE_CONSTRAINED
        ) {
            return OmniError.INTERNAL(
                message = "planned NATIVE_CONSTRAINED demoted without policy path",
                details = mapOf(
                    "plannedMode" to plannedMode.name,
                    "actualMode" to actualMode.name,
                ),
            )
        }
        return null
    }

    private fun deny(
        offered: StructuredMode,
        code: ValidationStatusCode,
        error: OmniError,
    ): ResolveResult.Deny =
        ResolveResult.Deny(
            ModeDenial(
                error = error,
                validationCode = code,
                offeredMode = offered,
            ),
        )
}
