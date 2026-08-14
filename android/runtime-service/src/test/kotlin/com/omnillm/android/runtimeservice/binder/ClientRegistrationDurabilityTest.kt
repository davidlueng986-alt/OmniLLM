package com.omnillm.android.runtimeservice.binder

import com.omnillm.data.persistence.ControlPlaneDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C-08b: ClientRegistration must be durable. The production store
 * ([RuntimeControlPlane] wiring) was process-local (ConcurrentHashMap) — a
 * restart lost every registration, and the INV-017 revocation-epoch fence
 * reset to zero.
 *
 * Production store = facade wired with the control-plane SQLDelight backing.
 * RED on current code: `ClientRegistrationStore(durable = ...)` has no such
 * constructor parameter — the durability path does not exist.
 */
class ClientRegistrationDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = { "2026-08-12T00:00:00Z" }

    private fun principal(uid: Int, sameApp: Boolean = true) = ObservedPrincipal(
        callingUid = uid,
        callingPid = 1,
        userId = 0,
        packageCandidates = listOf("com.omnillm.app"),
        isSameAppUid = sameApp,
    )

    @Test
    fun registrationSurvivesStoreRecreation_principalAndScopesResolvable() {
        val file = tmp.newFile("client-registrations.db")
        val principal = principal(uid = 10_042)

        // First "process": register via the production store.
        val handle = ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val store = ClientRegistrationStore(durable = db.clientRegistrations)
            val reg = store.register(
                principal = principal,
                scopes = listOf("inference.create", "models.read"),
                displayName = "durable-client",
            )
            assertTrue(store.hasScope(reg, com.omnillm.core.canonical.generated.AccessScope.inference_create))
            assertNotNull(store.resolveActive(reg.registrationHandle, principal))
            reg.registrationHandle
        }

        // Restart: fresh store on the same DB file.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val store = ClientRegistrationStore(durable = db.clientRegistrations)
            val resolved = store.resolveActive(handle, principal)
            assertNotNull("registration must survive restart", resolved)
            assertTrue(resolved!!.grantedScopes.contains("inference.create"))
            assertTrue(resolved.grantedScopes.contains("models.read"))
            assertEquals("durable-client", resolved.displayName)
            assertEquals(
                "aidl:uid=10042:user=0",
                resolved.principalId.value,
            )
        }
    }

    @Test
    fun revocationEpochFence_survivesRestart() {
        val file = tmp.newFile("client-registrations-fence.db")
        val principal = principal(uid = 10_043)

        // Process 1: register A at epoch 0; revoke it (epoch -> 1).
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val store = ClientRegistrationStore(durable = db.clientRegistrations)
            val regA = store.register(principal, scopes = emptyList())
            assertEquals(0L, regA.revocationEpochAtIssue)
            assertTrue(store.revoke(regA.registrationHandle))
            assertEquals(1L, store.currentRevocationEpoch())
        }

        // Process 2: the fence must still hold after restart.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val store = ClientRegistrationStore(durable = db.clientRegistrations)
            assertEquals(1L, store.currentRevocationEpoch())
            // Registers issued at epoch 0 are fenced (INV-017).
            val all = store.snapshot()
            assertEquals(1, all.size)
            assertEquals("REVOKED", all[0].state)
            assertNull(store.resolveActive(all[0].registrationHandle, principal))
        }
    }

    @Test
    fun revokeAll_fencesEveryRegistration_acrossRestart() {
        val file = tmp.newFile("client-registrations-revoke-all.db")
        val principal = principal(uid = 10_044)

        // Process 1: two registrations; revokeAll bumps the epoch.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val store = ClientRegistrationStore(durable = db.clientRegistrations)
            store.register(principal, scopes = listOf("inference.create"))
            store.register(principal, scopes = listOf("models.read"))
            val epoch = store.revokeAll()
            assertEquals(1L, epoch)
        }

        // Process 2: every registration fenced + state REVOKED.
        ControlPlaneDatabase.openJdbcFile(file, clock = clock).use { db ->
            val store = ClientRegistrationStore(durable = db.clientRegistrations)
            assertEquals(1L, store.currentRevocationEpoch())
            val all = store.snapshot()
            assertEquals(2, all.size)
            assertTrue(all.all { it.state == "REVOKED" })
            assertTrue(all.all { store.resolveActive(it.registrationHandle, principal) == null })
        }
    }
}

private fun <T> ControlPlaneDatabase.use(block: (ControlPlaneDatabase) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
