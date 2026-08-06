package com.omnillm.android.runtimeservice.controlplane

/**
 * Durable runtime instance coordinates (ARCH-RUNTIME-LIFECYCLE §2).
 *
 * [bootId] + [runtimeEpoch] fence workers, plans, leases, commits and callbacks.
 * Epoch is advanced **before** any capability is issued.
 */
data class RuntimeIdentity(
    val runtimeInstanceId: String,
    val bootId: String,
    val runtimeEpoch: Long,
) {
    init {
        require(runtimeInstanceId.isNotBlank()) { "runtimeInstanceId must be non-blank" }
        require(bootId.isNotBlank()) { "bootId must be non-blank" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}
