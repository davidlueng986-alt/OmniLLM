package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.data.persistence.ClientRegistrationPorts
import com.omnillm.data.persistence.ClientRegistrationRow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ClientRegistration store for AIDL Runtime Binding
 * (CORE-INTERFACE §7, ANDROID-BINDER §3, access-control-catalog).
 *
 * Principal is **observed UID + Android user**, never caller self-reported package.
 *
 * C-08b: when wired with the control-plane SQLDelight backing ([durable]),
 * registrations + the global revocation epoch are durable across runtime
 * restart (ADR-010 single writer). Without [durable] the store is process-local
 * (unit scaffolds and callers that predate the wiring).
 *
 * CLIENT_REGISTRATION states (catalog): PENDING | ACTIVE | SUSPENDED |
 * REVOCATION_REQUESTED | DRAINING | REVOKED | EXPIRED.
 */
class ClientRegistrationStore(
    /** Durable control-plane backing (C-08b); null keeps legacy in-memory mode. */
    private val durable: ClientRegistrationPorts? = null,
) {

    private val byHandle = ConcurrentHashMap<String, ClientRegistration>()
    private val revocationEpoch = AtomicLong(0L)

    private fun epoch(): Long = durable?.currentRevocationEpoch() ?: revocationEpoch.get()

    private fun bumpEpoch(): Long = durable?.bumpRevocationEpoch() ?: revocationEpoch.incrementAndGet()

    fun currentRevocationEpoch(): Long = epoch()

    /**
     * Issue an ACTIVE registration for [principal] with [scopes].
     * Same-app local admin path may grant APP_CLIENT default scopes when [scopes] is empty.
     *
     * COR-21/SEC-02: `"*"` never passes through unless [allowWildcard] (LOCAL_ADMIN
     * flows only). Exported AIDL pairing requests are stripped of any wildcard —
     * expansion beyond APP_CLIENT requires explicit admin approval.
     */
    fun register(
        principal: ObservedPrincipal,
        scopes: Collection<String>,
        displayName: String? = null,
        allowWildcard: Boolean = false,
    ): ClientRegistration {
        val handle = UUID.randomUUID().toString()
        val granted = normalizeScopes(scopes, allowWildcard).ifEmpty {
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
            revocationEpochAtIssue = epoch(),
            displayName = displayName,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        if (durable != null) {
            val now = System.currentTimeMillis()
            durable.tx.inTransaction {
                durable.registrations.upsert(
                    ClientRegistrationRow(
                        registrationId = reg.registrationHandle,
                        principalId = reg.principalId.value,
                        observedUid = reg.callingUid,
                        userId = reg.userId,
                        transport = TRANSPORT_AIDL,
                        state = reg.state,
                        scopes = reg.grantedScopes,
                        packageCandidates = reg.packageCandidates,
                        displayName = reg.displayName,
                        revocationEpochAtIssue = reg.revocationEpochAtIssue,
                        createdAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    ),
                )
            }
        } else {
            byHandle[handle] = reg
        }
        return reg
    }

    fun find(handle: String): ClientRegistration? =
        durable?.registrations?.findByRegistrationId(handle)?.toModel()
            ?: byHandle[handle]

    /**
     * Resolve registration only when handle exists, UID matches observation,
     * state is ACTIVE, and revocation epoch has not advanced past issue.
     */
    fun resolveActive(
        handle: String,
        observed: ObservedPrincipal,
    ): ClientRegistration? {
        val reg = find(handle) ?: return null
        if (reg.callingUid != observed.callingUid) return null
        if (reg.userId != observed.userId) return null
        if (reg.state != "ACTIVE") return null
        if (epoch() > reg.revocationEpochAtIssue && reg.state == "ACTIVE") {
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

    /** Bump global revocation epoch and mark all ACTIVE/SUSPENDED as REVOKED. */
    fun revokeAll(): Long {
        val next = bumpEpoch()
        if (durable != null) {
            val now = System.currentTimeMillis()
            durable.tx.inTransaction {
                for (row in durable.registrations.listAll()) {
                    if (row.state == "ACTIVE" || row.state == "SUSPENDED") {
                        durable.registrations.updateState(
                            row.registrationId,
                            "REVOKED",
                            java.time.Instant.ofEpochMilli(now).toString(),
                        )
                    }
                }
            }
        } else {
            byHandle.replaceAll { _, reg ->
                if (reg.state == "ACTIVE" || reg.state == "SUSPENDED") {
                    reg.copy(state = "REVOKED")
                } else {
                    reg
                }
            }
        }
        return next
    }

    fun revoke(handle: String): Boolean {
        if (durable != null) {
            val existing = durable.registrations.findByRegistrationId(handle) ?: return false
            durable.tx.inTransaction {
                durable.registrations.updateState(
                    existing.registrationId,
                    "REVOKED",
                    java.time.Instant.ofEpochMilli(System.currentTimeMillis()).toString(),
                )
            }
            bumpEpoch()
            return true
        }
        val existing = byHandle[handle] ?: return false
        byHandle[handle] = existing.copy(state = "REVOKED")
        revocationEpoch.incrementAndGet()
        return true
    }

    fun snapshot(): List<ClientRegistration> =
        durable?.registrations?.listAll()?.map { it.toModel() }
            ?: byHandle.values.toList()

    companion object {
        /** Durable transport label: this store is the AIDL binding authority. */
        const val TRANSPORT_AIDL: String = "AIDL"

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

        /**
         * Normalize requested scopes. Unknown strings are dropped (fail closed);
         * `"*"` is only honored when [allowWildcard] (LOCAL_ADMIN approval).
         */
        private fun normalizeScopes(scopes: Collection<String>, allowWildcard: Boolean): Set<String> {
            val out = linkedSetOf<String>()
            for (raw in scopes) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                if (s == "*") {
                    // COR-21/SEC-02: wildcard never passes through exported AIDL.
                    if (allowWildcard) out.add("*")
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

/** Map a durable row back to the binder-facing model (C-08b). */
private fun ClientRegistrationRow.toModel(): ClientRegistration =
    ClientRegistration(
        registrationHandle = registrationId,
        principalId = PrincipalId.parse(principalId),
        callingUid = observedUid ?: -1,
        userId = userId ?: 0,
        packageCandidates = packageCandidates,
        grantedScopes = scopes,
        state = state,
        revocationEpochAtIssue = revocationEpochAtIssue,
        displayName = displayName,
        createdAtEpochMillis = createdAtEpochMillis,
    )
