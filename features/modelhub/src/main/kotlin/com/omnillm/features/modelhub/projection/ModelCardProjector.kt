package com.omnillm.features.modelhub.projection

import com.omnillm.features.modelhub.api.AcquisitionChannel
import com.omnillm.features.modelhub.api.AcquisitionJobView
import com.omnillm.features.modelhub.api.CatalogModelEntry
import com.omnillm.features.modelhub.api.CompatibilityStatus
import com.omnillm.features.modelhub.api.LicenseStatus
import com.omnillm.features.modelhub.api.ModelCard
import com.omnillm.features.modelhub.api.ModelHubAction
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobRecord
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.ports.LiveReferences

/**
 * Pure projections from MODEL_INSTALLATION + LOADED_MODEL + JOB FSMs
 * (CORE-MODEL §10, UX-STATE-CATALOG). No domain mutation.
 */
object ModelCardProjector {

    fun fromCatalog(entry: CatalogModelEntry): ModelCard =
        ModelCard(
            modelRevisionId = entry.modelRevisionId,
            artifactPackageId = entry.artifactPackageId,
            installationId = null,
            displayName = entry.displayName,
            alias = null,
            installationState = null,
            loadedModelState = null,
            acquisitionChannel = entry.acquisitionChannel,
            byteLength = entry.byteLength,
            quantizationDescriptorJson = entry.quantizationDescriptorJson,
            licenseStatus = if (entry.licenseDigest != null) {
                LicenseStatus.ACCEPTANCE_REQUIRED
            } else {
                LicenseStatus.UNKNOWN
            },
            licenseDigest = entry.licenseDigest,
            authenticityOk = when (entry.acquisitionChannel) {
                AcquisitionChannel.SIGNED_CATALOG -> true
                AcquisitionChannel.PINNED_DOWNLOAD -> null
                AcquisitionChannel.LOCAL_IMPORT -> false
                else -> null
            },
            compatibilityStatus = CompatibilityStatus.NOT_CHECKED,
            placementClass = null,
            performanceRecorded = false,
            pinned = false,
            liveReferenceCount = 0,
            riskFlags = riskFlagsForChannel(entry.acquisitionChannel),
            allowedActions = listOf(
                when (entry.acquisitionChannel) {
                    AcquisitionChannel.LOCAL_IMPORT -> ModelHubAction.IMPORT
                    else -> ModelHubAction.DOWNLOAD
                },
                ModelHubAction.VIEW_LICENSE,
                ModelHubAction.VIEW_EVIDENCE,
            ),
        )

    fun fromInstallation(
        snap: InstallationSnapshot,
        displayName: String,
        alias: String? = null,
        acquisitionChannel: String = AcquisitionChannel.UNKNOWN,
        byteLength: Long? = null,
        quantizationDescriptorJson: String? = null,
        licenseDigest: String? = null,
        licenseAccepted: Boolean = false,
        loaded: LoadedModelSnapshot? = null,
        refs: LiveReferences = LiveReferences(),
        activeJob: JobRecord? = null,
    ): ModelCard {
        val eval = snap.evaluation
        return ModelCard(
            modelRevisionId = snap.modelRevisionId.hex,
            artifactPackageId = snap.artifactPackageId.hex,
            installationId = snap.installationId.value,
            displayName = displayName,
            alias = alias,
            installationState = snap.state,
            loadedModelState = loaded?.state,
            acquisitionChannel = acquisitionChannel,
            byteLength = byteLength,
            quantizationDescriptorJson = quantizationDescriptorJson,
            licenseStatus = licenseStatusOf(eval, licenseDigest, licenseAccepted),
            licenseDigest = licenseDigest,
            authenticityOk = eval?.authenticityOk,
            compatibilityStatus = compatibilityOf(eval, snap.state),
            placementClass = eval?.placementClass,
            performanceRecorded = eval?.performanceRecorded == true,
            pinned = snap.pinned,
            liveReferenceCount = refs.total,
            riskFlags = riskFlagsForChannel(acquisitionChannel) + riskFlagsForState(snap, eval),
            rejectReason = snap.rejectReason,
            activeJobId = activeJob?.jobId?.value,
            activeJobKind = activeJob?.kind?.name,
            activeJobState = activeJob?.state,
            jobProgress = activeJob?.progress,
            allowedActions = allowedActionsForInstallation(snap, loaded, activeJob, refs),
        )
    }

    fun jobView(
        record: JobRecord,
        installationId: String? = null,
        modelRevisionId: String? = null,
    ): AcquisitionJobView =
        AcquisitionJobView(
            jobId = record.jobId.value,
            kind = record.kind.name,
            state = record.state,
            resourceVersion = record.resourceVersion,
            modelRevisionId = modelRevisionId,
            installationId = installationId,
            progress = record.progress,
            cancelRequested = record.cancelRequested,
            error = record.error,
            allowedActions = allowedActionsForJob(record),
        )

