package com.omnillm.companion

import android.app.Application
import android.util.Log

/**
 * Companion process Application (package [CompanionSandboxModule.PACKAGE_NAME]).
 *
 * Must not open main-app private files, DB, token vault, or Keystore aliases
 * belonging to `com.omnillm` (different UID — ADR-007).
 */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "CompanionApplication created package=$packageName uid=${android.os.Process.myUid()}")
    }

    private companion object {
        private const val TAG = "OmniCompanion"
    }
}
