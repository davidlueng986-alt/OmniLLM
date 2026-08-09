package com.omnillm.android.runtimeservice.http

import android.util.Log
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.auth.TokenAuthenticator
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.interfaces.http.gateway.LoopbackHttpGateway
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.policy.PolicyManager
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Starts / stops the loopback HTTP gateway from the runtime control plane process.
 *
 * INV-001: must only run in `:runtime` (never UI).
 * ADR-010: handler uses control-plane registries as sole writers.
 *
 * LAN TLS listener is owned by [ControlPlaneLanHost] / [ControlPlaneLanTlsEndpoint]
 * (default-off; started only after explicit ENABLE_LAN). This object still builds
 * the shared [ControlPlaneHttpHandler] for both loopback and LAN transports.
 */
object GatewayLifecycle {
    private const val TAG = "OmniHttpGateway"

    private val gateway = AtomicReference<BoundGateway?>(null)
    private val tokens = AtomicReference<LoopbackTokenService?>(null)
    private val handlerRef = AtomicReference<ControlPlaneHttpHandler?>(null)
    /**
     * SEC-08: single-peek bootstrap token display state. Plaintext is exposed to
     * the UI exactly once ([takeBootstrapTokenPlaintextOnce]); afterwards only
     * the masked form is available and the staged Secret Broker receipt is wiped.
     */
    private val bootstrapDisplay = AtomicReference<BootstrapTokenDisplay?>(null)
    private val bootstrapIssuanceKey = AtomicReference<String?>(null)

    fun tokenService(): LoopbackTokenService? = tokens.get()

    /** Shared control-plane HTTP handler (loopback + LAN TLS). */
    fun controlPlaneHandler(plane: RuntimeControlPlane): OmniHttpHandlerPort {
        handlerRef.get()?.let { return it }
        ensureTokenAndHandler(plane)
        return handlerRef.get()
            ?: error("control-plane HTTP handler not available")
    }

    /**
     * SEC-08 single-peek: first call returns the bootstrap token plaintext and
     * immediately drops the in-memory reference plus wipes the staged Secret
     * Broker receipt; all later calls return null (masked only).
     */
    fun takeBootstrapTokenPlaintextOnce(): String? {
        val display = bootstrapDisplay.get() ?: return null
        val plaintext = display.takePlaintextOnce() ?: return null
        bootstrapIssuanceKey.get()?.let { issuanceKey ->
            tokens.get()?.erasePlaintextReceipt(issuanceKey)
        }
        return plaintext
    }

    /** Masked bootstrap token for repeat display (never the plaintext). */
    fun maskedBootstrapToken(): String? = bootstrapDisplay.get()?.masked()

    fun clearBootstrapTokenPlaintext() {
        bootstrapDisplay.set(null)
        bootstrapIssuanceKey.set(null)
    }

    fun isRunning(): Boolean = gateway.get() != null

    /**
     * Ensure gateway is listening on loopback. Safe to call multiple times.
     *
     * COR-23c: the returned port is the REAL bound port and — crucially — is
     * only reported after the server provably accepts TCP connections on it.
     * Ktor binds asynchronously with `start(wait = false)`, so a bare
     * `config.port` return would be a fake success when the bind actually
     * failed or the port was occupied. Failing closed (null) beats reporting a
     * port nobody is listening on.
     *
     * @return bound port when the gateway is verifiably reachable, else null
     */
    fun ensureStarted(
        plane: RuntimeControlPlane,
        config: GatewayConfig = GatewayConfig(),
        jobManager: JobManager = plane.jobManager,
        policyManager: PolicyManager = plane.policyManager,
    ): Int? {
        gateway.get()?.let { return it.boundPort }

        synchronized(this) {
            gateway.get()?.let { return it.boundPort }

            val tokenService = ensureTokenAndHandler(
                plane = plane,
                jobManager = jobManager,
                policyManager = policyManager,
            )
            val handler = handlerRef.get()
                ?: return null

            val gw = try {
                gatewayStarter.start(handler, tokenService, config)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start loopback HTTP gateway", e)
                tokens.set(null)
                handlerRef.set(null)
                clearBootstrapTokenPlaintext()
                return null
            }
            // COR-23c: prove the resolved port really accepts connections before
            // reporting success (asynchronous CIO bind — no fake success).
            if (!portAcceptsConnections(config.host, gw.boundPort)) {
                logW(
                    "loopback gateway did not become reachable on " +
                        "${config.host}:${gw.boundPort} — failing closed",
                )
                gw.stop()
                tokens.set(null)
                handlerRef.set(null)
                clearBootstrapTokenPlaintext()
                return null
            }
            gateway.set(gw)
            logI("Loopback HTTP gateway listening on ${config.host}:${gw.boundPort}")
            return gw.boundPort
        }
    }

