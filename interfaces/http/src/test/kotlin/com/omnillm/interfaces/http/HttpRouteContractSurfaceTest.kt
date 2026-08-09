package com.omnillm.interfaces.http

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.http.auth.AuthResult
import com.omnillm.interfaces.http.auth.HttpPrincipal
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.configureGateway
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Route-level contract tests (API-05 / API-06 / API-13):
 * - DELETE /assets/{assetId} requires a CommandRequest body and answers 204.
 * - PUT /assets/{assetId}/content parses multipart (command + content +
 *   expected_sha256) and enforces the digest.
 * - x-omnillm-allowed-transports is enforced on all 13 listed operations.
 */
class HttpRouteContractSurfaceTest {

    private val digest64 = "a".repeat(64)
    private val uuid = "11111111-1111-1111-1111-111111111111"

    private val allScopes = OpenApiScopes.REQUIRED.values.filterNotNull().toSet()

    private val principal = HttpPrincipal(
        principalId = "contract-principal",
        tokenId = "tok-contract",
        scopes = allScopes,
        revocationEpoch = 0L,
        loopbackOnly = true,
    )

    /** LAN listener principals must not be loopback-only (transport authz). */
    private val lanPrincipal = principal.copy(loopbackOnly = false)

    private fun authenticator(transport: HttpTransportKind) = TokenAuthenticator { token, _ ->
        if (token == "good") {
            AuthResult.Ok(if (transport == HttpTransportKind.LAN_TLS13) lanPrincipal else principal)
        } else {
            AuthResult.Unauthorized("bad")
        }
    }

