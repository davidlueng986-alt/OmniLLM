package com.omnillm.features.playground

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.playground.api.CancelInferenceSpec
import com.omnillm.features.playground.api.ChatMessage
import com.omnillm.features.playground.api.ChatRequestSpec
import com.omnillm.features.playground.api.InferenceIdentity
import com.omnillm.features.playground.api.SourceSessionRef
import com.omnillm.features.playground.ports.PlaygroundFeaturePorts
import com.omnillm.features.playground.ports.PlaygroundModelRow
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.playground.projection.CancelPhaseProjection
import com.omnillm.features.playground.projection.PlaygroundUiAction
import com.omnillm.features.playground.projection.RequestUiProjection
import com.omnillm.features.playground.usecase.PlaygroundService
import com.omnillm.features.playground.viewmodel.PlaygroundViewModel
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEAT-PLAYGROUND cancel paths:
 * - cancel phase progression (requested → acknowledged → execution stopped → terminal)
 * - client-generated request ids
 * - view-model cancel guard
 * - query-after-cancel (reply loss)
 */
class PlaygroundCancelTest {

    private lateinit var inference: FakeInferencePort
    private lateinit var api: PlaygroundService
    private val modelId = "c".repeat(64)
    private val digest = "d".repeat(64)

    @Before
    fun setUp() {
        inference = FakeInferencePort()
        inference.holdStreaming = true
        api = PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = inference,
                capabilities = FakeCapabilityPort(),
                models = FakeModelCatalog(
                    listOf(PlaygroundModelRow(modelId, "Stream Model", "READY")),
                ),
            ),
        )
    }

    @Test
    fun cancelPhase_order_isNormative() {
        assertEquals(CancelPhase.ACKNOWLEDGED, CancelPhaseProjection.advance(CancelPhase.REQUESTED))
        assertEquals(
            CancelPhase.EXECUTION_STOPPED,
            CancelPhaseProjection.advance(CancelPhase.ACKNOWLEDGED),
        )
        assertEquals(
            CancelPhase.TERMINAL,
            CancelPhaseProjection.advance(CancelPhase.EXECUTION_STOPPED),
        )
        assertEquals(CancelPhase.TERMINAL, CancelPhaseProjection.advance(CancelPhase.TERMINAL))
        assertEquals("request.cancel.requested", CancelPhaseProjection.labelKey(CancelPhase.REQUESTED))
        assertEquals(
            "request.cancel.acknowledged",
            CancelPhaseProjection.labelKey(CancelPhase.ACKNOWLEDGED),
        )
        assertEquals(
            "request.cancel.execution-stopped",
            CancelPhaseProjection.labelKey(CancelPhase.EXECUTION_STOPPED),
        )
        assertEquals("request.cancel.terminal", CancelPhaseProjection.labelKey(CancelPhase.TERMINAL))
    }

    @Test
    fun cancel_streamingRequest_reachesCancelledTerminal() = runBlocking {
        val requestId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val started = api.startChat(LocalUiPrincipal.ID, chatSpec(requestId)) as OmniResult.Ok
        assertEquals("STREAMING", started.value.state)
        assertTrue(PlaygroundUiAction.CANCEL in started.value.allowedActions)
        assertFalse(started.value.isTerminal)

        val cancel = api.cancelRequest(
            LocalUiPrincipal.ID,
            CancelInferenceSpec(
                requestId = requestId,
                commandId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                idempotencyKey = "cancel-1",
                canonicalInputDigest = digest,
            ),
        ) as OmniResult.Ok

        assertEquals(CancelPhase.TERMINAL, cancel.value.phase)
        assertTrue(cancel.value.isTerminal)
        assertEquals("request.cancel.terminal", cancel.value.labelKey)
        assertEquals("CANCELLED", cancel.value.requestState)

        val queried = api.queryRequest(LocalUiPrincipal.ID, requestId) as OmniResult.Ok
        assertEquals("CANCELLED", queried.value.state)
        assertTrue(queried.value.isTerminal)
        assertTrue(PlaygroundUiAction.CANCEL !in queried.value.allowedActions)
        assertEquals(1, inference.cancelCallCount)
    }

    @Test
    fun progressiveCancel_exposesEachPhase() = runBlocking {
        val progressive = FakeInferencePort.progressive()
        progressive.holdStreaming = true
        val progressiveApi = PlaygroundService(
            ports = PlaygroundFeaturePorts(
                inference = progressive,
                capabilities = FakeCapabilityPort(),
                models = FakeModelCatalog(
                    listOf(PlaygroundModelRow(modelId, "Stream Model", "READY")),
                ),
            ),
        )
        val requestId = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        progressiveApi.startChat(LocalUiPrincipal.ID, chatSpec(requestId))

        val phases = mutableListOf<CancelPhase>()
        repeat(4) { step ->
            val status = progressiveApi.cancelRequest(
                LocalUiPrincipal.ID,
                CancelInferenceSpec(
                    requestId = requestId,
                    commandId = "dddddddd-dddd-dddd-dddd-ddddddddddd$step",
                    idempotencyKey = "cancel-step-$step",
                    canonicalInputDigest = digest,
                ),
            ) as OmniResult.Ok
            phases += status.value.phase
        }
        assertEquals(
            listOf(
                CancelPhase.REQUESTED,
                CancelPhase.ACKNOWLEDGED,
                CancelPhase.EXECUTION_STOPPED,
                CancelPhase.TERMINAL,
            ),
            phases,
        )
        val final = progressiveApi.queryRequest(LocalUiPrincipal.ID, requestId) as OmniResult.Ok
        assertEquals("CANCELLED", final.value.state)
    }

    @Test
    fun cancel_unknownRequest_isNotFound() = runBlocking {
        val result = api.cancelRequest(
            LocalUiPrincipal.ID,
            CancelInferenceSpec(
                requestId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee",
                commandId = "ffffffff-ffff-ffff-ffff-ffffffffffff",
                idempotencyKey = "cancel-missing",
                canonicalInputDigest = digest,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.NOT_FOUND, result.error.code)
    }

    @Test
    fun cancel_alreadyTerminal_isIdempotentTerminal() = runBlocking {
        inference.holdStreaming = false
        val requestId = "12121212-1212-1212-1212-121212121212"
        api.startChat(LocalUiPrincipal.ID, chatSpec(requestId))

        val cancel = api.cancelRequest(
            LocalUiPrincipal.ID,
            CancelInferenceSpec(
                requestId = requestId,
                commandId = "13131313-1313-1313-1313-131313131313",
                idempotencyKey = "cancel-done",
                canonicalInputDigest = digest,
            ),
        ) as OmniResult.Ok
        assertEquals(CancelPhase.TERMINAL, cancel.value.phase)
        assertTrue(cancel.value.isTerminal)
        // Port cancel not required when already terminal in service short-circuit —
        // either path is acceptable; phase must be TERMINAL.
    }

    @Test
    fun clientGeneratedRequestId_isPreservedThroughCancelAndQuery() = runBlocking {
        val requestId = "14141414-1414-1414-1414-141414141414"
        val started = api.startChat(LocalUiPrincipal.ID, chatSpec(requestId)) as OmniResult.Ok
        assertEquals(requestId, started.value.requestId)

        api.cancelRequest(
            LocalUiPrincipal.ID,
            CancelInferenceSpec(
                requestId = requestId,
                commandId = "15151515-1515-1515-1515-151515151515",
                idempotencyKey = "cancel-id-preserve",
                canonicalInputDigest = digest,
            ),
        )
        val q = api.queryRequest(LocalUiPrincipal.ID, requestId) as OmniResult.Ok
        assertEquals(requestId, q.value.requestId)
        // Claim-or-return: second start with same id returns existing (no re-exec).
        val again = api.startChat(LocalUiPrincipal.ID, chatSpec(requestId)) as OmniResult.Ok
        assertEquals(requestId, again.value.requestId)
        // Fake port claim-return means chatStarts may be 2 but handle is same terminal.
        assertEquals("CANCELLED", again.value.state)
    }

    @Test
    fun viewModel_cancelActive_updatesState() {
        val vm = PlaygroundViewModel(api)
        val requestId = "16161616-1616-1616-1616-161616161616"
        vm.sendChat(chatSpec(requestId))
        assertNotNull(vm.state.activeRequest)
        assertEquals("STREAMING", vm.state.activeRequest!!.state)
        assertTrue(PlaygroundUiAction.CANCEL in vm.state.activeRequest!!.allowedActions)

        vm.cancelActive(
            CancelInferenceSpec(
                requestId = requestId,
                commandId = "17171717-1717-1717-1717-171717171717",
                idempotencyKey = "vm-cancel",
                canonicalInputDigest = digest,
            ),
        )
        assertNotNull(vm.state.lastCancel)
        assertEquals(CancelPhase.TERMINAL, vm.state.lastCancel!!.phase)
        assertEquals("CANCELLED", vm.state.activeRequest!!.state)
    }

    @Test
    fun viewModel_cancel_whenNotAllowed_stateConflict() {
        inference.holdStreaming = false
        val vm = PlaygroundViewModel(api)
        val requestId = "18181818-1818-1818-1818-181818181818"
        vm.sendChat(chatSpec(requestId))
        assertTrue(vm.state.activeRequest!!.isTerminal)
        assertTrue(PlaygroundUiAction.CANCEL !in vm.state.activeRequest!!.allowedActions)

        vm.cancelActive(
            CancelInferenceSpec(
                requestId = requestId,
                commandId = "19191919-1919-1919-1919-191919191919",
                idempotencyKey = "vm-cancel-bad",
                canonicalInputDigest = digest,
            ),
        )
        assertEquals(OmniErrorCode.STATE_CONFLICT, vm.state.error!!.code)
    }

    @Test
    fun requestProjection_streamingAllowsCancel() {
        val strip = RequestUiProjection.project(
            com.omnillm.features.playground.ports.InferenceHandle(
                requestId = "20202020-2020-2020-2020-202020202020",
                operationKind = "chat",
                state = "STREAMING",
            ),
        )
        assertEquals("request.generating", strip.labelKey)
        assertTrue(PlaygroundUiAction.CANCEL in strip.allowedActions)
        assertFalse(strip.isTerminal)
    }

    @Test
    fun requestProjection_abortedUncertain_specialActions() {
        val strip = RequestUiProjection.project(
            com.omnillm.features.playground.ports.InferenceHandle(
                requestId = "21212121-2121-2121-2121-212121212121",
                operationKind = "chat",
                state = "ABORTED_UNCERTAIN",
            ),
        )
        assertEquals("request.uncertain", strip.labelKey)
        assertTrue(PlaygroundUiAction.DO_NOT_REUSE_SESSION in strip.allowedActions)
        assertTrue(PlaygroundUiAction.EXPORT_DIAGNOSTICS in strip.allowedActions)
        assertTrue(strip.isTerminal)
    }

    private fun chatSpec(requestId: String): ChatRequestSpec =
        ChatRequestSpec(
            identity = InferenceIdentity(
                requestId = requestId,
                idempotencyKey = "idem-$requestId",
                canonicalInputDigest = digest,
            ),
            modelRevisionId = modelId,
            messages = listOf(ChatMessage(role = "user", content = "stream me")),
            stream = true,
            sourceSession = SourceSessionRef.None,
        )
}
