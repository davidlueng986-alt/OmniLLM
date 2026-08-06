package com.omnillm.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative isolation strategy tests (SEC-EXTERNAL-SANDBOX §10, Q-008).
 *
 * Full different-UID adversarial instrumentation (companion process cannot open
 * main-app CE/DE private files, DB, token vault, Keystore aliases) requires a
 * dual-APK device/emulator fixture. This suite encodes the **policy contracts**
 * that must hold before and after that fixture runs, and documents the remaining
 * instrumentation plan.
 *
 * ## Instrumentation plan (document + future androidTest)
 *
 * 1. Install main app (`com.omnillm`) and companion (`com.omnillm.companion`) signed
 *    with the same debug/release key (signature permission).
 * 2. Main app writes a sentinel under its app-private dir, e.g.
 *    `/data/user/0/com.omnillm/files/isolation-sentinel.txt` (or Context.filesDir).
 * 3. From companion process (service or androidTest running as companion UID):
 *    - Attempt `File(mainPrivatePath).readBytes()` → must fail (EACCES / FileNotFound).
 *    - Attempt open via guessed `content://` without grant → must fail.
 *    - Attempt open main-app database path → must fail.
 * 4. Host must only ever deliver **read-only ParcelFileDescriptor** of quarantined
 *    model bytes — never directory FDs or main-app data paths.
 * 5. Assert [CompanionSandboxModule.PACKAGE_NAME] ≠ [CompanionSandboxModule.MAIN_APP_PACKAGE]
 *    and no `sharedUserId` in either manifest (static check here + lint).
 * 6. When companion package missing / disabled / signer mismatch: host policy returns
 *    `TRUST_PLACEMENT_REQUIRED` (see TrustPlacementPolicy / CompanionAvailability).
 *
 * Evidence scenario: specs/quality-scenarios.yaml **Q-008**.
 */
class CompanionIsolationPolicyTest {

    @Test
    fun companionPackageDiffersFromMainApp() {
        assertNotEquals(
            CompanionSandboxModule.MAIN_APP_PACKAGE,
            CompanionSandboxModule.PACKAGE_NAME,
        )
        assertEqualsPackageShape(CompanionSandboxModule.PACKAGE_NAME)
        assertEqualsPackageShape(CompanionSandboxModule.MAIN_APP_PACKAGE)
    }

    @Test
    fun fdPolicyRejectsMainAppPrivatePathShapes() {
        val mainPrivatePaths = listOf(
            "/data/data/com.omnillm/files/secret.bin",
            "/data/user/0/com.omnillm/databases/omnillm.db",
            "/data/data/com.omnillm/shared_prefs/tokens.xml",
            "file:///data/data/com.omnillm/cache/x",
            "content://com.omnillm.provider/tokens",
            "..\\com.omnillm\\files\\x",
        )
        for (path in mainPrivatePaths) {
            assertFalse(
                "must reject main-app private shape: $path",
                CompanionFdPolicy.isOpaqueToken(path),
            )
        }
    }

    @Test
    fun fdPolicyAcceptsOpaqueHostTokensOnly() {
        assertTrue(CompanionFdPolicy.isOpaqueToken("fd-token-1"))
        assertTrue(CompanionFdPolicy.isOpaqueToken("pfd.abc_01"))
        assertFalse(CompanionFdPolicy.acceptMode("WRITE_ONLY"))
        assertTrue(CompanionFdPolicy.acceptMode(CompanionFdPolicy.MODE_READ_ONLY))
    }

    @Test
    fun registryDoesNotStoreRejectedPathTokens() {
        val closed = mutableListOf<String>()
        val reg = CompanionFdRegistry<String> { closed.add(it) }
        assertFalse(reg.register("/data/data/com.omnillm/files/x", "handle-1"))
        assertEquals(0, reg.size)
        assertTrue(closed.contains("handle-1"))
    }

    @Test
    fun companionMustNotClaimSameUidSecuritySandboxRole() {
        // Placement label for this package is external UID accelerated only.
        assertEquals(
            CompanionTicketValidator.PLACEMENT_EXTERNAL_UID_ACCELERATED,
            "EXTERNAL_UID_ACCELERATED",
        )
        // Process role string must match SingleWriterPolicy forbidden writers list.
        assertEquals("companion-sandbox", CompanionSandboxModule.PROCESS_ROLE)
    }

    private fun assertEqualsPackageShape(pkg: String) {
        assertTrue(pkg.startsWith("com.omnillm"))
        assertFalse(pkg.contains(" "))
    }

    // local assertEquals for string without importing AssertEquals overload noise
    private fun assertEquals(expected: String, actual: String) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
