package com.omnillm.android

/**
 * Module `:android:parser-isolated` — `isolatedProcess` metadata parser.
 *
 * Authority: ARCH-TRUST-TOPOLOGY §1/§5, SEC-INPUT §6, ANDROID-ISOLATED-PROCESS.
 *
 * - Runs under isolated UID (not App UID)
 * - Receives read-only FDs + bounded output pipe only
 * - Output is versioned typed descriptor; free JSON cannot drive trust placement
 * - Must not open catalog, DB, tokens, or network
 */
object ParserIsolatedModule {
    const val MODULE_PATH: String = ":android:parser-isolated"
    const val PROCESS_ROLE: String = "isolated-parser"
    const val PROCESS_SUFFIX: String = ":parser"
}
