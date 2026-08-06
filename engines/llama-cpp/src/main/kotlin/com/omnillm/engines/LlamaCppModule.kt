package com.omnillm.engines

/**
 * Compatibility re-export of the historical package path for `:engines:llama-cpp`.
 * Prefer [com.omnillm.engines.llamacpp.LlamaCppModule].
 */
object LlamaCppModule {
    const val MODULE_PATH: String = com.omnillm.engines.llamacpp.LlamaCppModule.MODULE_PATH
    const val ENGINE_ID: String = com.omnillm.engines.llamacpp.LlamaCppModule.ENGINE_ID
}
