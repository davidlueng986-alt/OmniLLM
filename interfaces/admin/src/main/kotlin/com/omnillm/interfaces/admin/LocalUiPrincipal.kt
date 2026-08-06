package com.omnillm.interfaces.admin

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.contracts.PrincipalId

/**
 * App-internal UI principal for the non-exported Admin binder
 * (`specs/access-control-catalog.yaml` principal `LOCAL_UI` → profile `LOCAL_ADMIN`).
 *
 * Never conflated with exported AIDL "same UID so no pairing" shortcuts
 * (SEC-AUTH-NET / INV-011).
 */
object LocalUiPrincipal {
    /** Catalog principal id used as durable claim principal. */
    val ID: PrincipalId = PrincipalId.parse(PrincipalKind.LOCAL_UI.name)

    val KIND: PrincipalKind = PrincipalKind.LOCAL_UI

    val PROFILE: AccessProfile = AccessProfile.LOCAL_ADMIN

    /** LOCAL_ADMIN has wildcard scopes on non-exported AIDL. */
    fun allows(scope: AccessScope): Boolean =
        AccessControlCatalog.profileAllowsScope(PROFILE, scope)
}
