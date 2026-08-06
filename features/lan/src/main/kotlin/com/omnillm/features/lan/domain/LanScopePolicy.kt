package com.omnillm.features.lan.domain

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope

/**
 * Scope policy for FEAT-LAN / LAN_CLIENT profile.
 *
 * Authority:
 * - specs/access-control-catalog.yaml (LAN_CLIENT, LanGrantableScope subset)
 * - specs/openapi LanGrantableScope enum
 * - FEAT-LAN §4 ACL defaults (infer-only until explicit per-scope approval)
 *
 * Invariants:
 * - requiresExplicitApprovalForEveryScope = true on LAN_CLIENT
 * - loopback admin scopes never accepted on LAN listener
 * - unknown scope ⇒ fail closed (INV-018)
 */
object LanScopePolicy {

    /** OpenAPI `LanGrantableScope` enum — only these may appear on a LAN token. */
    val GRANTABLE: Set<String> = setOf(
        AccessScope.models_read.id,
        AccessScope.inference_create.id,
        AccessScope.inference_cancel.id,
        AccessScope.inference_read_own.id,
        AccessScope.assets_create.id,
        AccessScope.assets_read_own.id,
        AccessScope.assets_delete_own.id,
        AccessScope.jobs_read_own.id,
        AccessScope.commands_read_own.id,
        AccessScope.content_reports_manage_own.id,
        AccessScope.content_reports_propose.id,
        AccessScope.content_reports_read_own.id,
    )

    /**
     * FEAT-LAN §4 default grant when the operator does not expand scopes:
     * inference.create / cancel / read-own only.
     */
    val DEFAULT_INFER_SCOPES: Set<String> = setOf(
        AccessScope.inference_create.id,
        AccessScope.inference_cancel.id,
        AccessScope.inference_read_own.id,
    )

    /**
     * Scopes that require an extra explicit checkbox beyond the default infer set
     * (FEAT-LAN §4: models.read, assets.*, metrics not on LAN grantable enum, etc.).
     */
    val EXPLICIT_OPT_IN: Set<String> =
        GRANTABLE - DEFAULT_INFER_SCOPES

    /** Never grantable on LAN regardless of profile list (admin / telemetry-adjacent). */
    val NEVER_ON_LAN: Set<String> = setOf(
        AccessScope.lan_manage.id,
        AccessScope.tokens_manage.id,
        AccessScope.clients_manage.id,
        AccessScope.clients_read.id,
        AccessScope.settings_write.id,
        AccessScope.settings_read.id,
        AccessScope.metrics_read_detail.id,
        AccessScope.metrics_read_summary.id,
        AccessScope.models_manage.id,
        AccessScope.jobs_manage.id,
        AccessScope.jobs_read_all.id,
        AccessScope.diagnostics_export.id,
        // Consent assertion is LOCAL_UI / non-exported admin only (access-control invariants).
        AccessScope.content_reports_review_submit.id,
    )

    fun isGrantable(scopeId: String): Boolean = scopeId in GRANTABLE

    fun isNeverOnLan(scopeId: String): Boolean = scopeId in NEVER_ON_LAN

    /**
     * Validate requested scopes for a LAN pairing challenge / exchange.
     *
     * @param requested scopes the operator (or client) asked for
     * @param explicitlyApproved subset the local admin explicitly approved
     *        (required for every scope because LAN_CLIENT.requiresExplicitApprovalForEveryScope)
     */
    fun validateRequestedScopes(
        requested: Collection<String>,
        explicitlyApproved: Collection<String> = requested,
    ): LanScopeValidation {
        if (requested.isEmpty()) {
            return LanScopeValidation.Invalid("scopes must be non-empty")
        }
        val approved = explicitlyApproved.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val unknown = mutableListOf<String>()
        val notGrantable = mutableListOf<String>()
        val notApproved = mutableListOf<String>()
        val resolved = linkedSetOf<String>()

        for (raw in requested) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            val scope = AccessScope.fromId(id)
            if (scope == null) {
                unknown += id
                continue
            }
            if (id in NEVER_ON_LAN || !AccessControlCatalog.profileAllowsScope(AccessProfile.LAN_CLIENT, scope)) {
                notGrantable += id
                continue
            }
            if (id !in GRANTABLE) {
                notGrantable += id
                continue
            }
            if (id !in approved) {
                notApproved += id
                continue
            }
            resolved += id
        }

        if (unknown.isNotEmpty()) {
            return LanScopeValidation.Invalid(
                message = "unknown scope(s) (fail closed)",
                unknown = unknown,
            )
        }
        if (notGrantable.isNotEmpty()) {
            return LanScopeValidation.Invalid(
                message = "scope(s) not grantable on LAN",
                disallowed = notGrantable,
            )
        }
        if (notApproved.isNotEmpty()) {
            return LanScopeValidation.Invalid(
                message = "scope(s) require explicit local approval (LAN_CLIENT)",
                disallowed = notApproved,
            )
        }
        if (resolved.isEmpty()) {
            return LanScopeValidation.Invalid("scopes must be non-empty after normalize")
        }
        return LanScopeValidation.Ok(resolved)
    }

    /**
     * Authorize a single operation for an already-issued LAN principal.
     * Fail closed when the token lacks the required scope.
     */
    fun authorizeOperation(
        grantedScopes: Collection<String>,
        requiredScope: String,
    ): LanAuthzDecision {
        val req = AccessScope.fromId(requiredScope)
            ?: return LanAuthzDecision.Denied(
                reason = "unknown required scope (fail closed)",
                requiredScope = requiredScope,
            )
        if (requiredScope in NEVER_ON_LAN) {
            return LanAuthzDecision.Denied(
                reason = "scope never accepted on LAN listener",
                requiredScope = requiredScope,
            )
        }
        if (!AccessControlCatalog.profileAllowsScope(AccessProfile.LAN_CLIENT, req)) {
            return LanAuthzDecision.Denied(
                reason = "scope not in LAN_CLIENT profile",
                requiredScope = requiredScope,
            )
        }
        if (requiredScope !in grantedScopes) {
            return LanAuthzDecision.Denied(
                reason = "token missing required scope",
                requiredScope = requiredScope,
            )
        }
        return LanAuthzDecision.Allowed(requiredScope)
    }
}

sealed class LanScopeValidation {
    data class Ok(val scopes: Set<String>) : LanScopeValidation()
    data class Invalid(
        val message: String,
        val unknown: List<String> = emptyList(),
        val disallowed: List<String> = emptyList(),
    ) : LanScopeValidation()
}

sealed class LanAuthzDecision {
    data class Allowed(val scope: String) : LanAuthzDecision()
    data class Denied(val reason: String, val requiredScope: String) : LanAuthzDecision()
}
