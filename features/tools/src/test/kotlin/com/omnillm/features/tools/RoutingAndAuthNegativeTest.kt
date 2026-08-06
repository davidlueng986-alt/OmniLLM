package com.omnillm.features.tools

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.FallbackPolicy
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.tools.domain.StructuredCallerPolicy
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.StructuredOutputSpec
import com.omnillm.features.tools.policy.RoutingRevisionPolicy
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing: never silent cross-revision fallback.
 * Auth: fail closed without scope (LAN-style).
 */
class RoutingAndAuthNegativeTest {

    private val revA = "a".repeat(64)
    private val revB = "b".repeat(64)

    @Test
    fun routing_none_rejectsCrossRevision() {
        val r = RoutingRevisionPolicy.resolveActualRevision(
            requestedRevisionId = revA,
            selectedRevisionId = revB,
            fallbackPolicy = FallbackPolicy.NONE,
            allowedRevisionIds = emptySet(),
        )
        assertTrue(r is RoutingRevisionPolicy.Result.Denied)
        val err = (r as RoutingRevisionPolicy.Result.Denied).error
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, err.code)
        assertEquals("false", err.details["silentFallback"])
    }

    @Test
    fun routing_sameRevisionOnly_rejectsCrossRevision() {
        val r = RoutingRevisionPolicy.resolveActualRevision(
            requestedRevisionId = revA,
            selectedRevisionId = revB,
            fallbackPolicy = FallbackPolicy.SAME_REVISION_ONLY,
            allowedRevisionIds = setOf(revB),
        )
        assertTrue(r is RoutingRevisionPolicy.Result.Denied)
    }

    @Test
    fun routing_allowList_requiresExplicitMembership() {
        val denied = RoutingRevisionPolicy.resolveActualRevision(
            requestedRevisionId = revA,
            selectedRevisionId = revB,
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            allowedRevisionIds = setOf("c".repeat(64)),
        )
        assertTrue(denied is RoutingRevisionPolicy.Result.Denied)

        val ok = RoutingRevisionPolicy.resolveActualRevision(
            requestedRevisionId = revA,
            selectedRevisionId = revB,
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            allowedRevisionIds = setOf(revB),
        ) as RoutingRevisionPolicy.Result.Ok
        assertTrue(ok.outcome.fallbackApplied)
        assertEquals(revB, ok.outcome.actualModelRevisionId)
        assertEquals("allowlist-cross-revision", ok.outcome.reason)
    }

    @Test
    fun service_rejectsSilentCrossRevisionFromEngine() = runBlocking {
        val inference = RecordingInferencePort(
            structuredMode = StructuredMode.NATIVE_CONSTRAINED,
            actualRevisionOverride = revB,
        )
        val (svc, _, _) = buildService(
            modelId = revA,
            mode = StructuredMode.NATIVE_CONSTRAINED,
            inference = inference,
        )
        val result = svc.startStructured(
            LocalUiPrincipal.ID,
            structuredSpec(revA),
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(
            OmniErrorCode.CAPABILITY_UNSUPPORTED,
            (result as OmniResult.Err).error.code,
        )
        assertEquals(1, inference.structuredStartCount)
    }

    @Test
    fun service_allowList_acceptsExplicitCrossRevision() = runBlocking {
        val inference = RecordingInferencePort(
            structuredMode = StructuredMode.NATIVE_CONSTRAINED,
            actualRevisionOverride = revB,
        )
        val (svc, _, _) = buildService(
            modelId = revA,
            mode = StructuredMode.NATIVE_CONSTRAINED,
            inference = inference,
        )
        val spec = StructuredOutputSpec(
            identity = identity(),
            modelRevisionId = revA,
            schemaName = "demo",
            schema = simpleSchema(),
            callerPolicy = StructuredCallerPolicy.NATIVE_ONLY,
            fallbackPolicy = FallbackPolicy.ALLOW_LIST,
            allowedRevisionIds = setOf(revB),
        )
        val result = svc.startStructured(LocalUiPrincipal.ID, spec) as OmniResult.Ok
        assertTrue(result.value.fallbackApplied)
        assertEquals(revB, result.value.actualModelRevisionId)
        assertEquals(FallbackPolicy.ALLOW_LIST, result.value.fallbackPolicy)
    }

    @Test
    fun lanPrincipal_withoutScope_failClosed() = runBlocking {
        val scopes = ConfigurableScopePort(acceptedPrincipal = "HTTP_LAN").apply { denyAll() }
        val (svc, inference, _) = buildService(
            modelId = revA,
            mode = StructuredMode.NATIVE_CONSTRAINED,
            scopes = scopes,
        )
        val lan = PrincipalId.parse("HTTP_LAN")
        val r = svc.startStructured(lan, structuredSpec(revA))
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.FORBIDDEN, (r as OmniResult.Err).error.code)
        assertEquals(AccessScopeName.INFERENCE_CREATE, r.error.details["scope"])
        assertEquals(0, inference.structuredStartCount)
    }

    @Test
    fun capabilityUnknown_failClosed_noEngineStart() = runBlocking {
        val caps = supportedCaps(revA)
        caps.set(revA, CapabilityId.STRUCTURED_OUTPUT, CapabilityState.UNKNOWN)
        val (svc, inference, _) = buildService(
            modelId = revA,
            mode = StructuredMode.NATIVE_CONSTRAINED,
            caps = caps,
        )
        val r = svc.startStructured(LocalUiPrincipal.ID, structuredSpec(revA))
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNKNOWN, (r as OmniResult.Err).error.code)
        assertEquals(0, inference.structuredStartCount)
    }

    @Test
    fun schemaBomb_neverStartsInference() = runBlocking {
        val (svc, inference, _) = buildService(
            modelId = revA,
            mode = StructuredMode.NATIVE_CONSTRAINED,
        )
        var deep: Any? = mapOf("type" to "string")
        repeat(40) {
            deep = mapOf("type" to "object", "properties" to mapOf("n" to deep))
        }
        @Suppress("UNCHECKED_CAST")
        val bomb = deep as Map<String, Any?>
        val r = svc.startStructured(
            LocalUiPrincipal.ID,
            structuredSpec(revA, schema = bomb),
        )
        assertTrue(r is OmniResult.Err)
        assertEquals(OmniErrorCode.INVALID_REQUEST, (r as OmniResult.Err).error.code)
        assertEquals(0, inference.structuredStartCount)
    }

    @Test
    fun negotiate_unsupported_notOperable() = runBlocking {
        val caps = supportedCaps(revA)
        caps.set(revA, CapabilityId.STRUCTURED_OUTPUT, CapabilityState.UNSUPPORTED)
        caps.setMode(revA, StructuredMode.UNSUPPORTED)
        val (svc, _, _) = buildService(modelId = revA, caps = caps)
        val n = svc.negotiate(LocalUiPrincipal.ID, revA) as OmniResult.Ok
        assertFalse(n.value.operable)
        assertTrue(n.value.blockingReasonKey != null)
    }
}

/** Wire scope id constant for assertions. */
private object AccessScopeName {
    const val INFERENCE_CREATE = "inference.create"
}
