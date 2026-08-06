package com.omnillm.features.admin.projection

import com.omnillm.features.admin.model.AdminHomeUi
import com.omnillm.features.admin.model.SettingsFieldUi
import com.omnillm.features.admin.model.SettingsScreenUi
import com.omnillm.features.admin.ports.ModelRevisionSummary
import com.omnillm.interfaces.admin.AdminSettingsView
import com.omnillm.interfaces.admin.AdminSnapshotView
import com.omnillm.runtime.policy.SettingValue

/**
 * Projects AdminSnapshot + optional model catalog into home / settings UI models.
 */
object AdminHomeProjection {

    fun projectHome(
        snapshot: AdminSnapshotView,
        models: List<ModelRevisionSummary> = emptyList(),
        resourcePressureLabel: String? = null,
    ): AdminHomeUi =
        AdminHomeUi(
            snapshotVersion = snapshot.snapshotVersion,
            highWatermark = snapshot.highWatermark,
            runtimeState = snapshot.runtimeState,
            lanState = snapshot.lanState,
            activeJobCount = snapshot.activeJobs.size,
            jobs = snapshot.activeJobs.map { JobUiProjection.projectListItem(it) },
            models = models,
            settingsResourceVersion = snapshot.settings.resourceVersion,
            resourcePressureLabel = resourcePressureLabel,
        )

    fun projectSettings(settings: AdminSettingsView): SettingsScreenUi =
        SettingsScreenUi(
            resourceVersion = settings.resourceVersion,
            fields = settings.values.entries
                .sortedBy { it.key }
                .map { (key, value) ->
                    SettingsFieldUi(
                        key = key,
                        displayValue = formatSetting(value),
                        valueType = valueTypeName(value),
                    )
                },
            lastCommand = null,
        )

    private fun formatSetting(value: SettingValue): String = when (value) {
        is SettingValue.BoolValue -> value.value.toString()
        is SettingValue.IntValue -> value.value.toString()
        is SettingValue.NumberValue -> value.value.toString()
        is SettingValue.StringValue -> value.value
        is SettingValue.EnumValue -> value.value
        is SettingValue.StringListValue -> value.value.joinToString(",")
    }

    private fun valueTypeName(value: SettingValue): String = when (value) {
        is SettingValue.BoolValue -> "bool"
        is SettingValue.IntValue -> "int"
        is SettingValue.NumberValue -> "number"
        is SettingValue.StringValue -> "string"
        is SettingValue.EnumValue -> "enum"
        is SettingValue.StringListValue -> "stringList"
    }
}
