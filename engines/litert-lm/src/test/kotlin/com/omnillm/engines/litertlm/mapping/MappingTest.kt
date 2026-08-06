package com.omnillm.engines.litertlm.mapping

import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.litertlm.sdk.SdkError
import com.omnillm.engines.litertlm.sdk.SdkErrorCode
import com.omnillm.engines.litertlm.sdk.SdkStreamEvent
import com.omnillm.engines.litertlm.sdk.SdkStreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MappingTest {

    @Test
    fun capabilityUnknown_mapsToCatalog() {
        val err = ErrorMapper.toOmniError(
            SdkError(
                code = SdkErrorCode.CAPABILITY_UNKNOWN,
                message = "embedding unproven",
                attributes = mapOf("operation" to "EMBED"),
            ),
        )
        assertTrue(err is OmniError.CAPABILITY_UNKNOWN)
        assertEquals("LiteRT-LM", err.details["engineId"])
    }

    @Test
    fun sanitize_redactsPathsAndPointers() {
        val out = ErrorMapper.sanitize(
            mapOf(
                "modelPath" to "/data/local/tmp/x.litertlm",
                "pointer" to "0xdeadbeef",
                "backend" to "cpu",
            ),
        )
        assertEquals(ErrorMapper.REDACTED, out["modelPath"])
        assertEquals(ErrorMapper.REDACTED, out["pointer"])
        assertEquals("cpu", out["backend"])
    }

    @Test
    fun eventNormalizer_terminalIsUnique() {
        val n = EventNormalizer(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            runtimeEpoch = 1L,
        )
        val delta = n.normalize(
            SdkStreamEvent(kind = SdkStreamKind.TOKEN_DELTA, payloadDigestHex = "ab".repeat(32)),
        )
        assertEquals(EngineEventKinds.DELTA, delta!!.kind)
        val stop = n.normalize(SdkStreamEvent(kind = SdkStreamKind.STOP))
        assertEquals(EngineEventKinds.TERMINAL, stop!!.kind)
        assertNull(n.normalize(SdkStreamEvent(kind = SdkStreamKind.TOKEN_DELTA)))
        assertNull(n.ensureTerminal("COMPLETED"))
    }

    @Test
    fun parameterValidator_failsClosedOnUnsupported() {
        val err = ParameterValidator.validateAttributes(
            mapOf("multimodalInput" to "true"),
        )
        assertNotNull(err)
        assertTrue(err is OmniError.CAPABILITY_UNSUPPORTED)
    }

    @Test
    fun parameterValidator_failsClosedOnUnknown() {
        val err = ParameterValidator.validateAttributes(
            mapOf("totallyUnknownParam" to "1"),
        )
        assertNotNull(err)
        assertTrue(err is OmniError.INVALID_REQUEST)
    }

    @Test
    fun phaseCancellation_defaultsUnknown() {
        val measured = PhaseCancellationMap.measuredModes()
        assertTrue(EnginePhases.REQUIRED.all { measured[it] == CancellationModes.UNKNOWN })
        assertTrue(PhaseCancellationMap.anyPhaseRequiresKillableWorker(measured))
    }
}
