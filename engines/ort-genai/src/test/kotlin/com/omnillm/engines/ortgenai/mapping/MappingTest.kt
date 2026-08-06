package com.omnillm.engines.ortgenai.mapping

import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.ortgenai.session.GenAiError
import com.omnillm.engines.ortgenai.session.GenAiErrorCode
import com.omnillm.engines.ortgenai.session.GenAiStreamEvent
import com.omnillm.engines.ortgenai.session.GenAiStreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MappingTest {

    @Test
    fun capabilityUnknown_mapsToCatalog() {
        val err = ErrorMapper.toOmniError(
            GenAiError(
                code = GenAiErrorCode.UNKNOWN_CAPABILITY,
                message = "embedding unproven",
                attributes = mapOf("operation" to "EMBED"),
            ),
        )
        assertTrue(err is OmniError.CAPABILITY_UNKNOWN)
        assertEquals("ONNX-Runtime-GenAI", err.details["engineId"])
    }

    @Test
    fun providerCrash_mapsToWorkerDied() {
        val err = ErrorMapper.toOmniError(
            GenAiError(
                code = GenAiErrorCode.PROVIDER_CRASH,
                message = "EP process died",
            ),
        )
        assertTrue(err is OmniError.WORKER_DIED)
    }

    @Test
    fun sanitize_redactsPathsAndPointers() {
        val out = ErrorMapper.sanitize(
            mapOf(
                "modelPath" to "/data/local/tmp/x.onnx",
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
            GenAiStreamEvent(
                kind = GenAiStreamKind.TOKEN_DELTA,
                payloadDigestHex = "ab".repeat(32),
            ),
        )
        assertEquals(EngineEventKinds.DELTA, delta!!.kind)
        val stop = n.normalize(GenAiStreamEvent(kind = GenAiStreamKind.STOP))
        assertEquals(EngineEventKinds.TERMINAL, stop!!.kind)
        assertNull(n.normalize(GenAiStreamEvent(kind = GenAiStreamKind.TOKEN_DELTA)))
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
    fun phaseCancellation_measuredDefaultsUnknown() {
        val measured = PhaseCancellationMap.measuredModes()
        EnginePhases.REQUIRED.forEach { phase ->
            assertEquals(CancellationModes.UNKNOWN, measured[phase])
        }
        assertTrue(PhaseCancellationMap.anyPhaseRequiresKillableWorker(measured))
        assertFalse(
            CancellationModes.isPrivilegedSafe(PhaseCancellationMap.modeForPhase(EnginePhases.LOAD)),
        )
        assertEquals(
            CancellationModes.WORKER_KILL_ONLY,
            PhaseCancellationMap.expectedModes[EnginePhases.LOAD],
        )
        assertEquals(
            CancellationModes.COOPERATIVE,
            PhaseCancellationMap.expectedModes[EnginePhases.GENERATE],
        )
    }
}
