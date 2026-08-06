package com.omnillm.features.modelhub.catalog

import com.omnillm.features.modelhub.api.AcquisitionChannel
import com.omnillm.features.modelhub.api.CatalogModelEntry
import com.omnillm.features.modelhub.ports.SuggestedCatalogPort

/**
 * Offline / APK-embedded suggested catalog (SEC-SUPPLY §1 root bootstrap spirit).
 *
 * Ships with a single pinned-download fixture entry so ModelHub + AutoSetup work
 * without network. Does **not** claim QUALIFIED engines or elevate authenticity
 * beyond signed-catalog vs pin vs import channel labels (ADR-009).
 *
 * Production may replace this port with a supply-chain-verified catalog host.
 */
class OfflineFixtureCatalog(
    private val includeSafImportHint: Boolean = true,
) : SuggestedCatalogPort {

    private val pinned: CatalogModelEntry = CatalogModelEntry(
        modelRevisionId = FixtureArtifact.revisionIdHex(),
        artifactPackageId = FixtureArtifact.packageIdHex(),
        displayName = FixtureArtifact.DISPLAY_NAME,
        acquisitionChannel = AcquisitionChannel.PINNED_DOWNLOAD,
        byteLength = FixtureArtifact.BYTE_LENGTH,
        quantizationDescriptorJson = """{"format":"fixture","bits":"n/a"}""",
        licenseDigest = FixtureArtifact.LICENSE_DIGEST_HEX,
        sourceAssertionsSummary = "offline fixture pin; HTTPS policy required",
        supportedEngineBuildIds = emptyList(), // honest: no SUPPORTED claim
        suggested = true,
    )

    /**
     * Catalog-only import template (same identity) for SAF path demos.
     * Channel LOCAL_IMPORT ⇒ SOURCE_UNVERIFIED risk flag; never elevates trust.
     */
    private val safHint: CatalogModelEntry = CatalogModelEntry(
        modelRevisionId = FixtureArtifact.revisionIdHex(),
        artifactPackageId = FixtureArtifact.packageIdHex(),
        displayName = "${FixtureArtifact.DISPLAY_NAME} (SAF import)",
        acquisitionChannel = AcquisitionChannel.LOCAL_IMPORT,
        byteLength = FixtureArtifact.BYTE_LENGTH,
        quantizationDescriptorJson = """{"format":"fixture","bits":"n/a"}""",
        licenseDigest = FixtureArtifact.LICENSE_DIGEST_HEX,
        sourceAssertionsSummary = "local SAF/PFD; source unverified",
        supportedEngineBuildIds = emptyList(),
        suggested = true,
    )

    override fun listSuggested(): List<CatalogModelEntry> =
        if (includeSafImportHint) listOf(pinned, safHint) else listOf(pinned)

    override fun findByRevision(modelRevisionId: String): CatalogModelEntry? =
        listSuggested().firstOrNull { it.modelRevisionId == modelRevisionId.lowercase() }

    fun pinnedEntry(): CatalogModelEntry = pinned

    fun findByPinnedUrl(url: String): CatalogModelEntry? {
        val normalized = url.trim()
        return if (normalized == FixtureArtifact.PINNED_HTTPS_URL) pinned else null
    }

    companion object {
        /** Shared singleton for control-plane + UI projection parity. */
        val DEFAULT: OfflineFixtureCatalog = OfflineFixtureCatalog()
    }
}
