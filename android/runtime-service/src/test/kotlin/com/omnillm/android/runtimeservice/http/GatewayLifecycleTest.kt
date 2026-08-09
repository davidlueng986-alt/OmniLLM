package com.omnillm.android.runtimeservice.http

import com.omnillm.android.runtimeservice.featurehost.FeaturePackHost
import com.omnillm.interfaces.http.gateway.GatewayConfig
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.RequestRegistryModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * TST-04 / COR-23c: GatewayLifecycle start/stop and REAL-bound-port contract.
 *
 * - The production gateway path ([LoopbackHttpGateway] via the default
 *   [GatewayLifecycle.gatewayStarter]) must report a port that REALLY accepts
 *   connections (HTTP round-trip on the returned port).
 * - [GatewayLifecycle.portAcceptsConnections] must fail closed for a port
 *   nobody is listening on (the asynchronous-bind fake-success regression).
 * - Ephemeral binds (port 0) resolve to a concrete bound port — the exact
 *   "port=0" regression from COR-23c ([GatewayLifecycle.resolveBoundPort]).
 * - [BoundGateway.stop] is idempotent.
 */
class GatewayLifecycleTest {

    private fun handler(): com.omnillm.interfaces.http.OmniHttpHandlerPort {
        val jobs = JobManagerModule.createManager()
        val policy = PolicyModule.createManager()
        val observability = ObservabilityModule.createFacade()
        val host = FeaturePackHost.bootstrap(
            jobManager = jobs,
            policyManager = policy,
            observability = observability,
            clockMs = { 1_700_000_000_000L },
        )
        val ledgers = RequestRegistryModule.createInMemoryWithCommits()
        return ControlPlaneHttpHandler(
            runtimeState = { "READY" },
            resourceVersion = { 1L },
            requestRegistry = ledgers.requestRegistry,
            commandLedger = ledgers.commandLedger,
            tokenService = LoopbackTokenService(),
            jobManager = jobs,
            policyManager = policy,
            lanPorts = host.lanPorts,
            diagnosticsApi = host.diagnosticsApi,
            contentReportApi = host.contentReportApi,
            routingApi = host.routingApi,
            toolsApi = host.toolsApi,
            benchmarkApi = host.benchmarkApi,
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun httpGet(port: Int, path: String): Int {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        try {
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun productionGatewayPath_bindsAndReportsAReachablePort() {
        val port = freePort()
        val gw = GatewayLifecycle.gatewayStarter.start(
            handler = handler(),
            authenticator = LoopbackTokenService(),
            config = GatewayConfig(port = port),
        )
        try {
            assertEquals("bound port must equal the requested free port", port, gw.boundPort)
            // COR-23c: the reported port must REALLY accept connections.
            assertTrue(
                "gateway must accept TCP on the returned port",
                GatewayLifecycle.portAcceptsConnections("127.0.0.1", gw.boundPort),
            )
            assertEquals(
                "health must be reachable on the returned bound port",
                200,
                httpGet(gw.boundPort, "/health"),
            )
        } finally {
            gw.stop()
        }
        // After stop the port must be released for reuse.
        ServerSocket(port).use { assertTrue(it.localPort > 0) }
    }

    @Test
    fun portAcceptsConnections_failsClosedWhenNothingIsListening() {
        val free = freePort()
        assertFalse(
            "a port with no listener must NOT be reported as reachable",
            GatewayLifecycle.portAcceptsConnections("127.0.0.1", free, timeoutMs = 400L),
        )
        // With a real listener it must flip to true.
        ServerSocket(free).use { listener ->
            assertTrue(
                GatewayLifecycle.portAcceptsConnections("127.0.0.1", free, timeoutMs = 2_000L),
            )
        }
    }

    @Test
    fun ephemeralPortZero_resolvesToARealBoundPort() {
        // GatewayConfig forbids 0 for public config, but the underlying Ktor
        // bind supports ephemeral ports — the COR-23c regression is that the
        // resolver returns the REAL bound port, never the requested 0.
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            // Minimal app — the bind/resolve contract needs no routes.
        }
        server.start(wait = false)
        try {
            val real = GatewayLifecycle.resolveBoundPort(server)
            assertTrue(
                "resolved port must be a real bound port, got $real",
                real in 1..65535,
            )
            assertNotEquals(0, real)
            Socket("127.0.0.1", real).use { assertTrue(it.isConnected) }
        } finally {
            server.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        }
    }

    @Test
    fun boundGateway_stopIsIdempotent() {
        var closes = 0
        val gw = BoundGateway(boundPort = 4242) { closes++ }
        assertEquals(4242, gw.boundPort)
        gw.stop()
        gw.stop()
        gw.stop()
        assertEquals("closer must run exactly once", 1, closes)
    }
}
