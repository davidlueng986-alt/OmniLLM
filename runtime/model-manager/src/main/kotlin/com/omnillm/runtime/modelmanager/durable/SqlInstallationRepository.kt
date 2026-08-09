package com.omnillm.runtime.modelmanager.durable

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.ModelInstallationAggregate
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.data.persistence.InstallationLedgerPorts
import com.omnillm.data.persistence.InstallationRecordRow
import com.omnillm.core.ports.ledger.SingleWriterPolicy
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import java.time.Instant

/**
 * SQLDelight-backed [InstallationRepository] (ADR-010 / CORE-MODEL).
 *
 * Maps [InstallationSnapshot] ↔ [InstallationRecordRow] for catalog projections
 * and install state machine durability. Open only via control-plane ports.
 *
 * LoadedModel is **not** persisted here (separate aggregate; native handles non-durable).
 */
class SqlInstallationRepository(
    private val ports: InstallationLedgerPorts,
    private val clock: () -> String = { Instant.now().toString() },
    writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : InstallationRepository {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
        require(ports.writerRole == writerRole) {
            "InstallationLedgerPorts writerRole mismatch"
        }
    }

    override suspend fun get(installationId: InstallationId): InstallationSnapshot? =
        ports.installations.findByInstallationId(installationId.value)?.toSnapshot()

    override suspend fun findByRevision(modelRevisionId: ModelRevisionId): List<InstallationSnapshot> =
        ports.installations.listByRevisionId(modelRevisionId.hex).map { it.toSnapshot() }

    override suspend fun listAll(): List<InstallationSnapshot> =
        ports.installations.listCatalog().map { it.toSnapshot() }

    override suspend fun save(snapshot: InstallationSnapshot): OmniResult<Unit> {
        return try {
            val existing = ports.installations.findByInstallationId(snapshot.installationId.value)
            val now = clock()
            val createdAt = existing?.createdAt ?: now
            val resourceVersion = (existing?.resourceVersion ?: -1L) + 1L
            ports.tx.inTransaction {
                ports.installations.upsert(snapshot.toRow(createdAt, now, resourceVersion))
            }
            OmniResult.ok(Unit)
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(
                    message = "installation save failed: ${e.javaClass.simpleName}",
                    details = mapOf("installationId" to snapshot.installationId.value),
                ),
            )
        }
    }

    override suspend fun delete(installationId: InstallationId): OmniResult<Unit> {
        return try {
            ports.tx.inTransaction {
                ports.installations.delete(installationId.value)
            }
            OmniResult.ok(Unit)
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(
                    message = "installation delete failed: ${e.javaClass.simpleName}",
                    details = mapOf("installationId" to installationId.value),
                ),
            )
        }
    }
}

internal fun InstallationRecordRow.toSnapshot(): InstallationSnapshot {
    val evaluation =
        if (authenticityOk != null && licenseOk != null && placementClass != null) {
            EvaluationDimensions(
                authenticityOk = authenticityOk!!,
                licenseOk = licenseOk!!,
                compatibilityOk = compatibilityOk ?: false,
                performanceRecorded = performanceRecorded ?: false,
                placementClass = placementClass!!,
                trustEpoch = trustEpoch,
            )
        } else {
            null
        }
    val quarantine =
        if (quarantineJobId != null && quarantineAttemptId != null) {
            QuarantineKey(jobId = quarantineJobId!!, attemptId = quarantineAttemptId!!)
        } else {
            null
        }
    val storageRoot =
        if (storageRootKey.startsWith("pending/")) null else storageRootKey
    return InstallationSnapshot(
        aggregate = ModelInstallationAggregate(
            installationId = InstallationId(installationId),
            state = state,
        ),
        modelRevisionId = ModelRevisionId.parse(revisionId),
        artifactPackageId = ArtifactPackageId.parse(artifactPackageId),
        storageRootKey = storageRoot,
        quarantineKey = quarantine,
        evaluation = evaluation,
        templateEpoch = templateEpoch,
        tokenizerEpoch = tokenizerEpoch,
        pinned = pinned,
        rejectReason = rejectReason,
    )
}

internal fun InstallationSnapshot.toRow(
    createdAt: String,
    updatedAt: String,
    resourceVersion: Long,
): InstallationRecordRow {
    val storageKey = storageRootKey
        ?: InstallationRecordRow.pendingStorageRootKey(installationId.value)
    return InstallationRecordRow(
        installationId = installationId.value,
        revisionId = modelRevisionId.hex,
        artifactPackageId = artifactPackageId.hex,
        state = state,
        storageRootKey = storageKey,
        trustEpoch = evaluation?.trustEpoch ?: 0L,
        templateEpoch = templateEpoch,
        tokenizerEpoch = tokenizerEpoch,
        quarantineJobId = quarantineKey?.jobId,
        quarantineAttemptId = quarantineKey?.attemptId,
        authenticityOk = evaluation?.authenticityOk,
        licenseOk = evaluation?.licenseOk,
        compatibilityOk = evaluation?.compatibilityOk,
        performanceRecorded = evaluation?.performanceRecorded,
        placementClass = evaluation?.placementClass,
        pinned = pinned,
        rejectReason = rejectReason,
        resourceVersion = resourceVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
