package com.omnillm.engines

/**
 * Compatibility re-export of the historical package path for `:engines:mlc-llm`.
 * Prefer [com.omnillm.engines.mlcllm.MlcLlmModule].
 */
object MlcLlmModule {
    const val MODULE_PATH: String = com.omnillm.engines.mlcllm.MlcLlmModule.MODULE_PATH
    const val ENGINE_ID: String = com.omnillm.engines.mlcllm.MlcLlmModule.ENGINE_ID
    const val DESIGN_STATUS: String = com.omnillm.engines.mlcllm.MlcLlmModule.DESIGN_STATUS
    const val QUALIFICATION_STATUS: String =
        com.omnillm.engines.mlcllm.MlcLlmModule.QUALIFICATION_STATUS
}
