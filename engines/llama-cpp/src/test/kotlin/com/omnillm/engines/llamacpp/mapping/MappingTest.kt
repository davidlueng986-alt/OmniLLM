package com.omnillm.engines.llamacpp.mapping

import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.llamacpp.native.NativeError
import com.omnillm.engines.llamacpp.native.NativeErrorCode
import com.omnillm.engines.llamacpp.native.NativeStreamEvent
import com.omnillm.engines.llamacpp.native.NativeStreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MappingTest {

    @Test
    fun errorMapper_mapsNativeCodesToCatalog() {
        val unsupported = ErrorMapper.toOmniError(
            NativeError(NativeErrorCode.UNSUPPORTED_PARAMETER, message = "gpuLayers"),
        )
        assertTrue(unsupported is OmniError.CAPABILITY_UNSUPPORTED)

        val cancel = ErrorMapper.toOmniError(NativeError(NativeErrorCode.CANCELLED))
        assertTrue(cancel is OmniError.CANCELLED)

        val crash = ErrorMapper.toOmniError(NativeError(NativeErrorCode.WORKER_CRASH))
        assertTrue(crash is OmniError.WORKER_DIED)
    }

    @Test
    fun errorMapper_sanitizesPathsAndPointers() {
        val err = ErrorMapper.toOmniError(
            NativeError(
                code = NativeErrorCode.MODEL_OPEN_FAILED,
                attributes = mapOf(
                    "path" to "/data/local/tmp/model.gguf",
                    "pointer" to "0xdeadbeef",
                    "backend" to "cpu",
                ),
            ),
        )
        assertEquals(ErrorMapper.REDACTED, err.details["path"])
        assertEquals(ErrorMapper.REDACTED, err.details["pointer"])
        assertEquals("cpu", err.details["backend"])
        assertEquals("llama.cpp", err.details["engineId"])
    }

    @Test
    fun eventNormalizer_halfOpenSequenceAndUniqueTerminal() {
        val req = RequestId.parse(UUID.randomUUID().toString())
        val norm = EventNormalizer(req, runtimeEpoch = 1L)

        val m = norm.normalize(NativeStreamEvent(NativeStreamKind.METADATA))!!
        assertEquals(0L, m.seq)
        assertEquals(EngineEventKinds.METADATA, m.kind)

        val d = norm.normalize(
            NativeStreamEvent(
                NativeStreamKind.TOKEN_DELTA,
                payloadDigestHex = "dd".repeat(32),
            ),
        )!!
        assertEquals(1L, d.seq)
        assertEquals(EngineEventKinds.DELTA, d.kind)

        val t = norm.normalize(
            NativeStreamEvent(
                NativeStreamKind.STOP,
                attributes = mapOf("stopReason" to "COMPLETED"),
            ),
        )!!
        assertEquals(2L, t.seq)
        assertTrue(t.isTerminal)

        assertNull(norm.normalize(NativeStreamEvent(NativeStreamKind.TOKEN_DELTA)))
        assertNull(norm.ensureTerminal("LATE"))
        assertTrue(norm.hasTerminal)
    }

    @Test
    fun eventNormalizer_catalogKindMapping() {
        assertEquals(EngineEventKinds.DELTA, EventNormalizer.catalogKindOf(NativeStreamKind.TOKEN_DELTA))
        assertEquals(EngineEventKinds.TERMINAL, EventNormalizer.catalogKindOf(NativeStreamKind.STOP))
    }

    @Test
    fun parameterValidator_rejectsUnknownAndUnsupported() {
        val unknown = ParameterValidator.validateAttributes(mapOf("totallyUnknown" to "1"))
        assertNotNull(unknown)
        assertTrue(unknown is OmniError.INVALID_REQUEST)

        val unsupported = ParameterValidator.validateAttributes(mapOf("gpuLayers" to "32"))
        assertNotNull(unsupported)
        assertTrue(unsupported is OmniError.CAPABILITY_UNSUPPORTED)

        assertNull(ParameterValidator.validateAttributes(mapOf("nCtx" to "2048", "temperature" to "0.7")))
    }

    @Test
    fun phaseCancellation_defaultsUnknownAndRequiresWorker() {
        val measured = PhaseCancellationMap.measuredModes()
        EnginePhases.REQUIRED.forEach { phase ->
            assertEquals(CancellationModes.UNKNOWN, measured[phase])
        }
        assertTrue(PhaseCancellationMap.anyPhaseRequiresKillableWorker(measured))
        assertFalse(
            PhaseCancellationMap.requiresKillableWorker(CancellationModes.COOPERATIVE),
        )
        assertTrue(
            PhaseCancellationMap.requiresKillableWorker(CancellationModes.UNKNOWN),
        )
        assertEquals(
            CancellationModes.COOPERATIVE,
            PhaseCancellationMap.expectedModes[EnginePhases.GENERATE],
        )
    }
}
