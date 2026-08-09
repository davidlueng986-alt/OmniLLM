package com.omnillm.runtime.policy.acl
import com.omnillm.core.ports.security.RevocationScope
import com.omnillm.core.ports.security.RevocationSubjectKind
import com.omnillm.core.ports.security.TransportConstraint

import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.PrincipalKind
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.policy.security.TokenService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessControlEnforcerTest {

    private val stack = PolicyModule.createSecurityStack()
    private val acl = stack.accessControl
    private val tokens = stack.tokenService

    @Test
    fun localUi_allowsAnyCatalogScope() {
        val principal = acl.localUiPrincipal()
        val ok = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "patchSettings",
                requiredScope = AccessScope.settings_write,
                observedRevocationEpoch = principal.revocationEpoch,
            ),
        )
        assertTrue(ok is OmniResult.Ok)

        val report = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "reviewSubmitContentReport",
                requiredScope = AccessScope.content_reports_review_submit,
                observedRevocationEpoch = principal.revocationEpoch,
            ),
        )
        assertTrue(report is OmniResult.Ok)
    }

    @Test
    fun reviewSubmit_deniedForExportedProfiles() {
        val principal = AccessControlEnforcer.PrincipalContext(
            principalId = PrincipalId.parse("app-1"),
            kind = PrincipalKind.ANDROID_APP,
            profile = AccessProfile.APP_CLIENT,
            grantedScopes = setOf("content-reports.review-submit", "models.read"),
            revocationEpoch = 0L,
            transport = AccessControlEnforcer.AccessTransport.EXPORTED_RUNTIME_AIDL,
            isLocalUi = false,
        )
        val denied = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "reviewSubmit",
                requiredScope = AccessScope.content_reports_review_submit,
                observedRevocationEpoch = 0L,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
    }

    @Test
    fun missingScope_failClosed() {
        val principal = AccessControlEnforcer.PrincipalContext(
            principalId = PrincipalId.parse("dev-1"),
            kind = PrincipalKind.HTTP_LOOPBACK,
            profile = AccessProfile.DEVELOPER_CLIENT,
            grantedScopes = setOf("models.read"),
            revocationEpoch = 0L,
            transport = AccessControlEnforcer.AccessTransport.LOOPBACK_HTTP,
        )
        val denied = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "createChatCompletion",
                requiredScope = AccessScope.inference_create,
                observedRevocationEpoch = 0L,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
        assertEquals("inference.create", denied.error.details["requiredScope"])
    }

    @Test
    fun loopbackToken_rejectedOnLanTransport() {
        val principal = AccessControlEnforcer.PrincipalContext(
            principalId = PrincipalId.parse("admin-http"),
            kind = PrincipalKind.HTTP_LOCAL_ADMIN,
            profile = AccessProfile.LOCAL_ADMIN_HTTP,
            grantedScopes = setOf("models.read"),
            revocationEpoch = 0L,
            transport = AccessControlEnforcer.AccessTransport.LAN_TLS_HTTP,
            loopbackOnlyToken = true,
        )
        val denied = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "listModels",
                requiredScope = AccessScope.models_read,
                observedRevocationEpoch = 0L,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
    }

    @Test
    fun staleRevocationEpoch_failClosed() {
        val principalId = PrincipalId.parse("p-epoch")
        val scope = RevocationScope(principalId.value, RevocationSubjectKind.PRINCIPAL)
        stack.revocation.ensureActive(scope)
        stack.revocation.revokeAndFence(
            scope = scope,
            actorPrincipalId = PrincipalId.parse("admin"),
            reason = "test",
        )
        val principal = AccessControlEnforcer.PrincipalContext(
            principalId = principalId,
            kind = PrincipalKind.HTTP_LOOPBACK,
            profile = AccessProfile.DEVELOPER_CLIENT,
            grantedScopes = setOf("models.read"),
            revocationEpoch = 0L, // stale
            transport = AccessControlEnforcer.AccessTransport.LOOPBACK_HTTP,
        )
        val denied = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "listModels",
                requiredScope = AccessScope.models_read,
                observedRevocationEpoch = 0L,
                epochSubject = scope,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
        assertTrue(denied.error.message!!.contains("epoch"))
    }

    @Test
    fun currentEpoch_allowsOperation() {
        val principalId = PrincipalId.parse("p-ok")
        val scope = RevocationScope(principalId.value, RevocationSubjectKind.PRINCIPAL)
        stack.revocation.ensureActive(scope)
        val epoch = stack.revocation.currentEpoch(scope)
        val principal = AccessControlEnforcer.PrincipalContext(
            principalId = principalId,
            kind = PrincipalKind.HTTP_LOOPBACK,
            profile = AccessProfile.DEVELOPER_CLIENT,
            grantedScopes = setOf("models.read"),
            revocationEpoch = epoch,
            transport = AccessControlEnforcer.AccessTransport.LOOPBACK_HTTP,
        )
        val ok = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "listModels",
                requiredScope = AccessScope.models_read,
                observedRevocationEpoch = epoch,
                epochSubject = scope,
            ),
        )
        assertTrue(ok is OmniResult.Ok)
    }

    @Test
    fun ownScope_ownerMismatch_denied() {
        val principal = AccessControlEnforcer.PrincipalContext(
            principalId = PrincipalId.parse("owner-a"),
            kind = PrincipalKind.HTTP_LOOPBACK,
            profile = AccessProfile.DEVELOPER_CLIENT,
            grantedScopes = setOf("inference.read-own"),
            revocationEpoch = 0L,
            transport = AccessControlEnforcer.AccessTransport.LOOPBACK_HTTP,
        )
        val denied = acl.authorize(
            principal,
            AccessControlEnforcer.OperationRequest(
                operationId = "getRequest",
                requiredScope = AccessScope.inference_read_own,
                resourceOwnerPrincipalId = "owner-b",
                observedRevocationEpoch = 0L,
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
    }

    @Test
    fun fromAuthenticatedToken_roundTripAuthorize() {
        val issued = tokens.issue(
            TokenService.IssueRequest(
                registrationId = "reg",
                principalId = PrincipalId.parse("http-dev"),
                scopes = setOf("models.read", "inference.create"),
                transportConstraint = TransportConstraint.LOOPBACK_ONLY,
                ttlSeconds = 3600L,
                profile = AccessProfile.DEVELOPER_CLIENT,
            ),
        ) as OmniResult.Ok
        val auth = tokens.authenticate(
            issued.value.plaintextOnce,
            TransportConstraint.LOOPBACK_ONLY,
        ) as OmniResult.Ok
        val principal = acl.fromAuthenticatedToken(
            token = auth.value,
            transport = AccessControlEnforcer.AccessTransport.LOOPBACK_HTTP,
            kind = PrincipalKind.HTTP_LOOPBACK,
            profile = AccessProfile.DEVELOPER_CLIENT,
        )
        assertTrue(
            acl.authorize(
                principal,
                AccessControlEnforcer.OperationRequest(
                    operationId = "listModels",
                    requiredScope = AccessScope.models_read,
                    observedRevocationEpoch = principal.revocationEpoch,
                ),
            ) is OmniResult.Ok,
        )
    }

    @Test
    fun requireKnownScope_failClosed() {
        val err = acl.requireKnownScope("infer") as OmniResult.Err
        assertEquals(OmniErrorCode.INVALID_REQUEST, err.error.code)
    }

    @Test
    fun fenceApi_requireRevocationEpoch() {
        val subject = RevocationScope("fence-subj", RevocationSubjectKind.TOKEN)
        assertTrue(acl.requireRevocationEpoch(subject, 0L) is OmniResult.Ok)
        stack.revocation.revokeAndFence(
            scope = subject,
            actorPrincipalId = PrincipalId.parse("admin"),
            reason = "fence",
        )
        val stale = acl.requireRevocationEpoch(subject, 0L) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, stale.error.code)
        assertEquals(1L, acl.currentEpoch(subject))
        assertTrue(acl.requireRevocationEpoch(subject, 1L) is OmniResult.Ok)
    }
}
