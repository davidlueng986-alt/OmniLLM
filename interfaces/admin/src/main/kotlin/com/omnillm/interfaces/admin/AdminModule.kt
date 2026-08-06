package com.omnillm.interfaces.admin

import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.policy.PolicyManager
import com.omnillm.runtime.requestregistry.CommandLedger

/**
 * Module entry for `:interfaces:admin` (FEAT-ADMIN, CORE-INTERFACE, UX-IA).
 *
 * Pure JVM Admin API surface for the non-exported local UI path.
 * Android AIDL parcel projection lives in `:android:runtime-service`.
 */
object AdminModule {
    const val MODULE_PATH: String = ":interfaces:admin"

    fun createService(
        commandLedger: CommandLedger,
        jobManager: JobManager,
        policyManager: PolicyManager,
        jobObservers: JobSubscriptionRegistry = JobSubscriptionRegistry(),
        runtimeStateProvider: () -> String,
        lanStateProvider: () -> String = {
            val enabled = policyManager.settingsSnapshot().values["server.lanEnabled"]
            if (enabled is com.omnillm.runtime.policy.SettingValue.BoolValue && enabled.value) {
                "ENABLED"
            } else {
                "DISABLED"
            }
        },
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): AdminApiService =
        AdminApiService(
            commandLedger = commandLedger,
            jobManager = jobManager,
            policyManager = policyManager,
            jobObservers = jobObservers,
            runtimeStateProvider = runtimeStateProvider,
            lanStateProvider = lanStateProvider,
            clockMs = clockMs,
        )
}
