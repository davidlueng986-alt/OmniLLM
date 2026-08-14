package com.omnillm.android.runtimeservice.transport

import com.omnillm.android.runtimeservice.featurehost.FeaturePackHost
import com.omnillm.android.runtimeservice.http.ControlPlaneHttpHandler
import com.omnillm.android.runtimeservice.http.ControlPlaneLanTlsEndpoint
import com.omnillm.android.runtimeservice.http.LoopbackTokenService
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.ports.security.TransportConstraint
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.LanCommandIdentity
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.JsonRawBody
import com.omnillm.interfaces.http.LanEnableRequestDto
import com.omnillm.interfaces.http.LanPairingChallengeCreateRequestDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.policy.security.CryptoPrimitives
import com.omnillm.runtime.policy.security.LanPairingTranscript
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D14b: API-10 full LAN pairing journey through [ControlPlaneHttpHandler] with
 * the REAL control-plane LAN host + TLS identity (identity-only endpoint,
 * no socket bind) + durable PairingChallengeService / TokenService:
 *
 *   enable -> create challenge -> local approval -> complete exchange -> token issued
 *
 * The approval step is intentionally driven through the LAN pairing port:
 * `lanPairing.requiresLocalApproval` means approval is a LOCAL_UI action with no
 * HTTP route (spec SEC-PROFILE) — the same port the UI/AIDL facade uses.
 * Previous tests only covered the wire shape (WireLanPairingContractTest) and
 * the fail-closed exchange (LaunchCriticalHttpSurfaceTest).
 */
class LanPairingFullFlowHttpSurfaceTest {

    private var now = 1_700_000_000_000L

    private val digest64 = "a".repeat(64)
    private val principal = HttpPrincipal(
        principalId = "lan-admin",
        tokenId = "tok-lan-admin",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun cmd(key: String): CommandRequestDto =
        CommandRequestDto(
            commandId = uuid(),
            idempotencyKey = key,
            canonicalInputDigest = digest64,
        )

    private data class LanSurface(
        val handler: ControlPlaneHttpHandler,
        val stack: PolicyModule.SecurityStack,
        val host: FeaturePackHost,
    )

    private fun surface(): LanSurface {
        val jobs = JobManagerModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val clock: () -> Long = { now }
        val stack = PolicyModule.createSecurityStack(clockMs = clock)
        val tls = ControlPlaneLanTlsEndpoint(
            secretBroker = stack.secretBroker,
            handlerProvider = { null },
            authenticatorProvider = { null },
            clockMs = clock,
        )
        val host = FeaturePackHost.bootstrap(
            jobManager = jobs,
            policyManager = stack.policyManager,
            observability = observability,
            clockMs = clock,
            securityStack = stack,
            lanTlsEndpoint = tls,
            lanBindNetwork = false,
        )
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val h = ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            lanPorts = host.lanPorts,
        )
        return LanSurface(handler = h, stack = stack, host = host)
    }

    private fun parseObject(body: JsonRawBody): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(body.json).jsonObject

