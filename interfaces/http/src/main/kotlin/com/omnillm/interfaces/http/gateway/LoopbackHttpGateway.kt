package com.omnillm.interfaces.http.gateway

import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.OpenApiAuthority
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.installOmniHttpRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.http.HttpJson
import com.omnillm.interfaces.http.OmniErrorHttp
import java.util.concurrent.atomic.AtomicReference

/**
 * Loopback Ktor HTTP gateway (CORE-INTERFACE, FEAT-SERVER, SEC-AUTH-NET).
 *
 * - Binds literal loopback only ([GatewayConfig.host])
 * - Token auth on all routes except minimal `/health` (and LAN pairing exchange when on LAN)
 * - Maps every OpenAPI path to [OmniHttpHandlerPort] — no engine selection here
 * - Fail closed if packaged OpenAPI authority is missing
 */
class LoopbackHttpGateway(
    private val handler: OmniHttpHandlerPort,
    private val authenticator: TokenAuthenticator,
    private val config: GatewayConfig = GatewayConfig(),
    private val transport: HttpTransportKind = HttpTransportKind.LOOPBACK,
) {
    private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)

    val isRunning: Boolean get() = serverRef.get() != null

    val boundPort: Int get() = config.port

    fun start(wait: Boolean = false) {
        // Fail closed if OpenAPI packaging is broken.
        OpenApiAuthority.requirePackagedDocument().close()

        val existing = serverRef.get()
        if (existing != null) return

        val server = embeddedServer(CIO, port = config.port, host = config.host) {
            configureGateway(
                handler = handler,
                authenticator = authenticator,
                config = config,
                transport = transport,
            )
        }
        if (!serverRef.compareAndSet(null, server)) {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            return
        }
        server.start(wait = wait)
    }

    fun stop(gracePeriodMillis: Long = 500, timeoutMillis: Long = 2_000) {
        val server = serverRef.getAndSet(null) ?: return
        server.stop(gracePeriodMillis = gracePeriodMillis, timeoutMillis = timeoutMillis)
    }
}

/**
 * Application module for tests / custom engines without starting CIO.
 */
fun Application.configureGateway(
    handler: OmniHttpHandlerPort,
    authenticator: TokenAuthenticator,
    config: GatewayConfig = GatewayConfig(),
    transport: HttpTransportKind = HttpTransportKind.LOOPBACK,
) {
    install(ContentNegotiation) {
        json(HttpJson.codec)
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val err = OmniError.INTERNAL(
                message = "unhandled gateway error",
                details = mapOf("type" to (cause::class.simpleName ?: "Throwable")),
            )
            OmniErrorHttp.respond(call, err)
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            // Unknown path ⇒ fail closed (INV-018) with canonical error body when possible.
            if (!call.response.isCommitted) {
                OmniErrorHttp.respond(
                    call,
                    OmniError.NOT_FOUND(message = "unknown path"),
                )
            }
        }
    }
    installOmniHttpRoutes(
        handler = handler,
        authenticator = authenticator,
        config = config,
        transport = transport,
    )
}
