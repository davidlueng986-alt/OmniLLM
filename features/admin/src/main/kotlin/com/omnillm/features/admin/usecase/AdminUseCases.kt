package com.omnillm.features.admin.usecase

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.admin.model.AdminFeatureError
import com.omnillm.features.admin.model.AdminHomeUi
import com.omnillm.features.admin.model.CommandStatusUi
import com.omnillm.features.admin.model.JobDetailUi
import com.omnillm.features.admin.model.JobListItemUi
import com.omnillm.features.admin.model.JobObserverSessionUi
import com.omnillm.features.admin.model.SettingsScreenUi
import com.omnillm.features.admin.ports.AdminFeaturePorts
import com.omnillm.features.admin.projection.AdminHomeProjection
import com.omnillm.features.admin.projection.CommandUiProjection
import com.omnillm.features.admin.projection.JobUiProjection
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminJobEvent
import com.omnillm.interfaces.admin.AdminJobEventBatch
import com.omnillm.interfaces.admin.AdminJobEventSink
import com.omnillm.interfaces.admin.AdminJobSpec
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.policy.SettingValue

/**
 * FEAT-ADMIN use-cases. Compose [AdminFeaturePorts] only — no domain rewrite of
 * Request / Session / Trust (FEATURE-SYSTEM).
 */

class GetAdminHomeUseCase(
    private val ports: AdminFeaturePorts,
) {
    operator fun invoke(principal: PrincipalId = LocalUiPrincipal.ID): AdminHomeUi {
        val snapshot = ports.snapshot.getSnapshot(principal)
        val models = ports.models.listRevisionSummaries()
        val pressure = ports.runtimeStatus?.resourcePressureLabel()
        return AdminHomeProjection.projectHome(snapshot, models, pressure)
    }
}

class GetSettingsUseCase(
    private val ports: AdminFeaturePorts,
) {
    operator fun invoke(principal: PrincipalId = LocalUiPrincipal.ID): SettingsScreenUi {
        val settings = ports.snapshot.getSettings(principal)
        return AdminHomeProjection.projectSettings(settings)
    }
}

class PatchSettingsUseCase(
    private val ports: AdminFeaturePorts,
) {
    /**
     * CAS settings patch via durable command. [expectedVersion] required
     * (FEAT-ADMIN §2 — no blind last-write-wins).
     */
    operator fun invoke(
        command: AdminCommandRequest,
        changes: Map<String, SettingValue>,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): CommandStatusUi {
        val result = ports.commands.applySettings(principal, command, changes)
        return CommandUiProjection.project(result)
    }
}

class QueryCommandUseCase(
    private val ports: AdminFeaturePorts,
) {
    /** Reply-loss reconciliation — query only, never re-execute (ADR-004/005). */
    operator fun invoke(
        commandId: String,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): CommandStatusUi {
        val result = ports.commands.queryCommand(principal, commandId)
        return CommandUiProjection.project(result)
    }
}

class StartJobUseCase(
    private val ports: AdminFeaturePorts,
) {
    operator fun invoke(
        jobId: String,
        kind: String,
        command: AdminCommandRequest,
        parameters: JobParameters,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): OmniResult<JobDetailUi> {
        val spec = AdminJobSpec(
            jobId = jobId,
            kind = kind,
            command = command,
            parameters = parameters,
        )
        return when (val created = ports.jobs.startJob(principal, spec)) {
            is OmniResult.Ok -> OmniResult.ok(JobUiProjection.projectDetail(created.value))
            is OmniResult.Err -> OmniResult.err(created.error)
        }
    }
}

class GetJobUseCase(
    private val ports: AdminFeaturePorts,
) {
    operator fun invoke(
        jobId: String,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): OmniResult<JobDetailUi> =
        when (val q = ports.jobs.getJob(principal, jobId)) {
            is OmniResult.Ok -> OmniResult.ok(JobUiProjection.projectDetail(q.value))
            is OmniResult.Err -> OmniResult.err(q.error)
        }
}

class ListActiveJobsUseCase(
    private val ports: AdminFeaturePorts,
) {
    operator fun invoke(principal: PrincipalId = LocalUiPrincipal.ID): List<JobListItemUi> =
        ports.jobs.listActiveJobs(principal).map { JobUiProjection.projectListItem(it) }
}

class CancelJobUseCase(
    private val ports: AdminFeaturePorts,
) {
    /**
     * Cancel a non-terminal job (including paused — FEAT-ADMIN acceptance §3).
     * Returns Command projection; job terminal state is confirmed via [GetJobUseCase].
     */
    operator fun invoke(
        jobId: String,
        command: AdminCommandRequest,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): CommandStatusUi {
        // Surface NOT_FOUND early; terminal/state conflicts still go through Job Manager
        // so cancel-requested / idempotent CANCELLED claim-return stay consistent.
        // cancelRequested on RUNNING remains a legal control-plane path (WAIT_SAFE_STOP);
        // paused states remain cancellable (FEAT-ADMIN §7.3).
        when (val current = ports.jobs.getJob(principal, jobId)) {
            is OmniResult.Err -> {
                return CommandUiProjection.project(
                    com.omnillm.interfaces.admin.AdminCommandResult.failed(
                        command.commandId,
                        current.error,
                    ),
                )
            }
            is OmniResult.Ok -> Unit
        }
        val result = ports.jobs.cancelJob(principal, jobId, command)
        return CommandUiProjection.project(result)
    }
}

