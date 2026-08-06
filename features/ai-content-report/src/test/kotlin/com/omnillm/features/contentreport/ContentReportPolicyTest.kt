package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.contentreport.domain.ContentReportPayload
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.projection.ContentReportStateProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy unit tests: scopes, report≠telemetry, LAN auth fail-closed,
 * no long-lived secrets, no silent cross-revision fallback.
 */
class ContentReportPolicyTest {

    @Test
    fun report_stream_is_not_telemetry() {
        assertTrue(ContentReportPolicy.reportIsNotTelemetry())
        assertEquals("AI_CONTENT_REPORT", ContentReportPolicy.DATA_STREAM_KIND)
        assertFalse(
            ContentReportPolicy.DATA_STREAM_KIND == ContentReportPolicy.TELEMETRY_STREAM_KIND,
        )
        ContentReportStateProjection.assertNotTelemetry(ContentReportPolicy.DATA_STREAM_KIND)
    }

    @Test
    fun exported_profiles_lack_review_submit() {
        assertTrue(ContentReportPolicy.exportedProfilesLackReviewSubmit())
        assertFalse(
            ContentReportPolicy.profileAllows(
                AccessProfile.APP_CLIENT,
                AccessScope.content_reports_review_submit,
            ),
        )
        assertFalse(
            ContentReportPolicy.profileAllows(
                AccessProfile.DEVELOPER_CLIENT,
                AccessScope.content_reports_review_submit,
            ),
        )
        assertFalse(
            ContentReportPolicy.profileAllows(
                AccessProfile.LAN_CLIENT,
                AccessScope.content_reports_review_submit,
            ),
        )
        assertFalse(
            ContentReportPolicy.profileAllows(
                AccessProfile.LOCAL_ADMIN_HTTP,
                AccessScope.content_reports_review_submit,
            ),
        )
        assertTrue(
            ContentReportPolicy.profileAllows(
                AccessProfile.LOCAL_ADMIN,
                AccessScope.content_reports_review_submit,
            ),
        )
    }

    @Test
    fun mayReviewAndSubmit_only_local_trusted_ui() {
        assertTrue(
            ContentReportPolicy.mayReviewAndSubmit(
                CallerSurface.LOCAL_TRUSTED_UI,
                PrincipalKind.LOCAL_UI,
            ),
        )
        assertTrue(
            ContentReportPolicy.isLocalOnlyConsentPath(
                CallerSurface.LOCAL_TRUSTED_UI,
                PrincipalKind.LOCAL_UI,
            ),
        )
        // HTTP_LOCAL_ADMIN / loopback never share the Admin binder consent path.
        assertFalse(
            ContentReportPolicy.mayReviewAndSubmit(
                CallerSurface.LOCAL_TRUSTED_UI,
                PrincipalKind.HTTP_LOCAL_ADMIN,
            ),
        )
        assertFalse(
            ContentReportPolicy.mayReviewAndSubmit(
                CallerSurface.EXPORTED_AIDL,
                PrincipalKind.ANDROID_APP,
            ),
        )
        assertFalse(
            ContentReportPolicy.mayReviewAndSubmit(
                CallerSurface.LOOPBACK_HTTP,
                PrincipalKind.HTTP_LOOPBACK,
            ),
        )
        assertFalse(
            ContentReportPolicy.mayReviewAndSubmit(
                CallerSurface.LAN_HTTP,
                PrincipalKind.HTTP_LAN,
            ),
        )
    }

    @Test
    fun lan_unauthenticated_fails_closed() {
        val err = ContentReportPolicy.requireAuthenticatedLan(
            CallerSurface.LAN_HTTP,
            authenticated = false,
        )
        assertNotNull(err)
        assertEquals(OmniErrorCode.UNAUTHORIZED, err!!.code)

        assertNull(
            ContentReportPolicy.requireAuthenticatedLan(
                CallerSurface.LAN_HTTP,
                authenticated = true,
            ),
        )
        assertNull(
            ContentReportPolicy.requireAuthenticatedLan(
                CallerSurface.LOOPBACK_HTTP,
                authenticated = false,
            ),
        )
    }

    @Test
    fun external_userConfirmed_forbidden() {
        val err = ContentReportPolicy.rejectExternalUserConfirmed(
            CallerSurface.EXPORTED_AIDL,
            userConfirmed = true,
        )
        assertNotNull(err)
        assertEquals(OmniErrorCode.FORBIDDEN, err!!.code)
        assertEquals("CR-N001", err.details["fixture"])
    }

