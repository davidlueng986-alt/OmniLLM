package com.omnillm.features.server

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.server.api.CreateDeveloperClientSpec
import com.omnillm.features.server.api.RevokeSpec
import com.omnillm.features.server.api.SdkSampleCatalog
import com.omnillm.features.server.domain.ScopeCatalog
import com.omnillm.features.server.domain.ServerScreenPhase
import com.omnillm.features.server.projection.ServerStateProjection
import com.omnillm.features.server.viewmodel.DeveloperServerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-SERVER happy path: loopback, client/token, SDK samples, evidence metrics,
 * empty/loading/ready presentation.
 */
class DeveloperServerServiceTest {

    @Test
    fun initialSnapshot_isEmpty() {
        val api = service()
        val snap = api.snapshot()
        assertEquals(ServerScreenPhase.EMPTY, snap.presentation)
        assertTrue(snap.isEmpty)
        assertNull(snap.loopback)
        assertTrue(snap.clients.isEmpty())
        assertTrue(snap.tokens.isEmpty())
    }

    @Test
    fun refresh_readyLoopback_projectsReady() = runBlocking {
        val loopback = FakeLoopback()
        loopback.markReady()
        val api = service(ports(loopback = loopback))

        val r = api.refresh()
        assertTrue(r is OmniResult.Ok)
        val snap = (r as OmniResult.Ok).value
        assertEquals(ServerScreenPhase.READY, snap.presentation)
        assertTrue(snap.loopback!!.isHealthy)
        assertEquals("127.0.0.1", snap.loopback!!.host)
        assertEquals(11434, snap.loopback!!.port)
        assertFalse(snap.metrics.isEmpty())
        // Evidence labels preserved on every metric.
        snap.metrics.forEach { m ->
            assertNotNull(m.evidenceLabel)
            if (m.evidenceLabel == EvidenceLabel.UNKNOWN) {
                assertNull(m.displayValueOrNull())
            }
            if (m.evidenceLabel == EvidenceLabel.REPORTED) {
                assertNotNull(m.source)
            }
        }
    }

    @Test
    fun refresh_degradedRuntime_projectsDegraded() = runBlocking {
        val loopback = FakeLoopback()
        loopback.markDegraded("THERMAL")
        val api = service(ports(loopback = loopback))

        val snap = (api.refresh() as OmniResult.Ok).value
        assertEquals(ServerScreenPhase.DEGRADED, snap.presentation)
        assertTrue(snap.isDegraded)
        assertTrue(snap.loopback!!.degradedReasons.contains("THERMAL"))
    }

    @Test
    fun ensureLoopbackStarted_marksRunning() = runBlocking {
        val loopback = FakeLoopback()
        val api = service(ports(loopback = loopback))

        val r = api.ensureLoopbackStarted()
        assertTrue(r is OmniResult.Ok)
        assertEquals(1, loopback.ensureStartedCalls)
        assertTrue((r as OmniResult.Ok).value.running)
        assertEquals("READY", r.value.runtimeState)
    }

    @Test
    fun createClientAndIssueToken_plaintextOnce_andDefaultScopes() = runBlocking {
        val api = service()
        val spec = CreateDeveloperClientSpec(
            command = cmd("create"),
            clientId = "sdk-cli",
            displayName = "SDK CLI",
            scopes = ScopeCatalog.SMOKE_MINIMAL,
            expiresInSeconds = 3600L,
        )
        val issued = api.createClientAndIssueToken(spec = spec)
        assertTrue(issued is OmniResult.Ok)
        val receipt = (issued as OmniResult.Ok).value
        assertTrue(receipt.tokenPlaintext.isNotBlank())
        assertEquals("sdk-cli", receipt.clientId)
        assertTrue(receipt.loopbackOnly)
        assertEquals(ScopeCatalog.SMOKE_MINIMAL, receipt.scopes)

        val snap = api.snapshot()
        assertNotNull(snap.pendingTokenReceipt)
        assertEquals(1, snap.clients.size)
        assertEquals("ACTIVE", snap.clients.single().state)
        assertEquals(1, snap.tokens.size)
        assertEquals("ACTIVE", snap.tokens.single().state)

        api.acknowledgeTokenReceipt()
        assertNull(api.snapshot().pendingTokenReceipt)
    }

