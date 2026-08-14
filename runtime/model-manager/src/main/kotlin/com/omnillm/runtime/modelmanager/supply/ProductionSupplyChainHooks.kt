package com.omnillm.runtime.modelmanager.supply

/**
 * Production supply-chain hooks (C-14).
 *
 * The embedded catalog root is provisioned by the release / signing pipeline
 * (SEC-SUPPLY §1): [embeddedRootProvider] loads it (Android: APK assets).
 * Until a root is provisioned every trust decision here is fail-closed by
 * construction, and that is the intended posture — NOT a silent noop:
 *
 * - `ensureBootstrapped()` rejects with "missing embedded root" and the
 *   control plane logs the rejection at attach;
 * - [isInstalledRevisionSignatureOk] stays false ⇒ privileged loads remain
 *   `TRUST_PLACEMENT_REQUIRED` (no privileged engine placement);
 * - [verifySignatures] / [isInstalledRevisionSignatureOk] default to false
 *   until a manifest-signature verification provider is wired (crypto port).
 *
 * The moment a root asset is provisioned, bootstrap establishes trust state
 * and the real checks fire without code changes.
 *
 * Trust-state persistence is in-memory for this install lifecycle (matches
 * scaffold semantics); durable SQLite persistence of [CatalogTrustState] is a
 * documented follow-up (out of C-14 scope: `:data:persistence` single-writer).
 */
class ProductionSupplyChainHooks(
    private val embeddedRootProvider: () -> EmbeddedCatalogRoot?,
    private val verifyManifest: (CatalogMetadataEnvelope) -> Boolean = { false },
    private val installedSignatureOk: (String) -> Boolean = { false },
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val trustStateStore: TrustStateStore = InMemoryTrustStateStore(),
) : SupplyChainHooks {
    private val ledger = RevocationLedger()

    override fun embeddedRoot(): EmbeddedCatalogRoot? = embeddedRootProvider()

    override fun loadTrustState(): CatalogTrustState? = trustStateStore.load()

    override fun saveTrustState(state: CatalogTrustState) {
        trustStateStore.save(state)
    }

    override fun revocationLedger(): RevocationLedger = ledger

    override fun verifySignatures(envelope: CatalogMetadataEnvelope): Boolean =
        verifyManifest(envelope)

    override fun isInstalledRevisionSignatureOk(modelRevisionIdHex: String): Boolean =
        trustStateStore.load() != null && installedSignatureOk(modelRevisionIdHex)

    override fun isInstalledRevisionRevoked(modelRevisionIdHex: String, nowEpochMs: Long): Boolean =
        ledger.isRevoked(RevocationTargetKind.MODEL_REVISION, modelRevisionIdHex, nowEpochMs)
}

/**
 * Durable trust-state boundary for [ProductionSupplyChainHooks].
 * In-memory default matches scaffold semantics; SQLite persistence of
 * [CatalogTrustState] is a documented follow-up (SEC-SUPPLY §4 anti-rollback
 * across process restarts would need the durable variant).
 */
interface TrustStateStore {
    fun load(): CatalogTrustState?
    fun save(state: CatalogTrustState)
}

class InMemoryTrustStateStore : TrustStateStore {
    private var state: CatalogTrustState? = null
    override fun load(): CatalogTrustState? = state
    override fun save(state: CatalogTrustState) {
        this.state = state
    }
}
