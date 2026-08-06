package com.omnillm.features.admin.ports

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.interfaces.admin.AdminApiService
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminCommandResult
import com.omnillm.interfaces.admin.AdminJobEventSink
import com.omnillm.interfaces.admin.AdminJobSpec
import com.omnillm.interfaces.admin.AdminSettingsView
import com.omnillm.interfaces.admin.AdminSnapshotView
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.policy.SettingValue

/**
 * Adapts [AdminApiService] (interfaces/admin) to FEAT-ADMIN feature ports.
 *
 * Control-plane wiring remains on the runtime process; this adapter is a pure
 * delegation boundary so Feature Pack code never reaches JobStore / DB / engines.
 */
class AdminApiServiceAdapter(
    private val api: AdminApiService,
) : AdminSnapshotPort, AdminCommandPort, AdminJobPort {

    override fun getSnapshot(principal: PrincipalId): AdminSnapshotView =
        api.getSnapshot(principal)

    override fun getSettings(principal: PrincipalId): AdminSettingsView =
        api.getSettings(principal)

    override fun applySettings(
        principal: PrincipalId,
        command: AdminCommandRequest,
        changes: Map<String, SettingValue>,
    ): AdminCommandResult = api.applySettings(principal, command, changes)

    override fun queryCommand(principal: PrincipalId, commandId: String): AdminCommandResult =
        api.queryCommand(principal, commandId)

    override fun startJob(principal: PrincipalId, spec: AdminJobSpec): OmniResult<JobRecord> =
        api.startJob(principal, spec)

    override fun getJob(principal: PrincipalId, jobId: String): OmniResult<JobRecord> =
        api.getJob(principal, jobId)

    override fun cancelJob(
        principal: PrincipalId,
        jobId: String,
        command: AdminCommandRequest,
    ): AdminCommandResult = api.cancelJob(principal, jobId, command)

    override fun listActiveJobs(principal: PrincipalId): List<JobRecord> =
        api.getSnapshot(principal).activeJobs

    override fun observeJobs(
        principal: PrincipalId,
        cursor: String?,
        credit: Int,
        sink: AdminJobEventSink,
    ): OmniResult<String> = api.observeJobs(principal, cursor, credit, sink)

    override fun ackJobEvents(
        principal: PrincipalId,
        subscriptionId: String,
        streamEpoch: Long,
        eventToExclusive: Long,
    ): OmniResult<Unit> = api.ackJobEvents(principal, subscriptionId, streamEpoch, eventToExclusive)

    override fun closeSubscription(principal: PrincipalId, subscriptionId: String) {
        api.closeSubscription(principal, subscriptionId)
    }

    fun asFeaturePorts(
        models: AdminModelPort = EmptyAdminModelPort,
        runtimeStatus: AdminRuntimeStatusPort? = null,
    ): AdminFeaturePorts =
        AdminFeaturePorts(
            snapshot = this,
            commands = this,
            jobs = this,
            models = models,
            runtimeStatus = runtimeStatus,
        )
}
