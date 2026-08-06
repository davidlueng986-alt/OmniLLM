package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError

/**
 * Policy Manager (DATA-CONFIG, SEC-AUTH-NET, INV-017).
 *
 * - Settings merge on dual axes (value source vs hard constraint)
 * - SettingsSnapshot with resourceVersion CAS patch
 * - Revocation epoch bump + fence hooks
 *
 * Does not store raw secrets (DATA-CONFIG §6) — only metadata / epoch /
 * effective config. Single writer: runtime control plane (ADR-010).
 */
class PolicyManager(
    private val revocation: RevocationEpochManager = RevocationEpochManager(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var snapshot: SettingsSnapshot = SettingsSnapshot(
        resourceVersion = 0L,
        values = defaultProductValues(),
        effective = emptyMap(),
    )

    /** Layered source values retained for deterministic re-merge. */
    private val sourceLayers: MutableMap<String, MutableMap<String, SettingValue>> = linkedMapOf()

    /** Hard constraints keyed by setting. */
    private val hardConstraints: MutableMap<String, MutableList<HardConstraintContribution>> =
        linkedMapOf()

    init {
        // Seed product-default layer from catalog defaults.
        for ((key, def) in ConfigurationCatalog.SETTINGS) {
            val d = def.defaultValue ?: continue
            sourceLayers.getOrPut(key) { linkedMapOf() }["product-default"] = d
        }
        recomputeEffective()
    }

    fun settingsSnapshot(): SettingsSnapshot = snapshot

    fun revocationManager(): RevocationEpochManager = revocation

    /**
     * Publish or replace a candidate value for [key] from [source].
     * Source must be in setting.allowedSources (DATA-CONFIG §2.1).
     */
    fun putSourceValue(
        key: String,
        source: String,
        value: SettingValue,
    ): OmniResult<SettingsSnapshot> {
        val def = ConfigurationCatalog.definition(key)
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown setting key",
                    details = mapOf("key" to key),
                ),
            )
        if (source !in def.allowedSources) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "source not allowed for setting",
                    details = mapOf("key" to key, "source" to source),
                ),
            )
        }
        val schemaErr = SettingsMerger.validateAgainstSchema(def, value)
        if (schemaErr != null) return OmniResult.err(schemaErr)

        sourceLayers.getOrPut(key) { linkedMapOf() }[source] = value
        return recomputeEffective()
    }

    /**
     * Install a hard constraint contribution (intersection only).
     */
    fun putHardConstraint(
        key: String,
        contribution: HardConstraintContribution,
    ): OmniResult<SettingsSnapshot> {
        if (ConfigurationCatalog.definition(key) == null) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown setting key",
                    details = mapOf("key" to key),
                ),
            )
        }
        val list = hardConstraints.getOrPut(key) { mutableListOf() }
        // Replace same authority version row for determinism.
        list.removeAll { it.authority == contribution.authority }
        list.add(contribution)
        return recomputeEffective()
    }

    /**
     * Patch settings with compare-and-set on [baseVersion] (DATA-CONFIG §5).
     * Writes land as administrator-policy or principal-profile source values.
     */
    fun patchSettings(
        baseVersion: Long,
        changes: Map<String, SettingValue>,
        source: String = "administrator-policy",
        resourceChangeReservedKeys: Set<String> = emptySet(),
    ): OmniResult<SettingsSnapshot> {
        if (changes.isEmpty()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "settings patch requires at least one change"),
            )
        }
        if (baseVersion != snapshot.resourceVersion) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "settings baseVersion mismatch",
                    details = mapOf(
                        "baseVersion" to baseVersion.toString(),
                        "current" to snapshot.resourceVersion.toString(),
                    ),
                ),
            )
        }
        // Validate all keys first (atomic).
        for ((key, value) in changes) {
            val def = ConfigurationCatalog.definition(key)
                ?: return OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = "unknown setting key",
                        details = mapOf("key" to key),
                    ),
                )
            if (source !in def.allowedSources) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "source not allowed",
                        details = mapOf("key" to key, "source" to source),
                    ),
                )
            }
            val schemaErr = SettingsMerger.validateAgainstSchema(def, value)
            if (schemaErr != null) return OmniResult.err(schemaErr)
            if (def.requiresPlanReservationCommit && key !in resourceChangeReservedKeys) {
                return OmniResult.err(
                    OmniError.ADMISSION_REJECTED(
                        message = "resource-increasing setting requires plan/reserve/commit",
                        details = mapOf("key" to key),
                    ),
                )
            }
        }
        for ((key, value) in changes) {
            sourceLayers.getOrPut(key) { linkedMapOf() }[source] = value
        }
        return recomputeEffective(bumpVersion = true)
    }

    /**
     * Resolve a single key without mutating durable snapshot (plan-time pure).
     */
    fun resolve(
        key: String,
        extraCandidates: List<SettingCandidate> = emptyList(),
        resourceChangeReserved: Boolean = false,
    ): OmniResult<EffectiveSetting> {
        val base = sourceLayers[key]?.map { (s, v) -> SettingCandidate(s, v) }.orEmpty()
        return SettingsMerger.merge(
            key = key,
            candidates = base + extraCandidates,
            hardConstraints = hardConstraints[key].orEmpty(),
            resourceChangeReserved = resourceChangeReserved,
        )
    }

    // ----- Revocation facade -------------------------------------------------

    fun currentRevocationEpoch(scope: RevocationScope): Long =
        revocation.currentEpoch(scope)

    fun revokeAndFence(
        scope: RevocationScope,
        actor: PrincipalId,
        reason: String,
        authorised: Boolean = true,
    ): OmniResult<RevocationRecord> =
        revocation.revokeAndFence(scope, actor, reason, authorised)

    fun requireRevocationEpoch(scope: RevocationScope, observedEpoch: Long): OmniResult<Unit> =
        revocation.requireCurrentEpoch(scope, observedEpoch)

    // ----- Internals ---------------------------------------------------------

    private fun recomputeEffective(bumpVersion: Boolean = false): OmniResult<SettingsSnapshot> {
        val inputs = linkedMapOf<String, List<SettingCandidate>>()
        val allKeys = (sourceLayers.keys + hardConstraints.keys + ConfigurationCatalog.SETTINGS.keys)
            .toSortedSet()
        for (key in allKeys) {
            val cands = sourceLayers[key]?.map { (s, v) -> SettingCandidate(s, v) }.orEmpty()
            if (cands.isNotEmpty() || ConfigurationCatalog.definition(key)?.defaultValue != null) {
                inputs[key] = cands
            }
        }
        val reserved = inputs.keys.filter { key ->
            ConfigurationCatalog.definition(key)?.requiresPlanReservationCommit == true
        }.toSet()
        // For recompute of already-committed values, treat reservation as satisfied.
        when (
            val merged = SettingsMerger.mergeAll(
                inputs = inputs,
                hardConstraintsByKey = hardConstraints.mapValues { it.value.toList() },
                resourceChangeReservedKeys = reserved,
            )
        ) {
            is OmniResult.Err -> return merged
            is OmniResult.Ok -> {
                val effective = merged.value
                val values = effective.mapValues { it.value.effectiveValue }
                val version = if (bumpVersion) snapshot.resourceVersion + 1 else snapshot.resourceVersion
                snapshot = SettingsSnapshot(
                    resourceVersion = version,
                    values = values,
                    effective = effective,
                )
                return OmniResult.ok(snapshot)
            }
        }
    }

    private fun defaultProductValues(): Map<String, SettingValue> {
        val out = linkedMapOf<String, SettingValue>()
        for ((key, def) in ConfigurationCatalog.SETTINGS) {
            def.defaultValue?.let { out[key] = it }
        }
        return out
    }
}
