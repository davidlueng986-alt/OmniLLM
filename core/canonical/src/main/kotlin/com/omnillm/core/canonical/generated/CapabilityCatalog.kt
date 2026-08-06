// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

/** Capability catalog from specs/capability-catalog.yaml. */

/** Runtime availability states for a capability cell. */
enum class CapabilityAvailability {
    SUPPORTED,
    UNSUPPORTED,
    CONDITIONAL,
    UNKNOWN,
    TEMPORARILY_UNAVAILABLE
}

enum class CapabilityId(val id: String) {
    DEVICE_DISCOVERY("DEVICE_DISCOVERY"),
    MODEL_ACQUISITION("MODEL_ACQUISITION"),
    MODEL_IDENTITY("MODEL_IDENTITY"),
    COMPATIBILITY_EVALUATION("COMPATIBILITY_EVALUATION"),
    RECOMMENDATION("RECOMMENDATION"),
    AUTOMATED_CONFIGURATION("AUTOMATED_CONFIGURATION"),
    SAFE_INSTALLATION("SAFE_INSTALLATION"),
    TEXT_GENERATION("TEXT_GENERATION"),
    EMBEDDING("EMBEDDING"),
    VISION_INPUT("VISION_INPUT"),
    AUDIO_INPUT("AUDIO_INPUT"),
    STRUCTURED_OUTPUT("STRUCTURED_OUTPUT"),
    TOOL_CALLING("TOOL_CALLING"),
    ENGINE_LIFECYCLE("ENGINE_LIFECYCLE"),
    MODEL_LIFECYCLE("MODEL_LIFECYCLE"),
    SESSION_LIFECYCLE("SESSION_LIFECYCLE"),
    REQUEST_LIFECYCLE("REQUEST_LIFECYCLE"),
    JOB_LIFECYCLE("JOB_LIFECYCLE"),
    CAPABILITY_NEGOTIATION("CAPABILITY_NEGOTIATION"),
    STREAMING("STREAMING"),
    CANCELLATION("CANCELLATION"),
    DEADLINE("DEADLINE"),
    IDEMPOTENCY("IDEMPOTENCY"),
    RECONNECT("RECONNECT"),
    HTTP_INTERFACE("HTTP_INTERFACE"),
    AIDL_INTERFACE("AIDL_INTERFACE"),
    ADMIN_INTERFACE("ADMIN_INTERFACE"),
    LOCAL_UI_INTERFACE("LOCAL_UI_INTERFACE"),
    LAN_INTERFACE("LAN_INTERFACE"),
    MULTI_MODEL_ROUTING("MULTI_MODEL_ROUTING"),
    FALLBACK_POLICY("FALLBACK_POLICY"),
    SERVICE_HEALTH("SERVICE_HEALTH"),
    ENGINE_HEALTH("ENGINE_HEALTH"),
    MODEL_HEALTH("MODEL_HEALTH"),
    REQUEST_TRACE("REQUEST_TRACE"),
    JOB_PROGRESS("JOB_PROGRESS"),
    RESOURCE_ACCOUNTING("RESOURCE_ACCOUNTING"),
    PERFORMANCE_MEASUREMENT("PERFORMANCE_MEASUREMENT"),
    DIAGNOSTIC_REASONING("DIAGNOSTIC_REASONING"),
    EVIDENCE_LABELING("EVIDENCE_LABELING"),
    ASSET_LIFECYCLE("ASSET_LIFECYCLE"),
    JOB_RECOVERY("JOB_RECOVERY"),
    CONTENT_REPORTING("CONTENT_REPORTING"),
    USABILITY_VALIDATION("USABILITY_VALIDATION"),
    ACCESSIBILITY_VALIDATION("ACCESSIBILITY_VALIDATION")
    ;

    companion object {
        fun fromId(id: String): CapabilityId? =
            entries.firstOrNull { it.id == id }

        /** Unknown capability IDs fail closed (INV-018). */
        fun requireFromId(id: String): CapabilityId =
            fromId(id) ?: error("Unknown capability (fail closed): $id")
    }
}

data class CapabilityDefinition(
    val id: CapabilityId,
    val owner: String,
    val dependsOn: List<CapabilityId>,
)

object CapabilityCatalog {
    const val SCHEMA_VERSION: Int = 2
    val REQUIRED_EVIDENCE_BINDING: List<String> = listOf("engineBuildId", "backend", "deviceFingerprint", "modelEnvelope", "operationProfile", "evidenceId", "expiresAt")

