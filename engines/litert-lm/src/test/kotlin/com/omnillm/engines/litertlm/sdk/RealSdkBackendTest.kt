package com.omnillm.engines.litertlm.sdk

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.DeviceDescriptor
import com.omnillm.engines.litertlm.LitertLmModule
import com.omnillm.engines.litertlm.lock.UpstreamLock
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RealSdkBackend fail-closed behavior without the official AAR on the classpath.
 * Never asserts SUPPORTED or successful load of missing natives.
 */
class RealSdkBackendTest {

    @Test
    fun absentAar_isNotAvailable_andCreateOrNullIsNull() {
        val bridge = AbsentLitertLmSdkBridge()
        assertFalse(bridge.isPresent())
        val backend = RealSdkBackend.create(bridge = bridge)
        assertFalse(backend.isAvailable())
        assertFalse(backend.isSdkPresent())
        assertNull(RealSdkBackend.createOrNull(bridge = bridge))
    }

    @Test
    fun absentAar_probeLoadGenerate_failClosed() {
        val backend = RealSdkBackend.create(bridge = AbsentLitertLmSdkBridge())

        val probe = backend.probe(
            SdkProbeRequest(backend = "cpu", operationToken = "op-probe-1"),
        )
        assertTrue(probe is SdkResult.Err)
        assertEquals(SdkErrorCode.NOT_AVAILABLE, (probe as SdkResult.Err).error.code)

        val load = backend.loadEngine(
            SdkLoadRequest(
                storageRootKey = "broker:inst-1",
                installationKey = "inst-1",
                backend = "cpu",
                privilegedLoadTicketId = "ticket-1",
            ),
        )
        assertTrue(load is SdkResult.Err)
        assertEquals(SdkErrorCode.NOT_AVAILABLE, (load as SdkResult.Err).error.code)

        val gen = backend.generate(
            conversation = SdkConversationToken("nope"),
            request = SdkGenerateRequest(
                operationToken = "op-gen-1",
                promptDigestHex = "ab".repeat(32),
                maxTokens = 8,
            ),
            cancelFlag = { false },
            onEvent = {},
        )
        assertTrue(gen is SdkResult.Err)
        assertEquals(SdkErrorCode.NOT_AVAILABLE, (gen as SdkResult.Err).error.code)
    }

    @Test
    fun presentBridge_withoutExploratory_loadIsCapabilityUnknown() {
        val bridge = FakePresentBridge()
        val backend = RealSdkBackend.create(
            allowExploratoryExecute = false,
            bridge = bridge,
        )
        assertTrue(backend.isAvailable())

        val load = backend.loadEngine(
            SdkLoadRequest(
                storageRootKey = "broker:inst-1",
                installationKey = "inst-1",
                backend = "cpu",
                privilegedLoadTicketId = "ticket-1",
            ),
        )
        assertTrue(load is SdkResult.Err)
        assertEquals(SdkErrorCode.CAPABILITY_UNKNOWN, (load as SdkResult.Err).error.code)
    }

    @Test
    fun presentBridge_withExploratory_loadSucceeds_embedStillUnknown() {
        val bridge = FakePresentBridge()
        val backend = RealSdkBackend.create(
            allowExploratoryExecute = true,
            bridge = bridge,
        )
        val load = backend.loadEngine(
            SdkLoadRequest(
                storageRootKey = "broker:inst-1",
                installationKey = "inst-1",
                backend = "cpu",
                privilegedLoadTicketId = "ticket-1",
                attributes = mapOf("resolvedModelPath" to "/broker/resolved/model.litertlm"),
            ),
        )
        assertTrue(load is SdkResult.Ok)
        val engine = (load as SdkResult.Ok).value

        val emb = backend.embed(
            engine,
            SdkEmbedRequest(
                operationToken = "op-emb",
                inputDigestHex = "cd".repeat(32),
            ),
        )
        assertTrue(emb is SdkResult.Err)
        assertEquals(SdkErrorCode.CAPABILITY_UNKNOWN, (emb as SdkResult.Err).error.code)
    }

    @Test
    fun factory_productionUsesReal_hostTestsUseStub() {
        val prod = SdkBackendFactory.forProduction(UpstreamLock.template())
        assertEquals("RealSdkBackend", prod.javaClass.simpleName)
        assertFalse(prod.isExploratoryExecuteAllowed()) // incomplete lock

        val stub = SdkBackendFactory.forHostUnitTests()
        assertEquals("StubSdkBackend", stub.javaClass.simpleName)
        assertTrue(stub.exploratoryDryRun)
    }

    @Test
    fun productionEngine_describe_reportsRealBackendKind() = runBlocking {
        val eng = LitertLmModule.createProductionEngine(
            lock = UpstreamLock.template(),
            forceExploratory = false,
        )
        val desc = (eng.describe(
            DeviceDescriptor(DeviceExecutionFingerprint.parse("fp-real-sdk")),
        ) as OmniResult.Ok).value
        assertEquals("RealSdkBackend", desc.notes["sdkBackendKind"])
        assertEquals("false", desc.notes["sdkAvailable"]) // AAR absent on host JVM
        assertEquals(LitertLmModule.QUALIFICATION_STATUS, desc.notes["qualificationStatus"])
    }

