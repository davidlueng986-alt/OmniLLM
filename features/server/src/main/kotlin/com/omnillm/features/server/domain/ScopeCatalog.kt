package com.omnillm.features.server.domain

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope

/**
 * Scope helpers for FEAT-SERVER client / token onboarding.
 * Authority: `specs/access-control-catalog.yaml` — no invented scopes.
 */
object ScopeCatalog {

    /** Default scopes when creating a loopback developer client (DEVELOPER_CLIENT). */
    val DEVELOPER_CLIENT_DEFAULT: Set<String> =
        AccessControlCatalog.PROFILES
            .first { it.id == AccessProfile.DEVELOPER_CLIENT }
            .scopes
            .toSet()

    /** Minimal smoke-test scopes (health is public; inference + models + cancel). */
    val SMOKE_MINIMAL: Set<String> = setOf(
        AccessScope.models_read.id,
        AccessScope.inference_create.id,
        AccessScope.inference_cancel.id,
        AccessScope.inference_read_own.id,
    )

    /**
     * Validate requested scopes against the catalog.
     * Unknown scope ⇒ fail closed (INV-018).
     * Scopes outside [profile] are rejected unless profile is wildcard.
     */
    fun validateScopes(
        requested: Collection<String>,
        profile: AccessProfile = AccessProfile.DEVELOPER_CLIENT,
    ): ScopeValidation {
        if (requested.isEmpty()) {
            return ScopeValidation.Invalid("scopes must be non-empty")
        }
        val unknown = mutableListOf<String>()
        val disallowed = mutableListOf<String>()
        val resolved = linkedSetOf<String>()
        for (raw in requested) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            val scope = AccessScope.fromId(id)
            if (scope == null) {
                unknown += id
                continue
            }
            if (!AccessControlCatalog.profileAllowsScope(profile, scope)) {
                disallowed += id
                continue
            }
            resolved += id
        }
        if (unknown.isNotEmpty()) {
            return ScopeValidation.Invalid(
                message = "unknown scope(s) (fail closed)",
                unknown = unknown,
            )
        }
        if (disallowed.isNotEmpty()) {
            return ScopeValidation.Invalid(
                message = "scope(s) not allowed for profile ${profile.id}",
                disallowed = disallowed,
            )
        }
        if (resolved.isEmpty()) {
            return ScopeValidation.Invalid("scopes must be non-empty after normalize")
        }
        return ScopeValidation.Ok(resolved)
    }

    fun isKnownScope(id: String): Boolean = AccessScope.fromId(id) != null
}

sealed class ScopeValidation {
    data class Ok(val scopes: Set<String>) : ScopeValidation()
    data class Invalid(
        val message: String,
        val unknown: List<String> = emptyList(),
        val disallowed: List<String> = emptyList(),
    ) : ScopeValidation()
}
