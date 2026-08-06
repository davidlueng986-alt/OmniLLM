package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.data.modelstore.DeclaredArtifactFile
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.QuarantineFileRecord
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.domain.EvaluationDimensions
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.guards.InstallationGuardAtoms
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort
import com.omnillm.runtime.modelmanager.ports.TrustEvaluationPort

/**
 * Coordinates MODEL_INSTALLATION lifecycle (CORE-MODEL §1.1, §4).
 *
 * Pipeline: DISCOVERED → ACQUIRING → QUARANTINED → VERIFYING →
 * COMPATIBILITY_CHECK → READY, with drain/delete paths.
 *
 * Quarantine → verify → **atomic promote** → DB READY in one recoverable boundary.
 * Plan-like analysis does not mutate; transitions are control-plane commits (ADR-002/010).
 */
class InstallationCoordinator(
    private val repository: InstallationRepository,
    private val modelStore: ModelStorePort,
    private val trustEvaluation: TrustEvaluationPort,
    private val references: ReferenceSnapshotPort,
) {

    suspend fun discover(
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
    ): OmniResult<InstallationSnapshot> {
        repository.get(installationId)?.let {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "installation already exists",
                    details = mapOf("installationId" to installationId.value),
                ),
            )
        }
        val snap = InstallationSnapshot.discovered(installationId, modelRevisionId, artifactPackageId)
        return when (val saved = repository.save(snap)) {
            is OmniResult.Ok -> OmniResult.ok(snap)
            is OmniResult.Err -> saved
        }
    }

    suspend fun beginAcquire(
        installationId: InstallationId,
        quarantineKey: QuarantineKey,
        declared: List<DeclaredArtifactFile>,
        deadlineMonotonic: Long,
    ): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId)
            ?: return notFound(installationId)
        val applied = applyEvent(current, "BEGIN_ACQUIRE")
            ?: return illegal(current, "BEGIN_ACQUIRE")

        val opened = modelStore.openQuarantine(
            key = quarantineKey,
            installationId = toIdentityInstallationId(installationId),
            modelRevisionId = applied.modelRevisionId,
            artifactPackageId = applied.artifactPackageId,
            declared = declared,
            deadlineMonotonic = deadlineMonotonic,
        )
        if (opened is OmniResult.Err) return opened

        val next = applied.copy(quarantineKey = quarantineKey)
        return persist(next)
    }

    /**
     * Record full materialization of declared files → QUARANTINED (guard bytesMaterialized).
     */
    suspend fun materializeComplete(
        installationId: InstallationId,
        files: List<QuarantineFileRecord>,
    ): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId)
            ?: return notFound(installationId)
        val qKey = current.quarantineKey
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "no quarantine key on installation",
                    details = mapOf("installationId" to installationId.value),
                ),
            )

        for (f in files) {
            when (val r = modelStore.recordMaterialized(qKey, f)) {
                is OmniResult.Err -> return r
                is OmniResult.Ok -> Unit
            }
        }

        val guards = InstallationGuardAtoms.evaluator(bytesMaterialized = true)
        val applied = applyEvent(current, "MATERIALIZED", guards)
            ?: return illegal(current, "MATERIALIZED")
        return persist(applied)
    }

    suspend fun acquireFailed(installationId: InstallationId, reason: String): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val applied = applyEvent(current, "ACQUIRE_FAILED")
            ?: return illegal(current, "ACQUIRE_FAILED")
        return persist(applied.copy(rejectReason = reason))
    }

    suspend fun beginVerify(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val applied = applyEvent(current, "BEGIN_VERIFY")
            ?: return illegal(current, "BEGIN_VERIFY")
        return persist(applied)
    }

    /**
     * VERIFYING → COMPATIBILITY_CHECK on identity OK (recomputeTrust action).
     * Trust dimensions stay separate from compatibility (ADR-009).
     */
    suspend fun identityVerifiedOk(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val qKey = current.quarantineKey
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "missing quarantine for verify"),
            )

        val check = when (val r = modelStore.verifyQuarantineIdentity(qKey)) {
            is OmniResult.Ok -> r.value
            is OmniResult.Err -> return r
        }
        if (!check.ok) {
            return identityFailed(installationId, check.failureReason ?: "identity mismatch")
        }

        val trust = when (
            val t = trustEvaluation.evaluate(
                installationId,
                current.modelRevisionId,
                current.evaluation?.trustEpoch ?: 0L,
            )
        ) {
            is OmniResult.Ok -> t.value
            is OmniResult.Err -> return t
        }

        val guards = InstallationGuardAtoms.evaluator(identityVerified = true)
        val applied = applyEvent(current, "IDENTITY_OK", guards)
            ?: return illegal(current, "IDENTITY_OK")
        return persist(applied.copy(evaluation = trust))
    }

    suspend fun identityFailed(installationId: InstallationId, reason: String): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val applied = applyEvent(current, "IDENTITY_FAILED")
            ?: return illegal(current, "IDENTITY_FAILED")
        cleanupQuarantine(applied)
        return persist(applied.copy(rejectReason = reason))
    }

    /**
     * COMPATIBILITY_CHECK → READY via atomic promote then FSM PROMOTE.
     * Order: trust/placement evaluated → fsync/rename → durable READY (CORE-MODEL §4).
     */
    suspend fun promoteToReady(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        if (current.state != "COMPATIBILITY_CHECK") {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "promote requires COMPATIBILITY_CHECK",
                    details = mapOf("state" to current.state),
                ),
            )
        }

        val eval = current.evaluation
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "trust evaluation required before promote"),
            )
        if (!eval.authenticityOk || !eval.licenseOk) {
            return markUnsupported(installationId, "authenticity or license not satisfied")
        }
        if (eval.placementClass == PlacementClassLabels.TRUST_PLACEMENT_REQUIRED) {
            return markUnsupported(installationId, "placement cannot execute")
        }

        val qKey = current.quarantineKey
            ?: return OmniResult.err(OmniError.INVALID_REQUEST(message = "missing quarantine for promote"))

        // Atomic promote (fsync + rename) before READY transaction.
        val promote = when (
            val p = modelStore.atomicPromote(
                key = qKey,
                installationId = toIdentityInstallationId(installationId),
                modelRevisionId = current.modelRevisionId,
                artifactPackageId = current.artifactPackageId,
            )
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }

        val guards = InstallationGuardAtoms.evaluator(trustEvaluated = true)
        val applied = applyEvent(current, "PROMOTE", guards)
            ?: return illegal(current, "PROMOTE")

        val ready = applied.copy(
            storageRootKey = promote.storageRootKey,
            quarantineKey = null,
            evaluation = eval,
        )
        return persist(ready)
    }

    suspend fun markUnsupported(installationId: InstallationId, reason: String): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val applied = applyEvent(current, "UNSUPPORTED")
            ?: return illegal(current, "UNSUPPORTED")
        cleanupQuarantine(applied)
        return persist(applied.copy(rejectReason = reason))
    }

    /** READY → DRAINING on delete request; pin only blocks auto-eviction, not explicit delete drain. */
    suspend fun requestDelete(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val event = when (current.state) {
            "READY" -> "DELETE_REQUESTED"
            "REVOKED", "CORRUPT", "REJECTED" -> "DELETE_REQUESTED"
            "DISCOVERED" -> "DELETE_REQUESTED"
            "QUARANTINED" -> "DELETE_REQUESTED"
            else -> return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "delete not applicable",
                    details = mapOf("state" to current.state),
                ),
            )
        }
        val applied = applyEvent(current, event)
            ?: return illegal(current, event)
        if (current.state == "QUARANTINED" || current.state == "DISCOVERED") {
            cleanupQuarantine(applied)
        }
        return persist(applied)
    }

    suspend fun onRevocationEffective(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val reeval = when (
            val t = trustEvaluation.evaluate(
                installationId,
                current.modelRevisionId,
                (current.evaluation?.trustEpoch ?: 0L) + 1L,
            )
        ) {
            is OmniResult.Ok -> t.value
            is OmniResult.Err -> return t
        }
        val applied = applyEvent(current, "REVOCATION_EFFECTIVE")
            ?: return illegal(current, "REVOCATION_EFFECTIVE")
        return persist(applied.copy(evaluation = reeval))
    }

    suspend fun onIntegrityFailed(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val applied = applyEvent(current, "INTEGRITY_FAILED")
            ?: return illegal(current, "INTEGRITY_FAILED")
        return persist(applied)
    }

    /**
     * Complete drain when references are zero. [outcomeEvent] is one of
     * DRAINED_REVOKED / DRAINED_CORRUPT / DRAINED_DELETE.
     */
    suspend fun completeDrain(
        installationId: InstallationId,
        outcomeEvent: String,
    ): OmniResult<InstallationSnapshot> {
        require(outcomeEvent in setOf("DRAINED_REVOKED", "DRAINED_CORRUPT", "DRAINED_DELETE")) {
            "invalid drain outcome"
        }
        val current = repository.get(installationId) ?: return notFound(installationId)
        val refs = references.installationReferences(installationId)
        val guards = InstallationGuardAtoms.evaluator(references = refs)
        val applied = applyEvent(current, outcomeEvent, guards)
            ?: return illegal(current, outcomeEvent)
        return persist(applied)
    }

    suspend fun commitDelete(installationId: InstallationId): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        if (current.pinned && current.state != "DELETING") {
            // Pin blocks auto-eviction; explicit DELETING path still allowed after drain.
        }
        val applied = applyEvent(current, "DELETE_COMMITTED")
            ?: return illegal(current, "DELETE_COMMITTED")
        when (val d = repository.delete(installationId)) {
            is OmniResult.Err -> return d
            is OmniResult.Ok -> Unit
        }
        // Persist terminal tombstone optional — deleted from primary table.
        return OmniResult.ok(applied)
    }

    suspend fun cancelInFlight(installationId: InstallationId, reason: String): OmniResult<InstallationSnapshot> {
        val current = repository.get(installationId) ?: return notFound(installationId)
        val event = when (current.state) {
            "ACQUIRING", "VERIFYING", "COMPATIBILITY_CHECK" -> "CANCEL_REQUESTED"
            else -> return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "cancel not applicable",
                    details = mapOf("state" to current.state),
                ),
            )
        }
        val applied = applyEvent(current, event)
            ?: return illegal(current, event)
        cleanupQuarantine(applied)
        return persist(applied.copy(rejectReason = reason))
    }

    // --- helpers ---

    private fun applyEvent(
        snap: InstallationSnapshot,
        event: String,
        guards: com.omnillm.core.state.GuardEvaluator =
            InstallationGuardAtoms.evaluator(),
    ): InstallationSnapshot? {
        return when (val r = snap.aggregate.apply(event, guards)) {
            is AggregateTransitionResult.Success -> snap.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected -> null
        }
    }

    private suspend fun persist(snap: InstallationSnapshot): OmniResult<InstallationSnapshot> =
        when (val s = repository.save(snap)) {
            is OmniResult.Ok -> OmniResult.ok(snap)
            is OmniResult.Err -> s
        }

    private suspend fun cleanupQuarantine(snap: InstallationSnapshot) {
        snap.quarantineKey?.let { modelStore.cleanupQuarantine(it) }
    }

    private fun notFound(id: InstallationId): OmniResult<Nothing> =
        OmniResult.err(
            OmniError.NOT_FOUND(
                message = "installation not found",
                details = mapOf("installationId" to id.value),
            ),
        )

    private fun illegal(snap: InstallationSnapshot, event: String): OmniResult<Nothing> =
        OmniResult.err(
            OmniError.STATE_CONFLICT(
                message = "illegal installation transition",
                details = mapOf(
                    "installationId" to snap.installationId.value,
                    "state" to snap.state,
                    "event" to event,
                ),
            ),
        )

    private fun toIdentityInstallationId(
        id: InstallationId,
    ): com.omnillm.core.identity.InstallationId =
        com.omnillm.core.identity.InstallationId.ofValidated(id.value)
}
