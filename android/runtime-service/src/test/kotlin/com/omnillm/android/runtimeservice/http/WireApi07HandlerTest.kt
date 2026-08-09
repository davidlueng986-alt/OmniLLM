package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.lan.api.ApprovePairingChallengeSpec
import com.omnillm.features.lan.api.CompletePairingExchangeSpec
import com.omnillm.features.lan.api.CreatePairingChallengeSpec
import com.omnillm.features.lan.api.DisableLanSpec
import com.omnillm.features.lan.api.EnableLanSpec
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.ports.LanClientPort
import com.omnillm.features.lan.ports.LanPairingPort
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.ports.LanServicePort
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * API-07 handler tests: listClients maps the REAL LAN client registry
 * (display_name + revocation_epoch) and every paged response carries
 * snapshot_version.
 */
class WireApi07HandlerTest {

    private val admin = HttpPrincipal(
        principalId = "wire-admin",
        tokenId = "tok-wire",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun handlerWithLanClients(): ControlPlaneHttpHandler {
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
                ): OmniResult<com.omnillm.features.lan.api.LanChallengeView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not in hermetic fixture"))
                override suspend fun approveChallenge(
                    principal: PrincipalId,
                    spec: ApprovePairingChallengeSpec,
                ): OmniResult<com.omnillm.features.lan.api.LanChallengeView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not in hermetic fixture"))
                override suspend fun completeExchange(
                    principal: PrincipalId,
                    spec: CompletePairingExchangeSpec,
                ): OmniResult<LanTokenIssuanceReceipt> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not in hermetic fixture"))
            },
            clients = object : LanClientPort {
                override suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>> =
                    OmniResult.ok(
                        listOf(
                            LanClientView(
                                clientId = "lan-client-1",
                                displayName = "Living room phone",
                                state = "ACTIVE",
                                scopes = setOf("inference.create", "models.read"),
                                connectionEpoch = 1,
                                revocationEpoch = 4,
                                lastSeenAtEpochMs = Instant.parse("2026-08-04T01:00:00Z").toEpochMilli(),
                            ),
                        ),
                    )
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

    private fun bareHandler(): ControlPlaneHttpHandler {
        val ledgers = com.omnillm.runtime.RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 42L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
    }

    @Test
    fun listClients_mapsLanRegistryWithDisplayNameAndRevocationEpoch() = runBlocking {
        val h = handlerWithLanClients()
        val r = h.listClients(admin, null) as HttpHandlerResult.Ok
        assertEquals(1, r.body.items.size)
        val client = r.body.items.first()
        assertEquals("lan-client-1", client.clientId)
        assertEquals("Living room phone", client.displayName)
        assertEquals(4L, client.revocationEpoch)
        assertEquals(listOf("inference.create", "models.read"), client.scopes)
        assertEquals("ACTIVE", client.state)
        assertTrue("last_seen_at mapped", client.lastSeenAt != null)
        assertEquals("snapshot_version required by ClientPage", 42L, r.body.snapshotVersion)
    }

    @Test
    fun listModels_pageCarriesSnapshotVersion() = runBlocking {
        val h = bareHandler()
        val r = h.listModels(admin, null) as HttpHandlerResult.Ok
        assertEquals("snapshot_version required by ModelPage", 42L, r.body.snapshotVersion)
    }

    @Test
    fun listOwnJobs_pageCarriesSnapshotVersion() = runBlocking {
        val h = bareHandler()
        val r = h.listOwnJobs(admin, null) as HttpHandlerResult.Ok
        assertEquals("snapshot_version required by JobPage", 42L, r.body.snapshotVersion)
    }

    @Test
    fun listTokens_pageCarriesSnapshotVersion() = runBlocking {
        val h = bareHandler()
        val r = h.listTokens(admin, null) as HttpHandlerResult.Ok
        assertEquals("snapshot_version required by TokenPage", 42L, r.body.snapshotVersion)
    }
}
