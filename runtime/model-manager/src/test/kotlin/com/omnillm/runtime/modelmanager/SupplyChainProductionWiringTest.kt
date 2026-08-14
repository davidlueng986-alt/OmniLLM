package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.data.modelstore.ContentIdentityCheck
import com.omnillm.data.modelstore.ReadOnlyContentFd
import com.omnillm.data.modelstore.ReadyContentPort
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.ports.PrivilegedReverifyRequest
import com.omnillm.runtime.modelmanager.supply.CatalogMetadataRole
import com.omnillm.runtime.modelmanager.supply.CatalogRevocationRecord
import com.omnillm.runtime.modelmanager.supply.EmbeddedCatalogRoot
import com.omnillm.runtime.modelmanager.supply.InMemorySupplyChainHooks
import com.omnillm.runtime.modelmanager.supply.NoopSupplyChainHooks
import com.omnillm.runtime.modelmanager.supply.ProductionSupplyChainHooks
import com.omnillm.runtime.modelmanager.supply.RevocationTargetKind
import com.omnillm.runtime.modelmanager.supply.SupplyChainBootstrapService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-14 regression: production supply-chain hooks must be LIVE, not dead API.
 *
 * Audit finding: "Production supply-chain hooks often NoopSupplyChainHooks
 * (fail-closed until root wired)". Verified reality: the hook surface was not
 * merely noop — nothing invoked it. `SupplyChainBootstrapService` was never
 * called from any production path, and the privileged-load re-verify hardcoded
 * fail-closed lambdas ([DefaultPrivilegedLoadReverify.failClosedUntilSupplyWired])
 * that never consulted a hook. The embedded catalog root is provisioned by the
 * release pipeline (SEC-SUPPLY §1) and does not exist in-tree yet, so the
 * correct pre-provisioning posture IS fail-closed — but the surface must be
 * wired so that the moment a root is provisioned, the real checks fire.
 *
 * RED note (C-06 convention): this test compiles against the intended seams
 * `DefaultPrivilegedLoadReverify.hooksBacked` and `ProductionSupplyChainHooks`
 * — their absence IS the defect. The test cannot compile until the wiring
 * exists; after the fix it proves hooks drive signature/revocation decisions
 * and fail closed honestly when no root is provisioned.
 */
class SupplyChainProductionWiringTest {

    private val revId = ModelRevisionId.parse(
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    )
    private val installId = InstallationId("install-1")
    private val revHex = revId.hex

    private fun request() = PrivilegedReverifyRequest(
        installationId = installId,
        modelRevisionId = revId,
        storageRootKey = "installations/install-1",
        templateEpoch = 1L,
        tokenizerEpoch = 1L,
        engineBuildId = "engine-build-1",
        revocationEpoch = 0L,
    )

    private fun root(version: Long = 1L): EmbeddedCatalogRoot =
        EmbeddedCatalogRoot.fromBytes(
            metadataBytes = "root-meta-$version".toByteArray(Charsets.UTF_8),
            schemaMajorVersion = 1,
            rootVersion = version,
            keyIds = listOf("key-1"),
        )

    @Test
    fun hooksBacked_consultsSupplyChainHooks_whenTrustEstablished() = runBlocking {
        val hooks = InMemorySupplyChainHooks(root())

        val reverify = DefaultPrivilegedLoadReverify.hooksBacked(FakeReady(contentOk = true), hooks)
        val ticket = (reverify.reverify(request()) as OmniResult.Ok).value

        assertTrue(
            "signature decision must consult the live hook surface (C-14)",
            ticket.signatureChainOk,
        )
        assertTrue(
            "revocation decision must consult the live hook surface (C-14)",
            ticket.revocationOk,
        )
        assertTrue("all security dimensions pass under a trusted root", ticket.allOk)
        assertEquals(
            "placement stays conservative until placement policy is hook-wired (documented)",
            PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
            ticket.placementClass,
        )
    }

