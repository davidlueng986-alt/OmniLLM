package com.omnillm.runtime.job

/**
 * Job kinds from OpenAPI `JobSpec.kind` / FEAT-ADMIN long-work taxonomy.
 * Only catalog wire values — do not invent synonyms.
 */
enum class JobKind {
    DOWNLOAD,
    IMPORT,
    BENCHMARK,
    DELETE,
    DIAGNOSTIC_EXPORT,
    CONTENT_REPORT,
    ;

    companion object {
        fun fromCatalogName(name: String): JobKind? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): JobKind =
            fromCatalogName(name)
                ?: error("Unknown JobKind (fail closed): $name")
    }
}

/**
 * Delete target kinds from OpenAPI `DeleteJobParameters.resource_kind`.
 */
enum class DeleteResourceKind {
    INSTALLATION,
    MODEL_REVISION,
    ASSET,
    DIAGNOSTIC_EXPORT,
    ;

    companion object {
        fun fromCatalogName(name: String): DeleteResourceKind? =
            entries.firstOrNull { it.name == name }

        fun requireFromCatalogName(name: String): DeleteResourceKind =
            fromCatalogName(name)
                ?: error("Unknown DeleteResourceKind (fail closed): $name")
    }
}

/**
 * Pause / blocked reasons projected from JOB machine states
 * (FEAT-ADMIN §3–4, state-machines.yaml#JOB).
 */
enum class JobPauseReason {
    /** RUNNING → PAUSED_WAITING_INPUT (WAIT_INPUT). */
    WAITING_INPUT,

    /** RUNNING → PAUSED_WAITING_NETWORK (WAIT_NETWORK). */
    WAITING_NETWORK,

    /** RUNNING → PAUSED_WAITING_FOREGROUND (WAIT_FOREGROUND). */
    WAITING_FOREGROUND,
    ;

    fun toState(): String = when (this) {
        WAITING_INPUT -> "PAUSED_WAITING_INPUT"
        WAITING_NETWORK -> "PAUSED_WAITING_NETWORK"
        WAITING_FOREGROUND -> "PAUSED_WAITING_FOREGROUND"
    }

    fun waitEvent(): String = when (this) {
        WAITING_INPUT -> "WAIT_INPUT"
        WAITING_NETWORK -> "WAIT_NETWORK"
        WAITING_FOREGROUND -> "WAIT_FOREGROUND"
    }

    fun resumeEvent(): String = when (this) {
        WAITING_INPUT -> "INPUT_AVAILABLE"
        WAITING_NETWORK -> "NETWORK_AVAILABLE"
        WAITING_FOREGROUND -> "FOREGROUND_ALLOWED"
    }

    fun permanentFailEvent(): String? = when (this) {
        WAITING_INPUT -> "INPUT_PERMANENTLY_UNAVAILABLE"
        WAITING_NETWORK -> "POLICY_OR_DEADLINE_FAILED"
        WAITING_FOREGROUND -> "POLICY_OR_DEADLINE_FAILED"
    }

    companion object {
        fun fromState(state: String): JobPauseReason? = when (state) {
            "PAUSED_WAITING_INPUT" -> WAITING_INPUT
            "PAUSED_WAITING_NETWORK" -> WAITING_NETWORK
            "PAUSED_WAITING_FOREGROUND" -> WAITING_FOREGROUND
            else -> null
        }
    }
}