    @Test
    fun payload_rejects_pairing_secret_markers() {
        val clean = samplePayload()
        assertFalse(ContentReportPolicy.payloadContainsForbiddenSecretMaterial(clean))

        val dirty = clean.copy(description = "pairing_secret=abc-long-lived")
        assertTrue(ContentReportPolicy.payloadContainsForbiddenSecretMaterial(dirty))
    }

    @Test
    fun silent_cross_revision_fallback_forbidden() {
        val a = samplePayload(modelRevisionId = digest('1'))
        val b = samplePayload(modelRevisionId = digest('2'))
        val err = ContentReportPolicy.revisionBindingConflict(a, b)
        assertNotNull(err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, err!!.code)
        assertTrue(err.message!!.contains("cross-revision"))
    }

    @Test
    fun unknown_category_fails_closed() {
        assertNull(ContentReportPolicy.requireKnownCategory("NOT_A_CATEGORY"))
        val err = ContentReportPolicy.unknownCategoryError("NOT_A_CATEGORY")
        assertEquals(OmniErrorCode.INVALID_REQUEST, err.code)
    }

    @Test
    fun discard_rules_match_catalog() {
        assertTrue(ContentReportPolicy.mayDiscard(ContentReportState.DRAFT))
        assertTrue(ContentReportPolicy.mayDiscard(ContentReportState.QUEUED_OFFLINE))
        assertFalse(ContentReportPolicy.mayDiscard(ContentReportState.SUBMITTING))
        assertFalse(ContentReportPolicy.mayDiscard(ContentReportState.SUBMITTED))
        assertTrue(ContentReportPolicy.discardDisguisesSubmitted(ContentReportState.SUBMITTED))
    }

    @Test
    fun planPayload_is_pure_no_mutation() = runBlocking {
        val api = service()
        val plan = api.planPayload(proposalSpec(promptExcerpt = null, outputExcerpt = null))
        assertTrue(plan is OmniResult.Ok)
        val p = (plan as OmniResult.Ok).value
        assertTrue(p.payload.isMinimizedDefault())
        assertEquals(64, p.digestHex.length)
        // Still no reports persisted.
        val snap = assertOk(api.getSnapshot(principal()))
        assertTrue(snap.reports.isEmpty())
        assertFalse(snap.isTelemetryStream)
    }

    @Test
    fun createProposal_unknownCategory_failsClosed() = runBlocking {
        val api = service()
        val r = seedDraft(api, proposalSpec(category = "BOGUS"))
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
    }

    @Test
    fun lan_create_without_auth_failsClosed() = runBlocking {
        val api = service()
        val r = api.createProposal(
            principal = externalPrincipal("HTTP_LAN"),
            surface = CallerSurface.LAN_HTTP,
            profileAuthenticated = false,
            accessProfileId = "LAN_CLIENT",
            spec = proposalSpec(),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.UNAUTHORIZED, (r as OmniResult.Err).error.code)
    }

    @Test
    fun ux_projection_actions_for_catalog_states() {
        assertEquals(
            listOf("review-payload", "discard"),
            ContentReportStateProjection.actionsFor(ContentReportState.DRAFT),
        )
        assertEquals(
            listOf("view-receipt"),
            ContentReportStateProjection.actionsFor(ContentReportState.SUBMITTED),
        )
        assertFalse(ContentReportStateProjection.receiptIsModerationOutcome())
    }

    private fun samplePayload(
        modelRevisionId: String = digest('1'),
        engineBuildId: String = "eng-1",
    ): ContentReportPayload =
        ContentReportPayload(
            reportId = uuid("p"),
            category = com.omnillm.core.canonical.generated.ContentReportCategory.OTHER,
            createdAt = "2026-01-01T00:00:00Z",
            appBuild = "1.0.0",
            modelRevisionId = modelRevisionId,
            engineBuildId = engineBuildId,
            backend = "llama-cpp",
            localPolicyVersion = "pv1",
            outputDigest = digest('d'),
            userLocale = "en",
        )

    private fun <T> assertOk(r: OmniResult<T>): T {
        assertTrue("expected Ok got $r", r is OmniResult.Ok)
        return (r as OmniResult.Ok).value
    }
}
