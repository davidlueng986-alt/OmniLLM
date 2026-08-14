package com.omnillm.ui.admin

import ai.omnillm.api.CommandResult
import ai.omnillm.api.IJobObserver
import ai.omnillm.api.IOmniAdmin
import ai.omnillm.api.OmniAdminSnapshot
import ai.omnillm.api.OmniCommandRequest
import ai.omnillm.api.OmniConsentGrant
import ai.omnillm.api.OmniContentReportReceipt
import ai.omnillm.api.OmniJobInfo
import ai.omnillm.api.OmniJobSpec
import ai.omnillm.api.OmniSettingsPatch
import android.os.ParcelFileDescriptor
import java.util.concurrent.CopyOnWriteArrayList

/**
 * JVM fake of the non-exported [IOmniAdmin] binder (same shape as
 * `OmniAdminFacade` on the runtime process) for unit-testing the
 * app-ui binder wiring without Android framework services.
 */
class FakeOmniAdmin(
    snapshotValue: OmniAdminSnapshot,
) : IOmniAdmin.Stub() {

    @Volatile
    var snapshotValue: OmniAdminSnapshot = snapshotValue
        private set

    var startJobCalls: MutableList<OmniJobSpec> = CopyOnWriteArrayList()

    fun setSnapshot(snapshot: OmniAdminSnapshot) {
        snapshotValue = snapshot
    }

    override fun getSnapshot(): OmniAdminSnapshot = snapshotValue

    override fun getSettings(): ai.omnillm.api.OmniSettingsSnapshot =
        snapshotValue.settings ?: ai.omnillm.api.OmniSettingsSnapshot()

    override fun applySettings(patch: OmniSettingsPatch?): CommandResult {
        val result = CommandResult()
        result.commandId = patch?.command?.commandId.orEmpty()
        result.state = "SUCCEEDED"
        result.resourceVersion = 1L
        return result
    }

    override fun startJob(spec: OmniJobSpec?): OmniJobInfo {
        startJobCalls += spec ?: OmniJobSpec()
        val info = OmniJobInfo()
        info.jobId = spec?.jobId.orEmpty()
        info.state = "QUEUED"
        info.resourceVersion = 1L
        return info
    }

    override fun getJob(jobId: String?): OmniJobInfo {
        val info = OmniJobInfo()
        info.jobId = jobId.orEmpty()
        info.state = "QUEUED"
        info.resourceVersion = 1L
        return info
    }

    override fun cancelJob(jobId: String?, command: OmniCommandRequest?): CommandResult {
        val result = CommandResult()
        result.commandId = command?.commandId.orEmpty()
        result.state = "SUCCEEDED"
        result.resourceVersion = 2L
        return result
    }

    override fun queryCommand(commandId: String?): CommandResult {
        val result = CommandResult()
        result.commandId = commandId.orEmpty()
        result.state = "SUCCEEDED"
        return result
    }

    override fun observeJobs(cursor: String?, credit: Int, observer: IJobObserver?): String = "sub-1"

    override fun ackJobEvents(subscriptionId: String?, streamEpoch: Long, eventToExclusive: Long) = Unit

    override fun closeSubscription(subscriptionId: String?) = Unit

    override fun reviewContentReport(
        reportId: String?,
        canonicalPayloadDigest: String?,
        warningPolicyVersion: String?,
        localUserProfileId: String?,
        command: OmniCommandRequest?,
    ): OmniConsentGrant {
        val grant = OmniConsentGrant()
        grant.grantId = "grant-1"
        grant.reportId = reportId.orEmpty()
        grant.canonicalPayloadDigest = canonicalPayloadDigest.orEmpty()
        grant.warningPolicyVersion = warningPolicyVersion.orEmpty()
        grant.localUserProfileId = localUserProfileId.orEmpty()
        grant.issuedAtEpochMillis = 1L
        grant.expiresAtEpochMillis = Long.MAX_VALUE
        return grant
    }

    override fun submitContentReport(
        reportId: String?,
        consentGrantId: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val result = CommandResult()
        result.commandId = command?.commandId.orEmpty()
        result.state = "SUCCEEDED"
        result.affectedResourceId = reportId.orEmpty()
        result.resourceVersion = 4L
        return result
    }

    override fun getContentReportReceipt(reportId: String?): OmniContentReportReceipt {
        val receipt = OmniContentReportReceipt()
        receipt.reportId = reportId.orEmpty()
        return receipt
    }

    override fun executePlaygroundChat(
        modelRevisionId: String?,
        userMessage: String?,
        requestId: String?,
        idempotencyKey: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val result = CommandResult()
        result.commandId = command?.commandId.orEmpty()
        result.state = "SUCCEEDED"
        return result
    }

    override fun queryPlaygroundRequest(requestId: String?): CommandResult {
        val result = CommandResult()
        result.state = "SUCCEEDED"
        return result
    }

    override fun cancelPlaygroundRequest(
        requestId: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val result = CommandResult()
        result.commandId = command?.commandId.orEmpty()
        result.state = "SUCCEEDED"
        return result
    }

    override fun executeServerSmoke(
        modelRevisionId: String?,
        requestId: String?,
        idempotencyKey: String?,
        command: OmniCommandRequest?,
    ): CommandResult {
        val result = CommandResult()
        result.commandId = command?.commandId.orEmpty()
        result.state = "SUCCEEDED"
        return result
    }

    override fun getInferenceCapabilityState(
        capabilityId: String?,
        modelRevisionId: String?,
    ): String = "SUPPORTED"

    override fun importLocalFile(
        contentFd: ParcelFileDescriptor?,
        displayName: String?,
        expectedSha256: String?,
        expectedBytes: Long,
        modelRevisionId: String?,
        artifactPackageId: String?,
        installationId: String?,
        jobId: String?,
        command: OmniCommandRequest?,
    ): OmniJobInfo {
        val info = OmniJobInfo()
        info.jobId = jobId.orEmpty()
        info.state = "QUEUED"
        info.resourceVersion = 1L
        return info
    }
}
