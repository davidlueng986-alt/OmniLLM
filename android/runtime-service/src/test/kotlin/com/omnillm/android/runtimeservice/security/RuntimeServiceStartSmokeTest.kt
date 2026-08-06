package com.omnillm.android.runtimeservice.security

import com.omnillm.android.RuntimeServiceModule
import com.omnillm.android.runtimeservice.controlplane.LifecycleStepResult
import com.omnillm.android.runtimeservice.controlplane.RuntimeLifecycleController
import com.omnillm.android.runtimeservice.process.ProcessNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime control-plane start smoke (ANDROID-BASELINE, ARCH-RUNTIME-LIFECYCLE).
 *
 * Instrumented device bind lives under app-ui androidTest when an emulator/device
 * is available; this suite is the always-on **fake** path (no Robolectric required).
 *
 * Quality scenarios: recovery path supports **UX-FIRST-SUCCESS** recovery action;
 * process topology underpins **Q-014** (exported vs admin surfaces).
 */
class RuntimeServiceStartSmokeTest {

    @Test
    fun bootstrapToReady_acceptsWork_smoke() {
        val ctl = RuntimeLifecycleController(idSource = { "smoke-boot-${System.nanoTime()}" })
        assertEquals("STOPPED", ctl.state)
        assertFalse(ctl.acceptsWork())

        val step = ctl.bootstrapToReady(foregroundLegal = true)
        assertTrue(step is LifecycleStepResult.Accepted)
        assertEquals("READY", ctl.state)
        assertTrue(ctl.acceptsWork())
        assertTrue(ctl.identity.runtimeEpoch >= 1L)
        assertTrue(ctl.identity.bootId.isNotBlank())
    }

    @Test
    fun illegalForegroundStart_rejected_smoke() {
        val ctl = RuntimeLifecycleController()
        val rejected = ctl.legalStart(foregroundLegal = false)
        assertTrue(rejected is LifecycleStepResult.Rejected)
        assertEquals("STOPPED", ctl.state)
        assertFalse(ctl.acceptsWork())
    }

    @Test
    fun drainStopsAcceptingWork_smoke() {
        val ctl = RuntimeLifecycleController()
        ctl.bootstrapToReady(foregroundLegal = true)
        assertTrue(ctl.stopRequested() is LifecycleStepResult.Accepted)
        assertEquals("DRAINING", ctl.state)
        assertFalse(ctl.acceptsWork())
        assertTrue(ctl.drainComplete() is LifecycleStepResult.Accepted)
        assertEquals("STOPPED", ctl.state)
    }

    @Test
    fun processTopologyConstants_matchBaseline() {
        assertEquals(":runtime", RuntimeServiceModule.RUNTIME_PROCESS_SUFFIX)
        assertTrue(ProcessNames.isRuntimeProcessName("com.omnillm:runtime"))
        assertFalse(ProcessNames.isRuntimeProcessName("com.omnillm"))
        assertFalse(ProcessNames.isRuntimeProcessName("com.omnillm:engine_worker"))

        // Exported Runtime Binding vs non-exported Admin are distinct actions (Q-014).
        assertNotEquals(
            RuntimeServiceModule.Actions.BIND_RUNTIME,
            RuntimeServiceModule.Actions.BIND_ADMIN,
        )
        assertEquals("com.omnillm.action.BIND_RUNTIME", RuntimeServiceModule.Actions.BIND_RUNTIME)
        assertEquals("com.omnillm.action.BIND_ADMIN", RuntimeServiceModule.Actions.BIND_ADMIN)

        // Companion is a different package (ADR-007 / Q-008 surface).
        assertNotEquals("com.omnillm", RuntimeServiceModule.Companion.PACKAGE_NAME)
    }

    @Test
    fun recoveryPartial_degradedStillAcceptsWork_relRecovery() {
        // REL-RECOVERY: partial recovery lands DEGRADED and may still accept work.
        val ctl = RuntimeLifecycleController()
        assertTrue(ctl.legalStart(foregroundLegal = true) is LifecycleStepResult.Accepted)
        assertTrue(ctl.beginRecovery() is LifecycleStepResult.Accepted)
        assertTrue(ctl.recoveryPartial() is LifecycleStepResult.Accepted)
        assertEquals("DEGRADED", ctl.state)
        assertTrue(ctl.acceptsWork())
    }
}
