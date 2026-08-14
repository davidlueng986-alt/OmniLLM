package com.omnillm.ui.admin

import ai.omnillm.api.IJobObserver
import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniJobEventBatch
import ai.omnillm.api.OmniJobSpec
import ai.omnillm.api.OmniSettingEntry
import ai.omnillm.api.OmniSettingsPatch
import android.os.RemoteException
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.domain.JobId
import com.omnillm.features.admin.AdminFeatureModule
import com.omnillm.features.admin.ports.AdminCommandPort
import com.omnillm.features.admin.ports.AdminFeaturePorts
import com.omnillm.features.admin.ports.AdminJobPort
import com.omnillm.features.admin.ports.AdminModelPort
import com.omnillm.features.admin.ports.AdminRuntimeStatusPort
import com.omnillm.features.admin.ports.AdminSnapshotPort
import com.omnillm.features.admin.ports.ModelRevisionSummary
import com.omnillm.features.admin.usecase.AdminFeatureApi
import com.omnillm.features.admin.viewmodel.AdminHomeViewModel
import com.omnillm.features.admin.viewmodel.AdminJobsViewModel
import com.omnillm.features.admin.viewmodel.AdminSettingsViewModel
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminCommandResult
import com.omnillm.interfaces.admin.AdminJobEvent
import com.omnillm.interfaces.admin.AdminJobEventBatch
import com.omnillm.interfaces.admin.AdminJobEventSink
import com.omnillm.interfaces.admin.AdminJobSpec
import com.omnillm.interfaces.admin.AdminSettingsView
import com.omnillm.interfaces.admin.AdminSnapshotView
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.policy.SettingValue
import java.util.UUID

/**
 * Builds FEAT-ADMIN feature API + ViewModels from the non-exported [IOmniAdmin]
 * binder (INV-001). UI process never opens domain DB or loads engines.
 */
object BinderAdminFeatureFactory {

    fun createFeatureApi(admin: IOmniAdmin): AdminFeatureApi {
        val ports = BinderAdminPorts(admin).asFeaturePorts()
        return AdminFeatureModule.createApi(ports)
    }

    fun createHomeViewModel(admin: IOmniAdmin): AdminHomeViewModel =
        AdminFeatureModule.createHomeViewModel(createFeatureApi(admin))

    fun createJobsViewModel(admin: IOmniAdmin): AdminJobsViewModel =
        AdminFeatureModule.createJobsViewModel(createFeatureApi(admin))

    fun createSettingsViewModel(admin: IOmniAdmin): AdminSettingsViewModel =
        AdminFeatureModule.createSettingsViewModel(createFeatureApi(admin))

    /**
     * Production VM bundle for the UiSession binder listener (C-06): every
     * feature screen receives a live ViewModel when the Admin binder connects,
     * including diagnostics / routing / content-report. Screens render real
     * session data instead of the disconnected fallback state.
     */
    fun createFeatureViewModels(admin: IOmniAdmin): UiFeatureViewModels =
        UiFeatureViewModels(
            adminHome = createHomeViewModel(admin),
            adminJobs = createJobsViewModel(admin),
            adminSettings = createSettingsViewModel(admin),
            modelHub = AdminLiveFeatureFactory.createModelHubViewModel(admin),
            autoSetup = AdminLiveFeatureFactory.createAutoSetupViewModel(admin),
            playground = AdminLiveFeatureFactory.createPlaygroundViewModel(admin),
            dashboard = AdminLiveFeatureFactory.createDashboardViewModel(admin),
            benchmark = AdminLiveFeatureFactory.createBenchmarkViewModel(admin),
            server = AdminLiveFeatureFactory.createServerViewModel(admin),
            lan = AdminLiveFeatureFactory.createLanViewModel(admin),
            diagnostics = AdminLiveFeatureFactory.createDiagnosticsViewModel(admin),
            contentReport = AdminLiveFeatureFactory.createContentReportViewModel(admin),
            routing = AdminLiveFeatureFactory.createRoutingViewModel(admin),
        )
}

/**
 * All feature ViewModels served to the UI session over the Admin binder (INV-001).
 * Mirrors the [com.omnillm.ui.session.UiSession] VM slots so the session can
 * attach the full production set from one bundle.
 */
