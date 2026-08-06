package com.omnillm.android.runtimeservice.http

import android.util.Log
import com.omnillm.android.runtimeservice.controlplane.RuntimeControlPlane
import com.omnillm.interfaces.http.OmniHttpHandlerPort
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.interfaces.http.gateway.LoopbackHttpGateway
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.policy.PolicyManager
import java.util.concurrent.atomic.AtomicReference

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

    private val gateway = AtomicReference<LoopbackHttpGateway?>(null)
    private val tokens = AtomicReference<LoopbackTokenService?>(null)
    private val handlerRef = AtomicReference<ControlPlaneHttpHandler?>(null)
    private val bootstrapPlaintext = AtomicReference<String?>(null)

    fun tokenService(): LoopbackTokenService? = tokens.get()

    /** Shared control-plane HTTP handler (loopback + LAN TLS). */
    fun controlPlaneHandler(plane: RuntimeControlPlane): OmniHttpHandlerPort {
        handlerRef.get()?.let { return it }
        ensureTokenAndHandler(plane)
        return handlerRef.get()
            ?: error("control-plane HTTP handler not available")
    }

    /** One-time bootstrap token plaintext for local UI display (null after clear). */
    fun peekBootstrapTokenPlaintext(): String? = bootstrapPlaintext.get()

    fun clearBootstrapTokenPlaintext() {
        bootstrapPlaintext.set(null)
    }

    fun isRunning(): Boolean = gateway.get()?.isRunning == true

    /**
     * Ensure gateway is listening on loopback. Safe to call multiple times.
     * @return bound port or null on failure
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

            val gw = LoopbackHttpGateway(
                handler = handler,
                authenticator = tokenService,
                config = config,
            )
            return try {
                gw.start(wait = false)
                gateway.set(gw)
                Log.i(TAG, "Loopback HTTP gateway listening on ${config.host}:${config.port}")
                config.port
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start loopback HTTP gateway", e)
                tokens.set(null)
                handlerRef.set(null)
                bootstrapPlaintext.set(null)
                null
            }
        }
    }

    fun stop() {
        synchronized(this) {
            gateway.getAndSet(null)?.stop()
            tokens.set(null)
            handlerRef.set(null)
            bootstrapPlaintext.set(null)
            Log.i(TAG, "Loopback HTTP gateway stopped")
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
        if (bootstrapPlaintext.get() == null) {
            val issued = tokenService.issueBootstrapAdmin()
            bootstrapPlaintext.set(issued.plaintext)
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
        )
        handlerRef.set(handler)
        return tokenService
    }
}