    @Test
    fun fullJourney_enableChallengeApproveExchange_issuesScopedLanToken() = runBlocking {
        val s = surface()
        val h = s.handler

        // 1. enableLan through the handler — ACTIVE with TLS identity.
        val enabled = h.enableLan(
            principal,
            LanEnableRequestDto(command = cmd("lan-enable")),
        )
        assertTrue("enable must succeed: $enabled", enabled is HttpHandlerResult.Ok)
        assertEquals("SUCCEEDED", (enabled as HttpHandlerResult.Ok).body.state)

        // 2. createLanPairingChallenge through the handler (API-10 wire contract).
        val challengeId = uuid()
        val created = h.createLanPairingChallenge(
            principal,
            LanPairingChallengeCreateRequestDto(
                command = cmd("lan-challenge"),
                challengeId = challengeId,
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES.toList(),
                clientDisplayHint = "living-room",
                ttlSeconds = 300,
            ),
        )
        assertTrue("challenge create must succeed: $created", created is HttpHandlerResult.Ok)
        val challenge = parseObject((created as HttpHandlerResult.Ok).body)
        val serverChallengeId = challenge["challenge_id"]!!.jsonPrimitive.content
        val pairingSecret = challenge["pairing_secret"]!!.jsonPrimitive.content
        assertEquals("OmniLLM-LAN-Pairing-1", challenge["protocol_label"]!!.jsonPrimitive.content)
        assertEquals(
            LanScopePolicy.DEFAULT_INFER_SCOPES.sorted(),
            challenge["requested_scopes"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
        )
        assertTrue("one-time pairing secret must be present", pairingSecret.isNotBlank())
        assertTrue("QR payload must be present", challenge["qr_payload"]!!.jsonPrimitive.content.isNotBlank())
        assertNotNull(challenge["expires_at"])

        // 3. Local approval (LOCAL_UI-only action; no HTTP route by design).
        val approved = s.host.lanPorts.pairing.approveChallenge(
            LocalUiPrincipal.ID,
            ApprovePairingChallengeSpec(
                command = LanCommandIdentity(uuid(), "lan-approve"),
                challengeId = serverChallengeId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )
        assertTrue("approval must succeed: $approved", approved is OmniResult.Ok)

        // 4. completeLanPairing through the handler with a REAL channel-bound proof.
        val clientPublicKey = "client-public-key-material-32chars-min"
        val rec = s.stack.pairingChallenges.get(serverChallengeId)!!
        val secret = CryptoPrimitives.decodeBase64Url(pairingSecret)!!
        val transcript = LanPairingTranscript(
            serverSpkiSha256 = rec.serverSpkiSha256!!,
            connectionEpoch = rec.connectionEpoch!!,
            challengeId = serverChallengeId,
            serverNonce = rec.serverNonceBase64Url!!,
            clientPublicKey = clientPublicKey,
            requestedScopes = rec.requestedScopes,
            // PairingChallengeService builds the verification transcript from
            // record.createdAtEpochMs; the fixed clock makes it equal `now`.
            issuedAtEpochMs = now,
            expiresAtEpochMs = rec.expiresAtEpochMs,
        )
        val proof = s.stack.secretBroker.computeLanPairingProof(transcript, secret) as OmniResult.Ok
        val proofB64 = CryptoPrimitives.encodeBase64Url(proof.value)
        val exchangeBody =
            """{"command":{"command_id":"${uuid()}","idempotency_key":"lan-exchange-1"},"exchange_id":"${uuid()}","challenge_id":"$serverChallengeId","client_public_key":"$clientPublicKey","requested_scopes":${LanScopePolicy.DEFAULT_INFER_SCOPES.toJson()},"proof_base64url":"$proofB64"}"""

        val completed = h.completeLanPairing(exchangeBody)
        assertTrue("exchange must succeed: $completed", completed is HttpHandlerResult.Ok)
        val token = parseObject((completed as HttpHandlerResult.Ok).body)
        assertEquals("SUCCEEDED", token["state"]!!.jsonPrimitive.content)
        val tokenPlaintext = token["token"]!!.jsonPrimitive.content
        val tokenId = token["token_id"]!!.jsonPrimitive.content
        assertEquals(
            LanScopePolicy.DEFAULT_INFER_SCOPES.sorted(),
            token["scopes"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(),
        )
        assertTrue(tokenPlaintext.isNotBlank())
        assertTrue(tokenId.isNotBlank())
        assertNotNull(token["expires_at"])

        // 5. The issued token authenticates on the LAN channel with the pinned scopes…
        val lanAuth = s.stack.tokenService.authenticate(tokenPlaintext, TransportConstraint.LAN_ONLY, now)
        assertTrue("LAN token must authenticate on LAN listener: $lanAuth", lanAuth is OmniResult.Ok)
        assertEquals(LanScopePolicy.DEFAULT_INFER_SCOPES, (lanAuth as OmniResult.Ok).value.scopes)

        // …and is rejected on the loopback listener (transport fence, SEC-AUTH-NET).
        val loopbackAuth = s.stack.tokenService.authenticate(tokenPlaintext, TransportConstraint.LOOPBACK_ONLY, now)
        assertTrue("LAN token must NOT authenticate on loopback: $loopbackAuth", loopbackAuth is OmniResult.Err)
        assertEquals(
            OmniErrorCode.FORBIDDEN,
            (loopbackAuth as OmniResult.Err).error.code,
        )

        // 6. One-time: replaying the consumed challenge fails closed.
        val replay = h.completeLanPairing(exchangeBody)
        assertTrue("replay must fail closed: $replay", replay is HttpHandlerResult.Err)
    }

    @Test
    fun createChallenge_wireValidationFailsClosed() = runBlocking {
        val h = surface().handler
        val good = LanPairingChallengeCreateRequestDto(
            command = cmd("lan-challenge-bad"),
            challengeId = uuid(),
            requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES.toList(),
        )
        val badUuid = h.createLanPairingChallenge(principal, good.copy(challengeId = "not-a-uuid"))
        assertTrue(badUuid is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (badUuid as HttpHandlerResult.Err).error.code)

        val emptyScopes = h.createLanPairingChallenge(principal, good.copy(requestedScopes = emptyList()))
        assertTrue(emptyScopes is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (emptyScopes as HttpHandlerResult.Err).error.code)

        val badTtl = h.createLanPairingChallenge(principal, good.copy(ttlSeconds = 60))
        assertTrue(badTtl is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (badTtl as HttpHandlerResult.Err).error.code)
    }

    @Test
    fun exchange_wrongProofBurnsAttemptAndFailsClosed() = runBlocking {
        val s = surface()
        val h = s.handler
        h.enableLan(principal, LanEnableRequestDto(command = cmd("lan-enable-2"))) as HttpHandlerResult.Ok

        val created = h.createLanPairingChallenge(
            principal,
            LanPairingChallengeCreateRequestDto(
                command = cmd("lan-challenge-2"),
                challengeId = uuid(),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES.toList(),
            ),
        ) as HttpHandlerResult.Ok
        val serverChallengeId = parseObject(created.body)["challenge_id"]!!.jsonPrimitive.content
        val rec = s.stack.pairingChallenges.get(serverChallengeId)!!

        s.host.lanPorts.pairing.approveChallenge(
            LocalUiPrincipal.ID,
            ApprovePairingChallengeSpec(
                command = LanCommandIdentity(uuid(), "lan-approve"),
                challengeId = serverChallengeId,
                approvedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            ),
        )

        val badBody =
            """{"command":{"command_id":"${uuid()}","idempotency_key":"lan-bad-proof"},"exchange_id":"${uuid()}","challenge_id":"$serverChallengeId","client_public_key":"client-public-key-material-32chars-min","requested_scopes":${LanScopePolicy.DEFAULT_INFER_SCOPES.toJson()},"proof_base64url":"${"B".repeat(43)}"}"""
        val failed = h.completeLanPairing(badBody)
        assertTrue("wrong proof must fail closed: $failed", failed is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (failed as HttpHandlerResult.Err).error.code)

        val after = s.stack.pairingChallenges.get(serverChallengeId)!!
        assertEquals(rec.attemptsRemaining - 1, after.attemptsRemaining)
    }

    @Test
    fun exchange_beforeApproval_failsClosed() = runBlocking {
        val h = surface().handler
        h.enableLan(principal, LanEnableRequestDto(command = cmd("lan-enable-3"))) as HttpHandlerResult.Ok
        val created = h.createLanPairingChallenge(
            principal,
            LanPairingChallengeCreateRequestDto(
                command = cmd("lan-challenge-3"),
                challengeId = uuid(),
                requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES.toList(),
            ),
        ) as HttpHandlerResult.Ok
        val serverChallengeId = parseObject(created.body)["challenge_id"]!!.jsonPrimitive.content

        // No approval — exchange must fail closed (requiresLocalApproval).
        val early =
            """{"command":{"command_id":"${uuid()}","idempotency_key":"lan-early"},"exchange_id":"${uuid()}","challenge_id":"$serverChallengeId","client_public_key":"client-public-key-material-32chars-min","requested_scopes":${LanScopePolicy.DEFAULT_INFER_SCOPES.toJson()},"proof_base64url":"${"C".repeat(43)}"}"""
        val failed = h.completeLanPairing(early)
        assertTrue("unapproved challenge must fail closed: $failed", failed is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (failed as HttpHandlerResult.Err).error.code)
    }

    // ----- helpers ----------------------------------------------------------

    private fun Set<String>.toJson(): String =
        JsonArray(this.sorted().map { JsonPrimitive(it) }).toString()
}
