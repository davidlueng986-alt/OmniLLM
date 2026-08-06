package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.contentreport.api.BeginReviewSpec
import com.omnillm.features.contentreport.api.GrantConsentSpec
import com.omnillm.features.contentreport.api.SubmitReportSpec
import com.omnillm.features.contentreport.domain.ConsentGrant
import com.omnillm.features.contentreport.domain.ConsentGrantState
import com.omnillm.features.contentreport.domain.ContentReportPolicy
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.ports.InMemoryContentReportStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Negative consent fixtures from specs/content-reporting-fixtures.yaml
 * (CR-N001 … CR-N006).
 */
class ContentReportNegativeConsentTest {

    @Test
    fun CR_N001_external_userConfirmed_without_grant_forbidden() = runBlocking {
        val api = service()
        val r = api.createProposal(
            principal = externalPrincipal(),
            surface = CallerSurface.EXPORTED_AIDL,
            profileAuthenticated = true,
            accessProfileId = "APP_CLIENT",
            spec = proposalSpec(userConfirmed = true),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as OmniResult.Err).error.code)
        assertEquals("CR-N001", r.error.details["fixture"])
    }

    @Test
    fun CR_N002_consume_already_consumed_grant_stateConflict() = runBlocking {
        val store = InMemoryContentReportStore()
        val p = ports(store = store)
        val api = service(p)
        val spec = proposalSpec()
        assertOk(seedDraft(api, spec))
        val begin = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        val grantResult = assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = spec.reportId,
                    command = cmd("g1"),
                    canonicalPayloadDigest = begin.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        val grantId = grantResult.grant.grantId
        // First submit consumes grant.
        assertOk(
            api.submitReport(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                SubmitReportSpec(
                    reportId = spec.reportId,
                    consentGrantId = grantId,
                    command = cmd("s1"),
                    preferOnline = true,
                ),
            ),
        )
        // Second submit with same grant → CR-N002
        val again = api.submitReport(
            principal(),
            CallerSurface.LOCAL_TRUSTED_UI,
            SubmitReportSpec(
                reportId = spec.reportId,
                consentGrantId = grantId,
                command = cmd("s2"),
                preferOnline = true,
            ),
        )
        assertTrue(again is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (again as OmniResult.Err).error.code)
        assertEquals("CR-N002", again.error.details["fixture"])
    }

    @Test
    fun CR_N003_digest_substitution_after_review_stateConflict() = runBlocking {
        val api = service()
        val spec = proposalSpec()
        assertOk(seedDraft(api, spec))
        assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        val forged = digest('f')
        val r = api.grantConsent(
            principal(),
            CallerSurface.LOCAL_TRUSTED_UI,
            GrantConsentSpec(
                reportId = spec.reportId,
                command = cmd("g"),
                canonicalPayloadDigest = forged,
                warningPolicyVersion = "warn-v1",
                localUserProfileId = "user-1",
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (r as OmniResult.Err).error.code)
        assertEquals("CR-N003", r.error.details["fixture"])
    }

    @Test
    fun CR_N004_grant_from_another_principal_forbidden() = runBlocking {
        val store = InMemoryContentReportStore()
        val p = ports(store = store)
        val api = service(p)
        val spec = proposalSpec()
        assertOk(seedDraft(api, spec))
        val begin = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        val grantResult = assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = spec.reportId,
                    command = cmd("g1"),
                    canonicalPayloadDigest = begin.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        // Inject a grant bound to a different principal (simulates theft / mix-up).
        val stolen = grantResult.grant.copy(
            grantId = uuid("stolen"),
            principalId = "ANDROID_APP",
            state = ConsentGrantState.ISSUED,
            consumedAtEpochMs = null,
        )
        store.putGrant(stolen)

        val r = api.submitReport(
            principal(), // LOCAL_UI trying to use ANDROID_APP grant
            CallerSurface.LOCAL_TRUSTED_UI,
            SubmitReportSpec(
                reportId = spec.reportId,
                consentGrantId = stolen.grantId,
                command = cmd("s"),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as OmniResult.Err).error.code)
        assertEquals("CR-N004", r.error.details["fixture"])
    }

    @Test
    fun CR_N005_expired_grant_stateConflict() = runBlocking {
        val clock = ClockControl()
        val store = InMemoryContentReportStore()
        val p = ports(store = store, clock = clock)
        val api = service(p)
        val spec = proposalSpec()
        assertOk(seedDraft(api, spec))
        val begin = assertOk(
            api.beginLocalReview(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                BeginReviewSpec(spec.reportId, cmd("rev")),
            ),
        )
        val grantResult = assertOk(
            api.grantConsent(
                principal(),
                CallerSurface.LOCAL_TRUSTED_UI,
                GrantConsentSpec(
                    reportId = spec.reportId,
                    command = cmd("g1"),
                    canonicalPayloadDigest = begin.canonicalPayloadDigest,
                    warningPolicyVersion = "warn-v1",
                    localUserProfileId = "user-1",
                ),
            ),
        )
        // Advance past grant TTL.
        clock.advance(ContentReportPolicy.DEFAULT_GRANT_TTL_MS + 1)
        val r = api.submitReport(
            principal(),
            CallerSurface.LOCAL_TRUSTED_UI,
            SubmitReportSpec(
                reportId = spec.reportId,
                consentGrantId = grantResult.grant.grantId,
                command = cmd("s"),
            ),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.STATE_CONFLICT, (r as OmniResult.Err).error.code)
        assertEquals("CR-N005", r.error.details["fixture"])
    }

    @Test
    fun CR_N006_grant_via_exported_aidl_loopback_lan_forbidden() = runBlocking {
        val api = service()
        val spec = proposalSpec()
        assertOk(
            api.createProposal(
                principal = externalPrincipal(),
                surface = CallerSurface.EXPORTED_AIDL,
                profileAuthenticated = true,
                accessProfileId = "APP_CLIENT",
                spec = spec,
            ),
        )

        for (surface in listOf(
            CallerSurface.EXPORTED_AIDL,
            CallerSurface.LOOPBACK_HTTP,
            CallerSurface.LAN_HTTP,
        )) {
            val r = api.beginLocalReview(
                principal = externalPrincipal(),
                surface = surface,
                spec = BeginReviewSpec(spec.reportId, cmd("rev-${surface.name}")),
            )
            assertTrue("surface $surface should be FORBIDDEN", r is OmniResult.Err)
            assertEquals(OmniErrorCode.FORBIDDEN, (r as OmniResult.Err).error.code)
            assertEquals("CR-N006", r.error.details["fixture"])
        }

        val grantAttempt = api.grantConsent(
            principal = externalPrincipal(),
            surface = CallerSurface.LAN_HTTP,
            spec = GrantConsentSpec(
                reportId = spec.reportId,
                command = cmd("g"),
                canonicalPayloadDigest = digest('x'),
                warningPolicyVersion = "w",
                localUserProfileId = "u",
            ),
        )
        assertTrue(grantAttempt is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (grantAttempt as OmniResult.Err).error.code)
    }

    private fun <T> assertOk(r: OmniResult<T>): T {
        assertTrue("expected Ok got $r", r is OmniResult.Ok)
        return (r as OmniResult.Ok).value
    }
}
