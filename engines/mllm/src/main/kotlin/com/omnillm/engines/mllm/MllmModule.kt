package com.omnillm.engines.mllm

import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.mllm.lock.UpstreamLock
import com.omnillm.engines.mllm.lock.UpstreamLockLoader
import com.omnillm.engines.mllm.qualification.QualificationCells
import com.omnillm.engines.mllm.server.GomllmServerBridge
import com.omnillm.engines.mllm.server.MllmServerBackend
import com.omnillm.engines.mllm.server.OkHttpMllmTransport
import com.omnillm.engines.mllm.server.ServerBackend
import com.omnillm.engines.mllm.server.StubServerBackend
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Module `:engines:mllm` — Engine Pack (ENGINE-MLLM).
 *
 * Integration shape: device-local client-server via the upstream Go
 * `mllm_server.aar` (UbiquitousLearning/mllm v2.0.0 / mllm-chat v2.0.0).
 * [MllmServerBackend] starts the Go server (`gomllm.Gomllm.startServer`) and
 * speaks its OpenAI-compatible HTTP/SSE protocol on 127.0.0.1:8080.
 * Embedded server is private; Adapter owns lifecycle, private channel, runtime
 * credential, and canonical translation (ENGINE-MLLM §2).
 *
 * Hard rules:
 * - Never write OmniLLM DB / model store (ENGINE-STANDARD §3 / ADR-010)
 * - Never return native / server pointers across process
 * - Never silently ignore unsupported parameters
 * - Plan has no domain mutation (ADR-002)
 * - Incomplete UPSTREAM.lock ⇒ exploratory only; Registry stays UNKNOWN
 * - Design BASELINE ≠ runtime SUPPORTED
 * - Missing binary / AAR ⇒ fail closed (never load-as-success)
 */
object MllmModule {
    const val MODULE_PATH: String = ":engines:mllm"
    const val ENGINE_ID: String = "mllm"

    /** Build id until a complete lock produces a real artifact digest. */
    const val DEFAULT_ENGINE_BUILD_ID: String = "mllm-not-locked"

    const val DESIGN_STATUS: String = "BASELINE"
    const val QUALIFICATION_STATUS: String = "UNQUALIFIED"
    const val REGISTRY_EXPOSURE: String = "UNKNOWN"

    fun defaultEngineBuildId(): EngineBuildId =
        EngineBuildId.parse(DEFAULT_ENGINE_BUILD_ID)

    /**
     * Production [ServerBackend]: real mllm Go in-app server bridge +
     * loopback HTTP/SSE transport. Constructing it is host-safe; the GoMobile
     * binding loads only on Android arm64 at first use.
     */
    fun createRealServer(): MllmServerBackend =
        MllmServerBackend(
            bridge = GomllmServerBridge,
            transport = OkHttpMllmTransport(sharedOkHttpClient()),
        )

    /** Shared loopback transport client (connection pool across operations). */
    internal fun sharedOkHttpClient(): OkHttpClient =
        SharedHttpClientHolder.client

    private object SharedHttpClientHolder {
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE stream: no read deadline
            .build()
    }

    /**
     * Factory for the Kotlin adapter. Default [ServerBackend] is the real
     * [MllmServerBackend]; [StubServerBackend] remains available for host unit
     * tests only (never a silent production fallback — INV-018 style).
     *
     * [allowUnprovenExecution] defaults to lock completeness: with a complete
     * supply-chain lock the real execute paths run (qualification cells still
     * stay UNQUALIFIED — Registry exposure is a separate axis); with an
     * incomplete lock everything fails closed CAPABILITY_UNKNOWN.
     */
    fun createEngine(
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        server: ServerBackend = createRealServer(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        allowUnprovenExecution: Boolean = lock.isComplete(),
    ): MllmEngine =
        MllmEngine(
            engineBuildId = engineBuildId,
            lock = lock,
            server = server,
            allowUnprovenExecution = allowUnprovenExecution,
        )

    /**
     * Register build metadata with [EngineRegistry] and optionally seed UNQUALIFIED cells.
     * Does **not** mark any cell SUPPORTED — only PASS + QUALIFIED_WITH_ENVELOPE
     * evidence may project SUPPORTED later.
     *
     * @return registration metadata (designStatus BASELINE; upstreamLocked false
     *   when lock template is incomplete)
     */
    fun registerWith(
        registry: EngineRegistry,
        lock: UpstreamLock = UpstreamLockLoader.loadFromClasspathOrTemplate(),
        engineBuildId: EngineBuildId = lock.resolvedEngineBuildId()
            ?: defaultEngineBuildId(),
        seedCells: Boolean = true,
        deviceFingerprint: DeviceExecutionFingerprint? = null,
        driverFingerprint: String = QualificationCells.DRIVER_PLACEHOLDER,
    ): EngineRegistration {
        val reg = EngineRegistration(
            engineBuildId = engineBuildId,
            engineId = ENGINE_ID,
            upstreamCommitOrTag = lock.upstreamCommitOrTag(),
            patchSetDigest = lock.patchDigest.orEmpty(),
            toolchainDigest = lock.toolchainDigest.orEmpty(),
            artifactDigest = lock.artifactDigest.orEmpty(),
            designStatus = DESIGN_STATUS,
        )
        registry.register(reg)
        if (seedCells) {
            seedUnqualifiedPlaceholders(
                registry = registry,
                engineBuildId = engineBuildId,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = driverFingerprint,
            )
        }
        return reg
    }

    /**
     * Seed capability-matrix UNQUALIFIED / NOT_EXECUTED cells.
     * Used by [com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment].
     */
    fun seedUnqualifiedPlaceholders(
        registry: EngineRegistry,
        engineBuildId: EngineBuildId = defaultEngineBuildId(),
        deviceFingerprint: DeviceExecutionFingerprint? = null,
        driverFingerprint: String = QualificationCells.DRIVER_PLACEHOLDER,
    ): Int = QualificationCells.seedUnqualified(
        registry = registry,
        engineBuildId = engineBuildId,
        deviceFingerprint = deviceFingerprint,
        driverFingerprint = driverFingerprint,
    )
}
