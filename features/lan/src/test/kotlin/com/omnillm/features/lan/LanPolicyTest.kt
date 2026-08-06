package com.omnillm.features.lan

import com.omnillm.core.state.TransitionOutcome
import com.omnillm.features.lan.domain.ContentReportVsTelemetry
import com.omnillm.features.lan.domain.LanAuthzDecision
import com.omnillm.features.lan.domain.LanScopePolicy
import com.omnillm.features.lan.domain.LanScopeValidation
import com.omnillm.features.lan.domain.LanServiceLifecyclePolicy
import com.omnillm.features.lan.domain.PairingChallengePolicy
import com.omnillm.features.lan.domain.QrPayloadPolicy
import com.omnillm.features.lan.domain.QrValidation
import com.omnillm.features.lan.domain.ResponseFieldAllowlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy unit tests: default-off, scopes, QR, report≠telemetry, lifecycle.
 */
class LanPolicyTest {

    @Test
    fun lanDefaultOff_productAndFsm() {
        assertFalse(LanServiceLifecyclePolicy.DEFAULT_ENABLED)
        assertEquals("DISABLED", LanServiceLifecyclePolicy.INITIAL)
        assertEquals("server.lanEnabled", LanServiceLifecyclePolicy.SETTING_KEY)
        assertFalse(LanFeatureModule.createApi(ports()).snapshot().defaultLanEnabled)
    }

    @Test
    fun defaultInferScopes_onlyThree() {
        assertEquals(
            setOf("inference.create", "inference.cancel", "inference.read-own"),
            LanScopePolicy.DEFAULT_INFER_SCOPES,
        )
        val ok = LanScopePolicy.validateRequestedScopes(
            LanScopePolicy.DEFAULT_INFER_SCOPES,
            LanScopePolicy.DEFAULT_INFER_SCOPES,
        )
        assertTrue(ok is LanScopeValidation.Ok)
    }

    @Test
    fun modelsRead_requiresExplicitApproval() {
        val without = LanScopePolicy.validateRequestedScopes(
            requested = setOf("inference.create", "models.read"),
            explicitlyApproved = setOf("inference.create"),
        )
        assertTrue(without is LanScopeValidation.Invalid)
        assertTrue((without as LanScopeValidation.Invalid).disallowed.contains("models.read"))
    }

    @Test
    fun adminScopes_neverOnLan() {
        for (scope in listOf(
            "lan.manage",
            "tokens.manage",
            "clients.manage",
            "settings.write",
            "metrics.read-detail",
            "metrics.read-summary",
            "diagnostics.export",
            "content-reports.review-submit",
        )) {
            assertTrue(scope, LanScopePolicy.isNeverOnLan(scope))
            val v = LanScopePolicy.validateRequestedScopes(setOf(scope), setOf(scope))
            assertTrue(scope, v is LanScopeValidation.Invalid)
        }
    }

    @Test
    fun unknownScope_failsClosed() {
        val v = LanScopePolicy.validateRequestedScopes(
            setOf("not.a.scope"),
            setOf("not.a.scope"),
        )
        assertTrue(v is LanScopeValidation.Invalid)
        assertTrue((v as LanScopeValidation.Invalid).unknown.contains("not.a.scope"))
    }

    @Test
    fun authorizeOperation_missingScope_denied() {
        val d = LanScopePolicy.authorizeOperation(
            grantedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            requiredScope = "models.read",
        )
        assertTrue(d is LanAuthzDecision.Denied)
    }

    @Test
    fun authorizeOperation_inferCreate_allowed() {
        val d = LanScopePolicy.authorizeOperation(
            grantedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            requiredScope = "inference.create",
        )
        assertTrue(d is LanAuthzDecision.Allowed)
    }

    @Test
    fun qr_rejectsLongLivedTokenFields() {
        val v = QrPayloadPolicy.validatePayloadKeys(
            setOf(
                "protocol_label",
                "server_locator",
                "server_spki_sha256",
                "connection_epoch",
                "challenge_id",
                "pairing_secret",
                "expires_at",
                "bearer_token",
            ),
        )
        assertTrue(v is QrValidation.Rejected)
        assertTrue((v as QrValidation.Rejected).forbiddenFields.contains("bearer_token"))
    }

