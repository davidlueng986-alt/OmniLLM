package com.omnillm.runtime.policy

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyManagerTest {

    @Test
    fun snapshot_seededWithProductDefaults() {
        val pm = PolicyManager()
        val snap = pm.settingsSnapshot()
        assertEquals(0L, snap.resourceVersion)
        assertEquals(
            SettingValue.BoolValue(false),
            snap.values["server.loopbackEnabled"],
        )
        assertEquals(
            SettingValue.EnumValue("LOCAL_ONLY"),
            snap.values["privacy.telemetryMode"],
        )
    }

    @Test
    fun patchSettings_cas_and_bumpVersion() {
        val pm = PolicyManager()
        val patched = pm.patchSettings(
            baseVersion = 0L,
            changes = mapOf("server.loopbackEnabled" to SettingValue.BoolValue(true)),
            source = "administrator-policy",
        ) as OmniResult.Ok
        assertEquals(1L, patched.value.resourceVersion)
        assertEquals(
            SettingValue.BoolValue(true),
            patched.value.values["server.loopbackEnabled"],
        )

        val stale = pm.patchSettings(
            baseVersion = 0L,
            changes = mapOf("server.loopbackEnabled" to SettingValue.BoolValue(false)),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.STATE_CONFLICT, stale.error.code)
    }

    @Test
    fun hardConstraint_forceLanDisabled() {
        val pm = PolicyManager()
        pm.patchSettings(
            baseVersion = 0L,
            changes = mapOf("server.lanEnabled" to SettingValue.BoolValue(true)),
        )
        val constrained = pm.putHardConstraint(
            "server.lanEnabled",
            HardConstraintContribution(
                authority = "security-trust",
                forcedBool = false,
                policyVersion = "sec-1",
            ),
        ) as OmniResult.Ok
        assertEquals(
            SettingValue.BoolValue(false),
            constrained.value.values["server.lanEnabled"],
        )
        assertEquals(
            "forced-by-hard-constraint",
            constrained.value.effective["server.lanEnabled"]?.clampReason,
        )
    }

    @Test
    fun revokeAndFence_bumpsEpoch_and_sequencesHooks() {
        val events = mutableListOf<String>()
        val hooks = object : RevocationFenceHooks {
            override fun rejectNewUse(scope: RevocationScope, oldEpoch: Long, newEpoch: Long) {
                events += "reject:$oldEpoch->$newEpoch"
            }

            override fun cancelAndDrain(scope: RevocationScope, epoch: Long) {
                events += "drain:$epoch"
            }

            override fun rotateSecrets(scope: RevocationScope, epoch: Long) {
                events += "rotate:$epoch"
            }

            override fun audit(scope: RevocationScope, event: String, details: Map<String, String>) {
                events += "audit:$event"
            }

            override fun hasOldCapability(scope: RevocationScope, oldEpoch: Long): Boolean = false
        }
        val pm = PolicyManager(revocation = RevocationEpochManager(hooks = hooks))
        val scope = RevocationScope("tok-1", RevocationSubjectKind.TOKEN)
        assertEquals(0L, pm.currentRevocationEpoch(scope))

        val result = pm.revokeAndFence(
            scope = scope,
            actor = PrincipalId.parse("admin-local"),
            reason = "user-revoked",
        ) as OmniResult.Ok
        assertEquals(1L, result.value.epoch)
        assertEquals("ENFORCED", result.value.state)
        assertTrue(events.any { it.startsWith("reject:0->1") })
        assertTrue(events.any { it.startsWith("drain:1") })
        assertTrue(events.any { it.startsWith("rotate:1") })
        assertTrue(events.contains("audit:REVOKE"))
        assertTrue(events.contains("audit:EPOCH_COMMITTED"))
        assertTrue(events.contains("audit:ROTATION_COMPLETE"))

        val stale = pm.requireRevocationEpoch(scope, observedEpoch = 0L) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, stale.error.code)
        assertTrue(pm.requireRevocationEpoch(scope, observedEpoch = 1L) is OmniResult.Ok)
    }

    @Test
    fun unauthorisedRevoke_failClosed() {
        val pm = PolicyManager()
        val scope = RevocationScope("p-1", RevocationSubjectKind.PRINCIPAL)
        val denied = pm.revokeAndFence(
            scope = scope,
            actor = PrincipalId.parse("attacker"),
            reason = "nope",
            authorised = false,
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.FORBIDDEN, denied.error.code)
        assertEquals(0L, pm.currentRevocationEpoch(scope))
    }

    @Test
    fun resolve_requestExplicit_atPlanTime() {
        val pm = PolicyManager()
        pm.putSourceValue(
            "inference.maxOutputTokens",
            "product-default",
            SettingValue.IntValue(256),
        )
        val resolved = pm.resolve(
            "inference.maxOutputTokens",
            extraCandidates = listOf(
                SettingCandidate("request-explicit", SettingValue.IntValue(64)),
            ),
        ) as OmniResult.Ok
        assertEquals(64L, (resolved.value.effectiveValue as SettingValue.IntValue).value)
        assertEquals("request-explicit", resolved.value.selectedSource)
    }

    @Test
    fun putSource_unknownKey_failClosed() {
        val pm = PolicyManager()
        val err = pm.putSourceValue(
            "mystery.knob",
            "product-default",
            SettingValue.BoolValue(true),
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.INVALID_REQUEST, err.error.code)
    }

    @Test
    fun patch_resourceIncreasing_withoutReservation_rejected() {
        val pm = PolicyManager()
        val err = pm.patchSettings(
            baseVersion = 0L,
            changes = mapOf("runtime.prefillBatch" to SettingValue.IntValue(16)),
            source = "principal-profile",
        ) as OmniResult.Err
        assertEquals(OmniErrorCode.ADMISSION_REJECTED, err.error.code)
        assertFalse(
            pm.patchSettings(
                baseVersion = 0L,
                changes = mapOf("runtime.prefillBatch" to SettingValue.IntValue(16)),
                source = "principal-profile",
                resourceChangeReservedKeys = setOf("runtime.prefillBatch"),
            ) is OmniResult.Err,
        )
    }
}
