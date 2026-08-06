package com.omnillm.runtime.modelmanager.supply

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRootBootstrapTest {

    private fun root(version: Long, expiresAt: Long? = null): EmbeddedCatalogRoot {
        val bytes = "root-meta-v$version".toByteArray(Charsets.UTF_8)
        return EmbeddedCatalogRoot.fromBytes(
            metadataBytes = bytes,
            schemaMajorVersion = 1,
            rootVersion = version,
            expiresAtEpochMs = expiresAt,
            keyIds = listOf("key-1"),
        )
    }

    @Test
    fun bootstrapFromEmbedded_offline() {
        val embedded = root(0)
        val r = CatalogRootBootstrap.bootstrapFromEmbedded(embedded, nowEpochMs = 1_000L)
        assertTrue(r is CatalogRootBootstrap.Outcome.Accepted)
        val state = (r as CatalogRootBootstrap.Outcome.Accepted).state
        assertEquals(0L, state.highestRootVersion)
        assertEquals(embedded.metadataDigestHex, state.sourceArtifactDigestHex)
    }

    @Test
    fun bootstrap_rejectsExpiredEmbedded() {
        val embedded = root(0, expiresAt = 100L)
        val r = CatalogRootBootstrap.bootstrapFromEmbedded(embedded, nowEpochMs = 200L)
        assertTrue(r is CatalogRootBootstrap.Outcome.Rejected)
        assertEquals(
            OmniErrorCode.MODEL_REVOKED,
            (r as CatalogRootBootstrap.Outcome.Rejected).error.code,
        )
    }

    @Test
    fun rotateRoot_rejectsSkip() {
        val base = (CatalogRootBootstrap.bootstrapFromEmbedded(root(0), 1L)
            as CatalogRootBootstrap.Outcome.Accepted).state
        val skip = CatalogRootBootstrap.rotateRoot(
            current = base,
            orderedNewRoots = listOf(root(2)),
            nowEpochMs = 1L,
            maxSupportedSchemaMajor = 1,
        )
        assertTrue(skip is CatalogRootBootstrap.Outcome.Rejected)

        val ok = CatalogRootBootstrap.rotateRoot(
            current = base,
            orderedNewRoots = listOf(root(1), root(2)),
            nowEpochMs = 1L,
            maxSupportedSchemaMajor = 1,
        )
        assertTrue(ok is CatalogRootBootstrap.Outcome.Accepted)
        assertEquals(2L, (ok as CatalogRootBootstrap.Outcome.Accepted).state.highestRootVersion)
    }

    @Test
    fun advanceSequence_antiRollback() {
        val base = (CatalogRootBootstrap.bootstrapFromEmbedded(root(0), 1L)
            as CatalogRootBootstrap.Outcome.Accepted).state
        val digest = IdentityHashing.sha256Hex("snap-1")
        val advanced = CatalogRootBootstrap.advanceSequence(base, 5L, digest)
        assertTrue(advanced is CatalogRootBootstrap.Outcome.Accepted)
        val state = (advanced as CatalogRootBootstrap.Outcome.Accepted).state
        val rollback = CatalogRootBootstrap.advanceSequence(state, 3L, digest)
        assertTrue(rollback is CatalogRootBootstrap.Outcome.Rejected)
    }

    @Test
    fun supplyChainBootstrapService_persistsState() {
        val embedded = root(0)
        val hooks = InMemorySupplyChainHooks(embedded)
        val svc = SupplyChainBootstrapService(hooks) { 1_000L }
        val r = svc.ensureBootstrapped()
        assertTrue(r is CatalogRootBootstrap.Outcome.Accepted)
        assertTrue(hooks.loadTrustState() != null)
        // Idempotent second call.
        val r2 = svc.ensureBootstrapped()
        assertTrue(r2 is CatalogRootBootstrap.Outcome.Accepted)
    }

    @Test
    fun revocationLedger_olderCannotLiftNewer() {
        val ledger = RevocationLedger()
        val newer = CatalogRevocationRecord(
            authorityRole = CatalogMetadataRole.REVOCATION,
            metadataVersion = 2,
            metadataSequence = 10,
            reason = "compromised",
            targetKind = RevocationTargetKind.MODEL_REVISION,
            targetId = "a".repeat(64),
            effectiveFromEpochMs = 0L,
            effectiveUntilEpochMs = null,
            canonicalDigestHex = "b".repeat(64),
        )
        assertTrue(ledger.apply(newer) is RevocationLedger.ApplyResult.Applied)
        val older = newer.copy(metadataSequence = 5, reason = "attempted lift")
        val rejected = ledger.apply(older)
        assertTrue(rejected is RevocationLedger.ApplyResult.Rejected)
        assertTrue(ledger.isRevoked(RevocationTargetKind.MODEL_REVISION, "a".repeat(64), 1L))
    }

    @Test
    fun trustedClock_rollbackBlocksRemoteExpiry() {
        val first = TrustedClockGraceMachine.observe(
            previous = null,
            observedWallEpochMs = 1_000_000L,
            observedElapsedRealtimeMs = 5_000L,
            bootId = "boot-1",
        )
        assertTrue(first is TrustedClockGraceMachine.Outcome.Accepted)
        val anchored = (first as TrustedClockGraceMachine.Outcome.Accepted).record.copy(
            state = TrustedTimeState.ANCHORED,
            source = TrustedTimeSource.SIGNED_TIMESTAMP_METADATA,
        )
        val rolled = TrustedClockGraceMachine.observe(
            previous = anchored,
            observedWallEpochMs = 1_000L, // large rollback
            observedElapsedRealtimeMs = 5_100L,
            bootId = "boot-1",
        )
        assertTrue(rolled is TrustedClockGraceMachine.Outcome.Accepted)
        val rec = (rolled as TrustedClockGraceMachine.Outcome.Accepted).record
        assertEquals(TrustedTimeState.ROLLBACK_UNCERTAIN, rec.state)
        assertTrue(!TrustedClockGraceMachine.allowsRemoteMetadataExpiry(rec))
    }
}
