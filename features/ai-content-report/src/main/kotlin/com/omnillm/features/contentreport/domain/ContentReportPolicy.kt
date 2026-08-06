package com.omnillm.features.contentreport.domain

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.ContentReportCategory
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.interfaces.admin.LocalUiPrincipal

/**
 * Access + privacy policy for FEAT-AI-CONTENT-REPORT.
 *
 * Authority:
 * - `specs/access-control-catalog.yaml` (scopes / profiles / invariants)
 * - `specs/content-reporting-fixtures.yaml` (negative consent)
 * - SEC-PRIVACY: report stream ≠ telemetry
 * - FEAT-AI-CONTENT-REPORT: only trusted local UI may assert consent
 *
 * Fail closed on unknown category, unknown transport, missing auth, or any
 * attempt to obtain `content-reports.review-submit` outside LOCAL_UI.
 */
object ContentReportPolicy {

    /** Distinct data stream — never merged into general telemetry pipelines. */
    const val DATA_STREAM_KIND: String = "AI_CONTENT_REPORT"

    /** Telemetry stream kind (for inequality checks). */
    const val TELEMETRY_STREAM_KIND: String = "TELEMETRY"

    /** Default draft / queue TTL (retention-policy: 24h). */
    const val DEFAULT_TTL_MS: Long = 24L * 60L * 60L * 1000L

    /** Default grant lifetime after local review (short-lived, one-time). */
    const val DEFAULT_GRANT_TTL_MS: Long = 15L * 60L * 1000L

    val REVIEW_SUBMIT_SCOPE: AccessScope = AccessScope.content_reports_review_submit
    val PROPOSE_SCOPE: AccessScope = AccessScope.content_reports_propose
    val MANAGE_OWN_SCOPE: AccessScope = AccessScope.content_reports_manage_own
    val READ_OWN_SCOPE: AccessScope = AccessScope.content_reports_read_own

    /**
     * Caller surface classification for policy gates.
     * Maps to access-control transport notes — not inventing new principal kinds.
     */
    enum class CallerSurface {
        /** Non-exported admin AIDL / LOCAL_UI trusted review surface. */
        LOCAL_TRUSTED_UI,

        /** Exported runtime AIDL (APP_CLIENT). */
        EXPORTED_AIDL,

        /** Loopback HTTP (DEVELOPER_CLIENT / LOCAL_ADMIN_HTTP). */
        LOOPBACK_HTTP,

        /** LAN HTTP TLS (LAN_CLIENT) — auth required, fail closed. */
        LAN_HTTP,
    }

    fun reportIsNotTelemetry(): Boolean =
        DATA_STREAM_KIND != TELEMETRY_STREAM_KIND

    /**
     * Whether [profile] may invoke [scope]. Unknown profile ⇒ false (fail closed).
     */
    fun profileAllows(profile: AccessProfile, scope: AccessScope): Boolean =
        AccessControlCatalog.profileAllowsScope(profile, scope)

    /**
     * Review / ConsentGrant / submit is only reachable on trusted local UI
     * with LOCAL_ADMIN wildcard (non-exported admin binder).
     *
     * **Local-only consent path** (FEAT-AI-CONTENT-REPORT §5, CORE-INTERFACE §12):
     * - Surface must be [CallerSurface.LOCAL_TRUSTED_UI] (Admin binder, non-exported)
     * - Principal must be [PrincipalKind.LOCAL_UI]
     * - LOCAL_ADMIN_HTTP / loopback / LAN / exported AIDL never receive review-submit
     *
     * CR-N006: request grant through exported AIDL / loopback / LAN ⇒ FORBIDDEN.
     */
    fun mayReviewAndSubmit(
        surface: CallerSurface,
        principalKind: PrincipalKind,
    ): Boolean {
        if (!isLocalOnlyConsentPath(surface, principalKind)) return false
        // LOCAL_ADMIN wildcard; LOCAL_ADMIN_HTTP deliberately lacks review-submit.
        return profileAllows(AccessProfile.LOCAL_ADMIN, REVIEW_SUBMIT_SCOPE)
    }

    /**
     * True when consent review/grant/submit is allowed on this surface+principal.
     * HTTP / LAN / exported AIDL always false — consent is Admin binder local-only.
     */
    fun isLocalOnlyConsentPath(
        surface: CallerSurface,
        principalKind: PrincipalKind,
    ): Boolean =
        surface == CallerSurface.LOCAL_TRUSTED_UI &&
            principalKind == PrincipalKind.LOCAL_UI

    fun mayPropose(profile: AccessProfile): Boolean =
        profileAllows(profile, PROPOSE_SCOPE)

    fun mayManageOwn(profile: AccessProfile): Boolean =
        profileAllows(profile, MANAGE_OWN_SCOPE)

    fun mayReadOwn(profile: AccessProfile): Boolean =
        profileAllows(profile, READ_OWN_SCOPE)

