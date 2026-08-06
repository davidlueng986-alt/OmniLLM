package com.omnillm.engines

/**
 * Compatibility re-export of the historical package path for `:engines:mllm`.
 * Prefer [com.omnillm.engines.mllm.MllmModule].
 */
object MllmModule {
    const val MODULE_PATH: String = com.omnillm.engines.mllm.MllmModule.MODULE_PATH
    const val ENGINE_ID: String = com.omnillm.engines.mllm.MllmModule.ENGINE_ID
}
