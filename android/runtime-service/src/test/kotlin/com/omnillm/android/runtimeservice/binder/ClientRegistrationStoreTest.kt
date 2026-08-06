package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.canonical.generated.AccessScope
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
}
