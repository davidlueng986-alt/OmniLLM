package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.data.persistence.InstallationLedgerPorts
import com.omnillm.features.modelhub.ports.InstallationResourceVersionPort
import java.time.Instant

/**
 * Durable installation resource-version authority (D7 / COR-18 residual).
 *
 * Single source of truth: the `resource_version` column of the DURABLE
 * installation row ([InstallationLedgerPorts]), advanced +1 by
 * [com.omnillm.runtime.modelmanager.durable.SqlInstallationRepository] on every
 * save and additionally by [bump] on service-visible state transitions.
 *
 * The delete CAS ([com.omnillm.features.modelhub.usecase.ModelHubService.startDelete])
 * and the card/snapshot projection read the SAME durable value, so a fresh
 * snapshot always carries a version the delete CAS accepts — the "re-fetch
 * guidance" converges after a load and after a process restart (the old
 * in-memory counter reset on restart and never converged).
 *
 * Fail closed: unknown installations (no durable row) return null from
 * [currentVersion] — delete refuses instead of accepting a stale version.
 *
 * Only the control-plane sole writer may construct this (ADR-010); the
 * underlying SqlDelight store asserts the writer role at construction.
 */
class SqlDelightInstallationResourceVersionPort(
    private val ports: InstallationLedgerPorts,
    private val clock: () -> String = { Instant.now().toString() },
) : InstallationResourceVersionPort {

    override fun currentVersion(installationId: String): Long? =
        ports.installations.findByInstallationId(installationId)?.resourceVersion

    /**
     * Advance the durable version by one. Transitions that persist through the
     * ModelManager already advanced the row (+1 per save); this makes the
     * remaining service-visible transitions (e.g. license acceptance) advance
     * too. The extra +1 next to a repository save is harmless for the CAS: both
     * the snapshot and the CAS read the same row, so they always agree.
     */
    override fun bump(installationId: String): Long {
        val existing = ports.installations.findByInstallationId(installationId)
            ?: return 0L // nothing durable to bump — fail closed stays
        val next = existing.resourceVersion + 1L
        ports.tx.inTransaction {
            ports.installations.upsert(
                existing.copy(
                    resourceVersion = next,
                    updatedAt = clock(),
                ),
            )
        }
        return next
    }

    /**
     * No-op: the durable row is created by `discoverInstallation` (version 0).
     * Never fabricate a row for an unknown installation — fail closed.
     */
    override fun seed(installationId: String) = Unit

    /**
     * No-op: row deletion is owned by `commitInstallationDelete`; a committed
     * delete leaves no row, so [currentVersion] naturally fails closed.
     */
    override fun remove(installationId: String) = Unit
}