    /**
     * External caller asserting userConfirmed / consent without a grant
     * (CR-N001) ⇒ FORBIDDEN.
     */
    fun rejectExternalUserConfirmed(
        surface: CallerSurface,
        userConfirmed: Boolean,
    ): OmniError? {
        if (!userConfirmed) return null
        if (surface == CallerSurface.LOCAL_TRUSTED_UI) return null
        return OmniError.FORBIDDEN(
            message = "external caller cannot assert userConfirmed without ConsentGrant",
            details = mapOf(
                "fixture" to "CR-N001",
                "surface" to surface.name,
                "scope" to REVIEW_SUBMIT_SCOPE.id,
            ),
        )
    }

    /**
     * LAN / unauthenticated access fails closed (task: LAN/report fail closed on auth).
     */
    fun requireAuthenticatedLan(
        surface: CallerSurface,
        authenticated: Boolean,
    ): OmniError? {
        if (surface != CallerSurface.LAN_HTTP) return null
        if (authenticated) return null
        return OmniError.UNAUTHORIZED(
            message = "LAN content-report requires authenticated principal (fail closed)",
            details = mapOf("surface" to surface.name),
        )
    }

    /**
     * QR / pairing material must never carry long-lived secrets or report tokens.
     * Content reports never embed pairing secrets.
     */
    fun payloadContainsForbiddenSecretMaterial(payload: ContentReportPayload): Boolean {
        val candidates = listOfNotNull(
            payload.description,
            payload.promptExcerpt,
            payload.outputExcerpt,
            payload.diagnosticSummary,
        )
        // Structural: report payload fields are content only — no token/QR secret fields exist.
        // Guard against accidental attachment of known secret markers in free text for tests.
        val markers = listOf(
            "pairing_secret=",
            "long_lived_token=",
            "qr_secret=",
            "bearer ",
        )
        return candidates.any { text ->
            val lower = text.lowercase()
            markers.any { marker -> lower.contains(marker) }
        }
    }

    /**
     * Silent cross-revision fallback is forbidden: proposal modelRevisionId
     * is sticky; submit/freeze must match exactly.
     */
    fun revisionBindingConflict(
        proposal: ContentReportPayload,
        attempted: ContentReportPayload,
    ): OmniError? {
        if (proposal.modelRevisionId != attempted.modelRevisionId) {
            return OmniError.STATE_CONFLICT(
                message = "silent cross-revision fallback forbidden",
                details = mapOf(
                    "expectedModelRevisionId" to proposal.modelRevisionId,
                    "attemptedModelRevisionId" to attempted.modelRevisionId,
                ),
            )
        }
        if (proposal.engineBuildId != attempted.engineBuildId) {
            return OmniError.STATE_CONFLICT(
                message = "engineBuildId rewrite forbidden after proposal",
                details = mapOf(
                    "expectedEngineBuildId" to proposal.engineBuildId,
                    "attemptedEngineBuildId" to attempted.engineBuildId,
                ),
            )
        }
        if (proposal.outputDigest != attempted.outputDigest) {
            return OmniError.STATE_CONFLICT(
                message = "outputDigest rewrite forbidden after proposal",
                details = mapOf(
                    "expectedOutputDigest" to proposal.outputDigest,
                    "attemptedOutputDigest" to attempted.outputDigest,
                ),
            )
        }
        return null
    }

    fun unknownCategoryError(raw: String): OmniError =
        OmniError.INVALID_REQUEST(
            message = "unknown ContentReportCategory (fail closed)",
            details = mapOf("category" to raw),
        )

    fun requireKnownCategory(raw: String): ContentReportCategory? =
        ContentReportCategory.fromCatalogName(raw.trim())

    /**
     * Discard is only legal before server acceptance on catalog edges RPT-012.
     * SUBMITTING / CANCELLING / RECONCILING must reconcile first.
     */
    fun mayDiscard(state: ContentReportState): Boolean = when (state) {
        ContentReportState.DRAFT,
        ContentReportState.REVIEWING,
        ContentReportState.CONSENT_GRANTED,
        ContentReportState.QUEUED_OFFLINE,
        ContentReportState.FAILED_RETRYABLE,
        -> true
        else -> false
    }

    /**
     * SUBMITTED must not be disguised as discarded — only local retention cleanup.
     */
    fun discardDisguisesSubmitted(state: ContentReportState): Boolean =
        state == ContentReportState.SUBMITTED

    fun isLocalUiPrincipal(principalValue: String): Boolean =
        principalValue == LocalUiPrincipal.ID.value

    fun localUiMayReviewSubmit(): Boolean =
        mayReviewAndSubmit(CallerSurface.LOCAL_TRUSTED_UI, PrincipalKind.LOCAL_UI)

    /**
     * Profiles that never receive review-submit (catalog invariants).
     */
    fun exportedProfilesLackReviewSubmit(): Boolean {
        val exported = listOf(
            AccessProfile.APP_CLIENT,
            AccessProfile.DEVELOPER_CLIENT,
            AccessProfile.LAN_CLIENT,
            AccessProfile.LOCAL_ADMIN_HTTP,
            AccessProfile.WORKER,
        )
        return exported.none { profileAllows(it, REVIEW_SUBMIT_SCOPE) }
    }

    fun retentionWipesPayloadOn(state: ContentReportState): Boolean =
        state in setOf(
            ContentReportState.SUBMITTED,
            ContentReportState.FAILED_FINAL,
            ContentReportState.DISCARDED,
            ContentReportState.EXPIRED,
        )
}
