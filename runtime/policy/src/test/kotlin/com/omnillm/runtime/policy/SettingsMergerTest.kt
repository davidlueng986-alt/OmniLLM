package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMergerTest {

    @Test
    fun valueSource_requestExplicit_beats_productDefault() {
        val result = SettingsMerger.merge(
            key = "inference.temperature",
            candidates = listOf(
                SettingCandidate("product-default", SettingValue.NumberValue(0.7)),
                SettingCandidate("request-explicit", SettingValue.NumberValue(0.2)),
            ),
        ) as OmniResult.Ok
        assertEquals(0.2, (result.value.effectiveValue as SettingValue.NumberValue).value, 0.0)
        assertEquals("request-explicit", result.value.selectedSource)
    }

    @Test
    fun hardConstraint_cannotBeRelaxed_byRequestExplicit() {
        // request wants 8192 context; resource-hard-cap max 2048; clampAllowed=true
        val result = SettingsMerger.merge(
            key = "inference.contextTokens",
            candidates = listOf(
                SettingCandidate("request-explicit", SettingValue.IntValue(8192)),
            ),
            hardConstraints = listOf(
                HardConstraintContribution(
                    authority = "resource-hard-cap",
                    max = 2048.0,
                    policyVersion = "rv-1",
                ),
            ),
        ) as OmniResult.Ok
        assertEquals(2048L, (result.value.effectiveValue as SettingValue.IntValue).value)
        assertEquals("clamped-to-max", result.value.clampReason)
        assertEquals("resource-hard-cap", result.value.constraintVersions.keys.single())
    }

    @Test
    fun hardConstraint_rejects_whenClampNotAllowed() {
        val result = SettingsMerger.merge(
            key = "inference.temperature",
            candidates = listOf(
                SettingCandidate("request-explicit", SettingValue.NumberValue(1.5)),
            ),
            hardConstraints = listOf(
                HardConstraintContribution(
                    authority = "platform-safety",
                    max = 1.0,
                    policyVersion = "ps-1",
                ),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, result.error.code)
    }

    @Test
    fun disallowedSource_rejected() {
        val result = SettingsMerger.merge(
            key = "server.loopbackEnabled",
            candidates = listOf(
                SettingCandidate("request-explicit", SettingValue.BoolValue(true)),
            ),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, result.error.code)
    }

    @Test
    fun resourceIncreasing_requiresReservation() {
        val result = SettingsMerger.merge(
            key = "runtime.prefillBatch",
            candidates = listOf(
                SettingCandidate("product-default", SettingValue.IntValue(8)),
            ),
            resourceChangeReserved = false,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, result.error.code)

        val ok = SettingsMerger.merge(
            key = "runtime.prefillBatch",
            candidates = listOf(
                SettingCandidate("product-default", SettingValue.IntValue(8)),
            ),
            resourceChangeReserved = true,
        ) as OmniResult.Ok
        assertEquals(8L, (ok.value.effectiveValue as SettingValue.IntValue).value)
    }

    @Test
    fun deterministic_sameInputs_sameEffective() {
        val candidates = listOf(
            SettingCandidate("device-policy", SettingValue.IntValue(4)),
            SettingCandidate("principal-profile", SettingValue.IntValue(6)),
            SettingCandidate("product-default", SettingValue.IntValue(2)),
        )
        val constraints = listOf(
            HardConstraintContribution(
                authority = "resource-hard-cap",
                max = 8.0,
                policyVersion = "r-1",
            ),
            HardConstraintContribution(
                authority = "platform-safety",
                min = 1.0,
                policyVersion = "p-1",
            ),
        )
        val a = SettingsMerger.merge("runtime.cpuThreads", candidates, constraints) as OmniResult.Ok
        val b = SettingsMerger.merge("runtime.cpuThreads", candidates, constraints) as OmniResult.Ok
        assertEquals(a.value, b.value)
        assertEquals("principal-profile", a.value.selectedSource)
        assertEquals(6L, (a.value.effectiveValue as SettingValue.IntValue).value)
    }

    @Test
    fun unknownKey_failClosed() {
        val result = SettingsMerger.merge(
            key = "not.a.real.key",
            candidates = listOf(SettingCandidate("product-default", SettingValue.BoolValue(true))),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.INVALID_REQUEST, result.error.code)
    }

    @Test
    fun lowerAuthority_cannotWiden_higherMax() {
        // platform-safety max=4, resource tries max=16 — intersection max remains 4
        val result = SettingsMerger.merge(
            key = "inference.contextTokens",
            candidates = listOf(
                SettingCandidate("request-explicit", SettingValue.IntValue(100)),
            ),
            hardConstraints = listOf(
                HardConstraintContribution(
                    authority = "platform-safety",
                    max = 4.0,
                    policyVersion = "ps",
                ),
                HardConstraintContribution(
                    authority = "resource-hard-cap",
                    max = 16.0,
                    policyVersion = "rh",
                ),
            ),
        ) as OmniResult.Ok
        assertEquals(4L, (result.value.effectiveValue as SettingValue.IntValue).value)
        assertNotNull(result.value.clampReason)
    }

    @Test
    fun administratorPolicy_beats_productDefault_forLan() {
        val result = SettingsMerger.merge(
            key = "server.lanEnabled",
            candidates = listOf(
                SettingCandidate("product-default", SettingValue.BoolValue(false)),
                SettingCandidate("administrator-policy", SettingValue.BoolValue(true)),
            ),
        ) as OmniResult.Ok
        assertEquals(true, (result.value.effectiveValue as SettingValue.BoolValue).value)
        assertEquals("administrator-policy", result.value.selectedSource)
        assertNull(result.value.clampReason)
    }
}