data class UiFeatureViewModels(
    val adminHome: AdminHomeViewModel,
    val adminJobs: AdminJobsViewModel,
    val adminSettings: AdminSettingsViewModel,
    val modelHub: com.omnillm.features.modelhub.viewmodel.ModelHubViewModel,
    val autoSetup: com.omnillm.features.autosetup.viewmodel.AutoSetupViewModel,
    val playground: com.omnillm.features.playground.viewmodel.PlaygroundViewModel,
    val dashboard: com.omnillm.features.dashboard.viewmodel.DashboardViewModel,
    val benchmark: com.omnillm.features.benchmark.viewmodel.BenchmarkViewModel,
    val server: com.omnillm.features.server.viewmodel.DeveloperServerViewModel,
    val lan: com.omnillm.features.lan.viewmodel.LanAccessViewModel,
    val diagnostics: com.omnillm.features.diagnostics.viewmodel.DiagnosticsViewModel,
    val contentReport: com.omnillm.features.contentreport.viewmodel.ContentReportViewModel,
    val routing: com.omnillm.features.routing.viewmodel.RoutingViewModel,
)

/**
 * AIDL ??FEAT-ADMIN ports (transport projection only; semantics stay on control plane).
 * Also the default [AdminModelPort]: the AIDL snapshot already carries
 * [OmniModelInfo] entries, so the binder path projects the real model list
 * (C-05) instead of silently failing closed with [EmptyAdminModelPort].
 */