    @Test
    fun hooksBacked_revocationRecord_failClosed() = runBlocking {
        val hooks = InMemorySupplyChainHooks(root())
        hooks.revocationLedger().apply(
            CatalogRevocationRecord(
                authorityRole = CatalogMetadataRole.REVOCATION,
                metadataVersion = 1,
                metadataSequence = 1,
                reason = "compromised",
                targetKind = RevocationTargetKind.MODEL_REVISION,
                targetId = revHex,
                effectiveFromEpochMs = 0L,
                effectiveUntilEpochMs = null,
                canonicalDigestHex = "b".repeat(64),
            ),
        )

        val reverify = DefaultPrivilegedLoadReverify.hooksBacked(FakeReady(contentOk = true), hooks)
        val ticket = (reverify.reverify(request()) as OmniResult.Ok).value

        assertFalse(
            "revoked revision must fail the revocation dimension (C-14)",
            ticket.revocationOk,
        )
        assertFalse("revoked revision must never be all-ok", ticket.allOk)
    }

    @Test
    fun hooksBacked_noopHooks_failClosedLikePreWiring() = runBlocking {
        val reverify =
            DefaultPrivilegedLoadReverify.hooksBacked(FakeReady(contentOk = true), NoopSupplyChainHooks())
        val ticket = (reverify.reverify(request()) as OmniResult.Ok).value

        assertFalse(
            "no bootstrapped trust state must fail the signature dimension (C-14)",
            ticket.signatureChainOk,
        )
        assertFalse(ticket.allOk)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, ticket.placementClass)
    }

    @Test
    fun productionHooks_withoutEmbeddedRoot_bootstrapRejectsAndFailClosed() {
        val hooks = ProductionSupplyChainHooks(embeddedRootProvider = { null })
        val outcome = SupplyChainBootstrapService(hooks) { 1_000L }.ensureBootstrapped()

        assertTrue(
            "bootstrap must reject honestly (no silent noop) when root is missing",
            outcome is com.omnillm.runtime.modelmanager.supply.CatalogRootBootstrap.Outcome.Rejected,
        )
        assertFalse("no root => no signature trust", hooks.isInstalledRevisionSignatureOk(revHex))
    }

    @Test
    fun productionHooks_withEmbeddedRoot_bootstrapAcceptsAndHooksFire() {
        val hooks = ProductionSupplyChainHooks(
            embeddedRootProvider = { root() },
            installedSignatureOk = { it == revHex },
        )
        val outcome = SupplyChainBootstrapService(hooks) { 1_000L }.ensureBootstrapped()

        assertTrue(
            "bootstrapped root must be accepted",
            outcome is com.omnillm.runtime.modelmanager.supply.CatalogRootBootstrap.Outcome.Accepted,
        )
        assertTrue("trust state must persist via the hook surface", hooks.loadTrustState() != null)
        assertTrue(
            "provisioned root must fire the real signature check",
            hooks.isInstalledRevisionSignatureOk(revHex),
        )
        assertFalse(
            "unknown revision must fail the signature check",
            hooks.isInstalledRevisionSignatureOk("f".repeat(64)),
        )
    }

    private class FakeReady(private val contentOk: Boolean) : ReadyContentPort {
        private val digest = Sha256Digest.parse(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )

        override suspend fun openReadOnly(
            installationId: com.omnillm.core.identity.InstallationId,
            storageRootKey: String,
        ): OmniResult<List<ReadOnlyContentFd>> =
            OmniResult.ok(
                listOf(
                    ReadOnlyContentFd(
                        role = "weights",
                        blobId = BlobId.parse(digest.hex),
                        byteLength = 4L,
                        fdToken = "fd-token-1",
                    ),
                ),
            )

        override suspend fun verifyOpenFds(
            fds: List<ReadOnlyContentFd>,
        ): OmniResult<ContentIdentityCheck> =
            OmniResult.ok(
                ContentIdentityCheck(
                    ok = contentOk,
                    computedDigests = if (contentOk) mapOf("weights" to digest) else emptyMap(),
                    failureReason = if (contentOk) null else "digest mismatch",
                ),
            )

        override suspend fun closeFds(fds: List<ReadOnlyContentFd>): OmniResult<Unit> =
            OmniResult.ok(Unit)
    }
}
