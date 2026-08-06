package com.omnillm.features.benchmark

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.benchmark.api.BenchmarkApi
import com.omnillm.features.benchmark.api.BenchmarkCommandIdentity
import com.omnillm.features.benchmark.domain.EnvironmentSnapshot
import com.omnillm.features.benchmark.domain.LatencySketch
import com.omnillm.features.benchmark.domain.MeasurementMetrics
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.ports.BenchmarkCapabilityAvailabilityPort
import com.omnillm.features.benchmark.ports.FixedBenchmarkCapabilityAvailabilityPort
import com.omnillm.features.benchmark.ports.FixedBenchmarkEnvironmentPort
import com.omnillm.runtime.JobManagerModule
import com.omnillm.runtime.ObservabilityModule
import org.junit.Assert.assertTrue

internal object TestFixtures {
    val REV_A: String = "a".repeat(64)
    val REV_B: String = "b".repeat(64)
    val DIGEST: String = "c".repeat(64)
    val PACKAGE: String = "d".repeat(64)
    val FIXTURE: String = "e".repeat(64)

    fun baseProfile(
        modelRevisionId: String = REV_A,
        engineBuildId: String = "engine-build-1",
        backend: String = "cpu",
        contextLength: Int = 2048,
        threads: Int = 4,
        thermalCeilingCelsius: Int? = 45,
        sampleCount: Int = 5,
        fixtureCorpusDigest: String? = FIXTURE,
    ): MeasurementProfile =
        MeasurementProfile(
            engineBuildId = engineBuildId,
            adapterVersion = "adapter-1.0",
            backend = backend,
            modelRevisionId = modelRevisionId,
            artifactPackageId = PACKAGE,
            quantizationDescriptor = "q4_k_m",
            deviceExecutionFingerprint = "device-fp-1",
            driver = "driver-1",
            os = "android-36",
            pageSizeBytes = 16_384,
            contextLength = contextLength,
            kvFormat = "f16",
            threads = threads,
            batch = 1,
            parallelSessions = 1,
            promptTokenLimit = 512,
            outputTokenLimit = 128,
            tokenizerTemplateDigest = null,
            samplingConfigDigest = null,
            fixtureCorpusDigest = fixtureCorpusDigest,
            warmupCount = 1,
            sampleCount = sampleCount,
            thermalCeilingCelsius = thermalCeilingCelsius,
            powerCondition = "battery_unrestricted",
            metricMethod = "hdr_histogram",
            clockSource = "monotonic_ns",
            histogramSketchVersion = "hdr-v1",
        )

    fun sampleMetrics(
        methodVersion: String = "hdr-v1",
        sampleCount: Long = 5L,
    ): MeasurementMetrics =
        MeasurementMetrics(
            loadTimeMs = 120.0,
            ttftMs = 45.0,
            throughputTokensPerSec = 32.0,
            interTokenLatency = LatencySketch(
                methodVersion = methodVersion,
                sampleCount = sampleCount,
                sumMs = 100.0,
                p50Ms = 18.0,
                p95Ms = 40.0,
                p99Ms = 55.0,
                errorBoundMs = 2.0,
                sketchDigest = "f".repeat(64),
                evidenceLabel = EvidenceLabel.MEASURED,
            ),
            endToEndLatencyMs = 500.0,
            peakResidentBytes = 512_000_000L,
            peakAcceleratorBytes = null,
            peakWorkspaceBytes = 64_000_000L,
            energyHintJoules = null,
            thermalEventCount = 0,
            errorCount = 0,
            evidenceLabel = EvidenceLabel.MEASURED,
            methodVersion = methodVersion,
        )

    fun nominalEnv(
        revision: String = REV_A,
        engineBuildId: String = "engine-build-1",
        backend: String = "cpu",
        thermalCelsius: Int? = 35,
        thermalState: String = "NOMINAL",
    ): EnvironmentSnapshot =
        EnvironmentSnapshot(
            thermalCelsius = thermalCelsius,
            thermalState = thermalState,
            batteryPercent = 80,
            powerSource = "battery",
            backgroundRestriction = false,
            driverResetDetected = false,
            backendActual = backend,
            engineBuildIdActual = engineBuildId,
            modelRevisionIdActual = revision,
            capturedAtEpochMs = 1_000L,
        )

    fun command(
        commandId: String = "11111111-1111-1111-1111-111111111111",
        idempotencyKey: String = "bench-1",
        digest: String = DIGEST,
    ): BenchmarkCommandIdentity =
        BenchmarkCommandIdentity(
            commandId = commandId,
            idempotencyKey = idempotencyKey,
            canonicalInputDigest = digest,
        )

    fun api(
        env: EnvironmentSnapshot = nominalEnv(),
        capabilities: BenchmarkCapabilityAvailabilityPort =
            FixedBenchmarkCapabilityAvailabilityPort(
                BenchmarkFeatureModule.REQUIRED_CAPABILITIES.associateWith {
                    CapabilityState.SUPPORTED
                },
            ),
        clockMs: () -> Long = { 1_000L },
    ): BenchmarkApi =
        BenchmarkFeatureModule.createApi(
            jobManager = JobManagerModule.createManager(),
            observability = ObservabilityModule.createFacade(clockWallMs = clockMs),
            environment = FixedBenchmarkEnvironmentPort(env),
            capabilityAvailability = capabilities,
            clockMs = clockMs,
        )

    fun <T> assertOk(result: OmniResult<T>): T {
        assertTrue("expected Ok, got $result", result is OmniResult.Ok)
        return (result as OmniResult.Ok).value
    }
}