class BinderAdminPorts(
    private val admin: IOmniAdmin,
) : AdminSnapshotPort, AdminCommandPort, AdminJobPort, AdminRuntimeStatusPort, AdminModelPort {

    fun asFeaturePorts(
        models: AdminModelPort? = null,
    ): AdminFeaturePorts =
        AdminFeaturePorts(
            snapshot = this,
            commands = this,
            jobs = this,
            models = models ?: this,
            runtimeStatus = this,
        )

    override fun listRevisionSummaries(): List<ModelRevisionSummary> =
        try {
            admin.snapshot.models.orEmpty().mapNotNull { m ->
                if (m == null || m.modelRevisionId.isNullOrBlank()) return@mapNotNull null
                ModelRevisionSummary(
                    modelRevisionId = m.modelRevisionId,
                    displayName = m.displayName.orEmpty().ifBlank { null },
                    installationState = m.installationState,
                    // AIDL OmniModelInfo carries no trust label — honest null.
                    trustLabel = null,
                )
            }
        } catch (_: RemoteException) {
            emptyList()
        }

    override fun getSnapshot(principal: PrincipalId): AdminSnapshotView {
        requireLocalUi(principal)
        val snap = admin.snapshot
        return AdminSnapshotView(
            snapshotVersion = snap.snapshotVersion,
            highWatermark = 0L,
            runtimeState = snap.runtimeState.orEmpty().ifBlank { "UNKNOWN" },
            lanState = snap.lanState.orEmpty().ifBlank { "DISABLED" },
            activeJobs = snap.activeJobs.orEmpty().mapNotNull { aidlToJobRecord(it) },
            settings = toSettingsView(snap.settings),
        )
    }

    override fun getSettings(principal: PrincipalId): AdminSettingsView {
        requireLocalUi(principal)
        return toSettingsView(admin.settings)
    }

    override fun applySettings(
        principal: PrincipalId,
        command: AdminCommandRequest,
        changes: Map<String, SettingValue>,
    ): AdminCommandResult {
        requireLocalUi(principal)
        val patch = OmniSettingsPatch()
        patch.command = toAidlCommand(command)
        patch.changes = changes.map { (k, v) -> toAidlSetting(k, v) }.toTypedArray()
        return try {
            toDomainCommandResult(admin.applySettings(patch))
        } catch (e: RemoteException) {
            AdminCommandResult.failed(
                command.commandId,
                OmniError.INTERNAL(message = e.message ?: "applySettings remote failure"),
            )
        }
    }

    override fun queryCommand(principal: PrincipalId, commandId: String): AdminCommandResult {
        requireLocalUi(principal)
        return try {
            toDomainCommandResult(admin.queryCommand(commandId))
        } catch (e: RemoteException) {
            AdminCommandResult.failed(
                commandId,
                OmniError.INTERNAL(message = e.message ?: "queryCommand remote failure"),
            )
        }
    }

    override fun startJob(principal: PrincipalId, spec: AdminJobSpec): OmniResult<JobRecord> {
        requireLocalUi(principal)
        return try {
            val aidl = OmniJobSpec()
            aidl.kind = spec.kind
            aidl.command = toAidlCommand(spec.command)
            val info = admin.startJob(aidl)
            val record = aidlToJobRecord(info)
                ?: return OmniResult.err(OmniError.INTERNAL(message = "invalid job info"))
            OmniResult.ok(record)
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "startJob remote failure"))
        }
    }

    override fun getJob(principal: PrincipalId, jobId: String): OmniResult<JobRecord> {
        requireLocalUi(principal)
        return try {
            val info = admin.getJob(jobId)
            val record = aidlToJobRecord(info)
                ?: return OmniResult.err(OmniError.NOT_FOUND(message = "job not found"))
            OmniResult.ok(record)
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "getJob remote failure"))
        }
    }

    override fun cancelJob(
        principal: PrincipalId,
        jobId: String,
        command: AdminCommandRequest,
    ): AdminCommandResult {
        requireLocalUi(principal)
        return try {
            toDomainCommandResult(admin.cancelJob(jobId, toAidlCommand(command)))
        } catch (e: RemoteException) {
            AdminCommandResult.failed(
                command.commandId,
                OmniError.INTERNAL(message = e.message ?: "cancelJob remote failure"),
            )
        }
    }

    override fun listActiveJobs(principal: PrincipalId): List<JobRecord> =
        getSnapshot(principal).activeJobs

    override fun observeJobs(
        principal: PrincipalId,
        cursor: String?,
        credit: Int,
        sink: AdminJobEventSink,
    ): OmniResult<String> {
        requireLocalUi(principal)
        return try {
            val observer = object : IJobObserver.Stub() {
                override fun onEvents(batch: OmniJobEventBatch?) {
                    if (batch == null) return
                    val events = batch.events.orEmpty().mapIndexed { idx, it ->
                        AdminJobEvent(
                            eventId = it?.eventId?.toLongOrNull() ?: idx.toLong(),
                            jobId = it?.jobId.orEmpty(),
                            attemptNo = it?.attemptNo?.takeIf { n -> n > 0 } ?: 1,
                            kind = it?.kind.orEmpty().ifBlank { "STATE" },
                            state = it?.state.orEmpty(),
                            progress = it?.progress ?: 0.0,
                            occurredAtEpochMs = it?.occurredAtEpochMillis?.takeIf { t -> t > 0L }
                                ?: System.currentTimeMillis(),
                        )
                    }
                    sink.onEvents(
                        AdminJobEventBatch(
                            subscriptionId = batch.subscriptionId.orEmpty(),
                            streamEpoch = batch.streamEpoch,
                            eventFrom = batch.eventFrom,
                            eventTo = batch.eventTo,
                            events = events,
                        ),
                    )
                }

                override fun onRejected(error: ai.omnillm.api.OmniError?) {
                    sink.onRejected(
                        OmniError.of(
                            code = OmniErrorCode.fromCode(error?.code.orEmpty())
                                ?: OmniErrorCode.INTERNAL,
                            message = error?.message,
                        ),
                    )
                }
            }
            OmniResult.ok(admin.observeJobs(cursor, credit, observer))
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "observeJobs remote failure"))
        }
    }

    override fun ackJobEvents(
        principal: PrincipalId,
        subscriptionId: String,
        streamEpoch: Long,
        eventToExclusive: Long,
    ): OmniResult<Unit> {
        requireLocalUi(principal)
        return try {
            admin.ackJobEvents(subscriptionId, streamEpoch, eventToExclusive)
            OmniResult.ok(Unit)
        } catch (e: RemoteException) {
            OmniResult.err(OmniError.INTERNAL(message = e.message ?: "ackJobEvents remote failure"))
        }
    }

    override fun closeSubscription(principal: PrincipalId, subscriptionId: String) {
        try {
            admin.closeSubscription(subscriptionId)
        } catch (_: RemoteException) {
            // Best-effort death cleanup.
        }
    }

    override fun runtimeState(): String =
        try {
            admin.snapshot.runtimeState.orEmpty().ifBlank { "UNKNOWN" }
        } catch (_: RemoteException) {
            "UNKNOWN"
        }

    override fun lanState(): String =
        try {
            admin.snapshot.lanState.orEmpty().ifBlank { "DISABLED" }
        } catch (_: RemoteException) {
            "DISABLED"
        }

    override fun resourcePressureLabel(): String? = null

    private fun requireLocalUi(principal: PrincipalId) {
        check(principal.value == LocalUiPrincipal.ID.value) {
            "Admin binder path requires LOCAL_UI principal"
        }
    }

    private fun toSettingsView(snap: ai.omnillm.api.OmniSettingsSnapshot?): AdminSettingsView {
        if (snap == null) return AdminSettingsView(resourceVersion = 0L, values = emptyMap())
        val values = linkedMapOf<String, SettingValue>()
        for (entry in snap.values.orEmpty()) {
            if (entry == null || entry.key.isNullOrBlank()) continue
            val v = when (entry.valueType) {
                "boolean" -> SettingValue.BoolValue(entry.boolValue)
                "integer" -> SettingValue.IntValue(entry.longValue)
                "number" -> SettingValue.NumberValue(entry.doubleValue)
                "enum" -> SettingValue.EnumValue(entry.stringValue.orEmpty())
                "string_list" -> SettingValue.StringListValue(entry.stringListValue?.toList().orEmpty())
                else -> SettingValue.StringValue(entry.stringValue.orEmpty())
            }
            values[entry.key] = v
        }
        return AdminSettingsView(resourceVersion = snap.resourceVersion, values = values)
    }

    private fun toAidlCommand(command: AdminCommandRequest): OmniCommandRequest {
        val req = OmniCommandRequest()
        req.commandId = command.commandId
        req.idempotencyKey = command.idempotencyKey
        req.canonicalInputDigest = command.canonicalInputDigest
        if (command.expectedVersion != null) {
            req.hasExpectedVersion = true
            req.expectedVersion = command.expectedVersion!!
        }
        return req
    }

    private fun toAidlSetting(key: String, value: SettingValue): OmniSettingEntry {
        val entry = OmniSettingEntry()
        entry.key = key
        entry.stringListValue = emptyArray()
        when (value) {
            is SettingValue.BoolValue -> {
                entry.valueType = "boolean"
                entry.boolValue = value.value
            }
            is SettingValue.IntValue -> {
                entry.valueType = "integer"
                entry.longValue = value.value
            }
            is SettingValue.NumberValue -> {
                entry.valueType = "number"
                entry.doubleValue = value.value
            }
            is SettingValue.StringValue -> {
                entry.valueType = "string"
                entry.stringValue = value.value
            }
            is SettingValue.EnumValue -> {
                entry.valueType = "enum"
                entry.stringValue = value.value
            }
            is SettingValue.StringListValue -> {
                entry.valueType = "string_list"
                entry.stringListValue = value.value.toTypedArray()
            }
        }
        return entry
    }

    private fun toDomainCommandResult(result: ai.omnillm.api.CommandResult?): AdminCommandResult {
        if (result == null) {
            return AdminCommandResult.failed(
                UUID.randomUUID().toString(),
                OmniError.INTERNAL(message = "null CommandResult"),
            )
        }
        val err = result.error?.let {
            OmniError.of(
                code = OmniErrorCode.fromCode(it.code.orEmpty()) ?: OmniErrorCode.INTERNAL,
                message = it.message,
            )
        }
        return if (err != null) {
            AdminCommandResult.failed(result.commandId.orEmpty(), err)
        } else {
            AdminCommandResult.succeeded(
                commandId = result.commandId.orEmpty(),
                resourceVersion = result.resourceVersion,
                affectedResourceId = result.affectedResourceId,
                resultSchemaId = result.resultSchemaId,
                resultCanonicalJson = result.resultCanonicalJson,
            )
        }
    }

    /**
     * Minimal JobRecord projection from AIDL OmniJobInfo for Admin home / list.
     * Full parameter bodies stay on the control plane; UI never invents acquisition semantics.
     */
    private fun aidlToJobRecord(info: ai.omnillm.api.OmniJobInfo?): JobRecord? {
        if (info == null || info.jobId.isNullOrBlank()) return null
        return try {
            val now = System.currentTimeMillis()
            val total = if (info.progress in 0.0..1.0 && info.progress > 0.0) 100L else null
            val done = total?.let { (info.progress * it).toLong() } ?: 0L
            JobRecord(
                identity = JobIdentity(
                    jobId = JobId(info.jobId),
                    principalId = LocalUiPrincipal.ID,
                    kind = JobKind.DIAGNOSTIC_EXPORT,
                    idempotencyKey = IdempotencyKey.parse("binder-${info.jobId}".take(128)),
                    canonicalSpecDigest = "b".repeat(64),
                ),
                parameters = JobParameters.DiagnosticExport(),
                state = info.state.orEmpty().ifBlank { "QUEUED" },
                resourceVersion = info.resourceVersion,
                currentAttemptNo = 1,
                attempts = emptyList(),
                checkpoint = null,
                progress = JobProgress(
                    networkBytes = done,
                    materializedBytes = done,
                    verifiedBytes = done,
                    totalBytesKnown = total,
                ),
                pauseReason = null,
                error = info.error?.let {
                    OmniError.of(
                        code = OmniErrorCode.fromCode(it.code.orEmpty()) ?: OmniErrorCode.INTERNAL,
                        message = it.message,
                    )
                },
                events = emptyList(),
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        } catch (_: Exception) {
            null
        }
    }
}