/**
 * Job observer with cursor-gap recovery (FEAT-ADMIN §5 acceptance §4–5).
 *
 * On CURSOR_GONE: rebuild from AdminSnapshot highWatermark and resubscribe.
 * Application ACK advances exclusive cursor; death cleanup is transport-side.
 */
class ObserveJobsUseCase(
    private val ports: AdminFeaturePorts,
) {
    data class ObserveResult(
        val session: JobObserverSessionUi,
        val rebuiltFromSnapshot: Boolean = false,
        val homeAfterRebuild: AdminHomeUi? = null,
    )

    fun subscribe(
        cursor: String?,
        credit: Int,
        onEvents: (List<AdminJobEvent>) -> Unit,
        onRejected: (OmniError) -> Unit,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): OmniResult<ObserveResult> {
        val sink = object : AdminJobEventSink {
            override fun onEvents(batch: AdminJobEventBatch) {
                onEvents(batch.events)
            }

            override fun onRejected(error: OmniError) {
                onRejected(error)
            }
        }
        return when (val sub = ports.jobs.observeJobs(principal, cursor, credit, sink)) {
            is OmniResult.Ok -> OmniResult.ok(
                ObserveResult(
                    session = JobObserverSessionUi(
                        subscriptionId = sub.value,
                        streamEpoch = 1L,
                        lastEventExclusive = cursor?.toLongOrNull() ?: 0L,
                    ),
                ),
            )
            is OmniResult.Err -> {
                if (sub.error.code == OmniErrorCode.CURSOR_GONE) {
                    recoverFromCursorGap(principal, credit, onEvents, onRejected)
                } else {
                    OmniResult.err(sub.error)
                }
            }
        }
    }

    /**
     * Snapshot rebuild + resubscribe from high-watermark (FEAT-ADMIN §5).
     */
    fun recoverFromCursorGap(
        principal: PrincipalId = LocalUiPrincipal.ID,
        credit: Int,
        onEvents: (List<AdminJobEvent>) -> Unit,
        onRejected: (OmniError) -> Unit,
    ): OmniResult<ObserveResult> {
        val home = GetAdminHomeUseCase(ports)(principal)
        val cursor = home.highWatermark.toString()
        val sink = object : AdminJobEventSink {
            override fun onEvents(batch: AdminJobEventBatch) {
                onEvents(batch.events)
            }

            override fun onRejected(error: OmniError) {
                onRejected(error)
            }
        }
        return when (val sub = ports.jobs.observeJobs(principal, cursor, credit, sink)) {
            is OmniResult.Ok -> OmniResult.ok(
                ObserveResult(
                    session = JobObserverSessionUi(
                        subscriptionId = sub.value,
                        streamEpoch = 1L,
                        lastEventExclusive = home.highWatermark,
                        cursorGone = true,
                    ),
                    rebuiltFromSnapshot = true,
                    homeAfterRebuild = home,
                ),
            )
            is OmniResult.Err -> OmniResult.err(sub.error)
        }
    }

    fun ack(
        session: JobObserverSessionUi,
        eventToExclusive: Long,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ): OmniResult<JobObserverSessionUi> =
        when (
            val r = ports.jobs.ackJobEvents(
                principal,
                session.subscriptionId,
                session.streamEpoch,
                eventToExclusive,
            )
        ) {
            is OmniResult.Ok -> OmniResult.ok(
                session.copy(lastEventExclusive = eventToExclusive, lastError = null),
            )
            is OmniResult.Err -> OmniResult.err(r.error)
        }

    fun close(
        session: JobObserverSessionUi,
        principal: PrincipalId = LocalUiPrincipal.ID,
    ) {
        ports.jobs.closeSubscription(principal, session.subscriptionId)
    }
}

/**
 * Facade bundling FEAT-ADMIN use-cases for UI / Admin entry.
 */
class AdminFeatureApi(
    val ports: AdminFeaturePorts,
) {
    val getHome = GetAdminHomeUseCase(ports)
    val getSettings = GetSettingsUseCase(ports)
    val patchSettings = PatchSettingsUseCase(ports)
    val queryCommand = QueryCommandUseCase(ports)
    val startJob = StartJobUseCase(ports)
    val getJob = GetJobUseCase(ports)
    val listActiveJobs = ListActiveJobsUseCase(ports)
    val cancelJob = CancelJobUseCase(ports)
    val observeJobs = ObserveJobsUseCase(ports)

    fun mapError(error: OmniError): AdminFeatureError = AdminFeatureError.Port(error)
}
