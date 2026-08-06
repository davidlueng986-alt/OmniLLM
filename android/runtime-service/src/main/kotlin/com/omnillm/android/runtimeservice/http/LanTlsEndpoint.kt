package com.omnillm.android.runtimeservice.http

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.LanTlsGatewayConfig
import com.omnillm.interfaces.http.gateway.LanTlsHttpGateway
import com.omnillm.runtime.policy.security.LanTlsIdentity
import com.omnillm.runtime.policy.security.SecretBroker
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Control-plane LAN TLS endpoint (FEAT-LAN §1, SEC-PROFILE, ADR-010).
 *
 * Generates ECDSA P-256 identity via [LanTlsIdentity], wraps the private key with
 * [SecretBroker], and optionally binds a Netty TLS listener. Default product
 * posture remains off — this object is only started after explicit ENABLE.
 */
interface LanTlsEndpoint {
    fun status(): LanTlsEndpointStatus

    /**
     * Ensure TLS identity exists and (when [bindNetwork]) the listener is up.
     * Idempotent while already ready for the same epoch material.
     */
    fun start(
        bindNetwork: Boolean = true,
        config: LanTlsGatewayConfig = LanTlsGatewayConfig(),
        approvedInterfaces: List<String> = emptyList(),
    ): OmniResult<LanTlsEndpointStatus>

    fun stop(): OmniResult<LanTlsEndpointStatus>
}

data class LanTlsEndpointStatus(
    val running: Boolean,
    val identityReady: Boolean,
    val pairingEndpointReady: Boolean,
    val certificateValid: Boolean,
    val serverSpkiSha256: String?,
    val boundAddresses: List<String>,
    val port: Int?,
    val failureReason: String? = null,
)

/**
 * Software TLS endpoint: real ECDSA identity + Secret Broker wrap.
 * Network bind is optional ([bindNetwork]) so JVM unit tests can exercise
 * pairing without opening sockets; production passes bindNetwork=true.
 */
