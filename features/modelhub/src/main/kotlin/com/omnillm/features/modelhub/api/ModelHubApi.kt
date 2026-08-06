package com.omnillm.features.modelhub.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId

/**
 * Public ModelHub API for LOCAL_UI / Admin composition (FEAT-MODELHUB).
 *
 * Composes platform capabilities (MODEL_ACQUISITION, MODEL_IDENTITY,
 * SAFE_INSTALLATION, MODEL_LIFECYCLE, JOB_LIFECYCLE, …) without redefining
 * Request / Session / Trust semantics (FEATURE-SYSTEM §1).
 *
 * All mutations go through Job Manager + Model Manager ports; this feature
 * never writes the domain DB itself (ADR-010 / INV-001).
 */
interface ModelHubApi {

    /** Full hub snapshot (Suggested / Installed / Downloads & Imports). */
    suspend fun getSnapshot(principal: PrincipalId): OmniResult<ModelHubSnapshot>

    /** Single model card by installation or revision (catalog-only when not installed). */
    suspend fun getModelCard(
        principal: PrincipalId,
        installationId: String? = null,
        modelRevisionId: String? = null,
    ): OmniResult<ModelCard>

    /** List catalog suggestions not yet installed. */
    suspend fun listSuggested(principal: PrincipalId): OmniResult<List<ModelCard>>

    /** List installations (any MODEL_INSTALLATION state except DELETED). */
    suspend fun listInstalled(principal: PrincipalId): OmniResult<List<ModelCard>>

    /** List active DOWNLOAD / IMPORT / DELETE jobs for the hub section. */
    suspend fun listAcquisitionJobs(principal: PrincipalId): OmniResult<List<AcquisitionJobView>>

    /**
     * Discover installation + claim DOWNLOAD job (Plan has no mutation —
     * job create + installation discover are control-plane commits).
     */
    suspend fun startDownload(
        principal: PrincipalId,
        spec: StartDownloadSpec,
    ): OmniResult<ModelHubJobHandle>

    /** Discover installation + claim IMPORT job for SAF/asset materialize. */
    suspend fun startImport(
        principal: PrincipalId,
        spec: StartImportSpec,
    ): OmniResult<ModelHubJobHandle>

    /** Request installation delete via DELETE job + DRAINING path. */
    suspend fun startDelete(
        principal: PrincipalId,
        spec: StartDeleteSpec,
    ): OmniResult<ModelHubJobHandle>

    /** Cancel acquisition job; fail closed if job is terminal other than CANCELLED. */
    suspend fun cancelAcquisition(
        principal: PrincipalId,
        spec: CancelAcquisitionSpec,
    ): OmniResult<ModelHubJobHandle>

    /** Pin / unpin (eviction fence only). */
    suspend fun setPinned(
        principal: PrincipalId,
        spec: SetPinSpec,
    ): OmniResult<ModelCard>

    // ------------------------------------------------------------------
    // Control-plane pipeline steps (workers / runtime host call these)
    // ------------------------------------------------------------------

    /**
     * QUEUED → RUNNING attempt for an acquisition job and BEGIN_ACQUIRE
     * on the linked installation when declared artifacts are provided.
     */
    suspend fun beginAcquisitionAttempt(
        jobId: String,
        declaredRoles: List<AcquisitionDeclaredFile> = emptyList(),
        deadlineMonotonic: Long = Long.MAX_VALUE / 4,
    ): OmniResult<ModelHubJobHandle>

    /** Update progress counters (network / materialized / verified). */
    suspend fun updateAcquisitionProgress(
        update: AcquisitionProgressUpdate,
    ): OmniResult<ModelHubJobHandle>

    /**
     * Mark quarantine materialization complete → VERIFYING → READY pipeline.
     * Compatibility success does **not** elevate authenticity (ADR-009).
     */
    suspend fun completeAcquisitionMaterialize(
        jobId: String,
        files: List<AcquisitionMaterializedFile>,
    ): OmniResult<ModelCard>

    /** Fail running acquisition (job FAILURE + installation ACQUIRE_FAILED when applicable). */
    suspend fun failAcquisition(
        jobId: String,
        reason: String,
    ): OmniResult<ModelHubJobHandle>

    /**
     * Advance DELETE job: DRAINING → DRAINED_DELETE → DELETING → DELETE_COMMITTED
     * when live references are zero.
     */
    suspend fun completeDeleteWhenQuiescent(jobId: String): OmniResult<ModelHubJobHandle>
}

/** Declared artifact role for BEGIN_ACQUIRE (ADR-008 package entries). */
data class AcquisitionDeclaredFile(
    val role: String,
    val blobId: String,
    val byteLength: Long,
    val shardIndex: Int = 0,
) {
    init {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(blobId.matches(HEX64)) { "blobId must be 64-char hex" }
        require(byteLength >= 0L) { "byteLength must be non-negative" }
        require(shardIndex >= 0) { "shardIndex must be non-negative" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/** Materialized quarantine file after stream/PFD copy. */
data class AcquisitionMaterializedFile(
    val role: String,
    val expectedBlobId: String,
    val expectedByteLength: Long,
    val materializeHandle: String,
    val shardIndex: Int = 0,
) {
    init {
        require(role.isNotEmpty()) { "role must be non-empty" }
        require(expectedBlobId.matches(HEX64)) { "expectedBlobId must be 64-char hex" }
        require(expectedByteLength >= 0L) { "expectedByteLength must be non-negative" }
        require(materializeHandle.isNotEmpty()) { "materializeHandle must be non-empty" }
    }

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}
