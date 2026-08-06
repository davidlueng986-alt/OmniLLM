package com.omnillm.engines

/**
 * Compatibility re-export of the historical package path for `:engines:ort-genai`.
 * Prefer [com.omnillm.engines.ortgenai.OrtGenaiModule].
 */
object OrtGenaiModule {
    const val MODULE_PATH: String = com.omnillm.engines.ortgenai.OrtGenaiModule.MODULE_PATH
    const val ENGINE_ID: String = com.omnillm.engines.ortgenai.OrtGenaiModule.ENGINE_ID
    const val DESIGN_STATUS: String = com.omnillm.engines.ortgenai.OrtGenaiModule.DESIGN_STATUS
    const val QUALIFICATION_STATUS: String =
        com.omnillm.engines.ortgenai.OrtGenaiModule.QUALIFICATION_STATUS
    const val REGISTRY_EXPOSURE: String =
        com.omnillm.engines.ortgenai.OrtGenaiModule.REGISTRY_EXPOSURE
}
