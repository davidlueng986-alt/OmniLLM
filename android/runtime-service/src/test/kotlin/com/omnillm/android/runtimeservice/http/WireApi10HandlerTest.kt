package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanChallengeView
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.ports.LanClientPort
import com.omnillm.features.lan.ports.LanPairingPort
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.ports.LanServicePort
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.LanPairingChallengeCreateRequestDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * API-10 handler tests: the LAN pairing challenge request contract is
 * enforced (client-generated challenge_id, non-empty requested_scopes,
 * ttl_seconds const 300) and the LanPairingChallengeSecret response is
 * additionalProperties:false — no state / attempts_remaining leak.
 */
class WireApi10HandlerTest {

    private val digest64 = "a".repeat(64)

    private val admin = HttpPrincipal(
        principalId = "wire-admin",
        tokenId = "tok-wire",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun handler(): ControlPlaneHttpHandler {
        val ledgers = com.omnillm.runtime.RequestRegistryModule.createInMemoryWithCommits()
        val ports = LanRuntimePorts(
            service = object : LanServicePort {
                override suspend fun status(): OmniResult<LanServiceStatus> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not enabled in hermetic fixture"))
                override suspend fun enable(principal: PrincipalId, spec: EnableLanSpec): OmniResult<LanServiceStatus> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not enabled in hermetic fixture"))
                override suspend fun disable(principal: PrincipalId, spec: DisableLanSpec): OmniResult<LanServiceStatus> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not enabled in hermetic fixture"))
            },
            pairing = object : LanPairingPort {
                override suspend fun createChallenge(
                    principal: PrincipalId,
                    spec: CreatePairingChallengeSpec,
                ): OmniResult<LanChallengeView> =
                    OmniResult.ok(
                        LanChallengeView(
                            challengeId = spec.challengeId,
                            state = LanChallengeView.CHALLENGE_STATES.first(),
                            serverSpkiSha256 = "a".repeat(64),
                            connectionEpoch = 1,
                            requestedScopes = spec.requestedScopes,
                            expiresAtEpochMs = Instant.parse("2026-08-04T00:05:00Z").toEpochMilli(),
                            attemptsRemaining = 5,
                            pairingSecret = "secret-once",
                            qrPayload = "omnillm://pair?c=1",
                            clientDisplayHint = spec.clientDisplayHint,
                        ),
                    )
                override suspend fun approveChallenge(
                    principal: PrincipalId,
                    spec: ApprovePairingChallengeSpec,
                ): OmniResult<LanChallengeView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not in hermetic fixture"))
                override suspend fun completeExchange(
                    principal: PrincipalId,
                    spec: CompletePairingExchangeSpec,
                ): OmniResult<LanTokenIssuanceReceipt> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not in hermetic fixture"))
            },
            clients = object : LanClientPort {
                override suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>> =
                    OmniResult.ok(emptyList())
                override suspend fun revoke(
                    principal: PrincipalId,
                    spec: RevokeLanClientSpec,
                ): OmniResult<LanClientView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not in hermetic fixture"))
            },
        )
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 42L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            lanPorts = ports,
        )
    }

    private fun assertErrCode(result: HttpHandlerResult<*>, code: OmniErrorCode, context: String) {
        assertTrue("$context must fail closed with $code, got $result", result is HttpHandlerResult.Err)
        assertEquals(code, (result as HttpHandlerResult.Err).error.code)
    }

    private fun cmd(key: String = "lan-1"): CommandRequestDto =
        CommandRequestDto(
            commandId = uuid(),
            idempotencyKey = key,
            canonicalInputDigest = digest64,
        )

    @Test
    fun lanChallenge_acceptsClientChallengeId_andOmitsNonContractFields() = runBlocking {
        val h = handler()
        val challengeId = uuid()
        val r = h.createLanPairingChallenge(
            admin,
            LanPairingChallengeCreateRequestDto(
                command = cmd(),
                challengeId = challengeId,
                requestedScopes = listOf("inference.create", "models.read"),
                clientDisplayHint = "living-room",
            ),
        )
        assertTrue("challenge must be created: $r", r is HttpHandlerResult.Ok)
        val body = (r as HttpHandlerResult.Ok).body.json
        assertTrue("challenge_id echoed: $body", body.contains("\"challenge_id\":\"$challengeId\""))
        assertTrue("protocol_label present: $body", body.contains("\"protocol_label\":\"OmniLLM-LAN-Pairing-1\""))
        assertTrue("server_spki_sha256 present: $body", body.contains("server_spki_sha256"))
        assertTrue("connection_epoch present: $body", body.contains("connection_epoch"))
        assertTrue("pairing_secret present: $body", body.contains("pairing_secret"))
        assertTrue("requested_scopes present: $body", body.contains("requested_scopes"))
        assertTrue("expires_at present: $body", body.contains("expires_at"))
        assertTrue("qr_payload present: $body", body.contains("qr_payload"))
        // additionalProperties:false — state/attempts_remaining are NOT on the contract.
        assertTrue("state must NOT leak (additionalProperties:false): $body", !body.contains("\"state\""))
        assertTrue(
            "attempts_remaining must NOT leak (additionalProperties:false): $body",
            !body.contains("attempts_remaining"),
        )
    }

    @Test
    fun lanChallenge_malformedChallengeId_failsClosed() = runBlocking {
        val h = handler()
        val r = h.createLanPairingChallenge(
            admin,
            LanPairingChallengeCreateRequestDto(
                command = cmd("lan-bad-id"),
                challengeId = "not-a-uuid",
                requestedScopes = listOf("inference.create"),
            ),
        )
        assertErrCode(r, OmniErrorCode.INVALID_REQUEST, "lan challenge bad challenge_id")
    }

    @Test
    fun lanChallenge_emptyRequestedScopes_failsClosed() = runBlocking {
        val h = handler()
        val r = h.createLanPairingChallenge(
            admin,
            LanPairingChallengeCreateRequestDto(
                command = cmd("lan-no-scopes"),
                challengeId = uuid(),
                requestedScopes = emptyList(),
            ),
        )
        assertErrCode(r, OmniErrorCode.INVALID_REQUEST, "lan challenge empty requested_scopes")
    }

    @Test
    fun lanChallenge_wrongTtl_failsClosed() = runBlocking {
        val h = handler()
        val r = h.createLanPairingChallenge(
            admin,
            LanPairingChallengeCreateRequestDto(
                command = cmd("lan-bad-ttl"),
                challengeId = uuid(),
                requestedScopes = listOf("inference.create"),
                ttlSeconds = 301,
            ),
        )
        assertErrCode(r, OmniErrorCode.INVALID_REQUEST, "lan challenge ttl != 300")
    }
}
