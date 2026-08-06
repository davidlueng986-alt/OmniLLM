package com.omnillm.android.runtimeservice.process

import android.app.Application
import android.content.Context
import android.os.Process

/**
 * Process role detection for multi-process Application branching (INV-001).
 *
 * Only the `:runtime` process may construct the control plane, open domain DB
 * writers, or load trusted native engines.
 */
object ProcessIdentity {

    fun currentProcessName(): String = Application.getProcessName()

    fun isRuntimeProcess(): Boolean =
        ProcessNames.isRuntimeProcessName(currentProcessName())

    fun isMainUiProcess(context: Context): Boolean {
        val name = currentProcessName()
        return name == context.packageName
    }

    fun myUid(): Int = Process.myUid()

    fun myPid(): Int = Process.myPid()
}
