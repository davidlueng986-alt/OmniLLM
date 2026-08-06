package com.omnillm.engines.mllm.mapping

import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.mllm.server.ServerError
import com.omnillm.engines.mllm.server.ServerErrorCode
import com.omnillm.engines.mllm.server.ServerStreamEvent
import com.omnillm.engines.mllm.server.PrivateChannelProtocol
import com.omnillm.engines.mllm.server.ChannelKind
import com.omnillm.engines.mllm.server.PrivateChannelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MappingTest {

    @Test
    fun errorMapper_mapsNotLockedAndCrash() {
        val notLocked = ErrorMapper.toOmniError(
            ServerError(ServerErrorCode.NOT_LOCKED, message = "lock incomplete"),
        )
        assertTrue(notLocked is OmniError.CAPABILITY_UNKNOWN)

        val crash = ErrorMapper.toOmniError(
            ServerError(ServerErrorCode.SERVER_CRASH, message = "server died"),
        )
        assertTrue(crash is OmniError.WORKER_DIED)

        val channel = ErrorMapper.toOmniError(
            ServerError(ServerErrorCode.CHANNEL_POLICY, message = "LAN refused"),
        )
        assertTrue(channel is OmniError.CAPABILITY_UNSUPPORTED)
    }

    @Test
    fun errorMapper_sanitize_redactsSecrets() {
        val out = ErrorMapper.sanitize(
            mapOf(
                "modelPath" to "/data/local/tmp/model",
                "runtimeCredential" to "super-secret",
                "port" to "8080",
                "operationToken" to "op-1",
                "backend" to "cpu",
            ),
        )
        assertEquals(ErrorMapper.REDACTED, out["modelPath"])
        assertEquals(ErrorMapper.REDACTED, out["runtimeCredential"])
        assertEquals(ErrorMapper.REDACTED, out["port"])
        assertEquals("op-1", out["operationToken"])
        assertEquals("cpu", out["backend"])
    }

    @Test
    fun parameterValidator_rejectsUnknownAndUnsupported() {
        assertNotNull(ParameterValidator.validateAttributes(mapOf("nope" to "1")))
        assertNotNull(
            ParameterValidator.validateAttributes(mapOf("lanBind" to "true")),
        )
        assertNull(
            ParameterValidator.validateAttributes(mapOf("maxTokens" to "32", "backend" to "cpu")),
        )
    }

    @Test
    fun eventNormalizer_uniqueTerminal() {
        val norm = EventNormalizer(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            runtimeEpoch = 1L,
        )
        val m = norm.normalize(ServerStreamEvent.Metadata())
        assertEquals(EngineEventKinds.METADATA, m!!.kind)
        val d = norm.normalize(
            ServerStreamEvent.Delta(
                payloadDigestHex = "dd".repeat(32),
            ),
        )
        assertEquals(EngineEventKinds.DELTA, d!!.kind)
        val t1 = norm.normalize(ServerStreamEvent.Stop(stopReason = "COMPLETED"))
        assertEquals(EngineEventKinds.TERMINAL, t1!!.kind)
        assertTrue(norm.hasTerminal)
        assertNull(norm.normalize(ServerStreamEvent.Stop(stopReason = "AGAIN")))
        assertNull(norm.ensureTerminal("X"))
    }

    @Test
    fun phaseCancellation_expectedVsMeasured() {
        assertEquals(
            CancellationModes.WORKER_KILL_ONLY,
            PhaseCancellationMap.expectedModes[EnginePhases.LOAD],
        )
        assertEquals(
            CancellationModes.UNKNOWN,
            PhaseCancellationMap.modeForPhase(EnginePhases.GENERATE),
        )
        assertTrue(PhaseCancellationMap.anyPhaseRequiresKillableWorker())
    }

    @Test
    fun privateChannelPolicy_refusesLanAndMissingCredential() {
        assertNotNull(
            PrivateChannelProtocol.refuseReason(
                kind = ChannelKind.LOCALHOST_TCP,
                bindHost = "0.0.0.0",
                hasRuntimeCredential = true,
                allowLocalhostTcp = true,
            ),
        )
        assertNotNull(
            PrivateChannelProtocol.refuseReason(
                kind = ChannelKind.UNIX_DOMAIN,
                bindHost = null,
                hasRuntimeCredential = false,
                allowLocalhostTcp = false,
            ),
        )
        assertNull(
            PrivateChannelProtocol.refuseReason(
                kind = ChannelKind.UNIX_DOMAIN,
                bindHost = null,
                hasRuntimeCredential = true,
                allowLocalhostTcp = false,
            ),
        )

        val cfg = PrivateChannelConfig(
            kind = ChannelKind.LOCALHOST_TCP,
            bindHost = "127.0.0.1",
            runtimeCredential = "cred",
            allowLocalhostTcp = true,
        )
        assertTrue(cfg.isPolicyAllowed())
        assertFalse(
            PrivateChannelConfig(kind = ChannelKind.UNKNOWN, runtimeCredential = "x")
                .isPolicyAllowed(),
        )
        assertTrue(PrivateChannelProtocol.Methods.isKnown(PrivateChannelProtocol.Methods.PROBE))
        assertTrue(
            PrivateChannelProtocol.protocolCatalogFingerprint()
                .contains(PrivateChannelProtocol.PROTOCOL_ID),
        )
    }
}
