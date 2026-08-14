package com.omnillm.android.runtimeservice.http

import com.omnillm.interfaces.http.AssetCreateRequestDto
import com.omnillm.interfaces.http.CommandRequestDto
import com.omnillm.interfaces.http.HttpHandlerResult
import com.omnillm.interfaces.http.ModelInfoDto
import com.omnillm.interfaces.http.SettingsPatchDto
import com.omnillm.interfaces.http.TokenIssueRequestDto
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * COR-23 leftovers (a/b/f/i) + API-50 ACL wiring regression tests.
 *
 * - (a) listModels honors pageToken with real cursor pagination
 * - (b) jsonToSettingValue keeps JSON strings as STRING (no "0123" coercion)
 * - (f) revokeToken checks ownership (self or tokens.manage admin)
 * - (i) commitAsset re-validates content hash (TOCTOU guard)
 * - API-50: LAN-transport request to a loopback-only operation → FORBIDDEN
 */
class ControlPlaneHttpHandlerCor23Test {

    private val digest64 = "a".repeat(64)

    private val loopbackAdmin = HttpPrincipal(
        principalId = "http-local-admin",
        tokenId = "tok-admin",
        scopes = setOf("*"),
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    private fun lanPrincipal(scopes: Set<String> = LAN_CLIENT_SCOPES) = HttpPrincipal(
        principalId = "http-lan-client",
        tokenId = "tok-lan",
        scopes = scopes,
        revocationEpoch = 0L,
        loopbackOnly = false,
    )

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun cmd(key: String, expectedVersion: Long? = null): CommandRequestDto =
        CommandRequestDto(
            commandId = uuid(),
            idempotencyKey = key,
            canonicalInputDigest = digest64,
            expectedVersion = expectedVersion,
        )

    private fun handler(
        models: List<ModelInfoDto> = (1..3).map { i ->
            ModelInfoDto(
                modelRevisionId = "model-rev-$i",
                displayName = "model-$i",
            )
        },
    ): ControlPlaneHttpHandler {
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = jobs,
            policyManager = policy,
            modelCatalog = { models },
        )
    }

    // ----- COR-23a: listModels pagination ------------------------------------

    @Test
    fun listModels_honorsPageToken_firstPageHasNext() = runBlocking {
        val h = handler((1..3).map { i -> ModelInfoDto("$i".repeat(64), "model-$i") })
        val page1 = h.listModels(loopbackAdmin, null) as HttpHandlerResult.Ok
        assertEquals(3, page1.body.items.size)
        assertNull("catalog smaller than page size must not paginate", page1.body.nextPageToken)
    }

