package com.omnillm.features.admin.ports

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.interfaces.admin.AdminCommandRequest
import com.omnillm.interfaces.admin.AdminCommandResult
import com.omnillm.interfaces.admin.AdminJobEventSink
import com.omnillm.interfaces.admin.AdminJobSpec
import com.omnillm.interfaces.admin.AdminSettingsView
import com.omnillm.interfaces.admin.AdminSnapshotView
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.policy.SettingValue

/**
 * Feature-level ports into the runtime control plane for FEAT-ADMIN.
 *
 * Feature packs must compose these ports only — never open domain DB writers,
 * native engines, or engine-private knobs (INV-001, ADR-010, ARCH-EXTENSION).
 *
 * Implementations live behind [com.omnillm.interfaces.admin.AdminApiService]
 * (Job Manager / Policy / Command ledger) on the runtime process.
 */

/** Admin snapshot + settings reads (FEAT-ADMIN §5). */
interface AdminSnapshotPort {
    fun getSnapshot(principal: PrincipalId): AdminSnapshotView
    fun getSettings(principal: PrincipalId): AdminSettingsView
}

/**
 * Durable Command mutations and reply-loss query (FEAT-ADMIN §2, ADR-004/005).
 * All mutations return [AdminCommandResult] — never void success claims.
 */
interface AdminCommandPort {
    fun applySettings(
        principal: PrincipalId,
        command: AdminCommandRequest,
        changes: Map<String, SettingValue>,
    ): AdminCommandResult

    fun queryCommand(principal: PrincipalId, commandId: String): AdminCommandResult
}

/**
 * Job lifecycle surface: create / query / cancel / observe (FEAT-ADMIN §3–5).
 * Wired only through Job Manager claim-or-return semantics.
 */
interface AdminJobPort {
    fun startJob(principal: PrincipalId, spec: AdminJobSpec): OmniResult<JobRecord>
    fun getJob(principal: PrincipalId, jobId: String): OmniResult<JobRecord>
    fun cancelJob(
        principal: PrincipalId,
        jobId: String,
        command: AdminCommandRequest,
    ): AdminCommandResult

    fun listActiveJobs(principal: PrincipalId): List<JobRecord>

    fun observeJobs(
        principal: PrincipalId,
        cursor: String?,
        credit: Int,
        sink: AdminJobEventSink,
    ): OmniResult<String>

    fun ackJobEvents(
        principal: PrincipalId,
        subscriptionId: String,
        streamEpoch: Long,
        eventToExclusive: Long,
    ): OmniResult<Unit>

    fun closeSubscription(principal: PrincipalId, subscriptionId: String)
}

/**
 * Optional Model catalog read for AdminSnapshot model list (FEAT-ADMIN §5).
 * Feature does not redefine ModelRevision / Installation identity (ADR-008).
 * Default empty when Model Manager is not yet attached to Admin.
 */
interface AdminModelPort {
    fun listRevisionSummaries(): List<ModelRevisionSummary>
}

/**
 * Lightweight model row for Admin home / model management projections.
 * Digests and ids remain catalog identities — no trust/compat collapse (ADR-009).
 */
data class ModelRevisionSummary(
    val modelRevisionId: String,
    val displayName: String?,
    val installationState: String?,
    val trustLabel: String?,
) {
    init {
        require(modelRevisionId.isNotBlank()) { "modelRevisionId must be non-blank" }
    }
}

/**
 * Optional Orchestrator / runtime health facts for Admin home resource strip.
 * Does not expose Plan/Reserve internals; read-only operational labels only.
 */
interface AdminRuntimeStatusPort {
    fun runtimeState(): String
    fun lanState(): String
    fun resourcePressureLabel(): String?
}

/**
 * Bundle of feature ports used by FEAT-ADMIN use-cases and view-models.
 */
data class AdminFeaturePorts(
    val snapshot: AdminSnapshotPort,
    val commands: AdminCommandPort,
    val jobs: AdminJobPort,
    val models: AdminModelPort = EmptyAdminModelPort,
    val runtimeStatus: AdminRuntimeStatusPort? = null,
)

/** No-op model catalog until Model Manager Admin projection is wired. */
object EmptyAdminModelPort : AdminModelPort {
    override fun listRevisionSummaries(): List<ModelRevisionSummary> = emptyList()
}
