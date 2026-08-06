package com.omnillm.engines.mlcllm.mapping

import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.engines.api.CancellationModes
import com.omnillm.engines.api.EngineEventKinds
import com.omnillm.engines.api.EnginePhases
import com.omnillm.engines.mlcllm.runtime.NativeError
import com.omnillm.engines.mlcllm.runtime.NativeErrorCode
import com.omnillm.engines.mlcllm.runtime.NativeStreamEvent
import com.omnillm.engines.mlcllm.runtime.NativeStreamKind
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
            NativeError(
                code = NativeErrorCode.UNKNOWN_CAPABILITY,
                message = "embedding unproven",
                attributes = mapOf("operation" to "EMBED"),
            ),
        )
        assertTrue(err is OmniError.CAPABILITY_UNKNOWN)
        assertEquals("MLC-LLM", err.details["engineId"])
    }

    @Test
    fun workerAndDriverCrash_mapToWorkerDied() {
        val worker = ErrorMapper.toOmniError(
            NativeError(code = NativeErrorCode.WORKER_CRASH, message = "worker gone"),
        )
        assertTrue(worker is OmniError.WORKER_DIED)
        val driver = ErrorMapper.toOmniError(
            NativeError(code = NativeErrorCode.DRIVER_CRASH, message = "gpu reset"),
        )
        assertTrue(driver is OmniError.WORKER_DIED)
    }

    @Test
    fun sanitize_redactsPathsAndPointers() {
        val out = ErrorMapper.sanitize(
            mapOf(
                "modelPath" to "/data/local/tmp/x.so",
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
            NativeStreamEvent(kind = NativeStreamKind.TOKEN_DELTA, payloadDigestHex = "ab".repeat(32)),
        )
        assertEquals(EngineEventKinds.DELTA, delta!!.kind)
        val stop = n.normalize(NativeStreamEvent(kind = NativeStreamKind.STOP))
        assertEquals(EngineEventKinds.TERMINAL, stop!!.kind)
        assertNull(n.normalize(NativeStreamEvent(kind = NativeStreamKind.TOKEN_DELTA)))
        assertNull(n.ensureTerminal("COMPLETED"))
    }

    @Test
    fun parameterValidator_failsClosedOnUnsupported() {
        val err = ParameterValidator.validateAttributes(
            mapOf("multimodalImage" to "true"),
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