    @Test
    fun createClient_unknownScope_failsClosed() = runBlocking {
        val api = service()
        val spec = CreateDeveloperClientSpec(
            command = cmd("bad-scope"),
            clientId = "bad",
            displayName = "Bad",
            scopes = setOf("models.read", "not.a.real.scope"),
            expiresInSeconds = 3600L,
        )
        val r = api.createClientAndIssueToken(spec = spec)
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun createClient_disallowedScopeForDeveloper_failsClosed() = runBlocking {
        val api = service()
        // tokens.manage is not on DEVELOPER_CLIENT profile
        val spec = CreateDeveloperClientSpec(
            command = cmd("admin-scope"),
            clientId = "overreach",
            displayName = "Overreach",
            scopes = setOf("models.read", "tokens.manage"),
            expiresInSeconds = 3600L,
        )
        val r = api.createClientAndIssueToken(spec = spec)
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun revokeClient_andToken() = runBlocking {
        val api = service()
        val issued = api.createClientAndIssueToken(
            spec = CreateDeveloperClientSpec(
                command = cmd("rv"),
                clientId = "c1",
                displayName = "C1",
                scopes = ScopeCatalog.SMOKE_MINIMAL,
                expiresInSeconds = 3600L,
            ),
        ) as OmniResult.Ok

        val tokenId = issued.value.tokenId
        val revToken = api.revokeToken(
            spec = RevokeSpec(command = cmd("rt"), targetId = tokenId),
        )
        assertTrue(revToken is OmniResult.Ok)
        assertEquals("REVOKED", (revToken as OmniResult.Ok).value.state)

        val revClient = api.revokeClient(
            spec = RevokeSpec(command = cmd("rc"), targetId = "c1"),
        )
        assertTrue(revClient is OmniResult.Ok)
        assertEquals("REVOKED", (revClient as OmniResult.Ok).value.state)
    }

    @Test
    fun sdkSamples_includeClientGeneratedIdsAndCancelQuery() {
        val api = service()
        val samples = api.listSdkSamples()
        assertTrue(samples.size >= 8)
        val chat = samples.first { it.id == "chat-completions" }
        assertTrue(chat.headerHints.any { it.contains(SdkSampleCatalog.PLACEHOLDER_REQUEST_ID) })
        assertTrue(chat.headerHints.any { it.contains(SdkSampleCatalog.PLACEHOLDER_IDEMPOTENCY_KEY) })
        assertNotNull(samples.firstOrNull { it.id == "cancel-request" })
        assertNotNull(samples.firstOrNull { it.id == "query-request" })
        assertNotNull(samples.firstOrNull { it.id == "aidl-pairing" })
    }

    @Test
    fun smokeInference_usesClientGeneratedRequestId() = runBlocking {
        val inference = FakeInference()
        val loopback = FakeLoopback().also { it.markReady() }
        val api = service(ports(loopback = loopback, inference = inference))

        val requestId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val c = claim(requestId = requestId)
        val r = api.runSmokeInference(claim = c)
        assertTrue(r is OmniResult.Ok)
        assertEquals(requestId, inference.lastClaim!!.requestId)
        assertEquals(requestId, (r as OmniResult.Ok).value.requestId)
        assertEquals("QUEUED", r.value.requestState)
        assertEquals(requestId, api.snapshot().lastRequestId)
    }

    @Test
    fun viewModel_emptyLoadingReadyCycle() = runBlocking {
        val loopback = FakeLoopback()
        loopback.markReady()
        val api = service(ports(loopback = loopback))
        val vm = DeveloperServerViewModel(api)

        assertTrue(vm.uiState().isEmpty)

        vm.onRefresh()
        assertTrue(vm.uiState().isReady || vm.uiState().phase == ServerScreenPhase.READY)
        assertFalse(vm.uiState().isEmpty)
        assertNull(vm.uiState().error)
    }

    @Test
    fun evidenceSemantics_unknownNeverNumeric() {
        assertFalse(ServerStateProjection.allowsNumericMetricDisplay(EvidenceLabel.UNKNOWN))
        assertTrue(ServerStateProjection.allowsNumericMetricDisplay(EvidenceLabel.MEASURED))
        assertEquals("evidence.unknown", ServerStateProjection.evidenceDescriptionKey(EvidenceLabel.UNKNOWN))
    }

    @Test
    fun scopeCatalog_developerDefaultsFromAccessControl() {
        assertTrue(ScopeCatalog.DEVELOPER_CLIENT_DEFAULT.contains("inference.create"))
        assertTrue(ScopeCatalog.DEVELOPER_CLIENT_DEFAULT.contains("models.read"))
        assertFalse(ScopeCatalog.DEVELOPER_CLIENT_DEFAULT.contains("tokens.manage"))
        assertFalse(ScopeCatalog.DEVELOPER_CLIENT_DEFAULT.contains("lan.manage"))
    }
}
