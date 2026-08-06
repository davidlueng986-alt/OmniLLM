package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.android.runtimeservice.http.ControlPlaneLanTlsEndpoint
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.lan.LanFeatureModule
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanCommandIdentity
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.policy.security.CryptoPrimitives
import com.omnillm.runtime.policy.security.InMemorySecretBroker
import com.omnillm.runtime.policy.security.LanPairingTranscript
import com.omnillm.runtime.policy.security.SecurityProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Control-plane LAN host: default-off, TLS identity, pairing attempt limits, revoke.
 */
class ControlPlaneLanHostTest {

    private var now = 1_700_000_000_000L

    private fun stack() = PolicyModule.createSecurityStack(clockMs = { now })

    private fun host(
        stack: PolicyModule.SecurityStack = stack(),
        bindNetwork: Boolean = false,
    ): ControlPlaneLanHost {
        val tls = ControlPlaneLanTlsEndpoint(
            secretBroker = stack.secretBroker,
            handlerProvider = { null },
            authenticatorProvider = { null },
            clockMs = { now },
        )
        return ControlPlaneLanHost(
            policyManager = stack.policyManager,
            pairingService = stack.pairingChallenges,
            tokenService = stack.tokenService,
            tlsEndpoint = tls,
            bindNetworkOnEnable = bindNetwork,
            clockMs = { now },
        )
    }

    private fun cmd(s: String) = LanCommandIdentity(
        commandId = UUID.randomUUID().toString(),
        idempotencyKey = "idem-$s",
    )

    @Test
    fun defaultOff_pairingRejected() = runBlocking {
        val h = host()
        assertTrue(h.isDefaultOff())
        val st = h.status() as OmniResult.Ok
        assertFalse(st.value.enabled)
        assertEquals("DISABLED", st.value.state)
        val ch = h.createChallenge(
            LocalUiPrincipal.ID,
            CreatePairingChallengeSpec(
                command = cmd("off"),
                challengeId = UUID.randomUUID().toString(),
            ),
        )
        assertTrue(ch is OmniResult.Err)
    }

    @Test
    fun enable_withTlsIdentity_becomesActive_noNetworkBind() = runBlocking {
        val h = host(bindNetwork = false)
        val en = h.enable(LocalUiPrincipal.ID, EnableLanSpec(command = cmd("en"))) as OmniResult.Ok
        assertTrue(en.value.enabled)
        assertTrue(en.value.tlsReady)
        assertTrue(en.value.pairingEndpointReady)
        assertTrue(en.value.certificateValid)
        assertNotNull(en.value.serverSpkiSha256)
        assertEquals(64, en.value.serverSpkiSha256!!.length)
        assertTrue(en.value.mayAcceptClients)
        assertEquals("ACTIVE", en.value.state)
    }

