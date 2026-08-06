package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.domain.InstallationSnapshot
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadReverifyPort
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.PrivilegedReverifyRequest

/**
 * Privileged load gate (CORE-MODEL §6, SEC-PLACEMENT §4, INV-010).
 *
 * Every privileged load re-verifies actual read-only FD content identity,
 * manifest signature/root chain, revocation, template/tokenizer epoch, and
 * installation state. A stale READY DB flag is never sufficient.
 */
class PrivilegedLoadGate(
    private val installations: InstallationRepository,
    private val reverify: PrivilegedLoadReverifyPort,
) {

    /**
     * Issue a one-shot ticket for [commitLoad]. Fails closed on any check failure.
     */
    suspend fun issueTicket(
        installationId: InstallationId,
        engineBuildId: String,
        revocationEpoch: Long,
    ): OmniResult<PrivilegedLoadTicket> {
        val install = installations.get(installationId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "installation not found for privileged load",
                    details = mapOf("installationId" to installationId.value),
                ),
            )

        if (!install.allowsNewLoad()) {
            return if (install.state == "DRAINING" || install.state == "REVOKED") {
                OmniResult.err(
                    OmniError.MODEL_REVOKED(
                        message = "installation not loadable",
                        details = mapOf("state" to install.state),
                    ),
                )
            } else {
                OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "installation not READY",
                        details = mapOf("state" to install.state),
                    ),
                )
            }
        }

        val storageRoot = install.storageRootKey
            ?: return OmniResult.err(
                OmniError.INTERNAL(message = "READY installation missing storageRootKey"),
            )

        val eval = install.evaluation
        if (eval != null && eval.placementClass == PlacementClassLabels.TRUST_PLACEMENT_REQUIRED) {
            return OmniResult.err(
                OmniError.TRUST_PLACEMENT_REQUIRED(
                    message = "placement class forbids load",
                    details = mapOf("placementClass" to eval.placementClass),
                ),
            )
        }

        val request = PrivilegedReverifyRequest(
            installationId = installationId,
            modelRevisionId = install.modelRevisionId,
            storageRootKey = storageRoot,
            templateEpoch = install.templateEpoch,
            tokenizerEpoch = install.tokenizerEpoch,
            engineBuildId = engineBuildId,
            revocationEpoch = revocationEpoch,
        )

        return when (val ticket = reverify.reverify(request)) {
            is OmniResult.Ok -> validateTicket(ticket.value, install)
            is OmniResult.Err -> ticket
        }
    }

    private fun validateTicket(
        ticket: PrivilegedLoadTicket,
        install: InstallationSnapshot,
    ): OmniResult<PrivilegedLoadTicket> {
        if (!ticket.allOk) {
            return when {
                !ticket.revocationOk ->
                    OmniResult.err(
                        OmniError.MODEL_REVOKED(
                            message = "revocation check failed on privileged re-verify",
                            details = mapOf("ticketId" to ticket.ticketId),
                        ),
                    )
                !ticket.contentIdentityOk ->
                    OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "content identity re-verify failed",
                            details = mapOf("ticketId" to ticket.ticketId),
                        ),
                    )
                !ticket.signatureChainOk ->
                    OmniResult.err(
                        OmniError.MODEL_REVOKED(
                            message = "manifest signature/root chain failed",
                            details = mapOf("ticketId" to ticket.ticketId),
                        ),
                    )
                !ticket.installationStateOk ->
                    OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "installation state check failed at re-verify",
                            details = mapOf(
                                "ticketId" to ticket.ticketId,
                                "state" to install.state,
                            ),
                        ),
                    )
                !ticket.epochsOk ->
                    OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = "template/tokenizer epoch mismatch",
                            details = mapOf("ticketId" to ticket.ticketId),
                        ),
                    )
                else ->
                    OmniResult.err(
                        OmniError.INTERNAL(message = "privileged re-verify failed"),
                    )
            }
        }
        if (ticket.placementClass == PlacementClassLabels.TRUST_PLACEMENT_REQUIRED) {
            return OmniResult.err(
                OmniError.TRUST_PLACEMENT_REQUIRED(
                    message = "re-verify placement fail-closed",
                    details = mapOf("ticketId" to ticket.ticketId),
                ),
            )
        }
        return OmniResult.ok(ticket)
    }
}