    fun allowedActionsForJob(record: JobRecord): List<String> {
        if (record.isTerminal) {
            return if (record.state == "FAILED") listOf(ModelHubAction.RETRY) else emptyList()
        }
        return listOf(ModelHubAction.CANCEL)
    }

    fun allowedActionsForInstallation(
        snap: InstallationSnapshot,
        loaded: LoadedModelSnapshot?,
        activeJob: JobRecord?,
        refs: LiveReferences,
    ): List<String> {
        val actions = mutableListOf<String>()
        actions += ModelHubAction.VIEW_LICENSE
        actions += ModelHubAction.VIEW_EVIDENCE

        when (snap.state) {
            "DISCOVERED", "ACQUIRING", "QUARANTINED", "VERIFYING", "COMPATIBILITY_CHECK" -> {
                if (activeJob != null && !activeJob.isTerminal) {
                    actions += ModelHubAction.CANCEL
                }
            }
            "READY" -> {
                actions += ModelHubAction.DELETE
                actions += ModelHubAction.BENCHMARK
                actions += ModelHubAction.CHANGE_ALIAS
                // M5: license terms must be accepted before load/generate.
                if (snap.evaluation?.licenseOk != true) {
                    actions += ModelHubAction.ACCEPT_LICENSE
                }
                if (snap.pinned) actions += ModelHubAction.UNPIN else actions += ModelHubAction.PIN
                when (loaded?.state) {
                    null, "UNLOADED" -> actions += ModelHubAction.LOAD
                    "LOADED" -> actions += ModelHubAction.UNLOAD
                    "DRAINING", "UNLOADING" -> Unit
                    else -> Unit // mid-load or terminal: no load/unload action
                }
            }
            "DRAINING" -> {
                // Wait for refs — no force delete from UI unless forceAfterDrain job
                if (refs.isZero) {
                    // delete job may complete
                }
            }
            "REVOKED", "CORRUPT", "REJECTED" -> {
                actions += ModelHubAction.DELETE
                if (activeJob?.state == "FAILED") actions += ModelHubAction.RETRY
            }
            "DELETING" -> Unit
            "DELETED" -> Unit
            else -> Unit // fail closed: no invented actions for unknown states
        }
        return actions.distinct()
    }

    private fun licenseStatusOf(
        eval: EvaluationDimensions?,
        licenseDigest: String? = null,
        accepted: Boolean = false,
    ): String = when {
        eval?.licenseOk == true -> LicenseStatus.ACCEPTED
        licenseDigest != null && accepted -> LicenseStatus.ACCEPTED
        licenseDigest != null -> LicenseStatus.ACCEPTANCE_REQUIRED
        eval == null -> LicenseStatus.UNKNOWN
        else -> LicenseStatus.ACCEPTANCE_REQUIRED
    }

    private fun compatibilityOf(eval: EvaluationDimensions?, installationState: String): String =
        when {
            installationState == "REJECTED" && eval?.compatibilityOk == false ->
                CompatibilityStatus.UNSUPPORTED
            eval == null -> CompatibilityStatus.NOT_CHECKED
            eval.compatibilityOk && eval.performanceRecorded -> CompatibilityStatus.RUNTIME_OK
            eval.compatibilityOk -> CompatibilityStatus.STATIC_OK
            else -> CompatibilityStatus.NOT_CHECKED
        }

    private fun riskFlagsForChannel(channel: String): List<String> =
        when (channel) {
            AcquisitionChannel.LOCAL_IMPORT -> listOf("SOURCE_UNVERIFIED")
            AcquisitionChannel.UNKNOWN -> listOf("SOURCE_UNKNOWN")
            else -> emptyList()
        }

    private fun riskFlagsForState(
        snap: InstallationSnapshot,
        eval: EvaluationDimensions?,
    ): List<String> {
        val flags = mutableListOf<String>()
        when (snap.state) {
            "REVOKED" -> flags += "REVOKED"
            "CORRUPT" -> flags += "INTEGRITY_FAILED"
            "REJECTED" -> flags += "REJECTED"
            "DRAINING" -> flags += "DRAINING"
        }
        if (eval != null && !eval.authenticityOk) flags += "AUTHENTICITY_FAILED"
        if (eval != null && !eval.licenseOk) flags += "LICENSE_REQUIRED"
        return flags
    }

    fun isAcquisitionJob(kind: JobKind): Boolean =
        kind == JobKind.DOWNLOAD || kind == JobKind.IMPORT || kind == JobKind.DELETE
}
