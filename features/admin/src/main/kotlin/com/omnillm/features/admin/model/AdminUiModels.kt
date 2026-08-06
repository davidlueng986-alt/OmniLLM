package com.omnillm.features.admin.model

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.admin.ports.ModelRevisionSummary
import com.omnillm.features.admin.projection.CommandUiAction
import com.omnillm.features.admin.projection.JobUiAction
import com.omnillm.features.admin.projection.UiSeverity

/**
 * UI-domain models for FEAT-ADMIN. Pure projections — no domain mutation.
 * Labels/actions map from canonical JOB / COMMAND FSM states only.
 */

data class JobListItemUi(
    val jobId: String,
    val kind: String,
    val state: String,
    val labelKey: String,
    val severity: UiSeverity,
    val progressRatio: Double?,
    val currentPhase: String?,
    val attemptNo: Int?,
    val cancelRequested: Boolean,
    val pauseReason: String?,
    val allowedActions: List<JobUiAction>,
    val errorCode: String?,
    val updatedAtEpochMs: Long,
)

data class JobDetailUi(
    val list: JobListItemUi,
    val resourceVersion: Long,
    val networkBytes: Long,
    val materializedBytes: Long,
    val verifiedBytes: Long,
    val totalBytesKnown: Long?,
    val estimatedRemainingMs: Long?,
    val checkpointResumeCursor: String?,
    val eventCount: Int,
    val blockedReasonLabelKey: String?,
)

data class CommandStatusUi(
    val commandId: String,
    val state: String,
    val labelKey: String,
    val severity: UiSeverity,
    val isTerminal: Boolean,
    val isSuccess: Boolean,
    val resourceVersion: Long,
    val affectedResourceId: String?,
    val allowedActions: List<CommandUiAction>,
    val error: OmniError?,
    val resultCanonicalJson: String?,
)

data class SettingsFieldUi(
    val key: String,
    val displayValue: String,
    val valueType: String,
)

data class SettingsScreenUi(
    val resourceVersion: Long,
    val fields: List<SettingsFieldUi>,
    val lastCommand: CommandStatusUi?,
)

data class AdminHomeUi(
    val snapshotVersion: Long,
    val highWatermark: Long,
    val runtimeState: String,
    val lanState: String,
    val activeJobCount: Int,
    val jobs: List<JobListItemUi>,
    val models: List<ModelRevisionSummary>,
    val settingsResourceVersion: Long,
    val resourcePressureLabel: String?,
)

/** In-memory observer session held by the feature (not durable domain state). */
data class JobObserverSessionUi(
    val subscriptionId: String,
    val streamEpoch: Long,
    val lastEventExclusive: Long,
    val cursorGone: Boolean = false,
    val lastError: OmniError? = null,
)

sealed class AdminFeatureError {
    data class Port(val error: OmniError) : AdminFeatureError()
    data class Validation(val message: String) : AdminFeatureError()
    data class CursorGone(
        val highWatermark: Long,
        val detail: OmniError,
    ) : AdminFeatureError()
}
