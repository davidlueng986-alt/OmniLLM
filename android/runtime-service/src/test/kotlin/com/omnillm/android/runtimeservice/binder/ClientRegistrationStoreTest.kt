package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.canonical.generated.AccessScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientRegistrationStoreTest {

    @Test
    fun sameAppRegistersWithDefaultAppClientScopes() {
        val store = ClientRegistrationStore()
        val principal = ObservedPrincipal(
            callingUid = 10_001,
            callingPid = 42,
            userId = 0,
            packageCandidates = listOf("com.omnillm.app"),
            isSameAppUid = true,
        )
        val reg = store.register(principal, scopes = emptyList())
        assertTrue(store.hasScope(reg, AccessScope.inference_create))
        assertTrue(store.hasScope(reg, AccessScope.models_read))
        assertTrue(store.hasScope(reg, AccessScope.assets_create))
        assertFalse(store.hasScope(reg, AccessScope.clients_manage))
    }

    @Test
    fun resolveActiveRequiresUidMatchAndActiveState() {
        val store = ClientRegistrationStore()
        val principal = ObservedPrincipal(
            callingUid = 10_002,
            callingPid = 1,
            userId = 0,
            packageCandidates = emptyList(),
            isSameAppUid = true,
        )
        val reg = store.register(principal, scopes = listOf("inference.create", "unknown.scope"))
        assertTrue(store.hasScope(reg, AccessScope.inference_create))
        // unknown scope stripped (fail closed)
        assertFalse(reg.grantedScopes.contains("unknown.scope"))

        val ok = store.resolveActive(
            reg.registrationHandle,
            principal.copy(callingPid = 99),
        )
        assertNotNull(ok)

        val drift = store.resolveActive(
            reg.registrationHandle,
            principal.copy(callingUid = 99_999),
        )
        assertNull(drift)

        store.revoke(reg.registrationHandle)
        assertNull(store.resolveActive(reg.registrationHandle, principal))
    }

    @Test
    fun COR21_wildcardNeverPassesThroughExportedAidlPairing() {
        val store = ClientRegistrationStore()
        val principal = ObservedPrincipal(
            callingUid = 10_003,
            callingPid = 1,
            userId = 0,
            packageCandidates = listOf("com.omnillm.app"),
            isSameAppUid = true,
        )
        // Wildcard requested via exported pairing must be stripped (COR-21/SEC-02):
        // hasScope("*"→true) can only be reached via allowWildcard=true.
        val reg = store.register(principal, scopes = listOf("*", "inference.create"))
        assertFalse("wildcard must not pass through exported AIDL", "*" in reg.grantedScopes)
        assertTrue(reg.grantedScopes.contains("inference.create"))
    }

    @Test
    fun COR21_sameAppEmptyScopes_autoApprovalGrantsMinimalAppClientDefaults() {
        val store = ClientRegistrationStore()
        val principal = ObservedPrincipal(
            callingUid = 10_004,
            callingPid = 1,
            userId = 0,
            packageCandidates = listOf("com.omnillm.app"),
            isSameAppUid = true,
        )
        // The facade auto-approves same-app with EMPTY scopes (APP_CLIENT
        // profile) — requested scopes never expand the auto-approval.
        val reg = store.register(principal, scopes = emptyList())
        assertEquals(
            "auto-approval grants exactly the APP_CLIENT default set",
            ClientRegistrationStore.APP_CLIENT_DEFAULT_SCOPES,
            reg.grantedScopes,
        )
        assertTrue(store.hasScope(reg, AccessScope.content_reports_propose))
        assertFalse(store.hasScope(reg, AccessScope.models_manage))
        assertFalse(store.hasScope(reg, AccessScope.settings_write))
    }

    @Test
    fun COR21_thirdPartyWildcardOnly_grantsNothingWithoutApproval() {
        val store = ClientRegistrationStore()
        val thirdParty = ObservedPrincipal(
            callingUid = 50_001,
            callingPid = 1,
            userId = 0,
            packageCandidates = listOf("com.example.client"),
            isSameAppUid = false,
        )
        // Wildcard stripped; no APP_CLIENT defaults for third-party (no approval).
        val reg = store.register(thirdParty, scopes = listOf("*"))
        assertTrue("third-party without approval grants nothing", reg.grantedScopes.isEmpty())
        assertFalse("*" in reg.grantedScopes)
        assertFalse(store.hasScope(reg, AccessScope.inference_create))
    }

    @Test
    fun COR21_explicitLocalAdminWildcardOnlyViaAllowWildcard() {
        val store = ClientRegistrationStore()
        val principal = ObservedPrincipal(
            callingUid = 10_005,
            callingPid = 1,
            userId = 0,
            packageCandidates = listOf("com.omnillm.app"),
            isSameAppUid = true,
        )
        val reg = store.register(principal, scopes = listOf("*"), allowWildcard = true)
        assertTrue("*" in reg.grantedScopes)
        assertTrue(store.hasScope(reg, AccessScope.clients_manage))
    }
}
