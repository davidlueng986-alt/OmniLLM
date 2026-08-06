package com.omnillm.features.autosetup.config

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.features.autosetup.domain.AutomatedConfiguration
import com.omnillm.features.autosetup.domain.ConfigField
import com.omnillm.features.autosetup.domain.ConfigValueSources
import com.omnillm.features.autosetup.domain.DeviceDiscoverySnapshot
import com.omnillm.features.autosetup.domain.ModelCandidateInput
import com.omnillm.features.autosetup.domain.RecommendationReasonCodes
import com.omnillm.features.autosetup.domain.UserSetupPreferences

/**
 * Pure automated configuration (FEAT-AUTOSETUP §5).
 *
 * Every field carries source provenance. Workspace-increasing keys set
 * [AutomatedConfiguration.requiresPlanReservation] so callers re-Plan/reserve
 * (configuration-catalog: requiresPlanReservationCommit / admissionBound).
 */
object AutomatedConfigurationBuilder {

    private const val DEFAULT_CONTEXT: Int = 2048
    private const val DEFAULT_MAX_OUTPUT: Int = 512
    private const val DEFAULT_THREADS: Int = 2
    private const val DEFAULT_BATCH: Int = 1
    private const val DEFAULT_PARALLELISM: Int = 1
    private const val CONSERVATIVE_CONTEXT_UNKNOWN_DEVICE: Int = 1024

    fun build(
        candidate: ModelCandidateInput,
        device: DeviceDiscoverySnapshot,
        preferences: UserSetupPreferences,
    ): AutomatedConfiguration {
        val conservative = device.ramClassBytes == null ||
            device.gpuEvidenceLabel == EvidenceLabel.UNKNOWN

        val context = if (conservative) {
            ConfigField(
                key = "inference.contextTokens",
                value = CONSERVATIVE_CONTEXT_UNKNOWN_DEVICE,
                source = ConfigValueSources.DEVICE_EVIDENCE,
                reasonCode = RecommendationReasonCodes.RESOURCE_FIT,
            )
        } else {
            ConfigField(
                key = "inference.contextTokens",
                value = DEFAULT_CONTEXT,
                source = ConfigValueSources.MODEL_RECOMMENDATION,
                reasonCode = RecommendationReasonCodes.SELECTED,
            )
        }

        // Clamp context soft-bound by estimated peak vs RAM when both known.
        val ram = device.ramClassBytes
        val contextClamped = if (ram != null && candidate.estimatedPeakBytes > 0L) {
            val headroom = ram - candidate.estimatedPeakBytes
            if (headroom < 256L * 1024L * 1024L && context.value > 1024) {
                ConfigField(
                    key = context.key,
                    value = 1024,
                    source = ConfigValueSources.DEVICE_POLICY,
                    reasonCode = RecommendationReasonCodes.RESOURCE_FIT,
                )
            } else {
                context
            }
        } else {
            context
        }

        val maxOut = ConfigField(
            key = "inference.maxOutputTokens",
            value = DEFAULT_MAX_OUTPUT,
            source = ConfigValueSources.MODEL_RECOMMENDATION,
            reasonCode = RecommendationReasonCodes.SELECTED,
        )

        val threads = ConfigField(
            key = "runtime.cpuThreads",
            value = if (preferences.preferLowPower) 1 else DEFAULT_THREADS,
            source = if (preferences.preferLowPower) {
                ConfigValueSources.USER_PREFERENCE
            } else {
                ConfigValueSources.DEVICE_POLICY
            },
            reasonCode = RecommendationReasonCodes.USER_PREFERENCE,
        )

        val batch = ConfigField(
            key = "runtime.prefillBatch",
            value = DEFAULT_BATCH,
            source = ConfigValueSources.MODEL_RECOMMENDATION,
            reasonCode = RecommendationReasonCodes.SELECTED,
        )

        val parallelism = ConfigField(
            key = "parallelism",
            value = DEFAULT_PARALLELISM,
            source = ConfigValueSources.PRODUCT_DEFAULT,
            reasonCode = RecommendationReasonCodes.RESOURCE_FIT,
        )

        val backendValue = when {
            preferences.preferredBackend != null -> preferences.preferredBackend
            // Unknown accelerator → known CPU (FEAT-AUTOSETUP §6 / §8.2)
            device.gpuEvidenceLabel == EvidenceLabel.UNKNOWN &&
                device.npuEvidenceLabel == EvidenceLabel.UNKNOWN -> "cpu"
            else -> candidate.backend
        }
        val backendSource = when {
            preferences.preferredBackend != null -> ConfigValueSources.USER_PREFERENCE
            backendValue != candidate.backend -> ConfigValueSources.DEVICE_EVIDENCE
            else -> ConfigValueSources.MODEL_RECOMMENDATION
        }
        val backend = ConfigField(
            key = "runtime.backendPreference",
            value = backendValue,
            source = backendSource,
            reasonCode = if (backendValue == "cpu" &&
                (device.gpuEvidenceLabel == EvidenceLabel.UNKNOWN)
            ) {
                RecommendationReasonCodes.ACCELERATOR_UNKNOWN
            } else {
                RecommendationReasonCodes.SELECTED
            },
        )

        val placement = ConfigField(
            key = "placementClass",
            value = candidate.placementClass,
            source = ConfigValueSources.POLICY,
            reasonCode = RecommendationReasonCodes.TRUST_PLACEMENT,
        )

        val fallback = ConfigField(
            key = "runtime.fallbackPolicy",
            value = preferences.fallbackPolicy,
            source = ConfigValueSources.USER_PREFERENCE,
            reasonCode = RecommendationReasonCodes.USER_PREFERENCE,
        )

        val kv = candidate.notes["kvFormat"]?.let { fmt ->
            ConfigField(
                key = "kvFormat",
                value = fmt,
                source = ConfigValueSources.ENGINE_ENVELOPE,
                reasonCode = RecommendationReasonCodes.SELECTED,
            )
        }

        // prefillBatch and context are admission/resource bound in catalog.
        val requiresPlan = true

        return AutomatedConfiguration(
            modelRevisionId = candidate.modelRevisionId,
            engineBuildId = candidate.engineBuildId,
            backend = backend,
            contextTokens = contextClamped,
            maxOutputTokens = maxOut,
            threads = threads,
            prefillBatch = batch,
            parallelism = parallelism,
            placementClass = placement,
            fallbackPolicy = fallback,
            kvFormat = kv,
            candidateId = candidate.candidateId,
            displayName = candidate.displayName,
            requiresPlanReservation = requiresPlan,
        )
    }
}
