// ---------------------------------------------------------------------------
// GENERATED FILE — DO NOT EDIT BY HAND
// Source: tools/codegen/generate_contracts.py
// Authority: specs/*.yaml (formal-contract-artifacts)
// Re-run: ./gradlew generateContracts
// ---------------------------------------------------------------------------

package com.omnillm.core.canonical.generated

/** Access-control catalog from specs/access-control-catalog.yaml. */

enum class PrincipalKind {
    LOCAL_UI,
    ANDROID_APP,
    HTTP_LOOPBACK,
    HTTP_LOCAL_ADMIN,
    HTTP_LAN,
    ISOLATED_WORKER
    ;

    companion object {
        fun fromId(id: String): PrincipalKind? =
            entries.firstOrNull { it.name == id }
    }
}

enum class AccessScope(val id: String) {
    assets_create("assets.create"),
    assets_delete_own("assets.delete-own"),
    assets_read_own("assets.read-own"),
    clients_manage("clients.manage"),
    clients_read("clients.read"),
    content_reports_propose("content-reports.propose"),
    content_reports_manage_own("content-reports.manage-own"),
    content_reports_read_own("content-reports.read-own"),
    content_reports_review_submit("content-reports.review-submit"),
    commands_read_own("commands.read-own"),
    diagnostics_export("diagnostics.export"),
    inference_cancel("inference.cancel"),
    inference_create("inference.create"),
    inference_read_own("inference.read-own"),
    jobs_manage("jobs.manage"),
    jobs_read_all("jobs.read-all"),
    jobs_read_own("jobs.read-own"),
    lan_manage("lan.manage"),
    metrics_read_detail("metrics.read-detail"),
    metrics_read_summary("metrics.read-summary"),
    models_manage("models.manage"),
    models_read("models.read"),
    settings_read("settings.read"),
    settings_write("settings.write"),
    tokens_manage("tokens.manage")
    ;

    companion object {
        fun fromId(id: String): AccessScope? =
            entries.firstOrNull { it.id == id }

        fun requireFromId(id: String): AccessScope =
            fromId(id) ?: error("Unknown scope (fail closed): $id")
    }
}

enum class AccessProfile(val id: String) {
    LOCAL_ADMIN("LOCAL_ADMIN"),
    LOCAL_ADMIN_HTTP("LOCAL_ADMIN_HTTP"),
    APP_CLIENT("APP_CLIENT"),
    DEVELOPER_CLIENT("DEVELOPER_CLIENT"),
    LAN_CLIENT("LAN_CLIENT"),
    WORKER("WORKER")
    ;

    companion object {
        fun fromId(id: String): AccessProfile? =
            entries.firstOrNull { it.id == id }
    }
}

data class ScopeDefinition(
    val id: AccessScope,
    val operations: List<String>,
    val transport: String? = null,
)

data class ProfileDefinition(
    val id: AccessProfile,
    val scopes: List<String>,
    val transport: String? = null,
    val wildcardScopes: Boolean = false,
    val requiresExplicitLocalIssuance: Boolean = false,
    val acceptedOnLanListener: Boolean? = null,
    val requiresExplicitApprovalForEveryScope: Boolean = false,
)

object AccessControlCatalog {
    const val SCHEMA_VERSION: Int = 2

    val SCOPES: List<ScopeDefinition> = listOf(
        ScopeDefinition(AccessScope.assets_create, listOf("create-upload-handle", "upload", "commit")),
        ScopeDefinition(AccessScope.assets_delete_own, listOf("delete-own-asset")),
        ScopeDefinition(AccessScope.assets_read_own, listOf("query-own-asset")),
        ScopeDefinition(AccessScope.clients_manage, listOf("approve-suspend-revoke-clients")),
        ScopeDefinition(AccessScope.clients_read, listOf("list-client-registrations")),
        ScopeDefinition(AccessScope.content_reports_propose, listOf("create-ai-output-report-proposal")),
        ScopeDefinition(AccessScope.content_reports_manage_own, listOf("cancel-own-content-report", "discard-own-content-report")),
        ScopeDefinition(AccessScope.content_reports_read_own, listOf("read-own-content-report-status", "read-own-content-report-receipt")),
        ScopeDefinition(AccessScope.content_reports_review_submit, listOf("review-report-in-trusted-local-ui", "issue-one-time-consent-grant", "submit-report-with-consumed-grant"), transport = "non-exported local admin AIDL only"),
        ScopeDefinition(AccessScope.commands_read_own, listOf("query-own-command-result")),
        ScopeDefinition(AccessScope.diagnostics_export, listOf("create-redacted-diagnostic-export")),
        ScopeDefinition(AccessScope.inference_cancel, listOf("cancel-own-request")),
        ScopeDefinition(AccessScope.inference_create, listOf("chat", "embedding", "structured-output", "tool-call")),
        ScopeDefinition(AccessScope.inference_read_own, listOf("query-own-request", "resume-own-stream")),
        ScopeDefinition(AccessScope.jobs_manage, listOf("create-control-jobs")),
        ScopeDefinition(AccessScope.jobs_read_all, listOf("read-all-jobs")),
        ScopeDefinition(AccessScope.jobs_read_own, listOf("read-own-jobs")),
        ScopeDefinition(AccessScope.lan_manage, listOf("enable-disable-configure-lan")),
        ScopeDefinition(AccessScope.metrics_read_detail, listOf("read-local-detailed-metrics")),
        ScopeDefinition(AccessScope.metrics_read_summary, listOf("read-redacted-summary")),
        ScopeDefinition(AccessScope.models_manage, listOf("acquire-model", "import-model", "delete-model", "set-alias")),
        ScopeDefinition(AccessScope.models_read, listOf("list-models", "get-model")),
        ScopeDefinition(AccessScope.settings_read, listOf("read-effective-settings")),
        ScopeDefinition(AccessScope.settings_write, listOf("change-settings")),
        ScopeDefinition(AccessScope.tokens_manage, listOf("issue-rotate-revoke-tokens")),
    )

