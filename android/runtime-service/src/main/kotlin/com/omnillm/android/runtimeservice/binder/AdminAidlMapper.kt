package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.OmniAdminSnapshot
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniError
import ai.omnillm.api.OmniErrorDetail
import ai.omnillm.api.OmniJobEvent
import ai.omnillm.api.OmniJobEventBatch
import ai.omnillm.api.OmniJobInfo
import ai.omnillm.api.OmniJobSpec
import ai.omnillm.api.OmniSettingEntry
import ai.omnillm.api.OmniSettingsSnapshot
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminCommandResult
import com.omnillm.interfaces.admin.AdminJobEvent
import com.omnillm.interfaces.admin.AdminJobEventBatch
import com.omnillm.interfaces.admin.AdminJobSpec
import com.omnillm.interfaces.admin.AdminSettingsView
import com.omnillm.interfaces.admin.AdminSnapshotView
import com.omnillm.runtime.job.DeleteResourceKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.policy.SettingValue
import com.omnillm.core.errors.generated.OmniError as DomainOmniError
import com.omnillm.core.errors.generated.OmniErrorCode

/**
 * Maps pure Admin API models ↔ AIDL parcelables (ADR-011 transport projection only).
 */
object AdminAidlMapper {

    fun toDomainCommand(req: OmniCommandRequest?): AdminCommandRequest? {
        if (req == null) return null
        return try {
            AdminCommandRequest(
                commandId = req.commandId.orEmpty(),
                idempotencyKey = req.idempotencyKey.orEmpty(),
                canonicalInputDigest = req.canonicalInputDigest.orEmpty().lowercase(),
                expectedVersion = if (req.hasExpectedVersion) req.expectedVersion else null,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun toAidlCommandResult(result: AdminCommandResult): CommandResult {
        val out = CommandResult()
        out.commandId = result.commandId
        out.state = result.state
        out.resourceVersion = result.resourceVersion
        out.affectedResourceId = result.affectedResourceId
        out.resultSchemaId = result.resultSchemaId
        out.resultCanonicalJson = result.resultCanonicalJson
        out.error = result.error?.let { toAidlError(it) }
        return out
    }

    fun toAidlError(error: DomainOmniError): OmniError {
        val err = OmniError()
        err.code = error.code.code
        err.message = error.message
        err.retryable = error.retryable
        err.details = error.details.map { (k, v) ->
            OmniErrorDetail().apply {
                key = k
                valueType = "string"
                stringValue = v
                longValue = 0L
                doubleValue = 0.0
                boolValue = false
            }
        }.toTypedArray()
        return err
    }

    fun toAidlError(code: OmniErrorCode, message: String, details: Map<String, String> = emptyMap()): OmniError =
        toAidlError(DomainOmniError.of(code, message, details))

    fun toAidlJobInfo(record: JobRecord): OmniJobInfo {
        val info = OmniJobInfo()
        info.jobId = record.jobId.value
        info.state = record.state
        info.resourceVersion = record.resourceVersion
        info.progress = record.progress.ratioOrNull() ?: 0.0
        info.error = record.error?.let { toAidlError(it) }
        info.kind = record.identity.kind.name
        info.canonicalSpecDigest = record.identity.canonicalSpecDigest
        return info
    }

    fun toAidlSettings(view: AdminSettingsView): OmniSettingsSnapshot {
        val snap = OmniSettingsSnapshot()
        snap.resourceVersion = view.resourceVersion
        snap.values = view.values.map { (key, value) ->
            toSettingEntry(key, value)
        }.toTypedArray()
        return snap
    }

    fun toAidlSnapshot(
        view: AdminSnapshotView,
        models: Array<ai.omnillm.api.OmniModelInfo> = emptyArray(),
    ): OmniAdminSnapshot {
        val snap = OmniAdminSnapshot()
        snap.snapshotVersion = view.snapshotVersion
        snap.runtimeState = view.runtimeState
        snap.lanState = view.lanState
        snap.activeJobs = view.activeJobs.map { toAidlJobInfo(it) }.toTypedArray()
        snap.models = models
        snap.settings = toAidlSettings(view.settings)
        return snap
    }

    fun toAidlModelInfo(
        modelRevisionId: String,
        displayName: String,
        installationState: String?,
    ): ai.omnillm.api.OmniModelInfo =
        toAidlModelInfo(
            modelRevisionId = modelRevisionId,
            displayName = displayName,
            installationState = installationState,
            installationId = null,
            artifactPackageId = null,
            licenseStatus = null,
            licenseDigest = null,
            pinned = false,
            loadedModelState = null,
            allowedActions = emptyList(),
            liveReferenceCount = 0,
            compatibilityStatus = null,
            authenticityOk = null,
            acquisitionChannel = null,
            resourceVersion = null,
        )

    fun toAidlModelInfo(card: com.omnillm.features.modelhub.api.ModelCard): ai.omnillm.api.OmniModelInfo =
        toAidlModelInfo(
            modelRevisionId = card.modelRevisionId,
            displayName = card.displayName,
            installationState = card.installationState,
            installationId = card.installationId,
            artifactPackageId = card.artifactPackageId,
            licenseStatus = card.licenseStatus,
            licenseDigest = card.licenseDigest,
            pinned = card.pinned,
            loadedModelState = card.loadedModelState,
            allowedActions = card.allowedActions,
            liveReferenceCount = card.liveReferenceCount,
            compatibilityStatus = card.compatibilityStatus,
            authenticityOk = card.authenticityOk,
            acquisitionChannel = card.acquisitionChannel,
            resourceVersion = card.resourceVersion,
        )

    fun toAidlModelInfo(
        modelRevisionId: String,
        displayName: String,
        installationState: String?,
        installationId: String?,
        artifactPackageId: String?,
        licenseStatus: String?,
        licenseDigest: String?,
        pinned: Boolean,
        loadedModelState: String?,
        allowedActions: List<String>,
        liveReferenceCount: Int,
        compatibilityStatus: String?,
        authenticityOk: Boolean?,
        acquisitionChannel: String?,
        resourceVersion: Long?,
    ): ai.omnillm.api.OmniModelInfo {
        val info = ai.omnillm.api.OmniModelInfo()
        info.modelRevisionId = modelRevisionId
        info.displayName = displayName
        info.installationState = installationState
        info.capabilities = emptyArray()
        info.installationId = installationId
        info.artifactPackageId = artifactPackageId
        info.licenseStatus = licenseStatus
        info.licenseDigest = licenseDigest
        info.pinned = pinned
        info.loadedModelState = loadedModelState
        info.allowedActions = allowedActions.toTypedArray()
        info.liveReferenceCount = liveReferenceCount
        info.compatibilityStatus = compatibilityStatus
        if (authenticityOk != null) {
            info.hasAuthenticityOk = true
            info.authenticityOk = authenticityOk
        }
        info.acquisitionChannel = acquisitionChannel
        if (resourceVersion != null) {
            info.hasResourceVersion = true
            info.resourceVersion = resourceVersion
        }
        return info
    }

    fun toSettingEntry(key: String, value: SettingValue): OmniSettingEntry {
        val entry = OmniSettingEntry()
        entry.key = key
        entry.stringListValue = emptyArray()
        when (value) {
            is SettingValue.BoolValue -> {
                entry.valueType = "boolean"
                entry.boolValue = value.value
                entry.longValue = 0L
                entry.doubleValue = 0.0
                entry.stringValue = null
            }
            is SettingValue.IntValue -> {
                entry.valueType = "integer"
                entry.longValue = value.value
                entry.doubleValue = 0.0
                entry.boolValue = false
                entry.stringValue = null
            }
            is SettingValue.NumberValue -> {
                entry.valueType = "number"
                entry.doubleValue = value.value
                entry.longValue = 0L
                entry.doubleValue = 0.0
                entry.boolValue = false
                entry.stringValue = null
            }
            is SettingValue.StringValue -> {
                entry.valueType = "string"
                entry.stringValue = value.value
                entry.longValue = 0L
                entry.doubleValue = 0.0
                entry.boolValue = false
            }
            is SettingValue.EnumValue -> {
                entry.valueType = "enum"
                entry.stringValue = value.value
                entry.longValue = 0L
                entry.doubleValue = 0.0
                entry.boolValue = false
            }
            is SettingValue.StringListValue -> {
                entry.valueType = "string_list"
                entry.stringListValue = value.value.toTypedArray()
                entry.stringValue = null
                entry.longValue = 0L
                entry.doubleValue = 0.0
                entry.boolValue = false
            }
        }
        return entry
    }

    fun fromSettingEntry(entry: OmniSettingEntry): SettingValue? {
        return when (entry.valueType) {
            "boolean" -> SettingValue.BoolValue(entry.boolValue)
            "integer" -> SettingValue.IntValue(entry.longValue)
            "number" -> SettingValue.NumberValue(entry.doubleValue)
            "string" -> SettingValue.StringValue(entry.stringValue.orEmpty())
            "enum" -> SettingValue.EnumValue(entry.stringValue.orEmpty())
            "string_list" -> SettingValue.StringListValue(entry.stringListValue?.toList().orEmpty())
            else -> null
        }
    }

    fun toDomainJobSpec(spec: OmniJobSpec?): AdminJobSpec? {
        if (spec == null) return null
        val command = toDomainCommand(spec.command) ?: return null
        val kind = spec.kind.orEmpty()
        return try {
            val parameters = when (kind) {
                "DOWNLOAD" -> {
                    val d = spec.download ?: return null
                    JobParameters.Download(
                        sourceUrl = d.sourceUrl.orEmpty(),
                        expectedSha256 = d.expectedSha256,
                        expectedBytes = if (d.hasExpectedBytes) d.expectedBytes else null,
                        targetName = d.targetName,
                    )
                }
                "IMPORT" -> {
                    val i = spec.importSpec ?: return null
                    JobParameters.Import(
                        assetId = i.assetId.orEmpty(),
                        expectedFormat = i.expectedFormat,
                        expectedSha256 = i.expectedSha256,
                    )
                }
                "BENCHMARK" -> {
                    val b = spec.benchmark ?: return null
                    JobParameters.Benchmark(
                        modelRevisionId = b.modelRevisionId.orEmpty(),
                        engineBuildId = b.engineBuildId.orEmpty(),
                        backend = b.backend.orEmpty(),
                        measurementProfileId = b.measurementProfileId.orEmpty(),
                        iterations = b.iterations.takeIf { it > 0 },
                    )
                }
                "DELETE" -> {
                    val d = spec.deleteSpec ?: return null
                    val resourceKind = DeleteResourceKind.fromCatalogName(d.resourceKind.orEmpty())
                        ?: return null
                    JobParameters.Delete(
                        resourceKind = resourceKind,
                        resourceId = d.resourceId.orEmpty(),
                        expectedResourceVersion = d.expectedResourceVersion,
                        forceAfterDrain = d.forceAfterDrain,
                    )
                }
                "DIAGNOSTIC_EXPORT" -> {
                    val d = spec.diagnosticExport
                    JobParameters.DiagnosticExport(
                        includeDetail = d?.includeDetail ?: false,
                        categories = d?.categories?.toList().orEmpty(),
                        ttlSeconds = d?.ttlSeconds?.takeIf { it > 0 }?.toInt(),
                    )
                }
                "CONTENT_REPORT" -> {
                    val c = spec.contentReport ?: return null
                    JobParameters.ContentReport(reportId = c.reportId.orEmpty())
                }
                else -> return null
            }
            AdminJobSpec(
                jobId = spec.jobId.orEmpty(),
                kind = kind,
                command = command,
                parameters = parameters,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun toAidlJobEventBatch(batch: AdminJobEventBatch): OmniJobEventBatch {
        val out = OmniJobEventBatch()
        out.subscriptionId = batch.subscriptionId
        out.streamEpoch = batch.streamEpoch
        out.eventFrom = batch.eventFrom
        out.eventTo = batch.eventTo
        out.events = batch.events.map { toAidlJobEvent(it) }.toTypedArray()
        return out
    }

    fun toAidlJobEvent(event: AdminJobEvent): OmniJobEvent {
        val e = OmniJobEvent()
        e.eventId = event.eventId.toString()
        e.jobId = event.jobId
        e.attemptNo = event.attemptNo
        e.kind = event.kind
        e.state = event.state
        e.progress = event.progress
        e.occurredAtEpochMillis = event.occurredAtEpochMs
        e.error = event.error?.let { toAidlError(it) }
        return e
    }
}
