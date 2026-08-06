package com.omnillm.features.autosetup.catalog

import com.omnillm.core.canonical.generated.ArtifactPackageId
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.features.autosetup.domain.ModelCandidateInput
import com.omnillm.features.autosetup.ports.CatalogCandidatePort
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import com.omnillm.features.modelhub.catalog.OfflineFixtureCatalog

/**
 * AutoSetup catalog port backed by the shared offline ModelHub fixture
 * (FEAT-AUTOSETUP §3 + FEAT-MODELHUB offline software path).
 *
 * Capability cells stay honest: operation capability may be UNKNOWN for
 * exploratory engines — ranker fails closed / prefers known CPU paths.
 */
class FixtureCatalogCandidates(
    private val engineBuildId: String = "llama-cpp",
    private val backend: String = "cpu",
    /**
     * Catalog-reported operation capability for TEXT_GENERATION.
     * Default UNKNOWN — host software must not invent SUPPORTED without evidence.
     */
    private val textGenCapability: CapabilityState = CapabilityState.UNKNOWN,
) : CatalogCandidatePort {

    override suspend fun listCandidates(targetOperationId: String): OmniResult<List<ModelCandidateInput>> {
        val op = CapabilityId.values().firstOrNull { it.id == targetOperationId }
            ?: return OmniResult.ok(emptyList())
        val entry = OfflineFixtureCatalog.DEFAULT.pinnedEntry()
        val candidate = ModelCandidateInput(
            candidateId = "fixture-offline-v1",
            displayName = entry.displayName,
            modelRevisionId = ModelRevisionId.parse(entry.modelRevisionId),
            artifactPackageId = ArtifactPackageId.parse(entry.artifactPackageId),
            engineBuildId = EngineBuildId.parse(engineBuildId),
            backend = backend,
            placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
            operationCapabilityState = if (op == CapabilityId.TEXT_GENERATION) {
                textGenCapability
            } else {
                CapabilityState.UNSUPPORTED
            },
            targetOperation = op,
            estimatedPeakBytes = 32L * 1024L * 1024L,
            packageBytes = FixtureArtifact.BYTE_LENGTH,
            licenseOk = true,
            authenticityOk = false, // pin path — not signed root elevated
            stabilityEvidence = EvidenceLabel.UNKNOWN,
            qualityScore = 0.3,
            speedScore = 0.5,
            licenseLabel = "fixture",
            sourceTrustLabel = "PINNED_DOWNLOAD",
            readyInstallationId = null,
            notes = mapOf(
                "sourceUrl" to FixtureArtifact.PINNED_HTTPS_URL,
                "expectedSha256" to FixtureArtifact.blobIdHex(),
            ),
        )
        return OmniResult.ok(listOf(candidate))
    }
}
