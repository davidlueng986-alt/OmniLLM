package com.omnillm.android.runtimeservice.companion

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Android PackageManager probe for companion install/signer/UID
 * (SEC-EXTERNAL-SANDBOX §2, §7).
 *
 * Does not auto-install packages. Missing / disabled / signer mismatch ⇒ unavailable.
 */
class CompanionPackageProbe(
    private val context: Context,
    private val companionPackageOverride: String? = null,
) {
    fun mainAppUidOrNull(): Int? =
        try {
            context.packageManager.getApplicationInfo(context.packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    fun probe(): CompanionAvailability {
        val pm = context.packageManager
        val mainUid = try {
            pm.getApplicationInfo(context.packageName, 0).uid
        } catch (_: PackageManager.NameNotFoundException) {
            return CompanionAvailability.unavailable("main package info missing")
        }

        val companionPackage = companionPackageOverride
            ?: CompanionHostConstants.companionPackageForMain(context.packageName)

        val companionInfo = try {
            pm.getApplicationInfo(companionPackage, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            // Fail closed: missing companion is TRUST_PLACEMENT_REQUIRED for accel paths,
            // never same-UID "security sandbox" (SEC-EXTERNAL-SANDBOX §1, ADR-007).
            return CompanionAvailability.unavailable("companion package not installed")
        }

        if (!companionInfo.enabled) {
            return CompanionAvailability(
                installed = true,
                differentUid = companionInfo.uid != mainUid,
                sameSigner = false,
                protocolCompatible = false,
                packageName = companionPackage,
                mainAppPackage = context.packageName,
                detail = "companion package disabled",
            )
        }

        if (companionInfo.uid == mainUid) {
            // sharedUserId or misconfiguration — hard fail (must be different UID).
            return CompanionAvailability(
                installed = true,
                differentUid = false,
                sameSigner = false,
                protocolCompatible = false,
                packageName = companionPackage,
                mainAppPackage = context.packageName,
                detail = "companion UID equals main app UID; sharedUserId forbidden",
            )
        }

        val sameSigner = sameSigningPrincipal(pm, context.packageName, companionPackage)
        // Protocol is confirmed at handshake; install-time assume host major until bind.
        val protocolOk = sameSigner
        return CompanionAvailability(
            installed = true,
            differentUid = true,
            sameSigner = sameSigner,
            protocolCompatible = protocolOk,
            bindReachable = false,
            packageName = companionPackage,
            mainAppPackage = context.packageName,
            detail = if (sameSigner) "" else "signer mismatch",
        )
    }

    private fun sameSigningPrincipal(
        pm: PackageManager,
        mainPkg: String,
        companionPkg: String,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                pm.checkSignatures(mainPkg, companionPkg) == PackageManager.SIGNATURE_MATCH
            } else {
                @Suppress("DEPRECATION")
                pm.checkSignatures(mainPkg, companionPkg) == PackageManager.SIGNATURE_MATCH
            }
        } catch (_: Exception) {
            false
        }
    }
}
