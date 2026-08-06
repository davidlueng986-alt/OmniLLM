package com.omnillm.android.runtimeservice.controlplane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog RUNTIME FSM host tests (ARCH-RUNTIME-LIFECYCLE).
 * No invented states; edges must match specs/state-machines.yaml#RUNTIME.
 */
class RuntimeLifecycleControllerTest {

    @Test
    fun bootstrapToReady_advancesEpochAndAcceptsWork() {
        val ctl = RuntimeLifecycleController(idSource = { "id-${System.nanoTime()}" })
        assertEquals("STOPPED", ctl.state)
        assertEquals(0L, ctl.identity.runtimeEpoch)

        val result = ctl.bootstrapToReady(foregroundLegal = true)
        assertTrue(result is LifecycleStepResult.Accepted)
        assertEquals("READY", ctl.state)
        assertTrue(ctl.identity.runtimeEpoch >= 1L)
        assertTrue(ctl.acceptsWork())
    }

    @Test
    fun legalStart_withoutForegroundGuard_isRejected() {
        val ctl = RuntimeLifecycleController()
        val result = ctl.legalStart(foregroundLegal = false)
        assertTrue(result is LifecycleStepResult.Rejected)
        assertEquals("STOPPED", ctl.state)
    }

    @Test
    fun foregroundNotAllowed_fromStarting_toWaiting() {
        val ctl = RuntimeLifecycleController()
        assertTrue(ctl.legalStart(foregroundLegal = true) is LifecycleStepResult.Accepted)
        assertEquals("STARTING", ctl.state)

        assertTrue(ctl.foregroundNotAllowed() is LifecycleStepResult.Accepted)
        assertEquals("WAITING_FOR_USER_FOREGROUND", ctl.state)
        assertFalse(ctl.acceptsWork())

        assertTrue(ctl.userStart(foregroundLegal = true) is LifecycleStepResult.Accepted)
        assertEquals("STARTING", ctl.state)
    }

    @Test
    fun drain_readyToStopped() {
        val ctl = RuntimeLifecycleController()
        ctl.bootstrapToReady(foregroundLegal = true)
        assertTrue(ctl.stopRequested() is LifecycleStepResult.Accepted)
        assertEquals("DRAINING", ctl.state)
        assertTrue(ctl.drainComplete() is LifecycleStepResult.Accepted)
        assertEquals("STOPPED", ctl.state)
        assertFalse(ctl.acceptsWork())
    }

    @Test
    fun recoveryPartial_landsDegraded_stillAcceptsWork() {
        val ctl = RuntimeLifecycleController()
        ctl.legalStart(foregroundLegal = true)
        ctl.beginRecovery()
        assertTrue(ctl.recoveryPartial() is LifecycleStepResult.Accepted)
        assertEquals("DEGRADED", ctl.state)
        assertTrue(ctl.acceptsWork())
    }

    @Test
    fun recoveryFailed_toFaulted_thenReset() {
        val ctl = RuntimeLifecycleController()
        ctl.legalStart(foregroundLegal = true)
        ctl.beginRecovery()
        assertTrue(ctl.recoveryFailed() is LifecycleStepResult.Accepted)
        assertEquals("FAULTED", ctl.state)
        assertFalse(ctl.acceptsWork())
        assertTrue(ctl.resetOrStop() is LifecycleStepResult.Accepted)
        assertEquals("STOPPED", ctl.state)
    }
}
