package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden configuration vectors from
 * `specs/golden-vectors/canonical-encoding.yaml` (`configuration.vectors`,
 * CFG-001..CFG-003) — DATA-CONFIG resolution contract.
 *
 * - CFG-001: request candidate wins, then clamps under the resource hard cap
 *   (8192/4096/2048 → 6144, never above `resource-hard-cap`).
 * - CFG-002: an exact request-explicit enum excluded by the security-trust
 *   hard constraint fails closed with TRUST_PLACEMENT_REQUIRED — the enum is
 *   never silently changed (clampAllowed=false).
 * - CFG-003: the highest-precedence allowed value source wins
 *   (principal-profile 0.7 beats product-default 1.0).
 */
class GoldenConfigProfileVectorsTest {

    private data class Vector(
        val id: String,
        val expected: String,
        val action: () -> OmniResult<EffectiveSetting>,
        val assert: (OmniResult<EffectiveSetting>) -> Unit,
    )

    private fun vectors(): List<Vector> = listOf(
        Vector(
            id = "CFG-001",
            expected = "effective 6144 with clampReason clamped-to-max",
            action = {
                SettingsMerger.merge(
                    key = "inference.contextTokens",
                    candidates = listOf(
                        SettingCandidate("request-explicit", SettingValue.IntValue(8192)),
                        SettingCandidate("model-recommendation", SettingValue.IntValue(4096)),
                        SettingCandidate("product-default", SettingValue.IntValue(2048)),
                    ),
                    hardConstraints = listOf(
                        HardConstraintContribution(
                            authority = "platform-safety",
                            max = 16384.0,
                            policyVersion = "ps-v1",
                        ),
                        HardConstraintContribution(
                            authority = "resource-hard-cap",
                            max = 6144.0,
                            policyVersion = "rh-v1",
                        ),
                    ),
                )
            },
            assert = { r ->
                val ok = r as OmniResult.Ok
                assertEquals("request-explicit", ok.value.selectedSource)
                assertEquals(6144L, (ok.value.effectiveValue as SettingValue.IntValue).value)
                assertEquals("clamped-to-max", ok.value.clampReason)
                assertEquals(setOf("platform-safety", "resource-hard-cap"), ok.value.constraintVersions.keys)
            },
        ),
        Vector(
            id = "CFG-002",
            expected = "TRUST_PLACEMENT_REQUIRED (security-trust excludes ALLOW_LIST)",
            action = {
                SettingsMerger.merge(
                    key = "runtime.fallbackPolicy",
                    candidates = listOf(
                        SettingCandidate("request-explicit", SettingValue.EnumValue("ALLOW_LIST")),
                        SettingCandidate("product-default", SettingValue.EnumValue("NONE")),
                    ),
                    hardConstraints = listOf(
                        HardConstraintContribution(
                            authority = "security-trust",
                            allowedEnumValues = setOf("NONE", "SAME_REVISION_ONLY"),
                            policyVersion = "st-v1",
                        ),
                    ),
                )
            },
            assert = { r ->
                val err = r as OmniResult.Err
                assertEquals(OmniErrorCode.TRUST_PLACEMENT_REQUIRED, err.error.code)
            },
        ),
        Vector(
            id = "CFG-003",
            expected = "principal-profile 0.7 wins over product-default 1.0",
            action = {
                SettingsMerger.merge(
                    key = "inference.temperature",
                    candidates = listOf(
                        SettingCandidate("principal-profile", SettingValue.NumberValue(0.7)),
                        SettingCandidate("product-default", SettingValue.NumberValue(1.0)),
                    ),
                )
            },
            assert = { r ->
                val ok = r as OmniResult.Ok
                assertEquals("principal-profile", ok.value.selectedSource)
                assertEquals(0.7, (ok.value.effectiveValue as SettingValue.NumberValue).value, 0.0)
                assertEquals("no constraint present → no clamp", null, ok.value.clampReason)
            },
        ),
    )

    @Test
    fun goldenConfigurationVectors_allMatch() {
        val failures = mutableListOf<String>()
        for (vector in vectors()) {
            try {
                vector.assert(vector.action())
            } catch (e: AssertionError) {
                failures += "${vector.id}: ${vector.expected} — ${e.message}"
            }
        }
        assertTrue(
            "golden configuration vectors must pass:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun vectorIds_areUnique() {
        val ids = vectors().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