    @Test
    fun pairingFailLimits_exhaustsAttempts() = runBlocking {
        val stack = stack()
        val h = host(stack, bindNetwork = false)
        h.enable(LocalUiPrincipal.ID, EnableLanSpec(command = cmd("en"))) as OmniResult.Ok

        val created = h.createChallenge(
            LocalUiPrincipal.ID,
            CreatePairingChallengeSpec(
                command = cmd("cr"),
                challengeId = UUID.randomUUID().toString(),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        ) as OmniResult.Ok
        val challengeId = created.value.challengeId
        val secret = created.value.pairingSecret!!
        assertEquals(SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS, created.value.attemptsRemaining)

        h.approveChallenge(
            LocalUiPrincipal.ID,
            ApprovePairingChallengeSpec(
                command = cmd("ap"),
                challengeId = challengeId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )

        val st = (h.status() as OmniResult.Ok).value
        // Wrong SPKI / bad proof burns attempts via PairingChallengeService.
        repeat(SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS) {
            val fail = h.completeExchange(
                LocalUiPrincipal.ID,
                CompletePairingExchangeSpec(
                    command = cmd("ex-bad"),
                    exchangeId = UUID.randomUUID().toString(),
                    challengeId = challengeId,
                    clientPublicKey = "client-public-key-material-32chars-min",
                    requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                    proofBase64Url = "AAAA" + "B".repeat(40),
                    observedSpkiSha256 = st.serverSpkiSha256!!,
                    observedConnectionEpoch = st.connectionEpoch,
                ),
            )
            assertTrue(fail is OmniResult.Err)
        }

        // Further exchange rejected after budget exhausted.
        val after = h.completeExchange(
            LocalUiPrincipal.ID,
            CompletePairingExchangeSpec(
                command = cmd("ex-final"),
                exchangeId = UUID.randomUUID().toString(),
                challengeId = challengeId,
                clientPublicKey = "client-public-key-material-32chars-min",
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                proofBase64Url = "C".repeat(43),
                observedSpkiSha256 = st.serverSpkiSha256!!,
                observedConnectionEpoch = st.connectionEpoch,
            ),
        ) as OmniResult.Err
        assertTrue(
            after.error.code == OmniErrorCode.FORBIDDEN ||
                after.error.code == OmniErrorCode.UNAUTHORIZED,
        )

        // Secret must not have been a long-lived bearer shape in QR.
        assertFalse(created.value.qrPayload!!.contains("bearer_token"))
        assertFalse(created.value.qrPayload!!.contains("token_plaintext"))
        assertTrue(secret.length >= 32)
    }

    @Test
    fun pairingSuccess_thenRevoke_tokenFenced() = runBlocking {
        val stack = stack()
        val h = host(stack, bindNetwork = false)
        h.enable(LocalUiPrincipal.ID, EnableLanSpec(command = cmd("en"))) as OmniResult.Ok

        val created = h.createChallenge(
            LocalUiPrincipal.ID,
            CreatePairingChallengeSpec(
                command = cmd("cr"),
                challengeId = UUID.randomUUID().toString(),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        ) as OmniResult.Ok
        val challengeId = created.value.challengeId
        val secretB64 = created.value.pairingSecret!!
        val secret = CryptoPrimitives.decodeBase64Url(secretB64)!!

        h.approveChallenge(
            LocalUiPrincipal.ID,
            ApprovePairingChallengeSpec(
                command = cmd("ap"),
                challengeId = challengeId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )

        val rec = stack.pairingChallenges.get(challengeId)!!
        val clientPublicKey = "client-public-key-material-32chars-min"
        val transcript = LanPairingTranscript(
            serverSpkiSha256 = rec.serverSpkiSha256!!,
            connectionEpoch = rec.connectionEpoch!!,
            challengeId = challengeId,
            serverNonce = rec.serverNonceBase64Url!!,
            clientPublicKey = clientPublicKey,
            requestedScopes = rec.requestedScopes,
            issuedAtEpochMs = now, // create used clock `now`
            expiresAtEpochMs = rec.expiresAtEpochMs,
        )
        // PairingChallengeService builds transcript from record.createdAtEpochMs.
        val proofTranscript = LanPairingTranscript(
            serverSpkiSha256 = rec.serverSpkiSha256!!,
            connectionEpoch = rec.connectionEpoch!!,
            challengeId = challengeId,
            serverNonce = rec.serverNonceBase64Url!!,
            clientPublicKey = clientPublicKey,
            requestedScopes = rec.requestedScopes,
            issuedAtEpochMs = rec.let {
                // Use store record created time via re-create path: create was at `now`.
                now
            },
            expiresAtEpochMs = rec.expiresAtEpochMs,
        )
        // completeLanExchange uses record.createdAtEpochMs — must match create time.
        val proof = stack.secretBroker.computeLanPairingProof(proofTranscript, secret) as OmniResult.Ok
        val proofB64 = CryptoPrimitives.encodeBase64Url(proof.value)

        val receipt = h.completeExchange(
            LocalUiPrincipal.ID,
            CompletePairingExchangeSpec(
                command = cmd("ex"),
                exchangeId = UUID.randomUUID().toString(),
                challengeId = challengeId,
                clientPublicKey = clientPublicKey,
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
                proofBase64Url = proofB64,
                observedSpkiSha256 = rec.serverSpkiSha256!!,
                observedConnectionEpoch = rec.connectionEpoch!!,
            ),
        )
        assertTrue(
            "exchange failed: ${(receipt as? OmniResult.Err)?.error}",
            receipt is OmniResult.Ok,
        )
        val tok = (receipt as OmniResult.Ok).value
        assertFalse(tok.loopbackOnly)
        assertTrue(tok.tokenPlaintext.isNotBlank())

        val clients = h.listClients(LocalUiPrincipal.ID) as OmniResult.Ok
        assertEquals(1, clients.value.size)
        val clientId = clients.value.first().clientId

        val revoked = h.revoke(
            LocalUiPrincipal.ID,
            RevokeLanClientSpec(command = cmd("rv"), clientId = clientId),
        ) as OmniResult.Ok
        assertEquals("REVOKED", revoked.value.state)
        assertTrue(revoked.value.revocationEpoch >= 1L)

        val auth = stack.tokenService.authenticate(
            tok.tokenPlaintext,
            com.omnillm.runtime.policy.security.TokenService.TransportConstraint.LAN_ONLY,
            now,
        )
        assertTrue(auth is OmniResult.Err)
    }

    @Test
    fun disable_bumpsConnectionEpoch() = runBlocking {
        val h = host(bindNetwork = false)
        val en = h.enable(LocalUiPrincipal.ID, EnableLanSpec(command = cmd("en"))) as OmniResult.Ok
        val before = en.value.connectionEpoch
        val dis = h.disable(LocalUiPrincipal.ID, DisableLanSpec(command = cmd("dis"))) as OmniResult.Ok
        assertEquals("DISABLED", dis.value.state)
        assertTrue(dis.value.connectionEpoch > before)
        assertFalse(dis.value.enabled)
        assertFalse(dis.value.tlsReady)
    }

    @Test
    fun moduleConstants_matchSecurityProfile() {
        assertEquals(SecurityProfile.LAN_PAIRING_TTL_SECONDS, LanFeatureModule.PAIRING_TTL_SECONDS)
        assertEquals(SecurityProfile.LAN_PAIRING_MAX_ATTEMPTS, LanFeatureModule.PAIRING_MAX_ATTEMPTS)
        assertEquals(SecurityProfile.LAN_PAIRING_PROTOCOL_LABEL, LanFeatureModule.PAIRING_PROTOCOL_LABEL)
    }
}
