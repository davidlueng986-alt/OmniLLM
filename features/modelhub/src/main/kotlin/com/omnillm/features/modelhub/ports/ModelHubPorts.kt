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

/**
 * Engine load coordination surface for LOAD/UNLOAD commands (M4).
 * Wired by the runtime control plane — the feature never loads native engines
 * itself (INV-001). Returns null when no engine is attached (fail closed).
 */
interface ModelLoadRuntimePort {
    /** Attached primary engine build id (e.g. llama-cpp locked build), or null. */
    fun primaryEngineBuildId(): String?

    /** Device fingerprint used for LoadKey construction (runtime-observed). */
    fun deviceExecutionFingerprint(): com.omnillm.core.contracts.DeviceExecutionFingerprint?
}

/** Port that resolves nothing — feature tests / unattached hosts fail closed. */
object UnavailableModelLoadRuntimePort : ModelLoadRuntimePort {
    override fun primaryEngineBuildId(): String? = null
    override fun deviceExecutionFingerprint(): com.omnillm.core.contracts.DeviceExecutionFingerprint? = null
}

/**
 * License acceptance ledger (CORE-MODEL §8 / SEC-SUPPLY §7 / M5).
 * Append-only acceptance events bound to (principal, terms digest, source).
 * Identical bytes from different sources never share acceptance.
 */
interface LicenseAcceptancePort {
    fun accept(principalId: String, termsDigestHex: String, sourceAssertion: String)
    fun hasAccepted(principalId: String, termsDigestHex: String, sourceAssertion: String): Boolean
}

/** In-memory acceptance ledger (control-plane host; durable variant later). */
class InMemoryLicenseAcceptanceLedger : LicenseAcceptancePort {
    private val accepted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun key(p: String, d: String, s: String) = "$p|$d|$s"

    override fun accept(principalId: String, termsDigestHex: String, sourceAssertion: String) {
        require(termsDigestHex.matches(Regex("^[0-9a-f]{64}$"))) { "terms digest must be 64-hex" }
        require(sourceAssertion.isNotEmpty()) { "sourceAssertion must be non-empty" }
        accepted.add(key(principalId, termsDigestHex, sourceAssertion))
    }

    override fun hasAccepted(principalId: String, termsDigestHex: String, sourceAssertion: String): Boolean =
        accepted.contains(key(principalId, termsDigestHex, sourceAssertion))
}

/** Empty ledger — everything still requires acceptance (fail closed). */
object NoLicenseAcceptanceLedger : LicenseAcceptancePort {
    override fun accept(principalId: String, termsDigestHex: String, sourceAssertion: String) = Unit
    override fun hasAccepted(principalId: String, termsDigestHex: String, sourceAssertion: String): Boolean = false
}

/** Fixed list catalog for tests / seed. */
class FixedSuggestedCatalogPort(
    private val entries: List<CatalogModelEntry>,
) : SuggestedCatalogPort {
    override fun listSuggested(): List<CatalogModelEntry> = entries
    override fun findByRevision(modelRevisionId: String): CatalogModelEntry? =
        entries.firstOrNull { it.modelRevisionId == modelRevisionId }
}