class ControlPlaneLanTlsEndpoint(
    private val secretBroker: SecretBroker,
    private val handlerProvider: () -> OmniHttpHandlerPort?,
    private val authenticatorProvider: () -> TokenAuthenticator?,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : LanTlsEndpoint {

    private val lock = Any()
    private val gateway = AtomicReference<LanTlsHttpGateway?>(null)

    @Volatile
    private var material: LanTlsIdentity.Material? = null

    @Volatile
    private var wrapped: LanTlsIdentity.WrappedMaterial? = null

    @Volatile
    private var keyPassword: CharArray = CharArray(0)

    @Volatile
    private var lastStatus: LanTlsEndpointStatus = idleStatus()

    override fun status(): LanTlsEndpointStatus = synchronized(lock) { lastStatus }

    override fun start(
        bindNetwork: Boolean,
        config: LanTlsGatewayConfig,
        approvedInterfaces: List<String>,
    ): OmniResult<LanTlsEndpointStatus> {
        synchronized(lock) {
            try {
                val now = clockMs()
                val mat = material ?: LanTlsIdentity.generate(nowEpochMs = now).also { generated ->
                    when (val w = LanTlsIdentity.wrapPrivateKey(secretBroker, generated)) {
                        is OmniResult.Err -> return w
                        is OmniResult.Ok -> wrapped = w.value
                    }
                    material = generated
                    keyPassword = randomPassword()
                }
                if (!mat.isValidAt(now)) {
                    return fail("TLS certificate not valid at current time")
                }

                val addresses = resolveBindAddresses(approvedInterfaces, config.host)
                var networkRunning = false
                var port: Int? = null
                var failure: String? = null

                if (bindNetwork) {
                    val handler = handlerProvider()
                    val authenticator = authenticatorProvider()
                    if (handler == null || authenticator == null) {
                        failure = "HTTP handler/authenticator not ready for LAN TLS"
                    } else {
                        stopGatewayLocked()
                        val ks = LanTlsHttpGateway.keyStoreFrom(
                            privateKey = mat.keyPair.private,
                            certificate = mat.certificate,
                            alias = LanTlsIdentity.KEY_ALIAS,
                            password = keyPassword,
                        )
                        val bindConfig = config.copy(
                            host = addresses.firstOrNull() ?: config.host,
                            port = config.port,
                        )
                        val gw = LanTlsHttpGateway(
                            handler = handler,
                            authenticator = authenticator,
                            config = bindConfig,
                            keyStore = ks,
                            keyAlias = LanTlsIdentity.KEY_ALIAS,
                            keyPassword = keyPassword,
                        )
                        try {
                            gw.start(wait = false)
                            gateway.set(gw)
                            networkRunning = true
                            port = bindConfig.port
                        } catch (e: Exception) {
                            failure = "LAN TLS bind failed: ${e.message ?: e::class.simpleName}"
                            networkRunning = false
                            port = null
                        }
                    }
                } else {
                    // Identity-only path (hermetic tests): pairing endpoint logical-ready.
                    networkRunning = false
                    port = config.port
                }

                val identityReady = true
                val pairingReady = identityReady && mat.isValidAt(now) &&
                    (if (bindNetwork) networkRunning else true)
                val st = LanTlsEndpointStatus(
                    running = networkRunning || !bindNetwork,
                    identityReady = identityReady,
                    pairingEndpointReady = pairingReady,
                    certificateValid = mat.isValidAt(now),
                    serverSpkiSha256 = mat.spkiSha256,
                    boundAddresses = if (pairingReady) {
                        if (addresses.isNotEmpty()) addresses
                        else listOf(if (bindNetwork) config.host else "127.0.0.1")
                    } else {
                        emptyList()
                    },
                    port = if (pairingReady) port else null,
                    failureReason = failure,
                )
                lastStatus = st
                return if (pairingReady) {
                    OmniResult.ok(st)
                } else {
                    OmniResult.err(
                        OmniError.STATE_CONFLICT(
                            message = failure ?: "LAN TLS not ready",
                            details = mapOf(
                                "identityReady" to identityReady.toString(),
                                "bindNetwork" to bindNetwork.toString(),
                            ),
                        ),
                    )
                }
            } catch (e: Exception) {
                return fail(e.message ?: "LAN TLS start failed")
            }
        }
    }

    override fun stop(): OmniResult<LanTlsEndpointStatus> {
        synchronized(lock) {
            stopGatewayLocked()
            material?.let { LanTlsIdentity.wipe(it) }
            material = null
            wrapped = null
            keyPassword.fill('\u0000')
            keyPassword = CharArray(0)
            lastStatus = idleStatus()
            return OmniResult.ok(lastStatus)
        }
    }

    /** Current SPKI pin if identity is loaded. */
    fun spkiSha256(): String? = material?.spkiSha256

    private fun stopGatewayLocked() {
        gateway.getAndSet(null)?.stop()
    }

    private fun fail(message: String): OmniResult.Err {
        lastStatus = lastStatus.copy(failureReason = message, pairingEndpointReady = false)
        return OmniResult.Err(OmniError.INTERNAL(message = message))
    }

    private fun resolveBindAddresses(
        approvedInterfaces: List<String>,
        fallbackHost: String,
    ): List<String> {
        if (approvedInterfaces.isNotEmpty()) {
            return approvedInterfaces.filter { it.isNotBlank() }.distinct()
        }
        val discovered = mutableListOf<String>()
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return listOf(fallbackHost)
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val host = a.hostAddress ?: continue
                        discovered += host
                    }
                }
            }
        } catch (_: Exception) {
            // Best-effort; fall through.
        }
        if (discovered.isNotEmpty()) return discovered.distinct()
        return if (fallbackHost == "0.0.0.0" || fallbackHost == "::") {
            listOf("127.0.0.1")
        } else {
            listOf(fallbackHost)
        }
    }

    private fun randomPassword(): CharArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.toCharArray()
    }

    companion object {
        fun idleStatus(): LanTlsEndpointStatus =
            LanTlsEndpointStatus(
                running = false,
                identityReady = false,
                pairingEndpointReady = false,
                certificateValid = false,
                serverSpkiSha256 = null,
                boundAddresses = emptyList(),
                port = null,
            )
    }
}
