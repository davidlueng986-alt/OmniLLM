package com.omnillm.interfaces.http.gateway

import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.OpenApiAuthority
import com.omnillm.interfaces.http.auth.HttpTransportKind
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

/**
 * LAN TLS 1.3 HTTP gateway (FEAT-LAN, SEC-PROFILE, SEC-AUTH-NET §4).
 *
 * - Binds approved host/port with platform TLS (Netty SSL connector)
 * - Transport kind [HttpTransportKind.LAN_TLS13] — loopback-only tokens rejected
 * - Pairing exchange route requires this transport (no plaintext token exchange)
 * - Certificate / private key supplied by control-plane Secret Broker path
 *
 * INV-001: runs only in runtime control plane process.
 */
class LanTlsHttpGateway(
    private val handler: OmniHttpHandlerPort,
    private val authenticator: TokenAuthenticator,
    private val config: LanTlsGatewayConfig = LanTlsGatewayConfig(),
    private val keyStore: KeyStore,
    private val keyAlias: String,
    private val keyPassword: CharArray,
) {
    private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)

    val isRunning: Boolean get() = serverRef.get() != null

    val boundPort: Int get() = config.port

    val boundHost: String get() = config.host

    fun start(wait: Boolean = false) {
        OpenApiAuthority.requirePackagedDocument().close()
        if (serverRef.get() != null) return

        val env = applicationEnvironment {}
        val server = embeddedServer(
            factory = Netty,
            environment = env,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = keyAlias,
                    keyStorePassword = { keyPassword },
                    privateKeyPassword = { keyPassword },
                ) {
                    host = config.host
                    port = config.port
                    enabledProtocols = listOf("TLSv1.3", "TLSv1.2")
                }
            },
            module = {
                configureGateway(
                    handler = handler,
                    authenticator = authenticator,
                    config = GatewayConfig(
                        host = "127.0.0.1",
                        port = config.port,
                        maxJsonBodyBytes = config.maxJsonBodyBytes,
                        maxAssetUploadBytes = config.maxAssetUploadBytes,
                    ),
                    transport = HttpTransportKind.LAN_TLS13,
                )
            },
        )
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

    companion object {
        fun keyStoreFrom(
            privateKey: PrivateKey,
            certificate: X509Certificate,
            alias: String,
            password: CharArray,
        ): KeyStore {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry(alias, privateKey, password, arrayOf(certificate))
            return ks
        }
    }
}
