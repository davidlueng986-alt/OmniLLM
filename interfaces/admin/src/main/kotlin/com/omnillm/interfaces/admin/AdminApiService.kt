package com.omnillm.interfaces.admin

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.JobId
import com.omnillm.data.persistence.CommandLedgerStates
import com.omnillm.data.persistence.IdempotentCommandClaimRow
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.policy.SettingValue
import com.omnillm.runtime.requestregistry.ClaimOutcome
import com.omnillm.runtime.requestregistry.CommandLedger
import java.util.concurrent.atomic.AtomicLong

/**
 * Non-exported Admin API for LOCAL_UI (FEAT-ADMIN, CORE-INTERFACE §6–7, UX-IA).
 *
 * Responsibilities:
 * - Snapshots (runtime / jobs / settings + highWatermark)
 * - Durable commands via claim-or-return + [queryCommand] reply-loss path
 * - Job start/query/cancel with Job Manager
 * - Job observers with subscription handles, credit/ACK, death cleanup hooks
 * - Settings patch with resourceVersion CAS ([AdminCommandRequest.expectedVersion])
 *
 * All mutations return [AdminCommandResult] (never void success claims).
 * Transport adapters map to AIDL [CommandResult] / HTTP bodies without changing semantics.
 */
class AdminApiService(
    private val commandLedger: CommandLedger,
    private val jobManager: JobManager,
    private val policyManager: PolicyManager,
    val jobObservers: JobSubscriptionRegistry,
    private val runtimeStateProvider: () -> String,
    private val lanStateProvider: () -> String,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val snapshotSeq = AtomicLong(0L)

    // ------------------------------------------------------------------
    // Snapshots / reads
    // ------------------------------------------------------------------

    fun getSnapshot(principal: PrincipalId = LocalUiPrincipal.ID): AdminSnapshotView {
        requireLocalUi(principal)
        val settings = policyManager.settingsSnapshot()
        return AdminSnapshotView(
            snapshotVersion = snapshotSeq.incrementAndGet(),
            highWatermark = jobObservers.highWatermark(),
            runtimeState = runtimeStateProvider(),
            lanState = lanStateProvider(),
            activeJobs = jobManager.listActive(),
            settings = AdminSettingsView(
                resourceVersion = settings.resourceVersion,
                values = settings.values,
            ),
        )
    }

    fun getSettings(principal: PrincipalId = LocalUiPrincipal.ID): AdminSettingsView {
        requireLocalUi(principal)
        val snap = policyManager.settingsSnapshot()
        return AdminSettingsView(
            resourceVersion = snap.resourceVersion,
            values = snap.values,
        )
    }

    fun getJob(principal: PrincipalId, jobId: String): OmniResult<JobRecord> {
        requireLocalUi(principal)
        return try {
            jobManager.query(JobId(jobId))
        } catch (e: IllegalArgumentException) {
            OmniResult.err(OmniError.INVALID_REQUEST(message = e.message ?: "invalid jobId"))
        }
    }

    // ------------------------------------------------------------------
    // Settings patch (CAS on resourceVersion)
    // ------------------------------------------------------------------

    /**
     * Apply settings changes under durable command claim.
     * [AdminCommandRequest.expectedVersion] is **required** (settings already exist).
     * CAS mismatch ⇒ STATE_CONFLICT CommandResult (not silent last-write-wins).
     */
    fun applySettings(
        principal: PrincipalId,
        command: AdminCommandRequest,
        changes: Map<String, SettingValue>,
    ): AdminCommandResult {
        requireLocalUi(principal)
        if (!LocalUiPrincipal.allows(AccessScope.settings_write)) {
            return AdminCommandResult.failed(
                command.commandId,
                OmniError.FORBIDDEN(message = "settings.write not allowed"),
            )
        }
        val expected = command.expectedVersion
            ?: return failAndMaybeRecord(
                principal,
                AdminOperationKinds.PATCH_SETTINGS,
                command,
                OmniError.INVALID_REQUEST(
                    message = "expectedVersion required for settings patch",
                ),
            )
        if (changes.isEmpty()) {
            return failAndMaybeRecord(
                principal,
                AdminOperationKinds.PATCH_SETTINGS,
                command,
                OmniError.INVALID_REQUEST(message = "settings patch requires at least one change"),
            )
        }

        return executeCommand(principal, AdminOperationKinds.PATCH_SETTINGS, command) {
            when (
                val patched = policyManager.patchSettings(
                    baseVersion = expected,
                    changes = changes,
                    source = "administrator-policy",
                )
            ) {
                is OmniResult.Ok -> CommandExecution.Success(
                    resourceVersion = patched.value.resourceVersion,
                    affectedResourceId = "settings",
                    resultCanonicalJson =
                        """{"resourceVersion":${patched.value.resourceVersion}}""",
                )
                is OmniResult.Err -> CommandExecution.Failure(patched.error)
            }
        }
    }

    // ------------------------------------------------------------------
    // Jobs
    // ------------------------------------------------------------------

    /**
     * Create (or claim-return) a Job. Returns the durable [JobRecord].
     * Create-job uses [AdminOperationKinds.CREATE_JOB] command ledger claim;
     * job claim key is (principal, kind, idempotencyKey) inside [JobManager].
     */
    fun startJob(principal: PrincipalId, spec: AdminJobSpec): OmniResult<JobRecord> {
        requireLocalUi(principal)
        if (!LocalUiPrincipal.allows(AccessScope.jobs_manage)) {
            return OmniResult.err(OmniError.FORBIDDEN(message = "jobs.manage not allowed"))
        }
        val kind = JobKind.fromCatalogName(spec.kind)
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown job kind",
                    details = mapOf("kind" to spec.kind),
                ),
            )
        val command = spec.command
        // expectedVersion optional for create-new-resource.
        val cmdResult = executeCommand(principal, AdminOperationKinds.CREATE_JOB, command) {
            val identity = try {
                JobIdentity(
                    jobId = JobId(spec.jobId),
                    principalId = principal,
                    kind = kind,
                    idempotencyKey = IdempotencyKey.parse(command.idempotencyKey),
                    canonicalSpecDigest = command.canonicalInputDigest.lowercase(),
                )
            } catch (e: IllegalArgumentException) {
                return@executeCommand CommandExecution.Failure(
                    OmniError.INVALID_REQUEST(message = e.message ?: "invalid job identity"),
                )
            }
            when (val created = jobManager.create(identity, spec.parameters)) {
                is OmniResult.Ok -> {
                    val record = created.value.record
                    jobObservers.publishJobRecord(record)
                    CommandExecution.Success(
                        resourceVersion = record.resourceVersion,
                        affectedResourceId = record.jobId.value,
                        resultCanonicalJson = """{"jobId":"${record.jobId.value}"}""",
                        payload = record,
                    )
                }
                is OmniResult.Err -> CommandExecution.Failure(created.error)
            }
        }

        return when {
            cmdResult.isSuccess && cmdResult.affectedResourceId != null -> {
                val jobId = cmdResult.affectedResourceId
                jobManager.query(JobId(jobId))
            }
            cmdResult.error != null -> OmniResult.err(cmdResult.error)
            else -> OmniResult.err(
                OmniError.INTERNAL(message = "createJob completed without jobId"),
            )
        }
    }

    fun cancelJob(
        principal: PrincipalId,
        jobId: String,
        command: AdminCommandRequest,
    ): AdminCommandResult {
        requireLocalUi(principal)
        if (!LocalUiPrincipal.allows(AccessScope.jobs_manage)) {
            return AdminCommandResult.failed(
                command.commandId,
                OmniError.FORBIDDEN(message = "jobs.manage not allowed"),
            )
        }
        // FEAT-ADMIN §2 / CORE-INTERFACE durable Command: non-create mutations
        // must supply expectedVersion (CAS). No silent last-write-wins.
        val expected = command.expectedVersion
            ?: return failAndMaybeRecord(
                principal,
                AdminOperationKinds.CANCEL_JOB,
                command,
                OmniError.INVALID_REQUEST(
                    message = "expectedVersion required for cancelJob (CAS)",
                    details = mapOf("jobId" to jobId),
                ),
            )
        return executeCommand(principal, AdminOperationKinds.CANCEL_JOB, command) {
            val id = try {
                JobId(jobId)
            } catch (e: IllegalArgumentException) {
                return@executeCommand CommandExecution.Failure(
                    OmniError.INVALID_REQUEST(message = e.message ?: "invalid jobId"),
                )
            }
            when (val current = jobManager.query(id)) {
                is OmniResult.Err -> return@executeCommand CommandExecution.Failure(current.error)
                is OmniResult.Ok -> {
                    if (current.value.resourceVersion != expected) {
                        return@executeCommand CommandExecution.Failure(
                            OmniError.STATE_CONFLICT(
                                message = "job resourceVersion CAS mismatch",
                                details = mapOf(
                                    "jobId" to jobId,
                                    "expectedVersion" to expected.toString(),
                                    "actualVersion" to current.value.resourceVersion.toString(),
                                ),
                            ),
                        )
                    }
                }
            }
            when (val cancelled = jobManager.cancel(id)) {
                is OmniResult.Ok -> {
                    jobObservers.publishJobRecord(cancelled.value)
                    CommandExecution.Success(
                        resourceVersion = cancelled.value.resourceVersion,
                        affectedResourceId = jobId,
                        resultCanonicalJson =
                            """{"jobId":"$jobId","state":"${cancelled.value.state}","resourceVersion":${cancelled.value.resourceVersion}}""",
                    )
                }
                is OmniResult.Err -> CommandExecution.Failure(cancelled.error)
            }
        }
    }

    // ------------------------------------------------------------------
    // Command query (reply-loss reconciliation)
    // ------------------------------------------------------------------

    fun queryCommand(principal: PrincipalId, commandId: String): AdminCommandResult {
        requireLocalUi(principal)
        val parsed = try {
            CommandId.parse(commandId)
        } catch (e: IllegalArgumentException) {
            return AdminCommandResult.failed(
                commandId = commandId,
                error = OmniError.INVALID_REQUEST(message = e.message ?: "invalid commandId"),
            )
        }
        val row = commandLedger.queryCommand(parsed)
            ?: return AdminCommandResult.failed(
                commandId = commandId,
                error = OmniError.NOT_FOUND(
                    message = "command not found",
                    details = mapOf("commandId" to commandId),
                ),
            )
        if (row.principalId != principal.value) {
            return AdminCommandResult.failed(
                commandId = commandId,
                error = OmniError.FORBIDDEN(message = "command not owned by principal"),
            )
        }
        return row.toAdminCommandResult()
    }

    // ------------------------------------------------------------------
    // Job observers
    // ------------------------------------------------------------------

    fun observeJobs(
        principal: PrincipalId,
        cursor: String?,
        credit: Int,
        sink: AdminJobEventSink,
    ): OmniResult<String> {
        requireLocalUi(principal)
        if (!LocalUiPrincipal.allows(AccessScope.jobs_read_all) &&
            !LocalUiPrincipal.allows(AccessScope.jobs_read_own)
        ) {
            return OmniResult.err(OmniError.FORBIDDEN(message = "jobs read not allowed"))
        }
        return jobObservers.subscribe(principal, cursor, credit, sink)
    }

    /**
     * Application-level ACK — not a durable domain mutation; no CommandResult.
     * Errors surface via sink rejection on subsequent deliveries when needed.
     */
    fun ackJobEvents(
        principal: PrincipalId,
        subscriptionId: String,
        streamEpoch: Long,
        eventToExclusive: Long,
    ): OmniResult<Unit> {
        requireLocalUi(principal)
        return jobObservers.ack(subscriptionId, streamEpoch, eventToExclusive)
    }

    fun closeSubscription(principal: PrincipalId, subscriptionId: String) {
        requireLocalUi(principal)
        jobObservers.close(subscriptionId)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun requireLocalUi(principal: PrincipalId) {
        require(principal.value == LocalUiPrincipal.ID.value) {
            "AdminApiService accepts LOCAL_UI principal only (got ${principal.value})"
        }
    }

    private sealed class CommandExecution {
        data class Success(
            val resourceVersion: Long,
            val affectedResourceId: String? = null,
            val resultCanonicalJson: String? = null,
            val payload: JobRecord? = null,
        ) : CommandExecution()

        data class Failure(val error: OmniError) : CommandExecution()
    }

    /**
     * Claim → execute once → durable [CommandLedger.recordResult] before return.
     * Existing terminal claim is returned without re-execution (ADR-004/005).
     */
    private fun executeCommand(
        principal: PrincipalId,
        operationKind: String,
        command: AdminCommandRequest,
        body: () -> CommandExecution,
    ): AdminCommandResult {
        val commandId = try {
            CommandId.parse(command.commandId)
        } catch (e: IllegalArgumentException) {
            return AdminCommandResult.failed(
                command.commandId,
                OmniError.INVALID_REQUEST(message = e.message ?: "invalid commandId"),
            )
        }
        val key = try {
            IdempotencyKey.parse(command.idempotencyKey)
        } catch (e: IllegalArgumentException) {
            return AdminCommandResult.failed(
                command.commandId,
                OmniError.INVALID_REQUEST(message = e.message ?: "invalid idempotencyKey"),
            )
        }
        val digest = try {
            Sha256Digest.parse(command.canonicalInputDigest.lowercase())
        } catch (e: IllegalArgumentException) {
            return AdminCommandResult.failed(
                command.commandId,
                OmniError.INVALID_REQUEST(message = e.message ?: "invalid digest"),
            )
        }

        val claim = commandLedger.claim(
            principal = principal,
            operationKind = operationKind,
            idempotencyKey = key,
            canonicalHash = digest,
            commandId = commandId,
            expectedVersion = command.expectedVersion,
        )

        when (claim) {
            is ClaimOutcome.Conflict -> {
                return AdminCommandResult.failed(command.commandId, claim.error)
            }
            is ClaimOutcome.Existing -> {
                val row = claim.value
                if (row.state in CommandLedgerStates.TERMINAL) {
                    return row.toAdminCommandResult()
                }
                // Non-terminal existing: continue execution (recovery after crash mid-command).
            }
            is ClaimOutcome.New -> {
                commandLedger.updateState(commandId, "CLAIMED")
                commandLedger.updateState(commandId, "RUNNING")
            }
        }

        return when (val outcome = body()) {
            is CommandExecution.Success -> {
                val recorded = commandLedger.recordResult(
                    commandId = commandId,
                    state = "SUCCEEDED",
                    resultJson = outcome.resultCanonicalJson,
                    affectedResourceId = outcome.affectedResourceId,
                )
                when (recorded) {
                    is OmniResult.Ok -> {
                        // resourceVersion on CommandResult is the affected resource CAS
                        // version (settings / job), not the internal command-ledger bump.
                        AdminCommandResult.succeeded(
                            commandId = command.commandId,
                            resourceVersion = outcome.resourceVersion,
                            affectedResourceId = outcome.affectedResourceId,
                            resultCanonicalJson = outcome.resultCanonicalJson,
                        )
                    }
                    is OmniResult.Err -> AdminCommandResult.failed(command.commandId, recorded.error)
                }
            }
            is CommandExecution.Failure -> {
                commandLedger.recordResult(
                    commandId = commandId,
                    state = "FAILED",
                    errorCode = outcome.error.code.code,
                    resultJson = null,
                )
                AdminCommandResult.failed(
                    commandId = command.commandId,
                    error = outcome.error,
                    resourceVersion = 0L,
                    affectedResourceId = null,
                )
            }
        }
    }

    private fun failAndMaybeRecord(
        principal: PrincipalId,
        operationKind: String,
        command: AdminCommandRequest,
        error: OmniError,
    ): AdminCommandResult {
        // Validation failures before claim still return CommandResult (no void).
        // If claim fields are valid we durable-record FAILED; else ephemeral FAILED.
        return try {
            executeCommand(principal, operationKind, command) {
                CommandExecution.Failure(error)
            }
        } catch (_: Exception) {
            AdminCommandResult.failed(command.commandId, error)
        }
    }

    private fun IdempotentCommandClaimRow.toAdminCommandResult(): AdminCommandResult {
        val err = errorCode?.let { code ->
            OmniError.of(
                code = com.omnillm.core.errors.generated.OmniErrorCode.fromCode(code)
                    ?: com.omnillm.core.errors.generated.OmniErrorCode.INTERNAL,
                message = "command terminal with error",
            )
        }
        // Prefer affected-resource version recorded in result JSON (settings CAS / job rv)
        // so reply-loss query matches the original CommandResult.resourceVersion.
        val projectedVersion = extractResourceVersion(resultJson) ?: resourceVersion
        return AdminCommandResult(
            commandId = commandId,
            state = state,
            resourceVersion = projectedVersion,
            affectedResourceId = affectedResourceId,
            resultSchemaId = null,
            resultCanonicalJson = resultJson,
            error = err,
        )
    }

    private fun extractResourceVersion(resultJson: String?): Long? {
        if (resultJson.isNullOrBlank()) return null
        val match = RESOURCE_VERSION_IN_JSON.find(resultJson) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    companion object {
        private val RESOURCE_VERSION_IN_JSON =
            Regex("\"resourceVersion\"\\s*:\\s*(\\d+)")
    }
}
