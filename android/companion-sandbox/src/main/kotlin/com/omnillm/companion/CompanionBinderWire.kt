package com.omnillm.companion

/**
 * Cross-process Binder wire for companion sandbox (SEC-EXTERNAL-SANDBOX §2–§5).
 *
 * Not part of public AIDL catalog (`specs/aidl`) — internal host↔companion only.
 * Host mirrors these codes/descriptor in runtime-service without depending on this APK.
 *
 * Wire layout uses flat primitives only (no shared Parcelable classes) so the host
 * can implement an independent proxy with identical field order.
 */
object CompanionBinderWire {
    const val DESCRIPTOR: String = "com.omnillm.companion.ICompanionSandbox"

    // Transaction codes = IBinder.FIRST_CALL_TRANSACTION (1) + N
    /** attachSupervisor(supervisor, runtimeEpoch, bootId, runtimeInstanceId) */
    const val TRANSACTION_ATTACH_SUPERVISOR: Int = 1

    /** handshake(runtimeEpoch, bootId, requestId, runtimeInstanceId, ticket fields…) */
    const val TRANSACTION_HANDSHAKE: Int = 2

    /** registerReadOnlyPfd(token, pfd, runtimeEpoch, bootId, requestId) */
    const val TRANSACTION_REGISTER_RO_PFD: Int = 3

    /** close(runtimeEpoch, bootId, requestId) */
    const val TRANSACTION_CLOSE: Int = 4

    /** cancel(runtimeEpoch, bootId, requestId, operationId) */
    const val TRANSACTION_CANCEL: Int = 5

    /** query(runtimeEpoch, bootId, requestId) */
    const val TRANSACTION_QUERY: Int = 6

    const val TRANSACTION_GET_PROTOCOL_MAJOR: Int = 7
    const val TRANSACTION_GET_PROTOCOL_MINOR: Int = 8
    const val TRANSACTION_PROCESS_ROLE: Int = 9
    const val TRANSACTION_PID: Int = 10
    const val TRANSACTION_UID: Int = 11

    /** Result kind written first in reply after writeNoException. */
    const val RESULT_OK: Int = 0
    const val RESULT_HANDSHAKE_OK: Int = 1
    const val RESULT_REJECTED: Int = 2

    /** Pure ticket field order for parcel encode/decode (both sides must match). */
    data class TicketWire(
        val protocolMajor: Int,
        val protocolMinor: Int,
        val runtimeInstanceId: String,
        val runtimeEpoch: Long,
        val bootId: String,
        val operationId: String,
        val commitId: String,
        val engineBuildId: String,
        val modelContentIds: List<String>,
        val backend: String,
        val resourceEnvelopeSummary: String,
        val operatingConstraintSummary: String,
        val monotonicDeadlineMs: Long,
        val nonce: String,
        val placementClass: String,
    ) {
        fun toTicket(): SandboxExecutionTicket =
            SandboxExecutionTicket(
                protocolMajor = protocolMajor,
                protocolMinor = protocolMinor,
                runtimeInstanceId = runtimeInstanceId,
                runtimeEpoch = runtimeEpoch,
                bootId = bootId,
                operationId = operationId,
                commitId = commitId,
                engineBuildId = engineBuildId,
                modelContentIds = modelContentIds,
                backend = backend,
                resourceEnvelopeSummary = resourceEnvelopeSummary,
                operatingConstraintSummary = operatingConstraintSummary,
                monotonicDeadlineMs = monotonicDeadlineMs,
                nonce = nonce,
                placementClass = placementClass,
            )

        companion object {
            fun fromTicket(t: SandboxExecutionTicket): TicketWire =
                TicketWire(
                    protocolMajor = t.protocolMajor,
                    protocolMinor = t.protocolMinor,
                    runtimeInstanceId = t.runtimeInstanceId,
                    runtimeEpoch = t.runtimeEpoch,
                    bootId = t.bootId,
                    operationId = t.operationId,
                    commitId = t.commitId,
                    engineBuildId = t.engineBuildId,
                    modelContentIds = t.modelContentIds,
                    backend = t.backend,
                    resourceEnvelopeSummary = t.resourceEnvelopeSummary,
                    operatingConstraintSummary = t.operatingConstraintSummary,
                    monotonicDeadlineMs = t.monotonicDeadlineMs,
                    nonce = t.nonce,
                    placementClass = t.placementClass,
                )
        }
    }

    /**
     * Pure flatten of identity report for wire maps / tests (no Android types).
     */
    fun identityToMap(report: CompanionIdentityReport): Map<String, String> =
        mapOf(
            "packageName" to report.packageName,
            "packageVersionName" to report.packageVersionName,
            "packageVersionCode" to report.packageVersionCode.toString(),
            "signerDigestHex" to report.signerDigestHex,
            "processInstanceId" to report.processInstanceId,
            "pid" to report.pid.toString(),
            "protocolMajor" to report.protocolMajor.toString(),
            "protocolMinor" to report.protocolMinor.toString(),
            "uid" to report.uid.toString(),
        )

    fun identityFromMap(attrs: Map<String, String>): CompanionIdentityReport? {
        val pkg = attrs["packageName"] ?: return null
        val versionName = attrs["packageVersionName"] ?: return null
        val versionCode = attrs["packageVersionCode"]?.toLongOrNull() ?: return null
        val signer = attrs["signerDigestHex"] ?: return null
        val processInstance = attrs["processInstanceId"] ?: return null
        val pid = attrs["pid"]?.toIntOrNull() ?: return null
        val major = attrs["protocolMajor"]?.toIntOrNull() ?: return null
        val minor = attrs["protocolMinor"]?.toIntOrNull() ?: return null
        val uid = attrs["uid"]?.toIntOrNull() ?: return null
        return try {
            CompanionIdentityReport(
                packageName = pkg,
                packageVersionName = versionName,
                packageVersionCode = versionCode,
                signerDigestHex = signer,
                processInstanceId = processInstance,
                pid = pid,
                protocolMajor = major,
                protocolMinor = minor,
                uid = uid,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

}
