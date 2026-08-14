package com.omnillm.runtime.modelmanager.supply

/**
 * Model supply-chain hook surface (SEC-SUPPLY).
 *
 * Control plane hosts wire concrete signature verify, catalog fetch, and
 * persistence. Feature packs / workers never write catalog trust state
 * (ADR-010).
 *
 * Default [NoopSupplyChainHooks] is for unit tests and early scaffolding —
 * production runtime-service must inject embedded root + durable state.
 */
interface SupplyChainHooks {
    /** APK-embedded root bytes (full metadata, not URL-only). */
    fun embeddedRoot(): EmbeddedCatalogRoot?

    /** Load durable trust state for this install lifecycle, if any. */
    fun loadTrustState(): CatalogTrustState?

    /** Persist trust state (control plane single writer only). */
    fun saveTrustState(state: CatalogTrustState)

    /** Active revocation ledger snapshot. */
    fun revocationLedger(): RevocationLedger

    /**
     * Verify signatures over [envelope.canonicalBytes] against role keys.
     * Implementations fail closed on unknown algorithms (SEC-PROFILE).
     */
    fun verifySignatures(envelope: CatalogMetadataEnvelope): Boolean

    /**
     * Privileged-load gate (SEC-PLACEMENT §4): is the installed revision's
     * manifest signature / root chain acceptable under the current catalog
     * trust state? Must fail closed (false) while no trust state is
     * bootstrapped (no embedded root provisioned).
     */
    fun isInstalledRevisionSignatureOk(modelRevisionIdHex: String): Boolean

    /**
     * Privileged-load gate: is this revision revoked by the current ledger?
     * False = not revoked (a genuinely empty ledger revokes nothing).
     */
    fun isInstalledRevisionRevoked(modelRevisionIdHex: String, nowEpochMs: Long): Boolean
}

/**
 * Scaffold hooks: no embedded root, empty revocation, verify always false.
 * Fail closed for any privileged catalog action until wired.
 */
class NoopSupplyChainHooks : SupplyChainHooks {
    private val ledger = RevocationLedger()
    private var state: CatalogTrustState? = null

    override fun embeddedRoot(): EmbeddedCatalogRoot? = null
    override fun loadTrustState(): CatalogTrustState? = state
    override fun saveTrustState(state: CatalogTrustState) {
        this.state = state
    }
    override fun revocationLedger(): RevocationLedger = ledger
    override fun verifySignatures(envelope: CatalogMetadataEnvelope): Boolean = false
    override fun isInstalledRevisionSignatureOk(modelRevisionIdHex: String): Boolean = false
    override fun isInstalledRevisionRevoked(modelRevisionIdHex: String, nowEpochMs: Long): Boolean =
        ledger.isRevoked(RevocationTargetKind.MODEL_REVISION, modelRevisionIdHex, nowEpochMs)
}

/**
 * In-memory hooks with a provided embedded root (tests + offline bootstrap).
 *
 * The provided root IS the trust: [installedRevisionSignatureOk] defaults to
 * accepting the revision (overridable) and revocation consults the ledger
 * (plus an overridable extra check).
 */
class InMemorySupplyChainHooks(
    private val root: EmbeddedCatalogRoot,
    private val verify: (CatalogMetadataEnvelope) -> Boolean = { false },
    private val installedRevisionSignatureOk: (String) -> Boolean = { true },
    private val installedRevisionRevoked: (String, Long) -> Boolean = { _, _ -> false },
) : SupplyChainHooks {
    private val ledger = RevocationLedger()
    private var state: CatalogTrustState? = null

    override fun embeddedRoot(): EmbeddedCatalogRoot = root
    override fun loadTrustState(): CatalogTrustState? = state
    override fun saveTrustState(state: CatalogTrustState) {
        this.state = state
    }
    override fun revocationLedger(): RevocationLedger = ledger
    override fun verifySignatures(envelope: CatalogMetadataEnvelope): Boolean = verify(envelope)
    override fun isInstalledRevisionSignatureOk(modelRevisionIdHex: String): Boolean =
        installedRevisionSignatureOk(modelRevisionIdHex)
    override fun isInstalledRevisionRevoked(modelRevisionIdHex: String, nowEpochMs: Long): Boolean =
        ledger.isRevoked(RevocationTargetKind.MODEL_REVISION, modelRevisionIdHex, nowEpochMs) ||
            installedRevisionRevoked(modelRevisionIdHex, nowEpochMs)
}

/**
 * High-level bootstrap orchestration used by runtime control plane.
 */
class SupplyChainBootstrapService(
    private val hooks: SupplyChainHooks,
    private val clockEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Ensure catalog trust state exists from embedded root when possible.
     */
    fun ensureBootstrapped(): CatalogRootBootstrap.Outcome {
        hooks.loadTrustState()?.let {
            return CatalogRootBootstrap.Outcome.Accepted(it)
        }
        val embedded = hooks.embeddedRoot()
            ?: return CatalogRootBootstrap.Outcome.Rejected(
                error = com.omnillm.core.errors.generated.OmniError.INTERNAL(
                    message = "no embedded catalog root configured",
                ),
                reason = "missing embedded root",
            )
        return when (val r = CatalogRootBootstrap.bootstrapFromEmbedded(embedded, clockEpochMs())) {
            is CatalogRootBootstrap.Outcome.Accepted -> {
                hooks.saveTrustState(r.state)
                r
            }
            is CatalogRootBootstrap.Outcome.Rejected -> r
        }
    }
}
