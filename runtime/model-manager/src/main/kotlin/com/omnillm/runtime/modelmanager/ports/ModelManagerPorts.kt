package com.omnillm.runtime.modelmanager.ports

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.core.state.domain.RevisionLeaseId
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.domain.RevisionLeaseSnapshot

/**
 * Persistence ports for Model Manager. Implementations live under
 * `:data:persistence` and are written **only** by the runtime control plane
 * (ADR-010 / INV-001).
 */

interface InstallationRepository {
    suspend fun get(installationId: InstallationId): InstallationSnapshot?
    suspend fun findByRevision(modelRevisionId: ModelRevisionId): List<InstallationSnapshot>
    /** Full catalog snapshot for ModelHub projections (FEAT-MODELHUB). */
    suspend fun listAll(): List<InstallationSnapshot>
    suspend fun save(snapshot: InstallationSnapshot): OmniResult<Unit>
    suspend fun delete(installationId: InstallationId): OmniResult<Unit>
}

interface LoadedModelRepository {
    suspend fun get(loadedModelId: LoadedModelId): LoadedModelSnapshot?
    suspend fun findByInstallation(installationId: InstallationId): List<LoadedModelSnapshot>
    suspend fun save(snapshot: LoadedModelSnapshot): OmniResult<Unit>
    suspend fun delete(loadedModelId: LoadedModelId): OmniResult<Unit>
}

interface RevisionLeaseRepository {
    suspend fun get(leaseId: RevisionLeaseId): RevisionLeaseSnapshot?
    suspend fun findActiveByRevision(modelRevisionId: ModelRevisionId): List<RevisionLeaseSnapshot>
    suspend fun save(snapshot: RevisionLeaseSnapshot): OmniResult<Unit>
    suspend fun delete(leaseId: RevisionLeaseId): OmniResult<Unit>
}

/**
 * Live reference snapshot for drain guards (request / session / loaded model / job).
 */
data class LiveReferences(
    val requestCount: Int = 0,
    val sessionCount: Int = 0,
    val loadedModelCount: Int = 0,
    val jobCount: Int = 0,
    val leaseCount: Int = 0,
) {
    init {
        require(requestCount >= 0 && sessionCount >= 0 && loadedModelCount >= 0) {
            "reference counts must be non-negative"
        }
        require(jobCount >= 0 && leaseCount >= 0) { "reference counts must be non-negative" }
    }

    val total: Int get() = requestCount + sessionCount + loadedModelCount + jobCount + leaseCount
    val isZero: Boolean get() = total == 0
}

interface ReferenceSnapshotPort {
    suspend fun installationReferences(installationId: InstallationId): LiveReferences
    suspend fun loadedModelReferences(loadedModelId: LoadedModelId): LiveReferences
    suspend fun leaseReferences(leaseId: RevisionLeaseId): LiveReferences
}

/**
 * Effective trust evaluation without compatibility promotion (ADR-009 / INV-008).
 * Installation assertions must belong to the same revision (CORE-MODEL §5).
 */
interface TrustEvaluationPort {
    suspend fun evaluate(
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        currentTrustEpoch: Long,
    ): OmniResult<EvaluationDimensions>
}

/**
 * Privileged load re-verify inputs (CORE-MODEL §6 / INV-010 / SEC-PLACEMENT §4).
 * Actual FD content identity — never DB READY flag alone.
 */
data class PrivilegedReverifyRequest(
    val installationId: InstallationId,
    val modelRevisionId: ModelRevisionId,
    val storageRootKey: String,
    val templateEpoch: Long,
    val tokenizerEpoch: Long,
    val engineBuildId: String,
    val revocationEpoch: Long,
    val expectedContentDigests: Map<String, Sha256Digest> = emptyMap(),
) {
    init {
        require(storageRootKey.isNotEmpty()) { "storageRootKey must be non-empty" }
        require(templateEpoch >= 0L) { "templateEpoch must be non-negative" }
        require(tokenizerEpoch >= 0L) { "tokenizerEpoch must be non-negative" }
        require(engineBuildId.isNotEmpty()) { "engineBuildId must be non-empty" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
    }
}

data class PrivilegedLoadTicket(
    val ticketId: String,
    val installationId: InstallationId,
    val modelRevisionId: ModelRevisionId,
    val placementClass: String,
    val contentIdentityOk: Boolean,
    val signatureChainOk: Boolean,
    val revocationOk: Boolean,
    val installationStateOk: Boolean,
    val epochsOk: Boolean,
    val issuedMonotonic: Long,
    val expiryMonotonic: Long,
) {
    init {
        require(ticketId.isNotEmpty()) { "ticketId must be non-empty" }
        require(placementClass.isNotEmpty()) { "placementClass must be non-empty" }
        require(issuedMonotonic >= 0L && expiryMonotonic >= issuedMonotonic) {
            "ticket monotonic times invalid"
        }
    }

    val allOk: Boolean
        get() = contentIdentityOk && signatureChainOk && revocationOk &&
            installationStateOk && epochsOk
}

interface PrivilegedLoadReverifyPort {
    /**
     * Open actual read-only FDs, re-hash content identity, check manifest
     * signature/root chain, revocation, template/tokenizer epoch, installation state.
     */
    suspend fun reverify(request: PrivilegedReverifyRequest): OmniResult<PrivilegedLoadTicket>
}
