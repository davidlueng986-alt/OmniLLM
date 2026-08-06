package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.data.modelstore.ReadyContentPort
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.PrivilegedReverifyRequest
import java.util.UUID
import com.omnillm.core.identity.InstallationId as IdentityInstallationId

/**
 * Production privileged load re-verify (INV-010 / SEC-PLACEMENT section 4).
 *
 * Every ticket requires:
 * 1. Open actual read-only content FDs for the installation storage root
 * 2. Re-hash content identity on those FDs (never DB READY alone)
 * 3. Signature/root chain check via [signatureChainOk]
 * 4. Revocation check via [revocationOk]
 * 5. Template/tokenizer epoch non-negative on request (install epoch match is gate-side)
 *
 * Fail-closed: any missing hook or failed check yields a non-ok ticket or error.
 * Dry-load / benchmark evidence is never consulted (INV-008).
 */
class DefaultPrivilegedLoadReverify(
    private val readyContent: ReadyContentPort,
    /**
     * Manifest signature / catalog root chain for this installation.
     * Must fail closed (return false) when roots/assertions are unavailable.
     */
    private val signatureChainOk: suspend (PrivilegedReverifyRequest) -> Boolean,
    /** Active revocation ledger / trust epoch fence. */
    private val revocationOk: suspend (PrivilegedReverifyRequest) -> Boolean,
    /**
     * Placement class after independent authenticity evaluation.
     * Must not be promoted by compatibility/performance (INV-008).
     */
    private val placementClassFor: suspend (PrivilegedReverifyRequest) -> String,
    private val clockMonotonic: () -> Long = { System.nanoTime() },
    private val ticketTtlNs: Long = DEFAULT_TICKET_TTL_NS,
) : PrivilegedLoadReverifyPort {

    override suspend fun reverify(request: PrivilegedReverifyRequest): OmniResult<PrivilegedLoadTicket> {
        val issued = clockMonotonic()
        val expiry = issued + ticketTtlNs

        // 1–2 Content identity on actual FDs
        val open = readyContent.openReadOnly(
            installationId = IdentityInstallationId.ofValidated(request.installationId.value),
            storageRootKey = request.storageRootKey,
        )
        val fds = when (open) {
            is OmniResult.Ok -> open.value
            is OmniResult.Err -> {
                return OmniResult.ok(
                    failTicket(
                        request = request,
                        contentIdentityOk = false,
                        signatureChainOk = false,
                        revocationOk = false,
                        installationStateOk = false,
                        epochsOk = false,
                        issued = issued,
                        expiry = expiry,
                        placement = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
                    ),
                )
            }
        }

        val identity = when (val v = readyContent.verifyOpenFds(fds)) {
            is OmniResult.Ok -> v.value
            is OmniResult.Err -> {
                readyContent.closeFds(fds)
                return OmniResult.err(v.error)
            }
        }
        // Optional expected digest map (when provided) must match computed.
        var contentOk = identity.ok
        if (contentOk && request.expectedContentDigests.isNotEmpty()) {
            for ((role, expected) in request.expectedContentDigests) {
                val computed = identity.computedDigests[role]
                if (computed == null || computed.hex != expected.hex) {
                    contentOk = false
                    break
                }
            }
        }

        // 3 Signature / root chain
        val sigOk = try {
            signatureChainOk(request)
        } catch (_: Exception) {
            false
        }

        // 4 Revocation
        val revOk = try {
            revocationOk(request)
        } catch (_: Exception) {
            false
        }

        // 5 Epochs are carried on the request; mismatch is checked by installation
        // repository before issueTicket. Here we only require non-negative (init).
        val epochsOk = request.templateEpoch >= 0L && request.tokenizerEpoch >= 0L

        // Installation state was pre-checked by PrivilegedLoadGate; re-open success
        // is treated as state-ok for the ticket. Gate still validates READY.
        val installationStateOk = contentOk

        val placement = try {
            val p = placementClassFor(request)
            if (PlacementClassLabels.isKnown(p)) p else PlacementClassLabels.TRUST_PLACEMENT_REQUIRED
        } catch (_: Exception) {
            PlacementClassLabels.TRUST_PLACEMENT_REQUIRED
        }

        readyContent.closeFds(fds)

        // If content/sig/revocation fail, placement must not be privileged trusted.
        val effectivePlacement =
            if (contentOk && sigOk && revOk && installationStateOk && epochsOk) {
                placement
            } else if (!contentOk || !sigOk || !revOk) {
                // Fail closed on security checks — never executable privileged path.
                PlacementClassLabels.TRUST_PLACEMENT_REQUIRED
            } else {
                placement
            }

        return OmniResult.ok(
            PrivilegedLoadTicket(
                ticketId = UUID.randomUUID().toString(),
                installationId = request.installationId,
                modelRevisionId = request.modelRevisionId,
                placementClass = effectivePlacement,
                contentIdentityOk = contentOk,
                signatureChainOk = sigOk,
                revocationOk = revOk,
                installationStateOk = installationStateOk,
                epochsOk = epochsOk,
                issuedMonotonic = issued,
                expiryMonotonic = expiry,
            ),
        )
    }

    private fun failTicket(
        request: PrivilegedReverifyRequest,
        contentIdentityOk: Boolean,
        signatureChainOk: Boolean,
        revocationOk: Boolean,
        installationStateOk: Boolean,
        epochsOk: Boolean,
        issued: Long,
        expiry: Long,
        placement: String,
    ): PrivilegedLoadTicket =
        PrivilegedLoadTicket(
            ticketId = UUID.randomUUID().toString(),
            installationId = request.installationId,
            modelRevisionId = request.modelRevisionId,
            placementClass = placement,
            contentIdentityOk = contentIdentityOk,
            signatureChainOk = signatureChainOk,
            revocationOk = revocationOk,
            installationStateOk = installationStateOk,
            epochsOk = epochsOk,
            issuedMonotonic = issued,
            expiryMonotonic = expiry,
        )

    companion object {
        /** 60s ticket TTL in nanoseconds (monotonic). */
        const val DEFAULT_TICKET_TTL_NS: Long = 60_000_000_000L

        /**
         * Fail-closed factory: signature and revocation hooks return false until
         * supply-chain roots and revocation ledgers are wired (SEC-SUPPLY).
         * Content identity still re-hashes actual FDs when [readyContent] is real.
         */
        fun failClosedUntilSupplyWired(readyContent: ReadyContentPort): DefaultPrivilegedLoadReverify =
            DefaultPrivilegedLoadReverify(
                readyContent = readyContent,
                signatureChainOk = { false },
                revocationOk = { false },
                placementClassFor = { PlacementClassLabels.TRUST_PLACEMENT_REQUIRED },
            )
    }
}
