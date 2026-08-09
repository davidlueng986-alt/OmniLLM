package com.omnillm.runtime.policy

/**
 * Machine-readable configuration catalog projection
 * (`specs/configuration-catalog.yaml`, DATA-CONFIG).
 *
 * Value-source axis and hard-constraint axis are **never** mixed:
 * sources answer "which legal candidate value wins";
 * constraints only shrink the allowed range (intersection).
 */
object ConfigurationCatalog {

    /**
     * Authority order for selecting among allowed value sources.
     * From configuration-catalog.yaml `resolutionModel.valueSourcePrecedence`.
     *
     * `administrator-policy` appears as an allowedSources entry for several
     * LOCAL_ADMIN settings in the same catalog; it ranks just above
     * product-default so admin writes override defaults without beating
     * request/principal/model/device sources.
     */
    val VALUE_SOURCE_PRECEDENCE: List<String> = listOf(
        "request-explicit",
        "principal-profile",
        "model-recommendation",
        "device-policy",
        "administrator-policy",
        "product-default",
    )

    /**
     * Hard constraint authority order (highest first).
     * From configuration-catalog.yaml `resolutionModel.hardConstraintOrder`.
     * Lower layers may only shrink; never relax higher layers.
     */
    val HARD_CONSTRAINT_ORDER: List<String> = listOf(
        "platform-safety",
        "security-trust",
        "resource-hard-cap",
        "administrator-policy",
    )

    val SETTINGS: Map<String, SettingDefinition> = listOf(
        SettingDefinition(
            key = "inference.contextTokens",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.PUBLIC_STABLE,
            allowedSources = setOf(
                "request-explicit",
                "principal-profile",
                "model-recommendation",
                "device-policy",
                "product-default",
            ),
            clampAllowed = true,
            admissionBound = true,
            requiresPlanReservationCommit = false,
            defaultValue = null,
        ),
        SettingDefinition(
            key = "inference.maxOutputTokens",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.PUBLIC_STABLE,
            allowedSources = setOf(
                "request-explicit",
                "principal-profile",
                "model-recommendation",
                "device-policy",
                "product-default",
            ),
            clampAllowed = true,
            admissionBound = true,
            requiresPlanReservationCommit = false,
            defaultValue = null,
        ),
        SettingDefinition(
            key = "inference.temperature",
            type = SettingType.NUMBER,
            min = 0.0,
            max = 2.0,
            enumValues = null,
            settingClass = SettingClass.PUBLIC_STABLE,
            allowedSources = setOf(
                "request-explicit",
                "principal-profile",
                "model-recommendation",
                "product-default",
            ),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = null,
        ),
        SettingDefinition(
            key = "inference.topP",
            type = SettingType.NUMBER,
            min = null,
            exclusiveMin = 0.0,
            max = 1.0,
            enumValues = null,
            settingClass = SettingClass.PUBLIC_STABLE,
            allowedSources = setOf(
                "request-explicit",
                "principal-profile",
                "model-recommendation",
                "product-default",
            ),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = null,
        ),
        SettingDefinition(
            key = "runtime.cpuThreads",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf(
                "principal-profile",
                "model-recommendation",
                "device-policy",
                "product-default",
            ),
            clampAllowed = true,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            evidenceBound = true,
            defaultValue = null,
        ),
        SettingDefinition(
            key = "runtime.prefillBatch",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf(
                "principal-profile",
                "model-recommendation",
                "device-policy",
                "product-default",
            ),
            clampAllowed = true,
            admissionBound = false,
            requiresPlanReservationCommit = true,
            defaultValue = null,
        ),
        SettingDefinition(
            key = "runtime.backendPreference",
            type = SettingType.ENUM,
            min = null,
            max = null,
            enumValues = setOf("AUTO", "CPU", "GPU", "NPU"),
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf(
                "principal-profile",
                "model-recommendation",
                "device-policy",
                "product-default",
            ),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.EnumValue("AUTO"),
        ),
        SettingDefinition(
            key = "runtime.fallbackPolicy",
            type = SettingType.ENUM,
            min = null,
            max = null,
            enumValues = setOf("NONE", "SAME_REVISION_ONLY", "ALLOW_LIST"),
            settingClass = SettingClass.PUBLIC_STABLE,
            allowedSources = setOf(
                "request-explicit",
                "principal-profile",
                "product-default",
            ),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.EnumValue("NONE"),
        ),
        SettingDefinition(
            key = "runtime.exploratoryExecuteEnabled",
            type = SettingType.BOOLEAN,
            min = null,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            // Development ship mode defaults ON so generate is not blocked by paperwork.
            // Set ProductBuildMode.DEVELOPMENT_SHIP_MODE=false for compliance-style default OFF.
            defaultValue = SettingValue.BoolValue(
                com.omnillm.core.contracts.ProductBuildMode.defaultExploratoryExecuteEnabled(),
            ),
        ),
        SettingDefinition(
            key = "server.loopbackEnabled",
            type = SettingType.BOOLEAN,
            min = null,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.BoolValue(false),
        ),
        SettingDefinition(
            key = "server.lanEnabled",
            type = SettingType.BOOLEAN,
            min = null,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.BoolValue(false),
        ),
        SettingDefinition(
            key = "privacy.telemetryMode",
            type = SettingType.ENUM,
            min = null,
            max = null,
            enumValues = setOf("OFF", "LOCAL_ONLY", "EXPLICIT_EXPORT"),
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.EnumValue("LOCAL_ONLY"),
        ),
    ).associateBy { it.key }

