package com.omnillm.runtime

import com.omnillm.runtime.policy.InMemoryRevocationEpochStore
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.policy.RevocationEpochManager
import com.omnillm.runtime.policy.RevocationEpochStore
import com.omnillm.runtime.policy.RevocationFenceHooks
import com.omnillm.runtime.policy.acl.AccessControlEnforcer
import com.omnillm.runtime.policy.download.DownloadTransferLimits
import com.omnillm.runtime.policy.download.DownloadUrlPolicy
import com.omnillm.runtime.policy.download.ResolvedAddressPolicy
import com.omnillm.runtime.policy.input.HeaderLimits
import com.omnillm.runtime.policy.input.JsonParseLimits
import com.omnillm.runtime.policy.input.OutputAbuseLimits
import com.omnillm.runtime.policy.security.AccessTokenStore
import com.omnillm.runtime.policy.security.InMemoryAccessTokenStore
import com.omnillm.runtime.policy.security.InMemoryPairingChallengeStore
import com.omnillm.runtime.policy.security.InMemorySecretBroker
import com.omnillm.runtime.policy.security.PairingChallengeService
import com.omnillm.runtime.policy.security.PairingChallengeStore
import com.omnillm.runtime.policy.security.SecretBroker
import com.omnillm.runtime.policy.security.TokenService

/**
 * Module entry for `:runtime:policy` (DATA-CONFIG, SEC-AUTH-NET, SEC-PROFILE, SEC-INPUT).
 *
 * - Settings merge (value-source axis vs hard-constraint axis)
 * - Secret Broker (token / pairing crypto, one-time plaintext)
 * - Token service (HMAC verifier only, TOKEN FSM)
 * - Pairing challenge service (TTL, attempts, channel binding)
 * - Principal / ACL enforcement + revocation epoch fence
 * - JSON / header / output abuse floors and download URL/DNS deny policies
 *
 * Production control plane injects durable stores + Keystore/encrypted vault broker
 * via [createSecurityStack]; defaults remain in-memory for pure unit tests only.
 */
object PolicyModule {
    const val MODULE_PATH: String = ":runtime:policy"

    /** Catalog machine id for revocation FSM. */
    const val REVOCATION_MACHINE_ID: String = "REVOCATION"

    /** Catalog machine id for token FSM. */
    const val TOKEN_MACHINE_ID: String = "TOKEN"

    /** Catalog machine id for pairing challenge FSM. */
    const val PAIRING_CHALLENGE_MACHINE_ID: String = "PAIRING_CHALLENGE"

    fun createManager(
        fenceHooks: RevocationFenceHooks = RevocationFenceHooks.NOOP,
        epochStore: RevocationEpochStore = InMemoryRevocationEpochStore(),
    ): PolicyManager =
        PolicyManager(
            revocation = RevocationEpochManager(hooks = fenceHooks, store = epochStore),
        )

    /**
     * Full security stack for the control plane (ADR-010 single writer).
     * Shares one [RevocationEpochManager] across token / ACL / policy.
     *
     * @param broker production: Android Keystore / encrypted-blob [SecretBroker];
     *   tests: [InMemorySecretBroker]
     * @param accessTokenStore production: SQLDelight HMAC-verifier store
     * @param pairingStore production: SQLDelight pairing challenge store
     * @param epochStore production: SQLDelight revocation epoch store
     */
    fun createSecurityStack(
        fenceHooks: RevocationFenceHooks = RevocationFenceHooks.NOOP,
        clockMs: () -> Long = { System.currentTimeMillis() },
        broker: SecretBroker = InMemorySecretBroker(clockMs),
        accessTokenStore: AccessTokenStore = InMemoryAccessTokenStore(),
        pairingStore: PairingChallengeStore = InMemoryPairingChallengeStore(),
        epochStore: RevocationEpochStore = InMemoryRevocationEpochStore(),
    ): SecurityStack {
        val revocation = RevocationEpochManager(
            hooks = fenceHooks,
            clock = clockMs,
            store = epochStore,
        )
        val policy = PolicyManager(revocation = revocation, clock = clockMs)
        val tokens = TokenService(
            broker = broker,
            revocation = revocation,
            store = accessTokenStore,
            clockMs = clockMs,
        )
        val pairing = PairingChallengeService(
            broker = broker,
            tokenService = tokens,
            store = pairingStore,
            clockMs = clockMs,
        )
        val acl = AccessControlEnforcer(revocation = revocation)
        return SecurityStack(
            policyManager = policy,
            secretBroker = broker,
            tokenService = tokens,
            pairingChallenges = pairing,
            accessControl = acl,
            revocation = revocation,
        )
    }

    data class SecurityStack(
        val policyManager: PolicyManager,
        val secretBroker: SecretBroker,
        val tokenService: TokenService,
        val pairingChallenges: PairingChallengeService,
        val accessControl: AccessControlEnforcer,
        val revocation: RevocationEpochManager,
    )

    /** Default JSON bomb-protection floors (SEC-INPUT §1). */
    fun defaultJsonParseLimits(): JsonParseLimits = JsonParseLimits.DEFAULT

    /** Default HTTP header budgets (SEC-INPUT §2). */
    fun defaultHeaderLimits(): HeaderLimits = HeaderLimits.DEFAULT

    /** Default generation / SSE backpressure floors (SEC-INPUT §7). */
    fun defaultOutputAbuseLimits(): OutputAbuseLimits = OutputAbuseLimits.DEFAULT

    /** Default download URL policy (HTTPS-only, no userinfo) (SEC-INPUT §3). */
    fun defaultDownloadUrlPolicy(): DownloadUrlPolicy.Policy = DownloadUrlPolicy.Policy.DEFAULT

    /** Default DNS / resolved-IP deny policy (SEC-INPUT §3, SEC-003). */
    fun defaultResolvedAddressPolicy(): ResolvedAddressPolicy.Policy =
        ResolvedAddressPolicy.Policy.DEFAULT

    fun defaultDownloadTransferLimits(): DownloadTransferLimits = DownloadTransferLimits.DEFAULT
}
