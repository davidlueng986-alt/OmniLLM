package com.omnillm.android.runtimeservice.binder

import android.content.Context
import android.os.Binder
import android.os.Process
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Binder principal observation (ANDROID-BINDER §3 / INV-011).
 *
 * Authorization uses **system-provided** calling UID and Android user.
 * Never trust caller self-reported package names as identity.
 *
 * Same-app UI on the non-exported Admin binder is principal [PrincipalKind.LOCAL_UI]
 * with profile LOCAL_ADMIN — not an exported AIDL "same UID" shortcut.
 */
data class ObservedPrincipal(
    val callingUid: Int,
    val callingPid: Int,
    val userId: Int,
    /** Display candidates only — not an authorization key. */
    val packageCandidates: List<String>,
    val isSameAppUid: Boolean,
) {
    /** Eligible for non-exported [ai.omnillm.api.IOmniAdmin]. */
    val isLocalAdminEligible: Boolean
        get() = isSameAppUid

    /**
     * Catalog principal for Admin path. Only valid when [isLocalAdminEligible].
     * Uses fixed [LocalUiPrincipal.ID] — never a caller package string.
     */
    val localUiPrincipalId: PrincipalId
        get() = LocalUiPrincipal.ID

    val principalKind: PrincipalKind?
        get() = if (isSameAppUid) PrincipalKind.LOCAL_UI else null
}

object PrincipalObservation {

    fun observe(context: Context): ObservedPrincipal {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        // Multi-user: Android assigns UID = userId * 100000 + appId.
        val userId = uid / 100_000
        val packages = try {
            context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return ObservedPrincipal(
            callingUid = uid,
            callingPid = pid,
            userId = userId,
            packageCandidates = packages,
            isSameAppUid = uid == Process.myUid(),
        )
    }

    /**
     * Human-readable principal summary for pairing challenges.
     * Notes shared identity when multiple packages share the UID.
     */
    fun principalSummary(principal: ObservedPrincipal): String {
        val pkgs = principal.packageCandidates
        return when {
            principal.isLocalAdminEligible ->
                "principal=${PrincipalKind.LOCAL_UI.name} uid=${principal.callingUid}"
            pkgs.isEmpty() -> "uid=${principal.callingUid} user=${principal.userId}"
            pkgs.size == 1 -> "uid=${principal.callingUid} pkg=${pkgs[0]}"
            else -> "uid=${principal.callingUid} sharedIdentity=${pkgs.joinToString(",")}"
        }
    }
}