    val ALL: List<CapabilityDefinition> = listOf(
        CapabilityDefinition(CapabilityId.DEVICE_DISCOVERY, "device-platform", emptyList()),
        CapabilityDefinition(CapabilityId.MODEL_ACQUISITION, "model-platform", emptyList()),
        CapabilityDefinition(CapabilityId.MODEL_IDENTITY, "model-platform", emptyList()),
        CapabilityDefinition(CapabilityId.COMPATIBILITY_EVALUATION, "capability-platform", listOf(CapabilityId.MODEL_IDENTITY)),
        CapabilityDefinition(CapabilityId.RECOMMENDATION, "auto-setup", listOf(CapabilityId.DEVICE_DISCOVERY, CapabilityId.COMPATIBILITY_EVALUATION)),
        CapabilityDefinition(CapabilityId.AUTOMATED_CONFIGURATION, "auto-setup", listOf(CapabilityId.RECOMMENDATION)),
        CapabilityDefinition(CapabilityId.SAFE_INSTALLATION, "model-platform", listOf(CapabilityId.MODEL_ACQUISITION, CapabilityId.MODEL_IDENTITY)),
        CapabilityDefinition(CapabilityId.TEXT_GENERATION, "engine-platform", emptyList()),
        CapabilityDefinition(CapabilityId.EMBEDDING, "engine-platform", emptyList()),
        CapabilityDefinition(CapabilityId.VISION_INPUT, "engine-platform", emptyList()),
        CapabilityDefinition(CapabilityId.AUDIO_INPUT, "engine-platform", emptyList()),
        CapabilityDefinition(CapabilityId.STRUCTURED_OUTPUT, "engine-platform", listOf(CapabilityId.TEXT_GENERATION)),
        CapabilityDefinition(CapabilityId.TOOL_CALLING, "engine-platform", listOf(CapabilityId.STRUCTURED_OUTPUT)),
        CapabilityDefinition(CapabilityId.ENGINE_LIFECYCLE, "engine-platform", emptyList()),
        CapabilityDefinition(CapabilityId.MODEL_LIFECYCLE, "model-platform", listOf(CapabilityId.ENGINE_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.SESSION_LIFECYCLE, "session-platform", listOf(CapabilityId.MODEL_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.REQUEST_LIFECYCLE, "orchestrator", listOf(CapabilityId.SESSION_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.JOB_LIFECYCLE, "admin-platform", emptyList()),
        CapabilityDefinition(CapabilityId.CAPABILITY_NEGOTIATION, "capability-platform", emptyList()),
        CapabilityDefinition(CapabilityId.STREAMING, "interface-platform", listOf(CapabilityId.REQUEST_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.CANCELLATION, "interface-platform", listOf(CapabilityId.REQUEST_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.DEADLINE, "interface-platform", listOf(CapabilityId.REQUEST_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.IDEMPOTENCY, "reliability-platform", listOf(CapabilityId.REQUEST_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.RECONNECT, "reliability-platform", listOf(CapabilityId.IDEMPOTENCY)),
        CapabilityDefinition(CapabilityId.HTTP_INTERFACE, "interface-platform", emptyList()),
        CapabilityDefinition(CapabilityId.AIDL_INTERFACE, "interface-platform", emptyList()),
        CapabilityDefinition(CapabilityId.ADMIN_INTERFACE, "interface-platform", emptyList()),
        CapabilityDefinition(CapabilityId.LOCAL_UI_INTERFACE, "experience-platform", emptyList()),
        CapabilityDefinition(CapabilityId.LAN_INTERFACE, "network-platform", listOf(CapabilityId.HTTP_INTERFACE)),
        CapabilityDefinition(CapabilityId.MULTI_MODEL_ROUTING, "orchestrator", listOf(CapabilityId.CAPABILITY_NEGOTIATION)),
        CapabilityDefinition(CapabilityId.FALLBACK_POLICY, "orchestrator", listOf(CapabilityId.MULTI_MODEL_ROUTING)),
        CapabilityDefinition(CapabilityId.SERVICE_HEALTH, "observability", emptyList()),
        CapabilityDefinition(CapabilityId.ENGINE_HEALTH, "observability", listOf(CapabilityId.ENGINE_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.MODEL_HEALTH, "observability", listOf(CapabilityId.MODEL_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.REQUEST_TRACE, "observability", listOf(CapabilityId.REQUEST_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.JOB_PROGRESS, "observability", listOf(CapabilityId.JOB_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.RESOURCE_ACCOUNTING, "resource-governor", emptyList()),
        CapabilityDefinition(CapabilityId.PERFORMANCE_MEASUREMENT, "observability", emptyList()),
        CapabilityDefinition(CapabilityId.DIAGNOSTIC_REASONING, "observability", listOf(CapabilityId.SERVICE_HEALTH)),
        CapabilityDefinition(CapabilityId.EVIDENCE_LABELING, "observability", emptyList()),
        CapabilityDefinition(CapabilityId.ASSET_LIFECYCLE, "interface-platform", emptyList()),
        CapabilityDefinition(CapabilityId.JOB_RECOVERY, "reliability-platform", listOf(CapabilityId.JOB_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.CONTENT_REPORTING, "safety-platform", listOf(CapabilityId.REQUEST_LIFECYCLE)),
        CapabilityDefinition(CapabilityId.USABILITY_VALIDATION, "experience-platform", listOf(CapabilityId.LOCAL_UI_INTERFACE)),
        CapabilityDefinition(CapabilityId.ACCESSIBILITY_VALIDATION, "experience-platform", listOf(CapabilityId.LOCAL_UI_INTERFACE)),
    )

    private val byId: Map<CapabilityId, CapabilityDefinition> =
        ALL.associateBy { it.id }

    fun get(id: CapabilityId): CapabilityDefinition =
        byId.getValue(id)

    fun requireKnown(id: String): CapabilityId = CapabilityId.requireFromId(id)
}
