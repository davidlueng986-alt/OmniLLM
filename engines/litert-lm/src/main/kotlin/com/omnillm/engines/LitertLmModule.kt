package com.omnillm.engines

/**
 * Compatibility re-export of the historical package path for `:engines:litert-lm`.
 * Prefer [com.omnillm.engines.litertlm.LitertLmModule].
 */
object LitertLmModule {
    const val MODULE_PATH: String = com.omnillm.engines.litertlm.LitertLmModule.MODULE_PATH
    const val ENGINE_ID: String = com.omnillm.engines.litertlm.LitertLmModule.ENGINE_ID
}