    fun definition(key: String): SettingDefinition? = SETTINGS[key]

    fun requireDefinition(key: String): SettingDefinition =
        SETTINGS[key] ?: error("Unknown setting key (fail closed): $key")
}

enum class SettingType {
    BOOLEAN,
    INTEGER,
    NUMBER,
    ENUM,
    STRING,
}

enum class SettingClass {
    PUBLIC_STABLE,
    LOCAL_ADMIN,
    ENGINE_INTERNAL,
    EXPERIMENTAL_EVIDENCE_GATED,
}

data class SettingDefinition(
    val key: String,
    val type: SettingType,
    val min: Double? = null,
    val exclusiveMin: Double? = null,
    val max: Double? = null,
    val enumValues: Set<String>? = null,
    val settingClass: SettingClass,
    val allowedSources: Set<String>,
    val clampAllowed: Boolean,
    val admissionBound: Boolean = false,
    val requiresPlanReservationCommit: Boolean = false,
    val evidenceBound: Boolean = false,
    val defaultValue: SettingValue? = null,
)

/**
 * Typed setting value (OpenAPI SettingValue oneOf projection).
 */
sealed class SettingValue {
    data class BoolValue(val value: Boolean) : SettingValue()
    data class IntValue(val value: Long) : SettingValue()
    data class NumberValue(val value: Double) : SettingValue()
    data class StringValue(val value: String) : SettingValue() {
        init {
            require(value.length <= 4096) { "string setting maxLength 4096" }
        }
    }

    data class EnumValue(val value: String) : SettingValue()
    data class StringListValue(val value: List<String>) : SettingValue() {
        init {
            require(value.size <= 256) { "string list maxItems 256" }
            value.forEach { require(it.length <= 1024) { "list item maxLength 1024" } }
        }
    }

    fun asDoubleOrNull(): Double? = when (this) {
        is IntValue -> value.toDouble()
        is NumberValue -> value
        else -> null
    }

    fun asLongOrNull(): Long? = when (this) {
        is IntValue -> value
        is NumberValue -> if (value % 1.0 == 0.0) value.toLong() else null
        else -> null
    }

    fun asStringOrNull(): String? = when (this) {
        is StringValue -> value
        is EnumValue -> value
        else -> null
    }

    fun asBoolOrNull(): Boolean? = when (this) {
        is BoolValue -> value
        else -> null
    }
}

/** Candidate value from a single source. */
data class SettingCandidate(
    val source: String,
    val value: SettingValue,
)

/**
 * One hard-constraint contribution. Numeric bounds intersect; enum/bool
 * allowed-sets intersect. Lower authority cannot widen a higher bound.
 */
data class HardConstraintContribution(
    val authority: String,
    val min: Double? = null,
    val exclusiveMin: Double? = null,
    val max: Double? = null,
    val allowedEnumValues: Set<String>? = null,
    val forcedBool: Boolean? = null,
    val policyVersion: String,
) {
    init {
        require(authority.isNotBlank()) { "authority must be non-blank" }
        require(policyVersion.isNotBlank()) { "policyVersion must be non-blank" }
        require(authority in ConfigurationCatalog.HARD_CONSTRAINT_ORDER) {
            "unknown hard constraint authority (fail closed): $authority"
        }
    }
}

/** Deterministic merge result (DATA-CONFIG §3, §7). */
data class EffectiveSetting(
    val key: String,
    val effectiveValue: SettingValue,
    val selectedSource: String,
    val clampReason: String?,
    val constraintVersions: Map<String, String>,
    val sourceValues: Map<String, SettingValue>,
)

data class SettingsSnapshot(
    val resourceVersion: Long,
    val values: Map<String, SettingValue>,
    val effective: Map<String, EffectiveSetting> = emptyMap(),
) {
    init {
        require(resourceVersion >= 0L) { "resourceVersion must be non-negative" }
    }
}
