package com.omnillm.interfaces.admin

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.policy.SettingValue
import com.omnillm.runtime.policy.SettingsSnapshot

/**
 * Client-generated durable command identity (CORE-INTERFACE § Durable Command).
 * Create-new-resource may omit [expectedVersion]; all other mutations must supply it.
 */
data class AdminCommandRequest(
    val commandId: String,
    val idempotencyKey: String,
    val canonicalInputDigest: String,
    val expectedVersion: Long? = null,
) {
    init {
        require(commandId.isNotBlank()) { "commandId must be non-blank" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must be non-blank" }
        require(canonicalInputDigest.matches(HEX64)) {
            "canonicalInputDigest must be 64-char lower-case hex SHA-256"
        }
        expectedVersion?.let {
            require(it >= 0L) { "expectedVersion must be >= 0 when present" }
        }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * Durable command outcome — every Admin mutation returns this shape
 * (never void success claims; CORE-INTERFACE §6 / FEAT-ADMIN §2).
 */
data class AdminCommandResult(
    val commandId: String,
    val state: String,
    val resourceVersion: Long,
    val affectedResourceId: String? = null,
    val resultSchemaId: String? = null,
    val resultCanonicalJson: String? = null,
    val error: OmniError? = null,
) {
    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

    val isSuccess: Boolean
        get() = state == "SUCCEEDED"

    companion object {
        val TERMINAL_STATES: Set<String> = setOf(
            "SUCCEEDED",
            "FAILED",
            "CANCELLED",
            "UNCERTAIN",
        )

        fun failed(
            commandId: String,
            error: OmniError,
            resourceVersion: Long = 0L,
            affectedResourceId: String? = null,
        ): AdminCommandResult =
            AdminCommandResult(
                commandId = commandId.ifBlank { "00000000-0000-0000-0000-000000000000" },
                state = "FAILED",
                resourceVersion = resourceVersion,
                affectedResourceId = affectedResourceId,
                error = error,
            )

        fun succeeded(
            commandId: String,
            resourceVersion: Long,
            affectedResourceId: String? = null,
            resultSchemaId: String? = null,
            resultCanonicalJson: String? = null,
        ): AdminCommandResult =
            AdminCommandResult(
                commandId = commandId,
                state = "SUCCEEDED",
                resourceVersion = resourceVersion,
                affectedResourceId = affectedResourceId,
                resultSchemaId = resultSchemaId,
                resultCanonicalJson = resultCanonicalJson,
            )
    }
}

/** Projection of [SettingsSnapshot] for Admin / AIDL / HTTP adapters. */
data class AdminSettingsView(
    val resourceVersion: Long,
    val values: Map<String, SettingValue>,
)

/**
 * FEAT-ADMIN §5 AdminSnapshot projection.
 * AIDL parcel omits highWatermark; pure API still exposes it for rebuild/resubscribe.
 */
data class AdminSnapshotView(
    val snapshotVersion: Long,
    val highWatermark: Long,
    val runtimeState: String,
    val lanState: String,
    val activeJobs: List<JobRecord>,
    val settings: AdminSettingsView,
)

/** Kind-specific job create parameters accepted by [AdminApiService.startJob]. */
data class AdminJobSpec(
    val jobId: String,
    val kind: String,
    val command: AdminCommandRequest,
    val parameters: com.omnillm.runtime.job.JobParameters,
)

/** Delivered job stream event (FEAT-ADMIN observer / AIDL OmniJobEvent). */
data class AdminJobEvent(
    val eventId: Long,
    val jobId: String,
    val attemptNo: Int,
    val kind: String,
    val state: String,
    val progress: Double,
    val occurredAtEpochMs: Long,
    val error: OmniError? = null,
)

data class AdminJobEventBatch(
    val subscriptionId: String,
    val streamEpoch: Long,
    val eventFrom: Long,
    val eventTo: Long,
    val events: List<AdminJobEvent>,
)

/** Transport-agnostic observer sink (AIDL IJobObserver adapts this). */
interface AdminJobEventSink {
    fun onEvents(batch: AdminJobEventBatch)
    fun onRejected(error: OmniError)
}
