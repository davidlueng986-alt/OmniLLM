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
import com.omnillm.features.lan.api.LanClientView
import com.omnillm.features.lan.api.LanServiceStatus
import com.omnillm.features.lan.api.LanTokenIssuanceReceipt
import com.omnillm.features.lan.api.RevokeLanClientSpec
import com.omnillm.features.lan.ports.LanClientPort
import com.omnillm.features.lan.ports.LanPairingPort
import com.omnillm.features.lan.ports.LanRuntimePorts
import com.omnillm.features.lan.ports.LanServicePort
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.JobSpecDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * D23c wire-contract drift: TokenPage/JobPage/ClientPage all have
 * `next_page_token` (spec :2362-2434 / :2747-2774) and the three list
 * operations accept `page_token`, but the handler ignored it — page one
 * returned the ENTIRE collection and pagination was impossible. Only
 * listModels had a real cursor.
 *
 * Mirrors the listModels cursor implementation: opaque base64url cursor,
 * malformed -> INVALID_REQUEST, cursor beyond the collection -> CURSOR_GONE,
 * deterministic ordering (sorted by id) so pages never shift.
 */
class PaginationCursorTest {

    private val admin = HttpPrincipal(
        principalId = "d23c-principal",
        tokenId = "tok-d23c",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private val digest64 = "a".repeat(64)

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun bareHandler(): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
        )
    }

    // ----- listTokens --------------------------------------------------------

    @Test
    fun listTokens_paginatesWithCursor() = runBlocking {
        val h = bareHandler()
        repeat(105) { i ->
            h.tokenService.issue(
                principalId = "http-issued:d23c-tokens",
                scopes = setOf("inference.create"),
                ttlSeconds = 3600L,
                loopbackOnly = true,
                label = "token-$i",
            )
        }
        val page1 = h.listTokens(admin, null) as HttpHandlerResult.Ok
        assertEquals("page one must be bounded", 50, page1.body.items.size)
        assertNotNull("more tokens must yield a next token", page1.body.nextPageToken)

        val page2 = h.listTokens(admin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(50, page2.body.items.size)
        assertNotNull(page2.body.nextPageToken)
        assertTrue("pages must not overlap", page1.body.items.none { a -> page2.body.items.any { it.tokenId == a.tokenId } })

        val page3 = h.listTokens(admin, page2.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(5, page3.body.items.size)
        assertNull("last page must not carry a next token", page3.body.nextPageToken)

        // Deterministic: same token returns the same slice.
        val page2Again = h.listTokens(admin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(page2.body.items, page2Again.body.items)
    }

    @Test
    fun listTokens_malformedOrStaleCursor_failsClosed() = runBlocking {
        val h = bareHandler()
        val malformed = h.listTokens(admin, "not-a-cursor")
        assertTrue(malformed is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (malformed as HttpHandlerResult.Err).error.code)

        val beyond = h.listTokens(admin, encodeCursor("tokens-v1", 99))
        assertTrue(beyond is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.CURSOR_GONE, (beyond as HttpHandlerResult.Err).error.code)
    }

    // ----- listOwnJobs -------------------------------------------------------

    private fun benchmarkJob(h: ControlPlaneHttpHandler, i: Int): HttpHandlerResult<*> = runBlocking {
        h.createJob(
            admin,
            JobSpecDto(
                command = com.omnillm.interfaces.http.CommandRequestDto(
                    commandId = uuid(),
                    idempotencyKey = "d23c-job-$i-${uuid()}",
                    canonicalInputDigest = digest64,
                ),
                jobId = uuid(),
                kind = "BENCHMARK",
                parameters = JsonObject(
                    mapOf(
                        "model_revision_id" to JsonPrimitive("b".repeat(64)),
                        "engine_build_id" to JsonPrimitive("engine-$i"),
                        "backend" to JsonPrimitive("cpu"),
                        "measurement_profile_id" to JsonPrimitive("c".repeat(64)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun listOwnJobs_paginatesWithCursor() = runBlocking {
        val h = bareHandler()
        repeat(105) { i -> assertTrue("job $i must create", benchmarkJob(h, i) is HttpHandlerResult.Ok) }

        val page1 = h.listOwnJobs(admin, null) as HttpHandlerResult.Ok
        assertEquals("page one must be bounded", 50, page1.body.items.size)
        assertNotNull("more jobs must yield a next token", page1.body.nextPageToken)

        val page2 = h.listOwnJobs(admin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(50, page2.body.items.size)
        assertNotNull(page2.body.nextPageToken)

        val page3 = h.listOwnJobs(admin, page2.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(5, page3.body.items.size)
        assertNull("last page must not carry a next token", page3.body.nextPageToken)

        val page2Again = h.listOwnJobs(admin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(page2.body.items, page2Again.body.items)
    }

    @Test
    fun listOwnJobs_malformedCursor_failsClosed() = runBlocking {
        val h = bareHandler()
        val malformed = h.listOwnJobs(admin, "junk")
        assertTrue(malformed is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (malformed as HttpHandlerResult.Err).error.code)
    }

    // ----- listClients (LAN registry) ---------------------------------------

    private fun handlerWithLanClients(count: Int): ControlPlaneHttpHandler {
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        val views = (1..count).map { i ->
            LanClientView(
                clientId = "lan-client-%04d".format(i),
                displayName = "Client $i",
                state = "ACTIVE",
                scopes = setOf("inference.create"),
                connectionEpoch = 1,
                revocationEpoch = 0,
            )
        }
        val ports = LanRuntimePorts(
            service = object : LanServicePort {
                override suspend fun status(): OmniResult<LanServiceStatus> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not enabled"))
                override suspend fun enable(principal: PrincipalId, spec: EnableLanSpec): OmniResult<LanServiceStatus> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not enabled"))
                override suspend fun disable(principal: PrincipalId, spec: DisableLanSpec): OmniResult<LanServiceStatus> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "not enabled"))
            },
            pairing = object : LanPairingPort {
                override suspend fun createChallenge(
                    principal: PrincipalId,
                    spec: CreatePairingChallengeSpec,
                ): OmniResult<com.omnillm.features.lan.api.LanChallengeView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "stub"))
                override suspend fun approveChallenge(
                    principal: PrincipalId,
                    spec: ApprovePairingChallengeSpec,
                ): OmniResult<com.omnillm.features.lan.api.LanChallengeView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "stub"))
                override suspend fun completeExchange(
                    principal: PrincipalId,
                    spec: CompletePairingExchangeSpec,
                ): OmniResult<LanTokenIssuanceReceipt> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "stub"))
            },
            clients = object : LanClientPort {
                override suspend fun listClients(principal: PrincipalId): OmniResult<List<LanClientView>> =
                    OmniResult.ok(views)
                override suspend fun revoke(
                    principal: PrincipalId,
                    spec: RevokeLanClientSpec,
                ): OmniResult<LanClientView> =
                    OmniResult.err(OmniError.NOT_FOUND(message = "stub"))
            },
        )
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            lanPorts = ports,
        )
    }

    @Test
    fun listClients_paginatesWithCursor() = runBlocking {
        val h = handlerWithLanClients(105)
        val page1 = h.listClients(admin, null) as HttpHandlerResult.Ok
        assertEquals("page one must be bounded", 50, page1.body.items.size)
        assertNotNull("more clients must yield a next token", page1.body.nextPageToken)

        val page2 = h.listClients(admin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(50, page2.body.items.size)
        assertNotNull(page2.body.nextPageToken)

        val page3 = h.listClients(admin, page2.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(5, page3.body.items.size)
        assertNull("last page must not carry a next token", page3.body.nextPageToken)

        val page2Again = h.listClients(admin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(page2.body.items, page2Again.body.items)
    }

    @Test
    fun listClients_malformedCursor_failsClosed() = runBlocking {
        val h = handlerWithLanClients(2)
        val malformed = h.listClients(admin, "nope")
        assertTrue(malformed is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (malformed as HttpHandlerResult.Err).error.code)
    }

    // ----- helpers -----------------------------------------------------------

    private fun encodeCursor(prefix: String, index: Int): String {
        val raw = "$prefix:$index".toByteArray(Charsets.UTF_8)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
