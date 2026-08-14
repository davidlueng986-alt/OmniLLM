package com.omnillm.android.runtimeservice.controlplane

import android.os.Build
import android.util.Log
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.ProductBuildMode
import com.omnillm.engines.api.EngineRegistration
import com.omnillm.engines.api.EngineRegistry
import com.omnillm.engines.litertlm.LitertLmModule
import com.omnillm.engines.llamacpp.LlamaCppEngine
import com.omnillm.engines.llamacpp.LlamaCppModule
import com.omnillm.engines.llamacpp.lock.UpstreamLockLoader
import com.omnillm.engines.llamacpp.native.JniNativeBackend
import com.omnillm.engines.mlcllm.MlcLlmModule
import com.omnillm.engines.mlcllm.QualificationCells as MlcQualificationCells
import com.omnillm.engines.mllm.MllmModule
import com.omnillm.engines.ortgenai.OrtGenaiModule

/**
 * Engine Pack attach for the `:runtime` control plane (ENGINE-STANDARD, INV-001).
 *
 * Attaches **all catalog engines** to a single [EngineRegistry]:
 * - `llama.cpp` — real [JniNativeBackend] when packaged; else fail-closed (no Stub).
 * - LiteRT-LM, MLC-LLM, mllm, ONNX-Runtime-GenAI — metadata + UNQUALIFIED cells only
 *   (stub/UNKNOWN; no real native/SDK load).
 *
 * Rules:
 * - Runs only after lifecycle READY/DEGRADED (caller enforces).
 * - Registers build metadata + UNQUALIFIED cells only (no QUALIFIED/SUPPORTED without evidence).
 * - Selection / exposure governed by [EngineSelectionPolicy].
 * - Does not write OmniLLM domain DB (ENGINE-STANDARD §3 / ADR-010).
 */
