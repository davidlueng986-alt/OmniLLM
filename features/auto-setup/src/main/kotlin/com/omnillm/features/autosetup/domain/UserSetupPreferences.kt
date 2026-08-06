package com.omnillm.features.autosetup.domain

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.FallbackPolicy

/**
 * User-facing setup intent (FEAT-AUTOSETUP §1).
 *
 * Users describe purpose and preferences — not GGUF, EP, threads, or drivers.
 * [targetOperation] maps to a catalog [CapabilityId] (TEXT_GENERATION / EMBEDDING / …).
 */
data class UserSetupPreferences(
    /** Target operation capability from capability-catalog (fail closed if unknown). */
    val targetOperation: CapabilityId,
    /**
     * Quality weight 0.0–1.0 (higher prefers larger / higher-quality candidates).
     * Used only after hard safety / capability / resource filters.
     */
    val qualityPreference: Double = 0.5,
    /** Speed weight 0.0–1.0 (higher prefers smaller / faster candidates). */
    val speedPreference: Double = 0.5,
    /** Maximum download/install size in bytes; null = no soft budget. */
    val maxStorageBytes: Long? = null,
    /** Prefer lower power / thermal load when true. */
    val preferLowPower: Boolean = false,
    /**
     * Risk preference: when true, require executable privileged/trusted placement only
     * (reject TRUST_PLACEMENT_REQUIRED and untrusted placements).
     */
    val preferTrustedOnly: Boolean = true,
    /** Caller fallback policy for first inference (catalog FallbackPolicy). */
    val fallbackPolicy: FallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY,
    /** Preferred backend label when known (e.g. "cpu"); empty = auto. */
    val preferredBackend: String? = null,
) {
    init {
        require(qualityPreference in 0.0..1.0) { "qualityPreference must be 0..1" }
        require(speedPreference in 0.0..1.0) { "speedPreference must be 0..1" }
        maxStorageBytes?.let { require(it >= 0L) { "maxStorageBytes must be non-negative" } }
        preferredBackend?.let { require(it.isNotEmpty()) { "preferredBackend must be non-empty when set" } }
        // Unknown capability fail closed (INV-018).
        CapabilityId.requireFromId(targetOperation.id)
    }
}

/**
 * How the user selected a model source (FEAT-AUTOSETUP §3 step 5).
 * Values are journey labels — acquisition still goes through Job kinds DOWNLOAD/IMPORT.
 */
object ModelSourceKinds {
    const val CATALOG: String = "CATALOG"
    const val PINNED_DOWNLOAD: String = "PINNED_DOWNLOAD"
    const val SAF_IMPORT: String = "SAF_IMPORT"

    val ALL: Set<String> = setOf(CATALOG, PINNED_DOWNLOAD, SAF_IMPORT)

    fun isKnown(kind: String): Boolean = kind in ALL
}
