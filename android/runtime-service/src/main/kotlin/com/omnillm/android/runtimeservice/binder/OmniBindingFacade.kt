package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.IOmniBinding
import ai.omnillm.api.IOmniRuntime
import ai.omnillm.api.OmniPairingChallenge
import ai.omnillm.api.OmniPairingRequest
import ai.omnillm.api.OmniPairingStatus
import android.content.Context
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.interfaces.aidl.AidlAuthority
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Exported [IOmniBinding] entry (ANDROID-SERVICE §1–2).
 *
 * Unregistered callers may only receive pairing challenges / minimal errors —
 * never models, metrics, or admin capability.
 *
 * Pairing states follow `specs/state-machines.yaml#PAIRING_CHALLENGE`:
 * PENDING | APPROVED | REJECTED | EXPIRED | CONSUMED.
 *
 * Principal binding: observed UID + [ClientRegistrationStore] (INV-011).
 */
class OmniBindingFacade(
    private val context: Context,
    private val registrations: ClientRegistrationStore = RuntimeControlPlane.get()?.registrations
        ?: ClientRegistrationStore(),
    private val sessions: StreamSessionRegistry = RuntimeControlPlane.get()?.streamSessions
        ?: StreamSessionRegistry(),
    private val assets: AssetHandleBroker? = RuntimeControlPlane.get()?.assetBroker,
) : IOmniBinding.Stub() {

    private data class Challenge(
        val challengeId: String,
        val principal: ObservedPrincipal,
        val requestedScopes: Array<String>,
        val expiresAtEpochMillis: Long,
        /** PAIRING_CHALLENGE catalog state. */
        var state: String,
        var registrationHandle: String? = null,
        var grantedScopes: Array<String> = emptyArray(),
    )

    private val challenges = ConcurrentHashMap<String, Challenge>()

    override fun getProtocolMajor(): Int = AidlAuthority.SCHEMA_VERSION

    override fun getProtocolMinor(): Int = 0

    override fun beginPairing(request: OmniPairingRequest?): OmniPairingChallenge {
        val principal = PrincipalObservation.observe(context)
        val challengeId = UUID.randomUUID().toString()
        val scopes = request?.requestedScopes ?: emptyArray()
        val expires = System.currentTimeMillis() + CHALLENGE_TTL_MS

        // Scaffold: same-app principal is locally approvable and immediately consumable.
        // Third-party stays PENDING until Admin approval (TODO).
        val challenge = if (principal.isSameAppUid) {
            val reg = registrations.register(
                principal = principal,
                scopes = scopes.toList(),
                displayName = request?.clientDisplayName,
            )
            Challenge(
                challengeId = challengeId,
                principal = principal,
                requestedScopes = scopes,
                expiresAtEpochMillis = expires,
                state = "CONSUMED",
                registrationHandle = reg.registrationHandle,
                grantedScopes = reg.grantedScopes.toTypedArray(),
            )
        } else {
            Challenge(
                challengeId = challengeId,
                principal = principal,
                requestedScopes = scopes,
                expiresAtEpochMillis = expires,
                state = "PENDING",
            )
        }
        challenges[challengeId] = challenge

        return OmniPairingChallenge().apply {
            this.challengeId = challengeId
            this.observedPrincipalSummary = PrincipalObservation.principalSummary(principal)
            this.requestedScopes = scopes
            // Surface APPROVED when handle already issued for UX; durable challenge is CONSUMED.
            this.state = if (challenge.registrationHandle != null) "APPROVED" else challenge.state
            this.expiresAtEpochMillis = expires
        }
    }

    override fun queryPairing(challengeId: String?): OmniPairingStatus {
        val status = OmniPairingStatus()
        val id = challengeId.orEmpty()
        status.challengeId = id
        val challenge = challenges[id]
        if (challenge == null) {
            status.state = "EXPIRED"
            status.registrationHandle = null
            status.grantedScopes = emptyArray()
            status.expiresAtEpochMillis = 0L
            status.error = BinderErrors.notFound("pairing challenge not found")
            return status
        }
        if (System.currentTimeMillis() > challenge.expiresAtEpochMillis &&
            challenge.state == "PENDING"
        ) {
            challenge.state = "EXPIRED"
        }
        status.state = challenge.state
        status.registrationHandle = challenge.registrationHandle
        status.grantedScopes = challenge.grantedScopes
        status.expiresAtEpochMillis = challenge.expiresAtEpochMillis
        status.error = null
        return status
    }

    override fun openRuntime(registrationHandle: String?): IOmniRuntime? {
        val principal = PrincipalObservation.observe(context)
        val handle = registrationHandle.orEmpty()
        if (handle.isBlank()) {
            // Unregistered: no runtime surface (PAIRING_REQUIRED semantics).
            return null
        }
        val reg = registrations.resolveActive(handle, principal) ?: return null
        val broker = assets
            ?: RuntimeControlPlane.get()?.assetBroker
            ?: return null
        return OmniRuntimeFacade(
            context = context,
            registration = reg,
            registrations = registrations,
            sessions = sessions,
            assets = broker,
        )
    }

    companion object {
        private const val CHALLENGE_TTL_MS: Long = 5 * 60 * 1000L
    }
}
