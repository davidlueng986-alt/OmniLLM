package com.omnillm.features.tools

import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.features.tools.domain.StructuredCallerPolicy
import com.omnillm.features.tools.domain.StructuredMode
import com.omnillm.features.tools.domain.ValidationStatusCode
import com.omnillm.features.tools.policy.StructuredModePolicy
import com.omnillm.interfaces.admin.LocalUiPrincipal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FEAT-TOOLS §7.2 — explicit fallback / no silent demotion.
 */
class StructuredModePolicyTest {

    private val modelId = "d".repeat(64)

    @Test
    fun resolve_nativeOffered_selectsNative() {
        val r = StructuredModePolicy.resolve(
            cell = StructuredModePolicy.EngineStructuredCell(
                offeredMode = StructuredMode.NATIVE_CONSTRAINED,
                capabilityState = CapabilityState.SUPPORTED,
            ),
            caller = StructuredCallerPolicy.NATIVE_ONLY,
        )
        assertTrue(r is StructuredModePolicy.ResolveResult.Allow)
        val allow = r as StructuredModePolicy.ResolveResult.Allow
        assertEquals(StructuredMode.NATIVE_CONSTRAINED, allow.decision.actualMode)
        assertTrue(allow.decision.disclosed)
    }

    @Test
    fun resolve_postValidateOffered_nativeRequired_deniesWithoutSilentFallback() {
        val r = StructuredModePolicy.resolve(
            cell = StructuredModePolicy.EngineStructuredCell(
                offeredMode = StructuredMode.POST_VALIDATE,
                capabilityState = CapabilityState.SUPPORTED,
            ),
            caller = StructuredCallerPolicy.NATIVE_ONLY,
        )
        assertTrue(r is StructuredModePolicy.ResolveResult.Deny)
        val deny = r as StructuredModePolicy.ResolveResult.Deny
        assertEquals(ValidationStatusCode.MODE_POLICY_DENIED, deny.denial.validationCode)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, deny.denial.error.code)
        assertEquals("false", deny.denial.error.details["silentFallback"])
    }

    @Test
    fun resolve_postValidateOffered_allowed_disclosesModeAndAttempts() {
        val r = StructuredModePolicy.resolve(
            cell = StructuredModePolicy.EngineStructuredCell(
                offeredMode = StructuredMode.POST_VALIDATE,
                capabilityState = CapabilityState.SUPPORTED,
            ),
            caller = StructuredCallerPolicy.POST_VALIDATE_ALLOWED,
        )
        assertTrue(r is StructuredModePolicy.ResolveResult.Allow)
        val allow = r as StructuredModePolicy.ResolveResult.Allow
        assertEquals(StructuredMode.POST_VALIDATE, allow.decision.actualMode)
        assertEquals(3, allow.decision.maxAttempts)
        assertTrue(allow.decision.disclosed)
    }

    @Test
    fun resolve_repairOffered_allowed_disclosesRepairCap() {
        val r = StructuredModePolicy.resolve(
            cell = StructuredModePolicy.EngineStructuredCell(
                offeredMode = StructuredMode.REPAIR_RETRY,
                capabilityState = CapabilityState.SUPPORTED,
            ),
            caller = StructuredCallerPolicy.REPAIR_ALLOWED,
        )
        val allow = r as StructuredModePolicy.ResolveResult.Allow
        assertEquals(StructuredMode.REPAIR_RETRY, allow.decision.actualMode)
        assertEquals(2, allow.decision.maxRepairAttempts)
    }

    @Test
    fun resolve_unknownCapability_failClosed() {
        val r = StructuredModePolicy.resolve(
            cell = StructuredModePolicy.EngineStructuredCell(
                offeredMode = StructuredMode.NATIVE_CONSTRAINED,
                capabilityState = CapabilityState.UNKNOWN,
            ),
            caller = StructuredCallerPolicy.NATIVE_ONLY,
        )
        assertTrue(r is StructuredModePolicy.ResolveResult.Deny)
        assertEquals(
            OmniErrorCode.CAPABILITY_UNKNOWN,
            (r as StructuredModePolicy.ResolveResult.Deny).denial.error.code,
        )
    }

    @Test
    fun assertDisclosedMode_nullActual_errors() {
        val err = StructuredModePolicy.assertDisclosedMode(
            plannedMode = StructuredMode.NATIVE_CONSTRAINED,
            actualMode = null,
            freeTextWithoutSchema = false,
        )
        assertNotNull(err)
        assertEquals(OmniErrorCode.INTERNAL, err!!.code)
    }

    @Test
    fun assertDisclosedMode_silentFreeText_errors() {
        val err = StructuredModePolicy.assertDisclosedMode(
            plannedMode = StructuredMode.NATIVE_CONSTRAINED,
            actualMode = StructuredMode.NATIVE_CONSTRAINED,
            freeTextWithoutSchema = true,
        )
        assertNotNull(err)
        assertEquals("true", err!!.details["silentFallback"])
    }

    @Test
    fun service_startStructured_deniesWhenNativeRequiredButEnginePostOnly() = runBlocking {
        val (svc, inference, _) = buildService(
            modelId = modelId,
            mode = StructuredMode.POST_VALIDATE,
        )
        val result = svc.startStructured(
            LocalUiPrincipal.ID,
            structuredSpec(modelId, StructuredCallerPolicy.NATIVE_ONLY),
        )
        assertTrue(result is OmniResult.Err)
        assertEquals(OmniErrorCode.CAPABILITY_UNSUPPORTED, (result as OmniResult.Err).error.code)
        assertEquals(0, inference.structuredStartCount)
    }

    @Test
    fun service_startStructured_disclosesPostValidateWhenAllowed() = runBlocking {
        val (svc, inference, _) = buildService(
            modelId = modelId,
            mode = StructuredMode.POST_VALIDATE,
        )
        val result = svc.startStructured(
            LocalUiPrincipal.ID,
            structuredSpec(modelId, StructuredCallerPolicy.POST_VALIDATE_ALLOWED),
        )
        assertTrue(result is OmniResult.Ok)
        val view = (result as OmniResult.Ok).value
        assertEquals(StructuredMode.POST_VALIDATE, view.actualMode)
        assertTrue(view.traceFields.containsKey("actualMode"))
        assertEquals(1, inference.structuredStartCount)
        assertFalse(view.fallbackApplied)
    }
}