    @Test
    fun listModels_paginatesBeyondPageSize_andNextPageContinues() = runBlocking {
        val models = (1..105).map { i -> ModelInfoDto(i.toString().padStart(64, '0'), "m-$i") }
        val h = handler(models)
        val page1 = h.listModels(loopbackAdmin, null) as HttpHandlerResult.Ok
        assertEquals(50, page1.body.items.size)
        assertNotNull("more models must yield a next token", page1.body.nextPageToken)
        assertEquals("m-1", page1.body.items.first().displayName)

        val page2 = h.listModels(loopbackAdmin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(50, page2.body.items.size)
        assertNotNull(page2.body.nextPageToken)
        assertEquals("m-51", page2.body.items.first().displayName)

        val page3 = h.listModels(loopbackAdmin, page2.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(5, page3.body.items.size)
        assertNull("last page must not carry a next token", page3.body.nextPageToken)
        assertEquals("m-101", page3.body.items.first().displayName)

        // The same token must always return the same slice (deterministic).
        val page2Again = h.listModels(loopbackAdmin, page1.body.nextPageToken) as HttpHandlerResult.Ok
        assertEquals(page2.body.items, page2Again.body.items)
    }

    @Test
    fun listModels_malformedOrStaleToken_failsClosed() = runBlocking {
        val h = handler()
        val malformed = h.listModels(loopbackAdmin, "not-a-cursor")
        assertTrue(malformed is HttpHandlerResult.Err)
        assertEquals(
            OmniErrorCode.INVALID_REQUEST,
            (malformed as HttpHandlerResult.Err).error.code,
        )
        // Cursor beyond the catalog → CURSOR_GONE (410), not an empty page.
        val beyond = h.listModels(loopbackAdmin, encodeCursor(99))
        assertTrue(beyond is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.CURSOR_GONE, (beyond as HttpHandlerResult.Err).error.code)
    }

    // ----- COR-23b: string settings stay strings -----------------------------

    @Test
    fun settingsPatch_booleanString_isNotCoercedToBoolean() = runBlocking {
        // Regression: a JSON STRING "true" must never be coerced into a boolean
        // (old jsonToSettingValue ran booleanOrNull on strings). As a string it
        // is an invalid value for a BOOLEAN setting → INVALID_REQUEST.
        val h = handler()
        val snap = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        val patched = h.patchSettings(
            loopbackAdmin,
            SettingsPatchDto(
                command = cmd("settings-bool-str", expectedVersion = snap.body.resourceVersion),
                changes = mapOf("runtime.exploratoryExecuteEnabled" to JsonPrimitive("true")),
            ),
        )
        assertTrue(patched is HttpHandlerResult.Err)
        assertEquals(
            OmniErrorCode.INVALID_REQUEST,
            (patched as HttpHandlerResult.Err).error.code,
        )
        // The setting must remain untouched (false).
        val after = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        assertEquals(false, (after.body.values["runtime.exploratoryExecuteEnabled"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun settingsPatch_stringWithLeadingZeros_isNotCoercedToNumber() = runBlocking {
        // Regression: JSON STRING "0123" must never become IntValue(123).
        // privacy.telemetryMode is an ENUM setting writable via
        // administrator-policy; "0123" as a string is an unknown enum value →
        // the patch is rejected as INVALID_REQUEST, never accepted as 123.
        val h = handler()
        val snap = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        val patched = h.patchSettings(
            loopbackAdmin,
            SettingsPatchDto(
                command = cmd("settings-str", expectedVersion = snap.body.resourceVersion),
                changes = mapOf("privacy.telemetryMode" to JsonPrimitive("0123")),
            ),
        )
        assertTrue(patched is HttpHandlerResult.Err)
        assertEquals(
            OmniErrorCode.INVALID_REQUEST,
            (patched as HttpHandlerResult.Err).error.code,
        )
        // The setting must remain untouched.
        val after = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        assertEquals(
            "LOCAL_ONLY",
            (after.body.values["privacy.telemetryMode"] as JsonPrimitive).content,
        )
    }

    @Test
    fun settingsPatch_realJsonBoolean_stillMapsToBool() = runBlocking {
        // Only JSON STRINGS are protected from coercion — a REAL JSON boolean
        // must still map to BoolValue (and succeed on a boolean setting).
        val h = handler()
        val snap = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        val patched = h.patchSettings(
            loopbackAdmin,
            SettingsPatchDto(
                command = cmd("settings-bool", expectedVersion = snap.body.resourceVersion),
                changes = mapOf("server.loopbackEnabled" to JsonPrimitive(true)),
            ),
        )
        assertTrue("real JSON boolean must patch: $patched", patched is HttpHandlerResult.Ok)
        val after = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        assertEquals(true, (after.body.values["server.loopbackEnabled"] as JsonPrimitive).booleanOrNull)
    }

    // ----- COR-23f: revokeToken ownership ------------------------------------

    private fun issueSecondToken(h: ControlPlaneHttpHandler, principalId: String): String =
        (runBlocking {
            h.issueLoopbackAdminToken(
                loopbackAdmin,
                TokenIssueRequestDto(
                    command = cmd("issue-$principalId"),
                    clientId = "client-$principalId",
                    displayName = "test-$principalId",
                    // D23d: issuance requires explicit scopes + TTL (no fallbacks).
                    scopes = listOf("inference.create"),
                    expiresInSeconds = 3600,
                ),
            )
        } as HttpHandlerResult.Ok).body.tokenId

    @Test
    fun revokeToken_nonOwner_withoutAdminScope_isForbiddenAndNotRevoked() = runBlocking {
        val h = handler()
        val tokenId = issueSecondToken(h, "victim")
        // Another principal (self-scoped only, no tokens.manage).
        val other = HttpPrincipal(
            principalId = "http-other",
            tokenId = "tok-other",
            scopes = setOf("inference.create"),
            revocationEpoch = 0L,
            loopbackOnly = true,
        )
        val denied = h.revokeToken(other, tokenId, cmd("revoke-other"))
        assertTrue(denied is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (denied as HttpHandlerResult.Err).error.code)
        // Token must still exist (no revoke side-effect).
        assertNotNull(h.tokenService.get(tokenId))
        assertFalse(h.tokenService.get(tokenId)!!.revoked)
    }

    @Test
    fun revokeToken_adminScope_canRevokeAnothersToken() = runBlocking {
        val h = handler()
        val tokenId = issueSecondToken(h, "victim2")
        val admin = HttpPrincipal(
            principalId = "http-admin2",
            tokenId = "tok-admin2",
            scopes = setOf("tokens.manage"),
            revocationEpoch = 0L,
            loopbackOnly = true,
        )
        val revoked = h.revokeToken(admin, tokenId, cmd("revoke-admin"))
        assertTrue("admin must revoke: $revoked", revoked is HttpHandlerResult.Ok)
        assertTrue(h.tokenService.get(tokenId)!!.revoked)
    }

    @Test
    fun revokeToken_ownToken_succeeds() = runBlocking {
        val h = handler()
        val tokenId = issueSecondToken(h, "owner")
        // The token's own principal (holding tokens.manage) revokes its own
        // token — ownership check + enforcer both pass.
        val token = h.tokenService.get(tokenId)!!
        val owner = HttpPrincipal(
            principalId = token.principalId,
            tokenId = "tok-owner-self",
            scopes = setOf("tokens.manage", "inference.create"),
            revocationEpoch = 0L,
            loopbackOnly = true,
        )
        val revoked = h.revokeToken(owner, tokenId, cmd("revoke-owner"))
        assertTrue("self-revoke must succeed: $revoked", revoked is HttpHandlerResult.Ok)
    }

    // ----- COR-23i: commitAsset TOCTOU ---------------------------------------

    @Test
    fun commitAsset_revalidatesHashAgainstUploadTime() = runBlocking {
        val h = handler()
        val created = h.createAsset(
            loopbackAdmin,
            AssetCreateRequestDto(
                command = cmd("toc-create"),
                purpose = "image",
                maxBytes = 4096,
                ttlSeconds = 3600,
            ),
        ) as HttpHandlerResult.Ok
        val assetId = created.body.assetId

        val body = "original-content".toByteArray()
        val ok = h.uploadAsset(
            loopbackAdmin, assetId, body, body.size.toLong(), null,
            expectedSha256 = sha256(body),
        )
        assertTrue("upload must succeed: $ok", ok is HttpHandlerResult.Ok)

        // TOCTOU: the caller mutates the buffer IN PLACE after upload validation
        // (rec.content shares the same array). Commit must fail closed — the
        // committed bytes no longer match the validated digest.
        body[0] = 'X'.code.toByte()
        val commit = h.commitAsset(loopbackAdmin, assetId, cmd("toc-commit"))
        assertTrue("commit must reject mutated content: $commit", commit is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (commit as HttpHandlerResult.Err).error.code)

        // Asset must NOT be READY.
        val asset = h.getAsset(loopbackAdmin, assetId) as HttpHandlerResult.Ok
        assertFalse("asset must not be marked READY", asset.body.state == "READY")
    }

    @Test
    fun commitAsset_unmutatedContent_stillCommits() = runBlocking {
        val h = handler()
        val created = h.createAsset(
            loopbackAdmin,
            AssetCreateRequestDto(
                command = cmd("ok-create"),
                purpose = "image",
                maxBytes = 4096,
                ttlSeconds = 3600,
            ),
        ) as HttpHandlerResult.Ok
        val assetId = created.body.assetId
        val body = "stable-content".toByteArray()
        assertTrue(
            h.uploadAsset(loopbackAdmin, assetId, body, body.size.toLong(), null) is HttpHandlerResult.Ok,
        )
        val commit = h.commitAsset(loopbackAdmin, assetId, cmd("ok-commit"))
        assertTrue("unchanged content must commit: $commit", commit is HttpHandlerResult.Ok)
    }

    // ----- API-50: ACL on the HTTP path --------------------------------------

    @Test
    fun lanTransportRequest_toLoopbackOnlyOperation_isDenied() = runBlocking {
        val h = handler()
        val snap = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        // LAN_CLIENT profile does not hold settings.write — the enforcer must
        // deny even though the request would otherwise look plausible.
        val denied = h.patchSettings(
            lanPrincipal(),
            SettingsPatchDto(
                command = cmd("lan-settings", expectedVersion = snap.body.resourceVersion),
                changes = mapOf("runtime.exploratoryExecuteEnabled" to JsonPrimitive(true)),
            ),
        )
        assertTrue("LAN settings write must be FORBIDDEN: $denied", denied is HttpHandlerResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (denied as HttpHandlerResult.Err).error.code)
    }

    @Test
    fun lanTransportRequest_withInferScopes_isAllowed() = runBlocking {
        val h = handler()
        val allowed = h.listModels(lanPrincipal(), null)
        assertTrue("LAN clients may list models: $allowed", allowed is HttpHandlerResult.Ok)
    }

    @Test
    fun loopbackPrincipal_canUseLoopbackOnlyOperations() = runBlocking {
        val h = handler()
        val snap = h.getSettings(loopbackAdmin) as HttpHandlerResult.Ok
        val patched = h.patchSettings(
            loopbackAdmin,
            SettingsPatchDto(
                command = cmd("lo-settings", expectedVersion = snap.body.resourceVersion),
                changes = mapOf("runtime.exploratoryExecuteEnabled" to JsonPrimitive(true)),
            ),
        )
        assertTrue("loopback admin may patch settings: $patched", patched is HttpHandlerResult.Ok)
    }

    // ----- helpers -----------------------------------------------------------

    private fun encodeCursor(index: Int): String {
        val raw = "models-v1:$index".toByteArray(Charsets.UTF_8)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun sha256(bytes: ByteArray): String {
        val dig = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    companion object {
        /** LAN_CLIENT catalog scope set (access-control-catalog.yaml). */
        val LAN_CLIENT_SCOPES: Set<String> = setOf(
            "models.read",
            "inference.create",
            "inference.cancel",
            "inference.read-own",
            "assets.create",
            "assets.read-own",
            "assets.delete-own",
            "jobs.read-own",
            "content-reports.propose",
            "content-reports.manage-own",
            "content-reports.read-own",
            "commands.read-own",
        )
    }
}
