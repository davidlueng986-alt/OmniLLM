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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPrivilegedLoadReverifyTest {

    private val revId = ModelRevisionId.parse(
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    )
    private val installId = InstallationId("install-1")

    private fun request() = PrivilegedReverifyRequest(
        installationId = installId,
        modelRevisionId = revId,
        storageRootKey = "installations/install-1",
        templateEpoch = 1L,
        tokenizerEpoch = 1L,
        engineBuildId = "engine-build-1",
        revocationEpoch = 0L,
    )

    @Test
    fun failClosedFactory_neverIssuesPrivilegedTrusted() = runBlocking {
        val ready = FakeReady(contentOk = true)
        val reverify = DefaultPrivilegedLoadReverify.failClosedUntilSupplyWired(ready)
        val ticket = (reverify.reverify(request()) as OmniResult.Ok).value
        assertFalse(ticket.allOk)
        assertFalse(ticket.signatureChainOk)
        assertFalse(ticket.revocationOk)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, ticket.placementClass)
    }

    @Test
    fun contentIdentityFailure_failClosed() = runBlocking {
        val ready = FakeReady(contentOk = false)
        val reverify = DefaultPrivilegedLoadReverify(
            readyContent = ready,
            signatureChainOk = { true },
            revocationOk = { true },
            placementClassFor = { PlacementClassLabels.PRIVILEGED_TRUSTED },
        )
        val ticket = (reverify.reverify(request()) as OmniResult.Ok).value
        assertFalse(ticket.contentIdentityOk)
        assertEquals(PlacementClassLabels.TRUST_PLACEMENT_REQUIRED, ticket.placementClass)
    }

    @Test
    fun allChecksOk_returnsProposedPlacement() = runBlocking {
        val ready = FakeReady(contentOk = true)
        val reverify = DefaultPrivilegedLoadReverify(
            readyContent = ready,
            signatureChainOk = { true },
            revocationOk = { true },
            placementClassFor = { PlacementClassLabels.PRIVILEGED_TRUSTED },
        )
        val ticket = (reverify.reverify(request()) as OmniResult.Ok).value
        assertTrue(ticket.allOk)
        assertEquals(PlacementClassLabels.PRIVILEGED_TRUSTED, ticket.placementClass)
        assertTrue(ready.closed)
    }

    private class FakeReady(private val contentOk: Boolean) : ReadyContentPort {
        var closed: Boolean = false
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

        override suspend fun closeFds(fds: List<ReadOnlyContentFd>): OmniResult<Unit> {
            closed = true
            return OmniResult.ok(Unit)
        }
    }
}
