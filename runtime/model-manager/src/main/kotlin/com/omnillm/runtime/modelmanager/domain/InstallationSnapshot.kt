package com.omnillm.runtime.modelmanager.domain

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.ModelInstallationAggregate
import com.omnillm.data.modelstore.QuarantineKey

/**
 * Durable + in-memory projection of MODEL_INSTALLATION (CORE-MODEL §1.1).
 *
 * Separate from [LoadedModelSnapshot]: READY means eligible for load planning,
 * not that native weights are resident.
 */
data class InstallationSnapshot(
    val aggregate: ModelInstallationAggregate,
    val modelRevisionId: ModelRevisionId,
    val artifactPackageId: ArtifactPackageId,
    /** Content-addressed ready root after atomic promote; null while quarantined. */
    val storageRootKey: String? = null,
    val quarantineKey: QuarantineKey? = null,
    val evaluation: EvaluationDimensions? = null,
    val templateEpoch: Long = 0L,
    val tokenizerEpoch: Long = 0L,
    val pinned: Boolean = false,
    val rejectReason: String? = null,
) {
    val installationId: InstallationId get() = aggregate.installationId
    val state: String get() = aggregate.state

    fun isReady(): Boolean = state == "READY"
    fun isTerminal(): Boolean = aggregate.isTerminal()
    fun allowsNewLoad(): Boolean = state == "READY"

    companion object {
        fun discovered(
            installationId: InstallationId,
            modelRevisionId: ModelRevisionId,
            artifactPackageId: ArtifactPackageId,
        ): InstallationSnapshot =
            InstallationSnapshot(
                aggregate = ModelInstallationAggregate.initial(installationId),
                modelRevisionId = modelRevisionId,
                artifactPackageId = artifactPackageId,
            )
    }
}
