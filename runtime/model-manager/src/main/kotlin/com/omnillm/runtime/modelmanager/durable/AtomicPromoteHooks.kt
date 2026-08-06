package com.omnillm.runtime.modelmanager.durable

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.PromoteResult
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.data.persistence.InstallationLedgerPorts
import com.omnillm.data.persistence.InstallationRecordRow
import com.omnillm.core.identity.InstallationId as IdentityInstallationId
import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.ModelRevisionId

/**
 * Atomic promote boundary hooks (CORE-MODEL §4 / DATA-OWNERSHIP §3).
 *
 * Order:
 * 1. Filesystem fsync + rename via [ModelStorePort.atomicPromote]
 * 2. Durable READY marker in the installation ledger (same recoverable boundary
 *    from the control-plane writer's perspective — FS first, then DB)
 *
 * Crash reconciler uses content identity + storage_root_key, not temp names.
 * Callers must not mark READY without a successful promote result.
 */
object AtomicPromoteHooks {

    /**
     * Promote quarantine bytes then upsert READY row in one control-plane sequence.
     * Returns the promoted [PromoteResult] for snapshot composition.
     *
     * Does **not** advance FSM by itself — [InstallationCoordinator.promoteToReady]
     * still applies the PROMOTE event; this hook is the durable FS+DB boundary helper
     * for host wiring / recovery.
     */
    suspend fun promoteFilesystemThenMarkStorage(
        modelStore: ModelStorePort,
        ports: InstallationLedgerPorts,
        installationId: InstallationId,
        modelRevisionId: ModelRevisionId,
        artifactPackageId: ArtifactPackageId,
        quarantineKey: QuarantineKey,
        baseRow: InstallationRecordRow,
        readyState: String = "READY",
        clock: () -> String,
    ): OmniResult<PromoteResult> {
        val promote = when (
            val p = modelStore.atomicPromote(
                key = quarantineKey,
                installationId = IdentityInstallationId.ofValidated(installationId.value),
                modelRevisionId = modelRevisionId,
                artifactPackageId = artifactPackageId,
            )
        ) {
            is OmniResult.Ok -> p.value
            is OmniResult.Err -> return p
        }

        val now = clock()
        return try {
            ports.tx.inTransaction {
                ports.installations.upsert(
                    baseRow.copy(
                        state = readyState,
                        storageRootKey = promote.storageRootKey,
                        quarantineJobId = null,
                        quarantineAttemptId = null,
                        resourceVersion = baseRow.resourceVersion + 1L,
                        updatedAt = now,
                    ),
                )
            }
            OmniResult.ok(promote)
        } catch (e: Exception) {
            // FS promote succeeded; DB failed — row may stay pre-READY with pending key.
            // Reconciler re-reads content identity under installations/<id>.
            OmniResult.err(
                OmniError.INTERNAL(
                    message = "READY marker failed after FS promote: ${e.javaClass.simpleName}",
                    details = mapOf(
                        "installationId" to installationId.value,
                        "storageRootKey" to promote.storageRootKey,
                    ),
                ),
            )
        }
    }
}
