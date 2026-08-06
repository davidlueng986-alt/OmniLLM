package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError

/**
 * Deterministic settings merge (DATA-CONFIG §3, configuration-catalog.yaml).
 *
 * Algorithm:
 * 1. validate candidates (type / range / enum / unknown-field policy)
 * 2. pick highest-precedence candidate among allowedSources
 * 3. apply hard constraints in authority order (intersection only)
 * 4. clamp if setting.clampAllowed else reject
 * 5. resource-increasing settings with requiresPlanReservationCommit fail
 *    closed without a reservation token
 *
 * Same source set + policy versions + request snapshot ⇒ byte-equivalent
 * effective config (DATA-CONFIG acceptance).
 */
object SettingsMerger {

    fun merge(
        key: String,
        candidates: List<SettingCandidate>,
        hardConstraints: List<HardConstraintContribution> = emptyList(),
        /** When true, caller already completed plan/reserve/commit for resource increase. */
        resourceChangeReserved: Boolean = false,
    ): OmniResult<EffectiveSetting> {
        val def = ConfigurationCatalog.definition(key)
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown setting key",
                    details = mapOf("key" to key),
                ),
            )

        // Drop disallowed sources (fail closed for write; ignore for merge input).
        val allowed = candidates.filter { it.source in def.allowedSources }
        val invalidSource = candidates.firstOrNull { it.source !in def.allowedSources }
        if (invalidSource != null && allowed.isEmpty() && candidates.isNotEmpty()) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "source not in allowedSources",
                    details = mapOf(
                        "key" to key,
                        "source" to invalidSource.source,
                    ),
                ),
            )
        }

        val withDefaults = if (allowed.isEmpty() && def.defaultValue != null) {
            listOf(SettingCandidate("product-default", def.defaultValue))
        } else {
            allowed
        }
        if (withDefaults.isEmpty()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "no candidate value for setting",
                    details = mapOf("key" to key),
                ),
            )
        }

        // Validate each candidate type/range/enum against schema.
        for (c in withDefaults) {
            val typeErr = validateAgainstSchema(def, c.value)
            if (typeErr != null) {
                return OmniResult.err(
                    typeErr.copyWithDetails(
                        typeErr.details + mapOf("source" to c.source, "key" to key),
                    ),
                )
            }
        }

        val selected = selectHighestPrecedence(withDefaults)
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "unable to rank candidates", details = mapOf("key" to key)),
            )

        val orderedConstraints = hardConstraints
            .filter { it.authority in ConfigurationCatalog.HARD_CONSTRAINT_ORDER }
            .sortedBy { ConfigurationCatalog.HARD_CONSTRAINT_ORDER.indexOf(it.authority) }

        val applied = applyHardConstraints(def, selected.value, orderedConstraints)
        if (applied is OmniResult.Err) return applied

        val (effectiveValue, clampReason) = (applied as OmniResult.Ok).value

        if (def.requiresPlanReservationCommit && isResourceIncreasing(def, effectiveValue)) {
            if (!resourceChangeReserved) {
                return OmniResult.err(
                    OmniError.ADMISSION_REJECTED(
                        message = "resource-increasing setting requires plan/reserve/commit",
                        details = mapOf("key" to key),
                    ),
                )
            }
        }

        val versions = orderedConstraints.associate { it.authority to it.policyVersion }
        val sourceMap = withDefaults.associate { it.source to it.value }

        return OmniResult.ok(
            EffectiveSetting(
                key = key,
                effectiveValue = effectiveValue,
                selectedSource = selected.source,
                clampReason = clampReason,
                constraintVersions = versions,
                sourceValues = sourceMap,
            ),
        )
    }

    /**
     * Merge many keys deterministically (stable key order).
     */
    fun mergeAll(
        inputs: Map<String, List<SettingCandidate>>,
        hardConstraintsByKey: Map<String, List<HardConstraintContribution>> = emptyMap(),
        resourceChangeReservedKeys: Set<String> = emptySet(),
    ): OmniResult<Map<String, EffectiveSetting>> {
        val out = linkedMapOf<String, EffectiveSetting>()
        for (key in inputs.keys.sorted()) {
            when (
                val r = merge(
                    key = key,
                    candidates = inputs[key].orEmpty(),
                    hardConstraints = hardConstraintsByKey[key].orEmpty(),
                    resourceChangeReserved = key in resourceChangeReservedKeys,
                )
            ) {
                is OmniResult.Ok -> out[key] = r.value
                is OmniResult.Err -> return r
            }
        }
        return OmniResult.ok(out)
    }

    fun selectHighestPrecedence(candidates: List<SettingCandidate>): SettingCandidate? {
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { c ->
            val idx = ConfigurationCatalog.VALUE_SOURCE_PRECEDENCE.indexOf(c.source)
            if (idx >= 0) idx else ConfigurationCatalog.VALUE_SOURCE_PRECEDENCE.size
        }
    }

    fun validateAgainstSchema(def: SettingDefinition, value: SettingValue): OmniError? {
        when (def.type) {
            SettingType.BOOLEAN -> {
                if (value !is SettingValue.BoolValue) {
                    return OmniError.INVALID_REQUEST(message = "expected boolean")
                }
            }
            SettingType.INTEGER -> {
                val n = value.asLongOrNull()
                    ?: return OmniError.INVALID_REQUEST(message = "expected integer")
                def.min?.let {
                    if (n < it) return OmniError.INVALID_REQUEST(message = "below min")
                }
                def.max?.let {
                    if (n > it) return OmniError.INVALID_REQUEST(message = "above max")
                }
            }
            SettingType.NUMBER -> {
                val n = value.asDoubleOrNull()
                    ?: return OmniError.INVALID_REQUEST(message = "expected number")
                def.min?.let {
                    if (n < it) return OmniError.INVALID_REQUEST(message = "below min")
                }
                def.exclusiveMin?.let {
                    if (n <= it) return OmniError.INVALID_REQUEST(message = "not above exclusiveMin")
                }
                def.max?.let {
                    if (n > it) return OmniError.INVALID_REQUEST(message = "above max")
                }
            }
            SettingType.ENUM -> {
                val s = value.asStringOrNull()
                    ?: return OmniError.INVALID_REQUEST(message = "expected enum string")
                val allowed = def.enumValues
                    ?: return OmniError.INVALID_REQUEST(message = "enum values missing in schema")
                if (s !in allowed) {
                    return OmniError.INVALID_REQUEST(
                        message = "unknown enum value",
                        details = mapOf("value" to s),
                    )
                }
            }
            SettingType.STRING -> {
                if (value !is SettingValue.StringValue && value !is SettingValue.EnumValue) {
                    return OmniError.INVALID_REQUEST(message = "expected string")
                }
            }
        }
        return null
    }

    private fun applyHardConstraints(
        def: SettingDefinition,
        selected: SettingValue,
        constraints: List<HardConstraintContribution>,
    ): OmniResult<Pair<SettingValue, String?>> {
        var minBound = def.min
        var exclusiveMinBound = def.exclusiveMin
        var maxBound = def.max
        var enumAllow: Set<String>? = def.enumValues
        var forcedBool: Boolean? = null
        var clampReason: String? = null
        var current = selected

        for (c in constraints) {
            // Intersection: tighten min (raise floor), lower max (drop ceiling).
            if (c.min != null) {
                minBound = maxOf(minBound ?: c.min, c.min)
            }
            if (c.exclusiveMin != null) {
                exclusiveMinBound = maxOf(exclusiveMinBound ?: c.exclusiveMin, c.exclusiveMin)
            }
            if (c.max != null) {
                maxBound = when (val m = maxBound) {
                    null -> c.max
                    else -> minOf(m, c.max)
                }
            }
            if (c.allowedEnumValues != null) {
                enumAllow = (enumAllow ?: c.allowedEnumValues).intersect(c.allowedEnumValues)
            }
            if (c.forcedBool != null) {
                // Safety can force a boolean (e.g. lanEnabled=false); never force true over false safety.
                forcedBool = when (forcedBool) {
                    null -> c.forcedBool
                    false -> false
                    true -> c.forcedBool
                }
            }
        }

        when (def.type) {
            SettingType.INTEGER, SettingType.NUMBER -> {
                var n = current.asDoubleOrNull()
                    ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "numeric expected"))
                val floor = minBound
                val ceil = maxBound
                val exFloor = exclusiveMinBound

                if (floor != null && n < floor) {
                    if (!def.clampAllowed) {
                        return OmniResult.err(
                            OmniError.ADMISSION_REJECTED(
                                message = "value below hard min and clamp not allowed",
                                details = mapOf("key" to def.key),
                            ),
                        )
                    }
                    n = floor
                    clampReason = "clamped-to-min"
                }
                if (exFloor != null && n <= exFloor) {
                    if (!def.clampAllowed) {
                        return OmniResult.err(
                            OmniError.ADMISSION_REJECTED(
                                message = "value not above exclusiveMin and clamp not allowed",
                                details = mapOf("key" to def.key),
                            ),
                        )
                    }
                    // clamp just above exclusive min for numbers; integers step by 1
                    n = if (def.type == SettingType.INTEGER) {
                        kotlin.math.floor(exFloor).toLong() + 1.0
                    } else {
                        exFloor + Double.MIN_VALUE
                    }
                    clampReason = "clamped-above-exclusiveMin"
                }
                if (ceil != null && n > ceil) {
                    if (!def.clampAllowed) {
                        return OmniResult.err(
                            OmniError.ADMISSION_REJECTED(
                                message = "value above hard max and clamp not allowed",
                                details = mapOf("key" to def.key),
                            ),
                        )
                    }
                    n = ceil
                    clampReason = "clamped-to-max"
                }
                // inverted bounds after intersection
                if (floor != null && ceil != null && floor > ceil) {
                    return OmniResult.err(
                        OmniError.ADMISSION_REJECTED(
                            message = "hard constraints produced empty range",
                            details = mapOf("key" to def.key),
                        ),
                    )
                }
                current = if (def.type == SettingType.INTEGER) {
                    SettingValue.IntValue(n.toLong())
                } else {
                    SettingValue.NumberValue(n)
                }
            }
            SettingType.ENUM -> {
                val s = current.asStringOrNull()
                    ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "enum expected"))
                val allow = enumAllow
                if (allow != null && s !in allow) {
                    return OmniResult.err(
                        OmniError.ADMISSION_REJECTED(
                            message = "enum value excluded by hard constraint",
                            details = mapOf("key" to def.key, "value" to s),
                        ),
                    )
                }
                if (allow != null && allow.isEmpty()) {
                    return OmniResult.err(
                        OmniError.ADMISSION_REJECTED(
                            message = "hard constraints produced empty enum set",
                            details = mapOf("key" to def.key),
                        ),
                    )
                }
            }
            SettingType.BOOLEAN -> {
                val b = current.asBoolOrNull()
                    ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "boolean expected"))
                if (forcedBool != null && b != forcedBool) {
                    if (!def.clampAllowed) {
                        // Boolean safety force is always applied (security axis), not optional clamp.
                        current = SettingValue.BoolValue(forcedBool)
                        clampReason = "forced-by-hard-constraint"
                    } else {
                        current = SettingValue.BoolValue(forcedBool)
                        clampReason = "forced-by-hard-constraint"
                    }
                }
            }
            SettingType.STRING -> {
                // no numeric hard bounds
            }
        }

        return OmniResult.ok(current to clampReason)
    }

    /**
     * Heuristic: larger integer resource knobs are resource-increasing.
     * Catalog marks explicit requiresPlanReservationCommit keys.
     */
    private fun isResourceIncreasing(def: SettingDefinition, value: SettingValue): Boolean {
        if (!def.requiresPlanReservationCommit) return false
        // Any non-null value change on such a key is treated as resource-affecting.
        return value.asLongOrNull()?.let { it > 0L } ?: true
    }
}

