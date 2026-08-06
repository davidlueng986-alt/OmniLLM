package com.omnillm.android.runtimeservice.security

import com.omnillm.android.runtimeservice.binder.ClientRegistration
import com.omnillm.android.runtimeservice.binder.ClientRegistrationStore
import com.omnillm.android.runtimeservice.binder.ObservedPrincipal
import com.omnillm.core.canonical.generated.AccessScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative AIDL caller / registration security (SEC-THREAT, ANDROID-BINDER §3, INV-011).
 *
 * Unregistered callers must never obtain a usable [IOmniRuntime] surface
 * ([OmniBindingFacade.openRuntime] returns null). Auth is observed UID +
 * ClientRegistration — never self-reported package.
 *
 * Quality scenarios: **Q-007** (revocation fence), **Q-014** (exported runtime binder
 * cannot escalate to admin / privileged surfaces).
 */
class AidlCallerNegativeSecurityTest {

    private fun principal(
        uid: Int = 10_100,
        sameApp: Boolean = false,
        packages: List<String> = listOf("com.evil.thirdparty"),
    ): ObservedPrincipal =
        ObservedPrincipal(
            callingUid = uid,
            callingPid = 4242,
            userId = uid / 100_000,
            packageCandidates = packages,
            isSameAppUid = sameApp,
        )

    /**
     * Mirrors [com.omnillm.android.runtimeservice.binder.OmniBindingFacade.openRuntime]
     * registration gate without Android Binder Context.
     */
    private fun openRuntimeGate(
        registrationHandle: String?,
        observed: ObservedPrincipal,
        store: ClientRegistrationStore,
    ): ClientRegistration? {
        val handle = registrationHandle.orEmpty()
        if (handle.isBlank()) return null
        return store.resolveActive(handle, observed)
    }

    @Test
    fun unregisteredCaller_blankHandle_denied() {
        val store = ClientRegistrationStore()
        val observed = principal()
        assertNull(openRuntimeGate(null, observed, store))
        assertNull(openRuntimeGate("", observed, store))
        assertNull(openRuntimeGate("   ", observed, store))
    }

    @Test
    fun unregisteredCaller_unknownHandle_denied() {
        val store = ClientRegistrationStore()
        val observed = principal()
        assertNull(
            openRuntimeGate(
                registrationHandle = "00000000-0000-0000-0000-000000000000",
                observed = observed,
                store = store,
            ),
        )
    }

    @Test
    fun unregisteredCaller_thirdPartyWithoutPairing_cannotSelfGrantScopes() {
        val store = ClientRegistrationStore()
        val thirdParty = principal(uid = 20_001, sameApp = false)
        // Empty scopes + non-same-app ⇒ no default APP_CLIENT grant (fail closed).
        val reg = store.register(thirdParty, scopes = emptyList())
        assertTrue(reg.grantedScopes.isEmpty())
        assertFalse(store.hasScope(reg, AccessScope.inference_create))
        assertFalse(store.hasScope(reg, AccessScope.clients_manage))
    }

    @Test
    fun uidSpoof_orDrift_denied() {
        val store = ClientRegistrationStore()
        val owner = principal(uid = 10_200)
        val reg = store.register(owner, scopes = listOf("inference.create", "models.read"))
        assertNotNull(openRuntimeGate(reg.registrationHandle, owner, store))

        val spoofed = owner.copy(callingUid = 99_999)
        assertNull(openRuntimeGate(reg.registrationHandle, spoofed, store))
    }

    @Test
    fun revokedRegistration_denied_q007() {
        val store = ClientRegistrationStore()
        val owner = principal(uid = 10_300)
        val reg = store.register(owner, scopes = listOf("inference.create"))
        assertNotNull(openRuntimeGate(reg.registrationHandle, owner, store))

        assertTrue(store.revoke(reg.registrationHandle))
        assertNull(openRuntimeGate(reg.registrationHandle, owner, store))
    }

    @Test
    fun revokeAll_fencesActiveRegistrations_q007() {
        val store = ClientRegistrationStore()
        val a = principal(uid = 10_401)
        val b = principal(uid = 10_402)
        val ra = store.register(a, scopes = listOf("models.read"))
        val rb = store.register(b, scopes = listOf("models.read"))
        val epoch = store.revokeAll()
        assertTrue(epoch >= 1L)
        assertNull(openRuntimeGate(ra.registrationHandle, a, store))
        assertNull(openRuntimeGate(rb.registrationHandle, b, store))
    }

    @Test
    fun unknownScopeStrings_strippedFailClosed() {
        val store = ClientRegistrationStore()
        val owner = principal(uid = 10_500, sameApp = true)
        val reg = store.register(
            owner,
            scopes = listOf("inference.create", "not.a.real.scope", "tokens.manage"),
        )
        assertTrue(store.hasScope(reg, AccessScope.inference_create))
        // tokens.manage is catalog-known but may be granted if present; unknown stripped.
        assertFalse(reg.grantedScopes.contains("not.a.real.scope"))
    }

    @Test
    fun runtimeRegistration_neverGrantsAdminManageByDefault_q014() {
        val store = ClientRegistrationStore()
        val sameApp = principal(uid = 10_001, sameApp = true, packages = listOf("com.omnillm"))
        val reg = store.register(sameApp, scopes = emptyList())
        // APP_CLIENT defaults must not include clients.manage / settings write / tokens.manage.
        assertFalse(store.hasScope(reg, AccessScope.clients_manage))
        assertFalse(reg.grantedScopes.contains("settings.write"))
        assertFalse(reg.grantedScopes.contains("tokens.manage"))
        assertTrue(store.hasScope(reg, AccessScope.inference_create))
    }
}
