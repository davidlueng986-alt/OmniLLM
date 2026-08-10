package com.omnillm.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omnillm.android.RuntimeServiceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke for runtime service process topology (ANDROID-BASELINE / ANDROID-SERVICE).
 *
 * Run with a device or emulator:
 *   `./gradlew :android:app-ui:connectedDebugAndroidTest`
 *
 * When no device is available, unit fakes under
 * `:android:runtime-service` (`RuntimeServiceStartSmokeTest`) cover lifecycle start.
 *
 * Quality scenarios: process topology for **Q-014**; recovery surface for **UX-FIRST-SUCCESS**.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeServiceInstrumentedSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packageIsMainApp_notCompanion() {
        // Debug buildType applies applicationIdSuffix "-debug" (build.gradle.kts);
        // assert the build-time application id, not a hard-coded release id.
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertNotEquals(
            RuntimeServiceModule.Companion.PACKAGE_NAME,
            context.packageName,
        )
    }

    @Test
    fun runtimeBindingService_isResolvable_exportedSurface() {
        val pm = context.packageManager
        val intent = Intent(RuntimeServiceModule.Actions.BIND_RUNTIME).setPackage(context.packageName)
        val resolved = pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        assertTrue(
            "RuntimeBindingService must be merge-visible for BIND_RUNTIME (got $resolved)",
            resolved.isNotEmpty(),
        )
        val info = resolved.first().serviceInfo
        assertTrue(
            "RuntimeBindingService must run in :runtime process",
            info.processName?.endsWith(RuntimeServiceModule.RUNTIME_PROCESS_SUFFIX) == true ||
                // Some API levels report relative process names without package prefix.
                info.processName == RuntimeServiceModule.RUNTIME_PROCESS_SUFFIX ||
                info.processName?.endsWith(":runtime") == true,
        )
        assertTrue("Runtime binding entry must be exported", info.exported)
    }

    @Test
    fun adminBindingService_isResolvable_nonExported() {
        val pm = context.packageManager
        val intent = Intent(RuntimeServiceModule.Actions.BIND_ADMIN).setPackage(context.packageName)
        // Non-exported services may still resolve for same-app queries.
        val resolved = pm.queryIntentServices(intent, PackageManager.MATCH_ALL)
        assertTrue(
            "AdminBindingService should be present in package (got $resolved)",
            resolved.isNotEmpty(),
        )
        val info = resolved.first().serviceInfo
        assertFalse(
            "Admin binding must not be exported (Q-014)",
            info.exported,
        )
        assertNotEquals(
            RuntimeServiceModule.Actions.BIND_RUNTIME,
            RuntimeServiceModule.Actions.BIND_ADMIN,
        )
    }

    @Test
    fun bindRuntimePermission_isDeclaredDiscoveryOnly() {
        val pm = context.packageManager
        val permission = try {
            pm.getPermissionInfo(RuntimeServiceModule.PERMISSION_BIND_RUNTIME, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        assertTrue(
            "BIND_RUNTIME discovery permission must be declared",
            permission != null,
        )
        // SEC-01: signature-level permission — only same-signature APKs may
        // attempt the exported RuntimeBindingService bind (raised from normal).
        val level = permission!!.protectionLevel and 0xF
        assertEquals(
            "BIND_RUNTIME must be signature (SEC-01, raised from normal in 1a29f41)",
            android.content.pm.PermissionInfo.PROTECTION_SIGNATURE,
            level,
        )
    }
}