    @Test
    fun productionEngine_commitLoad_failClosedWithoutAar() = runBlocking {
        val eng = LitertLmModule.createProductionEngine()
        val device = DeviceExecutionFingerprint.parse("fp-load-fail")
        val plan = (
            eng.planLoad(
                com.omnillm.engines.api.LoadInput(
                    requestId = com.omnillm.core.contracts.RequestId.parse(
                        java.util.UUID.randomUUID().toString(),
                    ),
                    principalId = com.omnillm.core.contracts.PrincipalId.parse("p1"),
                    installationId = com.omnillm.core.identity.InstallationId.parse(
                        "550e8400-e29b-41d4-a716-446655440099",
                    ),
                    modelRevisionId = com.omnillm.core.canonical.generated.ModelRevisionId.parse(
                        "ab".repeat(32),
                    ),
                    loadKey = com.omnillm.core.contracts.LoadKey(
                        modelRevisionId = com.omnillm.core.canonical.generated.ModelRevisionId.parse(
                            "ab".repeat(32),
                        ),
                        engineBuildId = eng.engineBuildId,
                        backend = "cpu",
                        deviceExecutionFingerprint = device,
                        templateEpoch = 1L,
                        tokenizerEpoch = 1L,
                        loadConfigurationDigest = com.omnillm.core.canonical.generated.Sha256Digest.parse(
                            "cd".repeat(32),
                        ),
                    ),
                    device = DeviceDescriptor(device),
                    storageRootKey = "storage",
                    runtimeEpoch = 1L,
                    revocationEpoch = 0L,
                    templateEpoch = 1L,
                    tokenizerEpoch = 1L,
                ),
            ) as OmniResult.Ok
            ).value

        val res = com.omnillm.core.resource.Reservation(
            reservationId = com.omnillm.core.resource.ReservationId.parse("res-1"),
            principalId = "p1",
            issuerBootId = "boot",
            runtimeEpoch = 1L,
            nonce = "n1",
            deadlineMonotonic = Long.MAX_VALUE / 4,
            envelope = plan.resourceEnvelope,
        )
        val commit = com.omnillm.engines.api.CommitContext(
            commitId = com.omnillm.core.contracts.CommitId.parse(
                java.util.UUID.randomUUID().toString(),
            ),
            requestId = plan.requestId,
            principalId = plan.principalId,
            reservationId = res.reservationId,
            revisionLeaseId = com.omnillm.core.contracts.RevisionLeaseId.parse("lease"),
            issuerBootId = "boot",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            oneShotNonce = "nonce",
            privilegedLoadTicketId = "ticket",
        )
        val result = eng.commitLoad(plan, res, commit)
        assertTrue(result is OmniResult.Err)
        val err = (result as OmniResult.Err).error
        // NOT_AVAILABLE (no AAR) maps to ASSET_NOT_READY via ErrorMapper
        assertNotNull(err)
        assertTrue(
            err is OmniError.ASSET_NOT_READY || err is OmniError.CAPABILITY_UNKNOWN,
        )
    }

    @Test
    fun stub_exploratoryOff_failsClosed() {
        val stub = StubSdkBackend(exploratoryDryRun = false)
        assertFalse(stub.isAvailable())
        val probe = stub.probe(SdkProbeRequest(backend = "cpu", operationToken = "t"))
        assertTrue(probe is SdkResult.Err)
        assertEquals(SdkErrorCode.CAPABILITY_UNKNOWN, (probe as SdkResult.Err).error.code)
    }

    /**
     * Fake bridge that reports present without loading real SDK classes.
     * Used to exercise RealSdkBackend policy gates in host tests.
     */
    private class FakePresentBridge : LitertLmSdkBridge {
        private val engines = mutableSetOf<String>()
        private var seq = 0

        override fun isPresent(): Boolean = true
        override fun libraryLabel(): String = "fake-present-litert"

        override fun openEngine(
            modelPathBrokerKey: String,
            backend: String,
            attributes: Map<String, String>,
        ): SdkResult<SdkEngineToken> {
            if (attributes["resolvedModelPath"].isNullOrBlank()) {
                return SdkResult.err(
                    SdkError(
                        code = SdkErrorCode.CAPABILITY_UNKNOWN,
                        message = "missing resolvedModelPath",
                    ),
                )
            }
            val id = "fake-engine-${++seq}"
            engines.add(id)
            return SdkResult.ok(SdkEngineToken(id))
        }

        override fun createConversation(
            engine: SdkEngineToken,
            attributes: Map<String, String>,
        ): SdkResult<SdkConversationToken> =
            SdkResult.ok(SdkConversationToken("fake-conv-${++seq}"))

        override fun generate(
            conversation: SdkConversationToken,
            request: SdkGenerateRequest,
            cancelFlag: () -> Boolean,
            onEvent: (SdkStreamEvent) -> Unit,
        ): SdkResult<SdkGenerateOutcome> {
            onEvent(SdkStreamEvent(kind = SdkStreamKind.STOP, attributes = mapOf("stopReason" to "COMPLETED")))
            return SdkResult.ok(
                SdkGenerateOutcome(promptTokens = 1, completionTokens = 1, stopReason = "COMPLETED"),
            )
        }

        override fun closeConversation(conversation: SdkConversationToken): SdkResult<Unit> =
            SdkResult.ok(Unit)

        override fun closeEngine(engine: SdkEngineToken): SdkResult<Unit> {
            engines.remove(engine.value)
            return SdkResult.ok(Unit)
        }

        override fun requestCancel(operationToken: String): SdkResult<Unit> =
            SdkResult.ok(Unit)
    }
}
