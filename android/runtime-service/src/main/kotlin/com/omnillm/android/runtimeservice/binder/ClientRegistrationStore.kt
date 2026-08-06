package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.contracts.PrincipalId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process ClientRegistration store for AIDL Runtime Binding
 * (CORE-INTERFACE §7, ANDROID-BINDER §3, access-control-catalog).
 *
 * Principal is **observed UID + Android user**, never caller self-reported package.
 * Durable DB projection is TODO; this is the control-plane authority for the
 * runtime process until persistence wires CLIENT_REGISTRATION rows (ADR-010).
 *
 * CLIENT_REGISTRATION states (catalog): PENDING | ACTIVE | SUSPENDED |
 * REVOCATION_REQUESTED | DRAINING | REVOKED | EXPIRED.
 */
class ClientRegistrationStore {

    private val byHandle = ConcurrentHashMap<String, ClientRegistration>()
    private val revocationEpoch = AtomicLong(0L)

    fun currentRevocationEpoch(): Long = revocationEpoch.get()

    /**
     * Issue an ACTIVE registration for [principal] with [scopes].
     * Same-app local admin path may grant APP_CLIENT default scopes when [scopes] is empty.
     */
    fun register(
        principal: ObservedPrincipal,
        scopes: Collection<String>,
        displayName: String? = null,
    ): ClientRegistration {
        val handle = UUID.randomUUID().toString()
        val granted = normalizeScopes(scopes).ifEmpty {
            if (principal.isSameAppUid) APP_CLIENT_DEFAULT_SCOPES else emptySet()
        }
        val reg = ClientRegistration(
            registrationHandle = handle,
            principalId = principalIdOf(principal),
            callingUid = principal.callingUid,
            userId = principal.userId,
            packageCandidates = principal.packageCandidates,
            grantedScopes = granted,
            state = "ACTIVE",
            revocationEpochAtIssue = revocationEpoch.get(),
            displayName = displayName,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        byHandle[handle] = reg
        return reg
    }

    fun find(handle: String): ClientRegistration? = byHandle[handle]

    /**
     * Resolve registration only when handle exists, UID matches observation,
     * state is ACTIVE, and revocation epoch has not advanced past issue.
     */
    fun resolveActive(
        handle: String,
        observed: ObservedPrincipal,
    ): ClientRegistration? {
        val reg = byHandle[handle] ?: return null
        if (reg.callingUid != observed.callingUid) return null
        if (reg.userId != observed.userId) return null
        if (reg.state != "ACTIVE") return null
        if (revocationEpoch.get() > reg.revocationEpochAtIssue && reg.state == "ACTIVE") {
            // Epoch fence: treat as revoked until explicit re-issue (INV-017).
            return null
        }
        return reg
    }

    fun hasScope(registration: ClientRegistration, scope: AccessScope): Boolean {
        if ("*" in registration.grantedScopes) return true
        return scope.id in registration.grantedScopes
    }

    fun requireScope(registration: ClientRegistration, scope: AccessScope): Boolean =
        hasScope(registration, scope)

    /** Bump global revocation epoch and mark all ACTIVE as REVOCATION_REQUESTED. */
    fun revokeAll(): Long {
        val next = revocationEpoch.incrementAndGet()
        byHandle.replaceAll { _, reg ->
            if (reg.state == "ACTIVE" || reg.state == "SUSPENDED") {
                reg.copy(state = "REVOKED")
            } else {
                reg
            }
        }
        return next
    }

    fun revoke(handle: String): Boolean {
        val existing = byHandle[handle] ?: return false
        byHandle[handle] = existing.copy(state = "REVOKED")
        revocationEpoch.incrementAndGet()
        return true
    }

    fun snapshot(): List<ClientRegistration> = byHandle.values.toList()

    companion object {
        /** APP_CLIENT profile scopes from access-control-catalog.yaml. */
        val APP_CLIENT_DEFAULT_SCOPES: Set<String> = setOf(
            "models.read",
            "inference.create",
            "inference.cancel",
            "inference.read-own",
            "assets.create",
            "assets.read-own",
            "assets.delete-own",
            "jobs.read-own",
            "content-reports.propose",
            "content-reports.manage-own",
            "content-reports.read-own",
            "commands.read-own",
        )

        fun principalIdOf(principal: ObservedPrincipal): PrincipalId {
            // Stable, system-derived — never package name as authority.
            return PrincipalId.parse("aidl:uid=${principal.callingUid}:user=${principal.userId}")
        }

        private fun normalizeScopes(scopes: Collection<String>): Set<String> {
            val out = linkedSetOf<String>()
            for (raw in scopes) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                if (s == "*") {
                    out.add("*")
                    continue
                }
                // Fail closed on unknown scope strings (INV-018).
                AccessScope.fromId(s) ?: continue
                out.add(s)
            }
            return out
        }
    }
}

data class ClientRegistration(
    val registrationHandle: String,
    val principalId: PrincipalId,
    val callingUid: Int,
    val userId: Int,
    val packageCandidates: List<String>,
    val grantedScopes: Set<String>,
    val state: String,
    val revocationEpochAtIssue: Long,
    val displayName: String?,
    val createdAtEpochMillis: Long,
)