/** Preserve typed OmniError while extending details (catalog codes only). */
private fun OmniError.copyWithDetails(details: Map<String, String>): OmniError = when (this) {
    is OmniError.INVALID_REQUEST -> copy(details = details)
    is OmniError.UNAUTHORIZED -> copy(details = details)
    is OmniError.FORBIDDEN -> copy(details = details)
    is OmniError.NOT_FOUND -> copy(details = details)
    is OmniError.IDEMPOTENCY_CONFLICT -> copy(details = details)
    is OmniError.STATE_CONFLICT -> copy(details = details)
    is OmniError.CONTEXT_LIMIT_EXCEEDED -> copy(details = details)
    is OmniError.TRANSPORT_TOO_LARGE -> copy(details = details)
    is OmniError.RATE_LIMITED -> copy(details = details)
    is OmniError.CAPABILITY_UNSUPPORTED -> copy(details = details)
    is OmniError.CAPABILITY_UNKNOWN -> copy(details = details)
    is OmniError.ADMISSION_REJECTED -> copy(details = details)
    is OmniError.MODEL_REVOKED -> copy(details = details)
    is OmniError.TRUST_PLACEMENT_REQUIRED -> copy(details = details)
    is OmniError.PAIRING_REQUIRED -> copy(details = details)
    is OmniError.CURSOR_GONE -> copy(details = details)
    is OmniError.ASSET_NOT_READY -> copy(details = details)
    is OmniError.ASSET_EXPIRED -> copy(details = details)
    is OmniError.DEADLINE_EXCEEDED -> copy(details = details)
    is OmniError.CANCELLED -> copy(details = details)
    is OmniError.WORKER_DIED -> copy(details = details)
    is OmniError.ABORTED_UNCERTAIN -> copy(details = details)
    is OmniError.STREAM_INTERRUPTED -> copy(details = details)
    is OmniError.CONTENT_REPORT_UNAVAILABLE -> copy(details = details)
    is OmniError.INTERNAL -> copy(details = details)
}