    fun stop() {
        synchronized(this) {
            gateway.getAndSet(null)?.stop()
            tokens.set(null)
            handlerRef.set(null)
            clearBootstrapTokenPlaintext()
            logI("Loopback HTTP gateway stopped")
        }
    }

    private fun ensureTokenAndHandler(
        plane: RuntimeControlPlane,
        jobManager: JobManager = plane.jobManager,
        policyManager: PolicyManager = plane.policyManager,
    ): LoopbackTokenService {
        tokens.get()?.let { existing ->
            if (handlerRef.get() != null) return existing
        }
        val stack = plane.securityStack
        val tokenService = LoopbackTokenService(
            secretBroker = stack.secretBroker,
            tokenService = stack.tokenService,
        )
        if (bootstrapDisplay.get() == null) {
            // SEC-08: issue with 1h default TTL; plaintext held for single-peek display.
            val issued = tokenService.issueBootstrapAdmin()
            bootstrapDisplay.set(BootstrapTokenDisplay(issued.plaintext))
            bootstrapIssuanceKey.set(issued.issuanceKey)
        }
        tokens.set(tokenService)
        val handler = ControlPlaneHttpHandler(
            runtimeState = { plane.runtimeState },
            resourceVersion = { plane.identity.runtimeEpoch },
            requestRegistry = plane.requestRegistry,
            commandLedger = plane.commandLedger,
            tokenService = tokenService,
            jobManager = jobManager,
            policyManager = policyManager,
            orchestrator = plane.orchestrator,
            lanPorts = plane.featurePacks.lanPorts,
            diagnosticsApi = plane.diagnosticsApi,
            contentReportApi = plane.contentReportApi,
            routingApi = plane.routingApi,
            toolsApi = plane.toolsApi,
            benchmarkApi = plane.benchmarkApi,
            // API-50: ACL + profile/transport/epoch enforcement on the HTTP path.
            accessControl = stack.accessControl,
        )
        handlerRef.set(handler)
        return tokenService
    }

    /**
     * Gateway-start seam (COR-23c / TST-04): production starts the shared
     * [LoopbackHttpGateway]; hermetic tests may inject a fake.
     */
    internal fun interface GatewayStarter {
        fun start(
            handler: OmniHttpHandlerPort,
            authenticator: TokenAuthenticator,
            config: GatewayConfig,
        ): BoundGateway
    }

    internal var gatewayStarter: GatewayStarter = GatewayStarter { handler, authenticator, config ->
        val gw = LoopbackHttpGateway(handler, authenticator, config)
        gw.start(wait = false)
        BoundGateway(boundPort = gw.boundPort) { gw.stop() }
    }

    /**
     * COR-23c: true only when [host]:[port] actually accepts TCP connections
     * within [timeoutMs] (polling — the CIO bind is asynchronous).
     */
    internal fun portAcceptsConnections(
        host: String,
        port: Int,
        timeoutMs: Long = 3_000L,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 200)
                    return true
                }
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        return false
    }

    /**
     * COR-23c: resolve the port a Ktor engine ACTUALLY bound (suspend
     * [ApplicationEngine.resolvedConnectors] completes after the connector is
     * live). Used by the ephemeral-bind regression test; production currently
     * binds a fixed config port and verifies reachability via
     * [portAcceptsConnections].
     */
    internal fun resolveBoundPort(server: EmbeddedServer<*, *>): Int =
        runBlocking { server.engine.resolvedConnectors() }.first().port

    private fun logI(msg: String) = runCatching { Log.i(TAG, msg) }

    private fun logW(msg: String) = runCatching { Log.w(TAG, msg) }
}

/**
 * A running gateway handle exposing the REAL bound port, with idempotent stop.
 * Production wraps [LoopbackHttpGateway]; tests may inject a fake.
 */
internal class BoundGateway(
    val boundPort: Int,
    private val closer: () -> Unit,
) {
    private val stopped = AtomicBoolean(false)

    /** Idempotent close; the underlying server is stopped exactly once. */
    fun stop() {
        if (stopped.compareAndSet(false, true)) {
            closer()
        }
    }
}
