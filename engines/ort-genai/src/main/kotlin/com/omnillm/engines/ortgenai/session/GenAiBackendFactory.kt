package com.omnillm.engines.ortgenai.session

/**
 * Backend factory (ENGINE-ORTGENAI §2).
 *
 * - [create] always returns the real backend (fail-closed when natives are
 *   absent — never silently substitutes the stub in production, INV-018).
 * - [forHostUnitTests] returns the fail-closed stub for host unit tests that
 *   must not touch native loading at all.
 * - [isRuntimeAvailable] is an availability probe only — AAR presence is not
 *   qualification evidence.
 */
object GenAiBackendFactory {

    /**
     * Real backend for production / exploratory control-plane use.
     * [allowExploratoryExecute] mirrors the engine's unproven-execution gate:
     * false keeps LOAD/GENERATE at CAPABILITY_UNKNOWN even when natives load.
     * [modelDirProvider] overrides broker→path resolution (Stage 5 wiring).
     */
    fun create(
        allowExploratoryExecute: Boolean = false,
        modelDirProvider: ((GenAiLoadRequest) -> String?)? = null,
    ): GenAiBackend = RealGenAiBackend(
        allowExploratoryExecute = allowExploratoryExecute,
        modelDirProvider = modelDirProvider ?: { it.attributes["modelDir"] },
    )

    /** Host unit-test backend: never touches `ai.onnxruntime.genai.*` natives. */
    fun forHostUnitTests(exploratoryDryRun: Boolean = false): GenAiBackend =
        StubGenAiBackend(exploratoryDryRun = exploratoryDryRun)

    /** True only when the GenAI native libraries actually load on this runtime. */
    fun isRuntimeAvailable(): Boolean = OrtGenAiRuntime().isAvailable()
}