    val PROFILES: List<ProfileDefinition> = listOf(
        ProfileDefinition(AccessProfile.LOCAL_ADMIN, emptyList(), transport = "non-exported AIDL", wildcardScopes = true),
        ProfileDefinition(AccessProfile.LOCAL_ADMIN_HTTP, listOf("assets.create", "assets.delete-own", "assets.read-own", "clients.manage", "clients.read", "content-reports.propose", "content-reports.manage-own", "content-reports.read-own", "commands.read-own", "diagnostics.export", "inference.cancel", "inference.create", "inference.read-own", "jobs.manage", "jobs.read-all", "jobs.read-own", "lan.manage", "metrics.read-detail", "metrics.read-summary", "models.manage", "models.read", "settings.read", "settings.write", "tokens.manage"), transport = "literal loopback HTTP only", requiresExplicitLocalIssuance = true, acceptedOnLanListener = false),
        ProfileDefinition(AccessProfile.APP_CLIENT, listOf("models.read", "inference.create", "inference.cancel", "inference.read-own", "assets.create", "assets.read-own", "assets.delete-own", "jobs.read-own", "content-reports.propose", "content-reports.manage-own", "content-reports.read-own", "commands.read-own"), transport = "exported runtime AIDL"),
        ProfileDefinition(AccessProfile.DEVELOPER_CLIENT, listOf("models.read", "inference.create", "inference.cancel", "inference.read-own", "assets.create", "assets.read-own", "assets.delete-own", "metrics.read-summary", "jobs.read-own", "content-reports.propose", "content-reports.manage-own", "content-reports.read-own", "commands.read-own"), transport = "loopback HTTP"),
        ProfileDefinition(AccessProfile.LAN_CLIENT, listOf("models.read", "inference.create", "inference.cancel", "inference.read-own", "assets.create", "assets.read-own", "assets.delete-own", "jobs.read-own", "content-reports.propose", "content-reports.manage-own", "content-reports.read-own", "commands.read-own"), transport = "TLS LAN HTTP", requiresExplicitApprovalForEveryScope = true),
        ProfileDefinition(AccessProfile.WORKER, emptyList(), transport = "narrow supervisor binder only"),
    )

    val INVARIANTS: List<String> = listOf("caller-supplied package name is never a principal", "each operation declares exactly one required scope from this catalog", "revocation bumps a monotonic epoch and fences active, queued and pooled state", "admin binder is never returned by the exported runtime service", "response fields are allowlisted per scope and transport", "loopback admin bearer tokens are never accepted by the LAN listener", "LAN bearer tokens are issued only by the channel-bound LAN pairing exchange", "AIDL registration uses observed UID/user plus local approval and does not reuse the LAN pairing secret protocol", "content-reports.review-submit is reachable only through LOCAL_UI/LOCAL_ADMIN on the non-exported admin binder", "no exported profile can assert consent; external profiles can only propose, query, cancel or discard their own report")

    fun profileAllowsScope(profile: AccessProfile, scope: AccessScope): Boolean {
        val def = PROFILES.firstOrNull { it.id == profile } ?: return false
        if (def.wildcardScopes) return true
        return def.scopes.contains(scope.id)
    }

    fun requireKnownScope(id: String): AccessScope = AccessScope.requireFromId(id)
}
