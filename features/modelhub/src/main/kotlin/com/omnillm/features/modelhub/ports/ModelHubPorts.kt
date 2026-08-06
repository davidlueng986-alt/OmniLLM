package com.omnillm.features.modelhub.ports

import com.omnillm.features.modelhub.api.CatalogModelEntry
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.ports.LiveReferences

/**
 * Feature-facing ports. Implementations live in the runtime control plane host
 * (or test fakes). Feature code must not open DB / secrets (ADR-010 / INV-001).
 */

/** Signed / curated catalog suggestions (CORE-MODEL §2 Signed Catalog). */
interface SuggestedCatalogPort {
    fun listSuggested(): List<CatalogModelEntry>
    fun findByRevision(modelRevisionId: String): CatalogModelEntry?
}

/**
 * Optional loaded-model lookup for ModelCard projection (LOADED_MODEL separate
 * from MODEL_INSTALLATION — CORE-MODEL §1 / §10).
 */
interface LoadedModelQueryPort {
    suspend fun findByInstallation(installationId: String): List<LoadedModelSnapshot>
}

/** Live reference counts for delete/drain guards and card badges. */
interface LiveReferenceQueryPort {
    suspend fun installationReferences(installationId: String): LiveReferences
}

/**
 * Mutable display metadata (alias) keyed by installation or revision.
 * Alias is a mutable pointer only — never part of content identity (ADR-008).
 */
interface ModelDisplayMetadataPort {
    fun getDisplayName(modelRevisionId: String, installationId: String?): String?
    fun getAlias(installationId: String): String?
    fun putDisplayName(modelRevisionId: String, displayName: String)
    fun putAlias(installationId: String, alias: String?)
}

/**
 * Links JobId ↔ InstallationId for acquisition pipeline.
 * Durable mapping is owned by control plane; this port is the feature read/write surface.
 */
interface AcquisitionLinkStore {
    fun link(jobId: String, installationId: String, modelRevisionId: String)
    fun installationIdForJob(jobId: String): String?
    fun jobIdForInstallation(installationId: String): String?
    fun modelRevisionIdForJob(jobId: String): String?
    fun unlink(jobId: String)
}

/** In-memory link store for tests and scaffold hosts. */
class InMemoryAcquisitionLinkStore : AcquisitionLinkStore {
    private data class Link(
        val installationId: String,
        val modelRevisionId: String,
    )

    private val byJob = linkedMapOf<String, Link>()
    private val byInstallation = linkedMapOf<String, String>()

    override fun link(jobId: String, installationId: String, modelRevisionId: String) {
        byJob[jobId] = Link(installationId, modelRevisionId)
        byInstallation[installationId] = jobId
    }

    override fun installationIdForJob(jobId: String): String? = byJob[jobId]?.installationId

    override fun jobIdForInstallation(installationId: String): String? = byInstallation[installationId]

    override fun modelRevisionIdForJob(jobId: String): String? = byJob[jobId]?.modelRevisionId

    override fun unlink(jobId: String) {
        val link = byJob.remove(jobId) ?: return
        byInstallation.remove(link.installationId)
    }
}

/** In-memory display names / aliases. */
class InMemoryModelDisplayMetadataPort : ModelDisplayMetadataPort {
    private val names = linkedMapOf<String, String>()
    private val aliases = linkedMapOf<String, String>()

    override fun getDisplayName(modelRevisionId: String, installationId: String?): String? =
        installationId?.let { aliases[it] } ?: names[modelRevisionId]

    override fun getAlias(installationId: String): String? = aliases[installationId]

    override fun putDisplayName(modelRevisionId: String, displayName: String) {
        names[modelRevisionId] = displayName
    }

    override fun putAlias(installationId: String, alias: String?) {
        if (alias == null) aliases.remove(installationId) else aliases[installationId] = alias
    }
}

/** Empty suggested catalog. */
class EmptySuggestedCatalogPort : SuggestedCatalogPort {
    override fun listSuggested(): List<CatalogModelEntry> = emptyList()
    override fun findByRevision(modelRevisionId: String): CatalogModelEntry? = null
}

/** Fixed list catalog for tests / seed. */
class FixedSuggestedCatalogPort(
    private val entries: List<CatalogModelEntry>,
) : SuggestedCatalogPort {
    override fun listSuggested(): List<CatalogModelEntry> = entries
    override fun findByRevision(modelRevisionId: String): CatalogModelEntry? =
        entries.firstOrNull { it.modelRevisionId == modelRevisionId }
}
