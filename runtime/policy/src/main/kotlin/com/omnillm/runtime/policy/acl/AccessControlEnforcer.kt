package com.omnillm.runtime.policy.acl
import com.omnillm.core.ports.security.RevocationScope
import com.omnillm.core.ports.security.RevocationSubjectKind
import com.omnillm.core.ports.security.TransportConstraint

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.policy.RevocationEpochManager
import com.omnillm.runtime.policy.security.AuthenticatedToken
import com.omnillm.runtime.policy.security.TokenService

/**
 * Principal / ACL enforcement (SEC-AUTH-NET, access-control-catalog.yaml).
 *
 * - Caller-supplied package name is never a principal
 * - Each operation declares exactly one required scope from the catalog
 * - Scope checks on every operation
 * - Revocation epoch fence on every operation (SEC-006)
 * - Transport / profile constraints fail closed
 * - content-reports.review-submit only via LOCAL_UI / non-exported admin
 *
 * Does not invent scopes, profiles, or transports.
 */
class AccessControlEnforcer(
    private val revocation: RevocationEpochManager = RevocationEpochManager(),
) {
    /**
     * Authenticated principal context for a single operation admission check.
     * Built by transport adapters from observed identity + registration / token.
     */
    data class PrincipalContext(
        val principalId: PrincipalId,
        val kind: PrincipalKind,
        val profile: AccessProfile,
        /** Granted scopes (catalog ids). "*" only for LOCAL_ADMIN wildcard. */
        val grantedScopes: Set<String>,
        val revocationEpoch: Long,
        val transport: AccessTransport,
        /** Optional registration / token subject for epoch fence. */
        val registrationId: String? = null,
        val tokenId: String? = null,
        /** LOOPBACK_ONLY tokens never accepted on LAN. */
        val loopbackOnlyToken: Boolean = false,
        /** True when principal is non-exported local UI admin path. */
        val isLocalUi: Boolean = false,
    ) {
        init {
            require(revocationEpoch >= 0L)
            require(grantedScopes.isNotEmpty() || profile == AccessProfile.WORKER) {
                "grantedScopes empty only for WORKER"
            }
        }
    }

    enum class AccessTransport {
        /** Non-exported admin AIDL (LOCAL_UI). */
        LOCAL_ADMIN_AIDL,

        /** Exported runtime AIDL. */
        EXPORTED_RUNTIME_AIDL,

        /** Literal loopback HTTP. */
        LOOPBACK_HTTP,

        /** TLS 1.3 LAN HTTP. */
        LAN_TLS_HTTP,

        /** Narrow supervisor binder (workers). */
        SUPERVISOR_BINDER,
    }

    data class OperationRequest(
        val operationId: String,
        val requiredScope: AccessScope,
        /** Optional resource owner principal for read-own / delete-own checks. */
        val resourceOwnerPrincipalId: String? = null,
        /** Observed epoch on the request / stream binding. */
        val observedRevocationEpoch: Long,
        /** Subject used for epoch fence (principal / token / registration). */
        val epochSubject: RevocationScope? = null,
    )

    /**
     * Authorize [op] for [principal]. Fail closed on any mismatch.
     */
    fun authorize(
        principal: PrincipalContext,
        op: OperationRequest,
    ): OmniResult<Unit> {
        // 1) Unknown scope already impossible via AccessScope enum; re-check catalog.
        val scopeDef = AccessControlCatalog.SCOPES.firstOrNull { it.id == op.requiredScope }
            ?: return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "unknown required scope (fail closed)",
                    details = mapOf("scope" to op.requiredScope.id),
                ),
            )

        // 2) Transport binding for scope (e.g. content-reports.review-submit).
        val transportGate = enforceScopeTransport(principal, scopeDef.transport, op.requiredScope)
        if (transportGate is OmniResult.Err) return transportGate

        // 3) Profile may hold the scope.
        if (!AccessControlCatalog.profileAllowsScope(principal.profile, op.requiredScope) &&
            "*" !in principal.grantedScopes
        ) {
            // Still allow if grant list has the scope and profile is wildcard or grant was explicit.
            if (!principalHasScope(principal, op.requiredScope)) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "scope not allowed for profile",
                        details = mapOf(
                            "scope" to op.requiredScope.id,
                            "profile" to principal.profile.id,
                        ),
                    ),
                )
            }
        }

        // 4) Granted scope set (per registration / token).
        if (!principalHasScope(principal, op.requiredScope)) {
            return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "missing required scope",
                    details = mapOf(
                        "requiredScope" to op.requiredScope.id,
                        "operationId" to op.operationId,
                    ),
                ),
            )
        }

        // 5) Transport vs profile / loopback-only token invariants.
        val transportOk = enforceTransportInvariants(principal)
        if (transportOk is OmniResult.Err) return transportOk

        // 6) content-reports.review-submit: LOCAL_UI only.
        if (op.requiredScope == AccessScope.content_reports_review_submit) {
            if (!principal.isLocalUi || principal.kind != PrincipalKind.LOCAL_UI) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "content-reports.review-submit requires LOCAL_UI on non-exported admin binder",
                    ),
                )
            }
            if (principal.transport != AccessTransport.LOCAL_ADMIN_AIDL) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "content-reports.review-submit transport not allowed",
                        details = mapOf("transport" to principal.transport.name),
                    ),
                )
            }
        }

        // 7) Owner partition for *-own scopes when resource owner is known.
        if (isOwnScope(op.requiredScope) && op.resourceOwnerPrincipalId != null) {
            if (op.resourceOwnerPrincipalId != principal.principalId.value &&
                !principalHasScope(principal, AccessScope.jobs_read_all) &&
                principal.profile != AccessProfile.LOCAL_ADMIN &&
                "*" !in principal.grantedScopes
            ) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "resource not owned by principal",
                        details = mapOf(
                            "owner" to op.resourceOwnerPrincipalId,
                            "principal" to principal.principalId.value,
                        ),
                    ),
                )
            }
        }

        // 8) Revocation epoch fence (every operation).
        val subject = op.epochSubject
            ?: RevocationScope(principal.principalId.value, RevocationSubjectKind.PRINCIPAL)
        // Ensure tracked so epoch 0 is valid for brand-new principals.
        revocation.ensureActive(subject)
        when (val epoch = revocation.requireCurrentEpoch(subject, op.observedRevocationEpoch)) {
            is OmniResult.Err -> return OmniResult.err(
                OmniError.FORBIDDEN(
                    message = "stale revocation epoch",
                    details = epoch.error.details + mapOf(
                        "operationId" to op.operationId,
                        "subject" to subject.subjectId,
                    ),
                ),
            )
            is OmniResult.Ok -> Unit
        }

        // Also fence token subject when present.
        val tokenId = principal.tokenId
        if (tokenId != null) {
            val tokenScope = RevocationScope(tokenId, RevocationSubjectKind.TOKEN)
            val tokenEpoch = revocation.currentEpoch(tokenScope)
            if (tokenEpoch > 0L) {
                return OmniResult.err(
                    OmniError.UNAUTHORIZED(
                        message = "token revoked by epoch fence",
                        details = mapOf("tokenId" to tokenId, "epoch" to tokenEpoch.toString()),
                    ),
                )
            }
        }

        return OmniResult.ok(Unit)
    }

    /**
     * Build principal context from an authenticated HTTP/LAN token.
     */
    fun fromAuthenticatedToken(
        token: AuthenticatedToken,
        transport: AccessTransport,
        kind: PrincipalKind,
        profile: AccessProfile,
    ): PrincipalContext =
        PrincipalContext(
            principalId = PrincipalId.parse(token.principalId),
            kind = kind,
            profile = profile,
            grantedScopes = token.scopes,
            revocationEpoch = token.revocationEpoch,
            transport = transport,
            registrationId = token.registrationId,
            tokenId = token.tokenId,
            loopbackOnlyToken = token.transportConstraint ==
                TransportConstraint.LOOPBACK_ONLY,
            isLocalUi = false,
        )

    /**
     * LOCAL_UI principal (non-exported admin binder).
     */
    fun localUiPrincipal(
        principalId: PrincipalId = PrincipalId.parse(PrincipalKind.LOCAL_UI.name),
    ): PrincipalContext {
        val scope = RevocationScope(principalId.value, RevocationSubjectKind.PRINCIPAL)
        revocation.ensureActive(scope)
        return PrincipalContext(
            principalId = principalId,
            kind = PrincipalKind.LOCAL_UI,
            profile = AccessProfile.LOCAL_ADMIN,
            grantedScopes = setOf("*"),
            revocationEpoch = revocation.currentEpoch(scope),
            transport = AccessTransport.LOCAL_ADMIN_AIDL,
            isLocalUi = true,
        )
    }

    /**
     * Require a known catalog scope id (fail closed).
     */
    fun requireKnownScope(scopeId: String): OmniResult<AccessScope> {
        val scope = AccessScope.fromId(scopeId)
            ?: return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown scope (fail closed)",
                    details = mapOf("scope" to scopeId),
                ),
            )
        return OmniResult.ok(scope)
    }

    /**
     * Public fence API used by streams / commits / prepared ops.
     */
    fun requireRevocationEpoch(
        subject: RevocationScope,
        observedEpoch: Long,
    ): OmniResult<Unit> {
        revocation.ensureActive(subject)
        return revocation.requireCurrentEpoch(subject, observedEpoch)
    }

    fun currentEpoch(subject: RevocationScope): Long {
        revocation.ensureActive(subject)
        return revocation.currentEpoch(subject)
    }

    fun revocationManager(): RevocationEpochManager = revocation

    // ----- Internals --------------------------------------------------------

    private fun principalHasScope(principal: PrincipalContext, scope: AccessScope): Boolean {
        if ("*" in principal.grantedScopes) return true
        return scope.id in principal.grantedScopes
    }

    private fun isOwnScope(scope: AccessScope): Boolean =
        scope.id.endsWith("-own") || scope.id.endsWith(".read-own") ||
            scope == AccessScope.inference_read_own ||
            scope == AccessScope.inference_cancel ||
            scope == AccessScope.assets_read_own ||
            scope == AccessScope.assets_delete_own ||
            scope == AccessScope.jobs_read_own ||
            scope == AccessScope.commands_read_own ||
            scope == AccessScope.content_reports_read_own ||
            scope == AccessScope.content_reports_manage_own

    private fun enforceScopeTransport(
        principal: PrincipalContext,
        scopeTransport: String?,
        scope: AccessScope,
    ): OmniResult<Unit> {
        if (scopeTransport == null) return OmniResult.ok(Unit)
        // "non-exported local admin AIDL only"
        if (scopeTransport.contains("non-exported", ignoreCase = true) ||
            scopeTransport.contains("local admin", ignoreCase = true)
        ) {
            if (principal.transport != AccessTransport.LOCAL_ADMIN_AIDL || !principal.isLocalUi) {
                return OmniResult.err(
                    OmniError.FORBIDDEN(
                        message = "scope restricted to non-exported local admin AIDL",
                        details = mapOf("scope" to scope.id),
                    ),
                )
            }
        }
        return OmniResult.ok(Unit)
    }

    private fun enforceTransportInvariants(principal: PrincipalContext): OmniResult<Unit> {
        // Loopback admin bearer tokens never accepted by the LAN listener.
        if (principal.transport == AccessTransport.LAN_TLS_HTTP && principal.loopbackOnlyToken) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "loopback-only token rejected on LAN listener"),
            )
        }
        // LOCAL_ADMIN_HTTP never accepted on LAN.
        if (principal.transport == AccessTransport.LAN_TLS_HTTP &&
            principal.profile == AccessProfile.LOCAL_ADMIN_HTTP
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "LOCAL_ADMIN_HTTP profile rejected on LAN listener"),
            )
        }
        // WORKER only on supervisor binder.
        if (principal.profile == AccessProfile.WORKER &&
            principal.transport != AccessTransport.SUPERVISOR_BINDER
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "WORKER profile restricted to supervisor binder"),
            )
        }
        // Exported profiles cannot use LOCAL_ADMIN_AIDL transport as a shortcut.
        if (principal.transport == AccessTransport.LOCAL_ADMIN_AIDL &&
            principal.kind != PrincipalKind.LOCAL_UI
        ) {
            return OmniResult.err(
                OmniError.FORBIDDEN(message = "non-LOCAL_UI principal on admin binder"),
            )
        }
        return OmniResult.ok(Unit)
    }
}