    private val handler = object : OmniHttpHandlerPort by NotImplementedHttpHandler {
        override suspend fun getHealth(): HttpHandlerResult<HealthDto> =
            HttpHandlerResult.Ok(HealthDto(runtimeState = "READY", resourceVersion = 1))

        override suspend fun listModels(
            principal: HttpPrincipal,
            pageToken: String?,
        ): HttpHandlerResult<ModelPageDto> =
            HttpHandlerResult.Ok(ModelPageDto(items = emptyList()))

        override suspend fun getMetricDetail(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto> =
            HttpHandlerResult.Ok(MetricSummaryDto(resourceVersion = 1))

        override suspend fun getSettings(principal: HttpPrincipal): HttpHandlerResult<SettingsSnapshotDto> =
            HttpHandlerResult.Ok(SettingsSnapshotDto(resourceVersion = 0))

        override suspend fun listTokens(principal: HttpPrincipal, pageToken: String?): HttpHandlerResult<TokenPageDto> =
            HttpHandlerResult.Ok(TokenPageDto())

        override suspend fun deleteAsset(
            principal: HttpPrincipal,
            assetId: String,
            command: CommandRequestDto,
        ): HttpHandlerResult<CommandResultDto> =
            HttpHandlerResult.Ok(
                CommandResultDto(
                    commandId = command.commandId,
                    state = "SUCCEEDED",
                    resourceVersion = 1,
                    affectedResourceId = assetId,
                ),
            )

        override suspend fun uploadAsset(
            principal: HttpPrincipal,
            assetId: String,
            body: ByteArray,
            contentLength: Long?,
            command: CommandRequestDto?,
            expectedSha256: String?,
            expectedBytes: Long?,
        ): HttpHandlerResult<CommandResultDto> {
            if (expectedSha256 != null && expectedSha256 != sha256(body)) {
                return HttpHandlerResult.Err(
                    OmniError.INVALID_REQUEST(message = "content sha256 mismatch vs expected_sha256"),
                )
            }
            return HttpHandlerResult.Ok(
                CommandResultDto(
                    commandId = command?.commandId ?: "no-command",
                    state = "SUCCEEDED",
                    resourceVersion = 1,
                    affectedResourceId = assetId,
                ),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val dig = MessageDigest.getInstance("SHA-256").digest(bytes)
        return dig.joinToString("") { b -> "%02x".format(b) }
    }

    private val commandJson = """{"command_id":"$uuid","idempotency_key":"del-1","canonical_input_digest":"$digest64"}"""

    @Test
    fun deleteAsset_withCommandBody_answers204() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator(HttpTransportKind.LOOPBACK))
            }
            val r = client.delete("/omni/v1/assets/$uuid") {
                header("Authorization", "Bearer good")
                contentType(ContentType.Application.Json)
                setBody(commandJson)
            }
            // API-05: 204 No Content, not 200 with a body.
            assertEquals(HttpStatusCode.NoContent, r.status)
            assertEquals("", r.bodyAsText())
        }
    }

    @Test
    fun deleteAsset_withoutCommandBody_answers400() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator(HttpTransportKind.LOOPBACK))
            }
            val r = client.delete("/omni/v1/assets/$uuid") {
                header("Authorization", "Bearer good")
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
    }

    @Test
    fun uploadAsset_multipart_commandAndContent_answers200() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator(HttpTransportKind.LOOPBACK))
            }
            val content = "payload-bytes"
            val boundary = "----omnillm-contract-boundary"
            val body = listOf(
                "--$boundary",
                """Content-Disposition: form-data; name="command"""",
                "Content-Type: application/json",
                "",
                commandJson,
                "--$boundary",
                """Content-Disposition: form-data; name="expected_sha256"""",
                "",
                sha256(content.toByteArray()),
                "--$boundary",
                """Content-Disposition: form-data; name="content"; filename="payload.bin"""",
                "Content-Type: application/octet-stream",
                "",
                content,
                "--$boundary--",
            ).joinToString("\r\n")
            val r = client.put("/omni/v1/assets/$uuid/content") {
                header("Authorization", "Bearer good")
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", boundary))
                setBody(body)
            }
            // API-06: multipart parses command + content + expected_sha256.
            assertEquals(
                "multipart upload body: ${r.bodyAsText()}",
                HttpStatusCode.OK,
                r.status,
            )
            assertTrue(r.bodyAsText().contains("SUCCEEDED"))
        }
    }

    @Test
    fun uploadAsset_multipart_missingParts_answers400() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator(HttpTransportKind.LOOPBACK))
            }
            val boundary = "----omnillm-contract-boundary"
            val body = listOf(
                "--$boundary",
                """Content-Disposition: form-data; name="content"; filename="payload.bin"""",
                "Content-Type: application/octet-stream",
                "",
                "only-content",
                "--$boundary--",
            ).joinToString("\r\n")
            val r = client.put("/omni/v1/assets/$uuid/content") {
                header("Authorization", "Bearer good")
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", boundary))
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
    }

    @Test
    fun uploadAsset_multipart_shaMismatch_answers400() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator(HttpTransportKind.LOOPBACK))
            }
            val content = "payload-bytes"
            val boundary = "----omnillm-contract-boundary"
            val body = listOf(
                "--$boundary",
                """Content-Disposition: form-data; name="command"""",
                "Content-Type: application/json",
                "",
                commandJson,
                "--$boundary",
                """Content-Disposition: form-data; name="expected_sha256"""",
                "",
                "0".repeat(64),
                "--$boundary",
                """Content-Disposition: form-data; name="content"; filename="payload.bin"""",
                "Content-Type: application/octet-stream",
                "",
                content,
                "--$boundary--",
            ).joinToString("\r\n")
            val r = client.put("/omni/v1/assets/$uuid/content") {
                header("Authorization", "Bearer good")
                contentType(ContentType.MultiPart.FormData.withParameter("boundary", boundary))
                setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, r.status)
        }
    }

    // ----- API-13: x-omnillm-allowed-transports ------------------------------

    @Test
    fun loopbackOnlyOperations_rejectedOnLanTransport() = runBlocking {
        testApplication {
            application {
                configureGateway(
                    handler,
                    authenticator(HttpTransportKind.LAN_TLS13),
                    transport = HttpTransportKind.LAN_TLS13,
                )
            }
            val loopbackOnlyPaths = listOf(
                "/omni/v1/metrics/detail",
                "/omni/v1/settings",
                "/omni/v1/clients",
                "/omni/v1/tokens",
            )
            for (path in loopbackOnlyPaths) {
                val r = client.get(path) {
                    header("Authorization", "Bearer good")
                }
                assertEquals(
                    "$path must be FORBIDDEN on LAN transport",
                    HttpStatusCode.Forbidden,
                    r.status,
                )
                assertTrue("$path must reject with FORBIDDEN body", r.bodyAsText().contains("FORBIDDEN"))
            }
        }
    }

    @Test
    fun loopbackOnlyOperations_acceptedOnLoopbackTransport() = runBlocking {
        testApplication {
            application {
                configureGateway(handler, authenticator(HttpTransportKind.LOOPBACK))
            }
            val r = client.get("/omni/v1/metrics/detail") {
                header("Authorization", "Bearer good")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }
    }

    @Test
    fun unrestrictedOperations_workOnLanTransport() = runBlocking {
        testApplication {
            application {
                configureGateway(
                    handler,
                    authenticator(HttpTransportKind.LAN_TLS13),
                    transport = HttpTransportKind.LAN_TLS13,
                )
            }
            // listModels has no x-omnillm-allowed-transports restriction.
            val r = client.get("/v1/models") {
                header("Authorization", "Bearer good")
            }
            assertEquals(HttpStatusCode.OK, r.status)
        }
    }
}
