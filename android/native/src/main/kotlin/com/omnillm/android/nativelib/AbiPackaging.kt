package com.omnillm.android.nativelib

/**
 * ABI packaging policy (ANDROID-NATIVE §1, ANDROID-DEVICE §1).
 *
 * Product ships 64-bit ABIs for on-device LLM. 32-bit is opt-in only via
 * explicit build flavor — not the default Play artifact.
 */
object AbiPackaging {
    const val ARM64_V8A: String = "arm64-v8a"
    const val X86_64: String = "x86_64"
    const val ARMEABI_V7A: String = "armeabi-v7a"
    const val X86: String = "x86"

    /** Default Play / production ABI set. */
    val DEFAULT_ABIS: List<String> = listOf(ARM64_V8A, X86_64)

    /** Full multi-ABI set for emulator / sideload debug when explicitly enabled. */
    val ALL_ABIS: List<String> = listOf(ARM64_V8A, ARMEABI_V7A, X86_64, X86)

    /**
     * Gradle `ndk.abiFilters` values for production.
     * Callers should not invent ABI strings outside this set.
     */
    fun productionAbiFilters(): Array<String> = DEFAULT_ABIS.toTypedArray()

    fun isKnownAbi(abi: String): Boolean = abi in ALL_ABIS

    fun isProductionAbi(abi: String): Boolean = abi in DEFAULT_ABIS
}

/**
 * Notes for engine pack authors (ANDROID-NATIVE §2–§4).
 *
 * 1. Prefer NDK r28+ so 16 KB max-page-size is the default linker behavior.
 * 2. For older toolchains, set **both** `-Wl,-z,max-page-size=16384` and
 *    `-Wl,-z,common-page-size=16384` — one flag alone is insufficient.
 * 3. Scan all self-built, prebuilt, vendor, and transitive `.so` ELF segment
 *    alignments (`tools/ci/check_elf_16kb_alignment.py`).
 * 4. Verify APK/AAB uncompressed native library zip alignment
 *    (`zipalign -c -P 16` or `tools/ci/check_apk_16kb_zipalign.py`).
 * 5. Runtime loads only from verified application/module locations —
 *    never arbitrary downloaded `.so` paths.
 * 6. Engine model artifacts are a separate supply chain from engine code.
 * 7. Loaded library version feeds EngineBuildId + diagnostics only
 *    (does not elevate trust — INV-008).
 */
object NativePackagingNotes {
    const val DOC_ID: String = "ANDROID-NATIVE"
    const val POLICY_ID: String = "ANDROID-16KB"
    const val LINKER_MAX_PAGE: String = "-Wl,-z,max-page-size=16384"
    const val LINKER_COMMON_PAGE: String = "-Wl,-z,common-page-size=16384"
    const val REQUIRED_ALIGNMENT: Int = 16384

    val CMAKE_ANDROID_PAGE_SIZE_ARGS: List<String> = listOf(
        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
    )

    fun cmakeArguments(): List<String> = CMAKE_ANDROID_PAGE_SIZE_ARGS

    fun linkerFlags(): List<String> = listOf(LINKER_MAX_PAGE, LINKER_COMMON_PAGE)
}
