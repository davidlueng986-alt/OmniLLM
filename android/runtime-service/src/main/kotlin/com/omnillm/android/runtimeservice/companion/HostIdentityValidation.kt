package com.omnillm.android.runtimeservice.companion

/**
 * Pure host-side validation of companion handshake identity report
 * (SEC-EXTERNAL-SANDBOX §3, §7).
 *
 * Same-signer is established via PackageManager before bind; this checks
 * protocol + package + process identity attributes returned after ticket accept.
 */
object HostIdentityValidation {

    data class IdentityReport(
        val packageName: String,
        val packageVersionName: String,
        val packageVersionCode: Long,
        val signerDigestHex: String,
        val processInstanceId: String,
        val pid: Int,
        val protocolMajor: Int,
        val protocolMinor: Int,
        val uid: Int,
    )

    sealed class Result {
        data class Accepted(val report: IdentityReport) : Result()
        data class Rejected(val errorCode: String, val message: String) : Result()
    }

    fun fromAttributes(attrs: Map<String, String>): IdentityReport? {
        val pkg = attrs["packageName"] ?: return null
        val versionName = attrs["packageVersionName"] ?: return null
        val versionCode = attrs["packageVersionCode"]?.toLongOrNull() ?: return null
        val signer = attrs["signerDigestHex"] ?: return null
        val processInstance = attrs["processInstanceId"] ?: return null
        val pid = attrs["pid"]?.toIntOrNull() ?: return null
        val major = attrs["protocolMajor"]?.toIntOrNull() ?: return null
        val minor = attrs["protocolMinor"]?.toIntOrNull() ?: return null
        val uid = attrs["uid"]?.toIntOrNull() ?: return null
        if (pkg.isEmpty() || processInstance.isEmpty() || signer.isEmpty()) return null
        if (major <= 0 || pid <= 0) return null
        return IdentityReport(
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
    }

    /**
     * Validate report against expected companion package + host protocol.
     * [expectedPackageNames] may include debug suffix variants.
     * [mainAppUid] when non-null requires companion uid ≠ main (different UID).
     */
    fun validate(
        report: IdentityReport,
        expectedPackageNames: Set<String>,
        hostProtocolMajor: Int = CompanionHostConstants.PROTOCOL_MAJOR,
        hostProtocolMinor: Int = CompanionHostConstants.PROTOCOL_MINOR,
        mainAppUid: Int? = null,
        expectedSignerDigestHex: String? = null,
    ): Result {
        if (report.packageName !in expectedPackageNames) {
            return Result.Rejected(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion package name mismatch",
            )
        }
        if (!CompanionPackageRules.protocolCompatible(
                companionMajor = report.protocolMajor,
                companionMinor = report.protocolMinor,
                hostMajor = hostProtocolMajor,
                hostMinor = hostProtocolMinor,
            )
        ) {
            return Result.Rejected(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion protocol incompatible",
            )
        }
        if (mainAppUid != null && report.uid == mainAppUid) {
            return Result.Rejected(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion uid equals main app uid; sharedUserId forbidden",
            )
        }
        if (expectedSignerDigestHex != null &&
            expectedSignerDigestHex.isNotEmpty() &&
            !expectedSignerDigestHex.equals(report.signerDigestHex, ignoreCase = true)
        ) {
            return Result.Rejected(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion signer digest mismatch",
            )
        }
        if (report.signerDigestHex == "unknown") {
            return Result.Rejected(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion signer digest unknown",
            )
        }
        return Result.Accepted(report)
    }

    /**
     * Maps wire result kinds + attributes after a handshake transact.
     */
    fun interpretHandshakeReply(
        resultKind: Int,
        errorCode: String?,
        message: String?,
        attributes: Map<String, String>,
        expectedPackageNames: Set<String>,
        mainAppUid: Int? = null,
        expectedSignerDigestHex: String? = null,
    ): HostCompanionResult {
        return when (resultKind) {
            CompanionBinderWire.RESULT_HANDSHAKE_OK -> {
                val report = fromAttributes(attributes)
                    ?: return HostCompanionResult.Rejected(
                        "INVALID_REQUEST",
                        "malformed identity report",
                    )
                when (
                    val v = validate(
                        report = report,
                        expectedPackageNames = expectedPackageNames,
                        mainAppUid = mainAppUid,
                        expectedSignerDigestHex = expectedSignerDigestHex,
                    )
                ) {
                    is Result.Accepted ->
                        HostCompanionResult.HandshakeOk(
                            attributes = attributes,
                            identity = v.report,
                        )
                    is Result.Rejected ->
                        HostCompanionResult.Rejected(v.errorCode, v.message)
                }
            }
            CompanionBinderWire.RESULT_REJECTED ->
                HostCompanionResult.Rejected(
                    errorCode = errorCode
                        ?: CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                    message = message ?: "handshake rejected",
                )
            CompanionBinderWire.RESULT_OK ->
                // Handshake must return identity report, not bare Ok.
                HostCompanionResult.Rejected(
                    "INVALID_REQUEST",
                    "handshake must return identity report",
                )
            else ->
                HostCompanionResult.Rejected(
                    CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                    "unknown handshake result kind",
                )
        }
    }
}
