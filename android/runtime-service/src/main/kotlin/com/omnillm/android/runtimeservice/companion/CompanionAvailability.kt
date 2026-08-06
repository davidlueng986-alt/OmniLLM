package com.omnillm.android.runtimeservice.companion

/**
 * Live companion package facts for placement (SEC-EXTERNAL-SANDBOX §2, §7).
 *
 * Host never treats same-UID process as a security sandbox substitute when
 * companion is missing (ADR-007).
 */
data class CompanionAvailability(
    /** Package is installed and enabled. */
    val installed: Boolean,
    /** Companion Linux UID differs from main app UID (required). */
    val differentUid: Boolean,
    /** Signing certs match same-signer policy (signature permission usable). */
    val sameSigner: Boolean,
    /** Companion reported protocol major matches host expectation. */
    val protocolCompatible: Boolean,
    /** Optional: companion process bind probe succeeded recently. */
    val bindReachable: Boolean = false,
    val packageName: String = CompanionHostConstants.COMPANION_PACKAGE,
    val mainAppPackage: String = CompanionHostConstants.MAIN_APP_PACKAGE,
    val detail: String = "",
) {
    /**
     * True when untrusted accelerated placement may select EXTERNAL_UID_ACCELERATED.
     */
    val isAvailable: Boolean
        get() = installed && differentUid && sameSigner && protocolCompatible

    companion object {
        fun unavailable(reason: String): CompanionAvailability =
            CompanionAvailability(
                installed = false,
                differentUid = false,
                sameSigner = false,
                protocolCompatible = false,
                detail = reason,
            )

        fun available(
            bindReachable: Boolean = true,
            packageName: String = CompanionHostConstants.COMPANION_PACKAGE,
        ): CompanionAvailability =
            CompanionAvailability(
                installed = true,
                differentUid = true,
                sameSigner = true,
                protocolCompatible = true,
                bindReachable = bindReachable,
                packageName = packageName,
            )
    }
}

/**
 * Constants mirrored from companion module without depending on the companion APK
 * (host app must not merge companion as a library — separate applicationId/UID).
 */
object CompanionHostConstants {
    const val MAIN_APP_PACKAGE: String = "com.omnillm"
    const val COMPANION_PACKAGE: String = "com.omnillm.companion"
    /** Debug build applicationIdSuffix (.debug) for dual-APK local installs. */
    const val COMPANION_PACKAGE_DEBUG: String = "com.omnillm.companion.debug"
    const val PERMISSION_BIND_SANDBOX: String = "com.omnillm.companion.permission.BIND_SANDBOX"
    const val ACTION_BIND_SANDBOX: String = "com.omnillm.companion.action.BIND_SANDBOX"
    const val SERVICE_CLASS: String = "com.omnillm.companion.CompanionSandboxService"
    const val PROTOCOL_MAJOR: Int = 1
    const val PROTOCOL_MINOR: Int = 0
    const val PLACEMENT_EXTERNAL_UID_ACCELERATED: String = "EXTERNAL_UID_ACCELERATED"
    const val ERROR_TRUST_PLACEMENT_REQUIRED: String = "TRUST_PLACEMENT_REQUIRED"

    /**
     * Resolve companion applicationId from main app package
     * (debug main → debug companion).
     */
    fun companionPackageForMain(mainPackage: String): String =
        if (mainPackage.endsWith(".debug")) COMPANION_PACKAGE_DEBUG else COMPANION_PACKAGE
}

/**
 * Pure package-identity checks (no PackageManager) for unit tests.
 */
object CompanionPackageRules {
    fun packagesMustDiffer(main: String, companion: String): Boolean =
        main.isNotEmpty() && companion.isNotEmpty() && main != companion

    fun rejectsSharedUserIdClaim(sharedUserId: String?): Boolean =
        sharedUserId.isNullOrEmpty()

    /**
     * Host-side protocol compatibility: equal major required.
     * Companion minor must not exceed host minor (SEC-EXTERNAL-SANDBOX §7).
     */
    fun protocolCompatible(
        companionMajor: Int,
        companionMinor: Int,
        hostMajor: Int = CompanionHostConstants.PROTOCOL_MAJOR,
        hostMinor: Int = CompanionHostConstants.PROTOCOL_MINOR,
    ): Boolean {
        if (companionMajor != hostMajor) return false
        return companionMinor <= hostMinor
    }
}
