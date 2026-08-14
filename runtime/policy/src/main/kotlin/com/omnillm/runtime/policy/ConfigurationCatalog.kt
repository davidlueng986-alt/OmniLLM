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
            // Fail-closed default: this static JVM catalog must never lie about
            // build posture. The variant-scoped dev-mode default (ON for
            // debug/dev, OFF for release) is seeded by the Android control plane
            // (RuntimeControlPlane) into the "product-default" source layer from
            // its ProductBuildMode (BLD-02).
            defaultValue = SettingValue.BoolValue(false),
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

        // ----- Product modes (FTR-03) ----------------------------------------
        // Both modes are FAIL-CLOSED by default (OFF). Spec entries for
        // specs/configuration-catalog.yaml are coordinated with Stage 4a.
        SettingDefinition(
            key = "product.researchModeEnabled",
            type = SettingType.BOOLEAN,
            min = null,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            // Research Mode: raw diagnostics + backend selection options.
            // Off by default ??honest capability projection (FTR-03).
            defaultValue = SettingValue.BoolValue(false),
        ),
        SettingDefinition(
            key = "product.riskyPerformanceModeEnabled",
            type = SettingType.BOOLEAN,
            min = null,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            // Risky Performance Mode: requires explicit per-use risk
            // acknowledgment (RiskAck) and enables the performance path.
            // Off by default (FTR-03).
            defaultValue = SettingValue.BoolValue(false),
        ),

        // ----- Governor capacities (ARC-10) ----------------------------------
        // Hardcoded WaveAWiring / ControlPlaneFeaturePorts values moved into the
        // configuration catalog; code reads them via
        // ConfigurationCatalog.governorCapacities(snapshot). Spec entries for
        // specs/configuration-catalog.yaml are coordinated with Stage 4a.
        SettingDefinition(
            key = "resource.governorAnonMemoryCapBytes",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = true,
            admissionBound = true,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_ANON_MEMORY_CAP_BYTES),
        ),
        SettingDefinition(
            key = "resource.governorFileCacheCapBytes",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = true,
            admissionBound = true,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_FILE_CACHE_CAP_BYTES),
        ),
        SettingDefinition(
            key = "resource.governorThreadCap",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = true,
            admissionBound = true,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_THREAD_CAP),
        ),
        SettingDefinition(
            key = "resource.governorFdCap",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = true,
            admissionBound = true,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_FD_CAP),
        ),
        SettingDefinition(
            key = "resource.probeDeadlineMs",
            type = SettingType.INTEGER,
            min = 1.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = true,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_PROBE_DEADLINE_MS),
        ),

        // ----- C-11: principal admission rate limits (SEC-AUTH-NET) ----------
        // Spec entries for specs/configuration-catalog.yaml are coordinated
        // with the specs agent (mirror keys + bounds). 0 = disabled (fail-open,
        // documented: operators who set 0 accept unbounded traffic on that
        // axis; the default is enforced).
        SettingDefinition(
            key = "security.principalRpsLimit",
            type = SettingType.INTEGER,
            min = 0.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_PRINCIPAL_RPS_LIMIT.toLong()),
        ),
        SettingDefinition(
            key = "security.principalConcurrentRequests",
            type = SettingType.INTEGER,
            min = 0.0,
            max = null,
            enumValues = null,
            settingClass = SettingClass.LOCAL_ADMIN,
            allowedSources = setOf("administrator-policy", "product-default"),
            clampAllowed = false,
            admissionBound = false,
            requiresPlanReservationCommit = false,
            defaultValue = SettingValue.IntValue(DEFAULT_PRINCIPAL_CONCURRENT_REQUESTS.toLong()),
        ),
    ).associateBy { it.key }

    // ----- Catalog defaults (must match the values the control plane used
    // before config-driven wiring; changing defaults changes admission math) ---

    const val DEFAULT_ANON_MEMORY_CAP_BYTES: Long = 512L * 1024L * 1024L
    const val DEFAULT_FILE_CACHE_CAP_BYTES: Long = 8L * 1024L * 1024L * 1024L
    const val DEFAULT_THREAD_CAP: Long = 64L
    const val DEFAULT_FD_CAP: Long = 1024L
    const val DEFAULT_PROBE_DEADLINE_MS: Long = 30_000L

    /** C-11: default principal request rate (per second); 0 = disabled. */
    const val DEFAULT_PRINCIPAL_RPS_LIMIT: Int = 60

    /** C-11: default principal in-flight request quota; 0 = disabled. */
    const val DEFAULT_PRINCIPAL_CONCURRENT_REQUESTS: Int = 8

    /**
     * C-11: effective principal admission limits from the settings snapshot.
     * A limit of 0 disables that dimension (fail-open, documented) ??the
     * defaults (60 rps / 8 in-flight) are enforced unless explicitly changed.
     */
    data class SecurityLimits(
        val principalRpsLimit: Int = DEFAULT_PRINCIPAL_RPS_LIMIT,
        val principalConcurrentRequests: Int = DEFAULT_PRINCIPAL_CONCURRENT_REQUESTS,
    )

    /** Resolve C-11 limits; absent/invalid keys fall back to the defaults. */
    fun securityLimits(snapshot: SettingsSnapshot?): SecurityLimits {
        if (snapshot == null) return SecurityLimits()
        return SecurityLimits(
            principalRpsLimit = snapshot.values["security.principalRpsLimit"]
                ?.asLongOrNull()?.coerceAtLeast(0L)?.toInt() ?: DEFAULT_PRINCIPAL_RPS_LIMIT,
            principalConcurrentRequests = snapshot.values["security.principalConcurrentRequests"]
                ?.asLongOrNull()?.coerceAtLeast(0L)?.toInt()
                ?: DEFAULT_PRINCIPAL_CONCURRENT_REQUESTS,
        )
    }

    /** Effective multi-dimensional governor capacity (ARC-10). */
    data class GovernorCapacities(
        val anonMemoryBytes: Long,
        val fileCacheBytes: Long,
        val threads: Long,
        val fileDescriptors: Long,
        val temporaryDiskBytes: Long = 512L * 1024L * 1024L,
        val probeDeadlineMs: Long = DEFAULT_PROBE_DEADLINE_MS,
    )

    /** Effective product-mode flags (FTR-03). Both fail closed by default. */
    data class ProductModes(
        val researchModeEnabled: Boolean = false,
        val riskyPerformanceModeEnabled: Boolean = false,
    )

    /**
     * Resolve governor capacities from the effective settings snapshot.
     * Unknown/absent keys fall back to catalog defaults (fail closed, never 0).
     */
    fun governorCapacities(snapshot: SettingsSnapshot?): GovernorCapacities {
        if (snapshot == null) return GovernorCapacities(
            anonMemoryBytes = DEFAULT_ANON_MEMORY_CAP_BYTES,
            fileCacheBytes = DEFAULT_FILE_CACHE_CAP_BYTES,
            threads = DEFAULT_THREAD_CAP,
            fileDescriptors = DEFAULT_FD_CAP,
        )
        return GovernorCapacities(
            anonMemoryBytes = snapshot.values["resource.governorAnonMemoryCapBytes"]
                ?.asLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_ANON_MEMORY_CAP_BYTES,
            fileCacheBytes = snapshot.values["resource.governorFileCacheCapBytes"]
                ?.asLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_FILE_CACHE_CAP_BYTES,
            threads = snapshot.values["resource.governorThreadCap"]
                ?.asLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_THREAD_CAP,
            fileDescriptors = snapshot.values["resource.governorFdCap"]
                ?.asLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_FD_CAP,
            probeDeadlineMs = snapshot.values["resource.probeDeadlineMs"]
                ?.asLongOrNull()?.coerceAtLeast(1L) ?: DEFAULT_PROBE_DEADLINE_MS,
        )
    }

    /** Resolve product-mode flags; absent keys stay OFF (fail closed, FTR-03). */
    fun productModes(snapshot: SettingsSnapshot?): ProductModes {
        if (snapshot == null) return ProductModes()
        return ProductModes(
            researchModeEnabled =
                snapshot.values["product.researchModeEnabled"]?.asBoolOrNull() ?: false,
            riskyPerformanceModeEnabled =
                snapshot.values["product.riskyPerformanceModeEnabled"]?.asBoolOrNull() ?: false,
        )
    }

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

/** Deterministic merge result (DATA-CONFIG 禮3, 禮7). */
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