    @Test
    fun qr_encodeContainsOnlyAllowedFields() {
        val material = QrPayloadPolicy.QrMaterial(
            protocolLabel = LanFeatureModule.PAIRING_PROTOCOL_LABEL,
            serverLocator = "https://192.168.1.10:11443",
            serverSpkiSha256 = spki('c'),
            connectionEpoch = 1L,
            challengeId = uuid("ch"),
            pairingSecret = "A".repeat(32),
            expiresAtEpochMs = 1_700_000_300_000L,
            requestedScopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
        )
        val payload = QrPayloadPolicy.encode(material)
        assertFalse(payload.contains("bearer"))
        assertFalse(payload.contains("token_plaintext"))
        assertTrue(payload.contains("server_spki_sha256"))
        assertTrue(payload.contains("pairing_secret"))
        assertTrue(QrPayloadPolicy.isOneTimePairingSecretShape(material.pairingSecret))
    }

    @Test
    fun reportIsNotTelemetry() {
        assertFalse(ContentReportVsTelemetry.isTelemetryEventAllowed("content-report.submit"))
        assertFalse(ContentReportVsTelemetry.isTelemetryEventAllowed("ai-report"))
        assertTrue(ContentReportVsTelemetry.isTelemetryEventAllowed("engine.health.sample"))
        assertFalse(ContentReportVsTelemetry.telemetryModeImpliesReportConsent("LOCAL_ONLY"))
        assertFalse(ContentReportVsTelemetry.telemetryModeImpliesReportConsent("EXPLICIT_EXPORT"))
        assertFalse(ContentReportVsTelemetry.lanEnabledImpliesTelemetryExport(true))
        assertFalse(ContentReportVsTelemetry.mayGrantOnLan("content-reports.review-submit"))
        assertTrue(ContentReportVsTelemetry.mayGrantOnLan("content-reports.propose"))
    }

    @Test
    fun responseAllowlist_stripsPrivateFields() {
        val filtered = ResponseFieldAllowlist.filter(
            scopes = LanScopePolicy.DEFAULT_INFER_SCOPES,
            fields = mapOf(
                "request_id" to "r1",
                "state" to "SUCCEEDED",
                "private_path" to "/data/data/secret",
                "token_plaintext" to "leak",
                "raw_crash_detail" to "native abort",
            ),
        )
        assertEquals(setOf("request_id", "state"), filtered.keys)
        assertTrue(ResponseFieldAllowlist.isForbidden("driver_dump"))
    }

    @Test
    fun lanService_enableFromDisabled() {
        val outcome = LanServiceLifecyclePolicy.step(
            from = "DISABLED",
            event = "ENABLE",
            guards = mapOf("configurationValid" to true),
        )
        assertTrue(outcome is TransitionOutcome.Accepted)
        assertEquals("STARTING", (outcome as TransitionOutcome.Accepted).to)
    }

    @Test
    fun lanService_disableBumpsEpochAction() {
        val outcome = LanServiceLifecyclePolicy.step(
            from = "ACTIVE",
            event = "DISABLE",
        )
        assertTrue(outcome is TransitionOutcome.Accepted)
        val accepted = outcome as TransitionOutcome.Accepted
        assertEquals("DRAINING", accepted.to)
        assertTrue(LanServiceLifecyclePolicy.transitionBumpsEpoch(accepted.actions))
    }

    @Test
    fun pairingChallenge_approveThenConsume() {
        val approved = PairingChallengePolicy.step("PENDING", "APPROVE")
        assertTrue(approved is TransitionOutcome.Accepted)
        assertEquals("APPROVED", (approved as TransitionOutcome.Accepted).to)

        val consumed = PairingChallengePolicy.step("APPROVED", "CONSUME")
        assertTrue(consumed is TransitionOutcome.Accepted)
        assertEquals("CONSUMED", (consumed as TransitionOutcome.Accepted).to)
    }

    @Test
    fun pairingChallenge_rejectFromPending() {
        val rejected = PairingChallengePolicy.step("PENDING", "REJECT")
        assertTrue(rejected is TransitionOutcome.Accepted)
        assertEquals("REJECTED", (rejected as TransitionOutcome.Accepted).to)
    }
}
