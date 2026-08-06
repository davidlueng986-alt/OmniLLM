package com.omnillm.features

/**
 * Thin re-export for module path discovery.
 * Implementation lives under [com.omnillm.features.benchmark.BenchmarkFeatureModule].
 */
object BenchmarkModule {
    const val MODULE_PATH: String = com.omnillm.features.benchmark.BenchmarkFeatureModule.MODULE_PATH
    const val FEATURE_ID: String = com.omnillm.features.benchmark.BenchmarkFeatureModule.FEATURE_ID
}
