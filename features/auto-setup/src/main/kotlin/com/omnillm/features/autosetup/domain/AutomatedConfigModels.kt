package com.omnillm.features.autosetup.domain

import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.policy.ConfigurationCatalog

/**
 * Configuration value sources from `specs/configuration-catalog.yaml`
 * (`resolutionModel.valueSourcePrecedence`). Do not invent sources.
 */
object ConfigValueSources {
    const val REQUEST_EXPLICIT: String = "request-explicit"
    const val PRINCIPAL_PROFILE: String = "principal-profile"
    const val MODEL_RECOMMENDATION: String = "model-recommendation"
    const val DEVICE_POLICY: String = "device-policy"
    const val ADMINISTRATOR_POLICY: String = "administrator-policy"
    const val PRODUCT_DEFAULT: String = "product-default"

    /** Extended explainability labels used alongside catalog sources (FEAT-AUTOSETUP §5). */
    const val MODEL_HARD_LIMIT: String = "model-hard-limit"
    const val ENGINE_ENVELOPE: String = "engine-envelope"
    const val DEVICE_EVIDENCE: String = "device-evidence"
    const val USER_PREFERENCE: String = "user-preference"
    const val POLICY: String = "policy"

    val CATALOG_SOURCES: Set<String> = ConfigurationCatalog.VALUE_SOURCE_PRECEDENCE.toSet()
}

/**
 * Single automated configuration field with provenance (FEAT-AUTOSETUP §5).
 * UI must show source of each value.
 */
data class ConfigField<T>(
    val key: String,
    val value: T,
    val source: String,
    val reasonCode: String,
) {
    init {
        require(key.isNotEmpty()) { "config field key must be non-empty" }
        require(source.isNotEmpty()) { "config field source must be non-empty" }
        require(reasonCode.isNotEmpty()) { "config field reasonCode must be non-empty" }
    }
}

/**
 * Automated configuration output for load / first inference
 * (FEAT-AUTOSETUP §5: revision, engine, backend, context, KV, threads, batch,
 * parallelism, placement, fallback).
 *
 * Does not mutate domain; Plan/Reserve/Commit still required for resource increase.
 */
data class AutomatedConfiguration(
    val modelRevisionId: ModelRevisionId,
    val engineBuildId: EngineBuildId,
    val backend: ConfigField<String>,
    val contextTokens: ConfigField<Int>,
    val maxOutputTokens: ConfigField<Int>,
    val threads: ConfigField<Int>,
    val prefillBatch: ConfigField<Int>,
    val parallelism: ConfigField<Int>,
    val placementClass: ConfigField<String>,
    val fallbackPolicy: ConfigField<FallbackPolicy>,
    /** Opaque KV format label when known (engine envelope); null when unknown. */
    val kvFormat: ConfigField<String>?,
    val candidateId: String,
    val displayName: String,
    /** True when any workspace-increasing field needs re-plan before apply. */
    val requiresPlanReservation: Boolean,
) {
    init {
        require(candidateId.isNotEmpty()) { "candidateId must be non-empty" }
        require(displayName.isNotEmpty()) { "displayName must be non-empty" }
        require(PlacementClassLabels.isKnown(placementClass.value)) {
            "unknown placement class: ${placementClass.value}"
        }
        require(contextTokens.value >= 1) { "contextTokens must be >= 1" }
        require(maxOutputTokens.value >= 1) { "maxOutputTokens must be >= 1" }
        require(threads.value >= 1) { "threads must be >= 1" }
        require(prefillBatch.value >= 1) { "prefillBatch must be >= 1" }
        require(parallelism.value >= 1) { "parallelism must be >= 1" }
    }

    /** Flat map of setting key → string value for policy merge inputs. */
    fun asRecommendationSources(): Map<String, Pair<String, String>> = mapOf(
        "inference.contextTokens" to (contextTokens.value.toString() to contextTokens.source),
        "inference.maxOutputTokens" to (maxOutputTokens.value.toString() to maxOutputTokens.source),
        "runtime.cpuThreads" to (threads.value.toString() to threads.source),
        "runtime.prefillBatch" to (prefillBatch.value.toString() to prefillBatch.source),
        "runtime.backendPreference" to (backend.value.uppercase() to backend.source),
        "runtime.fallbackPolicy" to (fallbackPolicy.value.name to fallbackPolicy.source),
    )
}