class EnginePackAttachment private constructor(
    val registry: EngineRegistry,
    val llamaCppEngine: LlamaCppEngine?,
    val registrationsByEngineId: Map<String, EngineRegistration>,
    val nativeLibraryPresent: Boolean,
    val notes: Map<String, String>,
    /**
     * Live-attached peer engines keyed by catalog engineId (C-07). Empty when
     * policy is compliance or the official SDK/API is absent — peers stay
     * metadata-only (mlc/mllm are ALWAYS metadata-only; see [EngineSelectionPolicy]).
     */
    val peerEngines: Map<String, com.omnillm.engines.api.OmniEngine> = emptyMap(),
) {
    val llamaCppAttached: Boolean get() = llamaCppEngine != null

    val llamaCppRegistration: EngineRegistration?
        get() = registrationsByEngineId[LlamaCppModule.ENGINE_ID]

    /** LiteRT-LM real engine when attached live (C-07); null = metadata-only. */
    val litertLmEngine: com.omnillm.engines.litertlm.LitertLmEngine?
        get() = peerEngines[LitertLmModule.ENGINE_ID] as? com.omnillm.engines.litertlm.LitertLmEngine

    /** ONNX Runtime GenAI real engine when attached live (C-07); null = metadata-only. */
    val ortGenaiEngine: com.omnillm.engines.ortgenai.OrtGenaiEngine?
        get() = peerEngines[OrtGenaiModule.ENGINE_ID] as? com.omnillm.engines.ortgenai.OrtGenaiEngine

    /** Live peer engine by catalog engineId (null = metadata-only). */
    fun liveEngine(engineId: String): com.omnillm.engines.api.OmniEngine? = peerEngines[engineId]

    /** engineBuildId values of live-attached peer engines (binding route keys). */
    val livePeerEngineBuildIds: List<String>
        get() = peerEngines.values.map { it.engineBuildId.value }

    /** Catalog engineIds of live-attached peer engines. */
    val livePeerEngineIds: List<String>
        get() = peerEngines.keys.toList()

    /** All registered engineIds (stable catalog order when present). */
    val registeredEngineIds: List<String>
        get() = EngineSelectionPolicy.CATALOG_ENGINE_IDS.filter { it in registrationsByEngineId }

    companion object {
        private const val TAG = "OmniEnginePack"
        private const val DRIVER_PLACEHOLDER: String = "unknown-driver"

        /**
         * Attempt production attach. Safe to call once after READY/DEGRADED.
         * Does not write OmniLLM domain DB (ENGINE-STANDARD §3).
         *
         * Only llama-cpp may load a real native backend; peers stay stub/UNKNOWN.
         * Dev-mode posture travels via [buildMode] (BLD-02); default fail-closed.
         */
        fun attachAfterReady(
            buildMode: ProductBuildMode = ProductBuildMode.FAIL_CLOSED,
            deviceFingerprint: DeviceExecutionFingerprint = defaultDeviceFingerprint(),
        ): EnginePackAttachment {
            val registry = EngineRegistry()
            val registrations = linkedMapOf<String, EngineRegistration>()

            // ---- llama.cpp (native-eligible) ----
            val llamaLock = UpstreamLockLoader.loadFromClasspathOrTemplate()
            val llamaBuildId = llamaLock.resolvedEngineBuildId()
                ?: LlamaCppModule.defaultEngineBuildId()
            val llamaReg = LlamaCppModule.registerWith(
                registry = registry,
                lock = llamaLock,
                engineBuildId = llamaBuildId,
            )
            LlamaCppModule.seedUnqualifiedPlaceholders(
                registry = registry,
                deviceFingerprint = deviceFingerprint,
                engineBuildId = llamaBuildId,
                driverFingerprint = DRIVER_PLACEHOLDER,
            )
            registrations[LlamaCppModule.ENGINE_ID] = llamaReg

            // Production: JniNativeBackend only — never silent StubNativeBackend.
            val llamaEngine = LlamaCppModule.createEngineWithNativeOrNull(
                lock = llamaLock,
                engineBuildId = llamaBuildId,
            )
            val nativePresent = llamaEngine != null || JniNativeBackend.isNativePresent()

            // Peer engines: register catalog adapters (native allowed in DEVELOPMENT_SHIP_MODE).
            registerPeerEngines(registry, deviceFingerprint, registrations, buildMode)

            if (!buildMode.allowSupportedProjectionWithoutPass()) {
                check(!EngineSelectionPolicy.anyExecutableCell(registry, buildMode)) {
                    "Engine attach must not project SUPPORTED without qualification evidence"
                }
            }

            val notes = buildMap {
                putAll(EngineSelectionPolicy.summaryNotes(buildMode))
                put(
                    "mode",
                    if (buildMode.developmentShipMode) {
                        "development_ship"
                    } else {
                        "compliance"
                    },
                )
                put("llama.engineBuildId", llamaBuildId.value)
                put("llama.lockState", if (llamaLock.isComplete()) "LOCKED" else "NOT_LOCKED")
                put("llama.nativeLibrary", llamaEngine?.native?.libraryLabel() ?: "missing")
                put("llama.nativePresent", nativePresent.toString())
                put("llama.adapterAttached", (llamaEngine != null).toString())
                put("llama.qualificationStatus", LlamaCppModule.QUALIFICATION_STATUS)
                put("registry.registrations", registry.listRegistrations().size.toString())
                put("registry.cells", registry.listCells().size.toString())
                put(
                    "registryExposure",
                    if (buildMode.allowExecuteWithoutQualification()) {
                        "DEV_EXECUTABLE"
                    } else {
                        "UNKNOWN"
                    },
                )
                put(
                    "anyExecutable",
                    EngineSelectionPolicy.anyExecutableCell(registry, buildMode).toString(),
                )
                if (llamaEngine == null) {
                    put(
                        "llama.failClosed",
                        "libomnillm_llama missing or ABI mismatch — execute path remains fail-closed",
                    )
                }
            }

            if (llamaEngine != null) {
                Log.i(
                    TAG,
                    "llama-cpp adapter attached library=${llamaEngine.native.libraryLabel()} " +
                        "buildId=${llamaBuildId.value} " +
                        "mode=${notes["mode"]}",
                )
            } else {
                Log.w(
                    TAG,
                    "llama-cpp native lib missing — adapter not attached. " +
                        "Catalog engines registered; mode=${notes["mode"]}",
                )
            }
            Log.i(
                TAG,
                "engine registry attached engines=${registrations.keys.joinToString()} " +
                    "cells=${registry.listCells().size} " +
                    "anySupported=${notes["anySupported"]} mode=${notes["mode"]}",
            )

            return EnginePackAttachment(
                registry = registry,
                llamaCppEngine = llamaEngine,
                registrationsByEngineId = registrations.toMap(),
                nativeLibraryPresent = nativePresent,
                notes = notes,
            )
        }

        /**
         * Host / unit-test attach without JNI (registry for all engines + optional
         * llama-cpp stub engine). Production must use [attachAfterReady].
         */
        fun attachForTest(
            buildMode: ProductBuildMode = ProductBuildMode.FAIL_CLOSED,
            includeStubEngine: Boolean = false,
            deviceFingerprint: DeviceExecutionFingerprint =
                DeviceExecutionFingerprint.parse("device-fp-engine-test"),
            engineBuildId: EngineBuildId = LlamaCppModule.defaultEngineBuildId(),
            /**
             * Test-only: custom [NativeBackend] for the attached llama-cpp engine
             * (e.g. a fake emitting visible token-delta text for C-02). Null keeps
             * the catalog [StubNativeBackend]. Never used by production attach.
             */
            llamaBackend: com.omnillm.engines.llamacpp.native.NativeBackend? = null,
        ): EnginePackAttachment {
            val registry = EngineRegistry()
            val registrations = linkedMapOf<String, EngineRegistration>()

            val llamaLock = UpstreamLockLoader.loadFromClasspathOrTemplate()
            val llamaReg = LlamaCppModule.registerWith(
                registry = registry,
                lock = llamaLock,
                engineBuildId = engineBuildId,
            )
            LlamaCppModule.seedUnqualifiedPlaceholders(
                registry = registry,
                deviceFingerprint = deviceFingerprint,
                engineBuildId = engineBuildId,
                driverFingerprint = DRIVER_PLACEHOLDER,
            )
            registrations[LlamaCppModule.ENGINE_ID] = llamaReg

            registerPeerEngines(registry, deviceFingerprint, registrations, buildMode)

            if (!buildMode.allowSupportedProjectionWithoutPass()) {
                check(!EngineSelectionPolicy.anyExecutableCell(registry, buildMode)) {
                    "Engine attach must not project SUPPORTED without qualification evidence"
                }
            }

            val engine = if (includeStubEngine) {
                // Stub is test-only; EngineSelectionPolicy still forbids SUPPORTED.
                if (llamaBackend != null) {
                    LlamaCppModule.createEngine(
                        lock = llamaLock,
                        backend = llamaBackend,
                        engineBuildId = engineBuildId,
                    )
                } else {
                    LlamaCppModule.createEngine(lock = llamaLock, engineBuildId = engineBuildId)
                }
            } else {
                null
            }

            return EnginePackAttachment(
                registry = registry,
                llamaCppEngine = engine,
                registrationsByEngineId = registrations.toMap(),
                nativeLibraryPresent = false,
                notes = buildMap {
                    putAll(EngineSelectionPolicy.summaryNotes(buildMode))
                    put(
                        "mode",
                        if (buildMode.developmentShipMode) "development_ship" else "test",
                    )
                    put("includeStubEngine", includeStubEngine.toString())
                    put(
                        "registryExposure",
                        if (buildMode.allowExecuteWithoutQualification()) "DEV_EXECUTABLE" else "UNKNOWN",
                    )
                    put("registry.registrations", registry.listRegistrations().size.toString())
                    put("registry.cells", registry.listCells().size.toString())
                    put(
                        "anyExecutable",
                        EngineSelectionPolicy.anyExecutableCell(registry, buildMode).toString(),
                    )
                },
            )
        }

        /**
         * Register peer Engine Packs as metadata + UNQUALIFIED cells only.
         * Never loads real native/SDK backends (see [EngineSelectionPolicy]).
         */
        private fun registerPeerEngines(
            registry: EngineRegistry,
            deviceFingerprint: DeviceExecutionFingerprint,
            out: MutableMap<String, EngineRegistration>,
            buildMode: ProductBuildMode,
        ) {
            // In compliance mode peers may never use real native/SDK backends.
            // In DEVELOPMENT_SHIP_MODE every catalog engine may attempt a real backend
            // when the adapter is wired (see EngineSelectionPolicy / SHIP_BACKLOG E3-E6).
            val complianceOnly = !buildMode.allowAllEnginesNative()

            // LiteRT-LM — cells must live under the SAME engineBuildId as the
            // registration (INV-018: catalog cells exist, status UNKNOWN; the packaged
            // UPSTREAM.lock resolves a real build id, so defaults must not diverge).
            val litertReg = LitertLmModule.registerWith(registry = registry)
            LitertLmModule.seedUnqualifiedPlaceholders(
                registry = registry,
                deviceFingerprint = deviceFingerprint,
                engineBuildId = litertReg.engineBuildId,
                driverFingerprint = DRIVER_PLACEHOLDER,
            )
            out[LitertLmModule.ENGINE_ID] = litertReg
            if (complianceOnly) {
                require(!EngineSelectionPolicy.mayUseRealNativeBackend(LitertLmModule.ENGINE_ID, buildMode))
            }

            // MLC-LLM (register without default seed so we can pass device fingerprint)
            val mlcReg = MlcLlmModule.registerWith(
                registry = registry,
                seedPlaceholderCells = false,
            )
            MlcQualificationCells.seedUnqualified(
                registry = registry,
                engineBuildId = mlcReg.engineBuildId,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = DRIVER_PLACEHOLDER,
            )
            out[MlcLlmModule.ENGINE_ID] = mlcReg
            if (complianceOnly) {
                require(!EngineSelectionPolicy.mayUseRealNativeBackend(MlcLlmModule.ENGINE_ID, buildMode))
            }

            // mllm — metadata + UNQUALIFIED cells only (private-server stub; no AAR load)
            val mllmReg = MllmModule.registerWith(
                registry = registry,
                seedCells = false,
            )
            MllmModule.seedUnqualifiedPlaceholders(
                registry = registry,
                engineBuildId = mllmReg.engineBuildId,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = DRIVER_PLACEHOLDER,
            )
            out[MllmModule.ENGINE_ID] = mllmReg
            if (complianceOnly) {
                require(!EngineSelectionPolicy.mayUseRealNativeBackend(MllmModule.ENGINE_ID, buildMode))
            }

            // ONNX Runtime GenAI
            val ortReg = OrtGenaiModule.registerDesignCompleteUnqualified(
                registry = registry,
                deviceFingerprint = deviceFingerprint,
                driverFingerprint = DRIVER_PLACEHOLDER,
            )
            out[OrtGenaiModule.ENGINE_ID] = ortReg
            if (complianceOnly) {
                require(!EngineSelectionPolicy.mayUseRealNativeBackend(OrtGenaiModule.ENGINE_ID, buildMode))
            }
        }

        /** Host-observed device fingerprint (shared by load commands / probes). */
        fun defaultDeviceFingerprint(): DeviceExecutionFingerprint {
            val raw = listOf(
                Build.FINGERPRINT.takeIf { it.isNotBlank() },
                Build.MODEL,
                Build.DEVICE,
                Build.HARDWARE,
            ).filterNotNull().joinToString("|")
            val safe = raw.replace(Regex("[^A-Za-z0-9._:-]"), "_")
                .ifBlank { "android-device-unknown" }
            return DeviceExecutionFingerprint.parse(safe.take(200))
        }
    }
}
