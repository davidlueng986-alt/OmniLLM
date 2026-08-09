package com.omnillm.core.contracts

/**
 * Product build posture for OmniLLM Android — variant-scoped, injected, fail-closed.
 *
 * ## Design (BLD-02)
 *
 * `core/contracts` is a pure Kotlin JVM module: a hardcoded global constant cannot
 * know which Android buildType it is running in, so `DEVELOPMENT_SHIP_MODE` is no
 * longer a `const val`. Instead the flag is a **constructor value** passed into
 * construction (manual DI, same style as `XxxModule.createXxx` factories):
 *
 * - **Release builds**: wire [FAIL_CLOSED] (`developmentShipMode = false`) — dev
 *   semantics are OFF by default (fail-closed). Nothing in the codebase flips the
 *   flag for release; it must be explicitly passed.
 * - **Debug / dev builds**: the Android layer passes `developmentShipMode = true`
 *   from a per-buildType `BuildConfig` field (see
 *   `android/runtime-service/build.gradle.kts` + `RuntimeControlPlane.attach`),
 *   with an auditable override via `-Pomnillm.developmentShipMode=true|false`.
 * - No global mutable state: the value is immutable per instance and travels
 *   through the DI graph like every other dependency.
 *
 * When dev mode is ON, capability projections must never lie as plain
 * `SUPPORTED` (COR-10 / INV-018/019): bound engines project `CONDITIONAL` with
 * an explicit `development_ship_mode` condition instead (see
 * `EngineExecuteBinding.resolveCapability`).
 */
class ProductBuildMode(
    val developmentShipMode: Boolean,
) {

    /** When dev mode, allow every catalog engine to load a real backend if present. */
    fun allowAllEnginesNative(): Boolean = developmentShipMode

    /** When dev mode, treat bound adapters as executable without PASS packs. */
    fun allowExecuteWithoutQualification(): Boolean = developmentShipMode

    /** Default for `runtime.exploratoryExecuteEnabled` (dev ON / release OFF). */
    fun defaultExploratoryExecuteEnabled(): Boolean = developmentShipMode

    /** Skip attach-time assert that forbids any SUPPORTED cell. */
    fun allowSupportedProjectionWithoutPass(): Boolean = developmentShipMode

    companion object {
        /**
         * Default posture for anything not explicitly wired: dev semantics OFF
         * (fail-closed). Release builds must never construct [ProductBuildMode]
         * with `true`; only the debug/dev variant wiring may.
         */
        val FAIL_CLOSED: ProductBuildMode = ProductBuildMode(developmentShipMode = false)
    }
}
