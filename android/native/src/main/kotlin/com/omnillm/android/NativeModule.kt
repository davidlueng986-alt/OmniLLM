package com.omnillm.android

/**
 * Module `:android:native` — NDK packaging, ABI filters, 16 KB page-size gates.
 *
 * Authority: ANDROID-NATIVE, ANDROID-16KB platform policy.
 *
 * Packages `libomnillm_llama.so` (minimal llama-cpp JNI shim) for production ABIs.
 * UI process must not load this library (INV-001); only `:runtime` / workers.
 * See `engines/llama-cpp/NATIVE.md`.
 */
object NativeModule {
    const val MODULE_PATH: String = ":android:native"
    /** Locked NDK version string (must match libs.versions.toml). */
    const val NDK_VERSION_LOCK: String = "28.2.13676358"
    /** Required max ELF segment alignment for 16 KB devices. */
    const val REQUIRED_MAX_PAGE_SIZE: Int = 16384
    /** LoadLibrary name for the llama-cpp shim (matches CMake target). */
    const val LLAMA_CPP_LIBRARY_NAME: String = "omnillm_llama"
}
