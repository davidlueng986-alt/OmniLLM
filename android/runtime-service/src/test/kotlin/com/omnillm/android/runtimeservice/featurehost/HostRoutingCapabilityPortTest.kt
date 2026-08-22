package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * CODE-05: HostRoutingCapabilityPort must never invent SUPPORTED.
 */
class HostRoutingCapabilityPortTest {

    @Test
    fun detached_catalogCellsAreUnknown() {
        val port = HostRoutingCapabilityPort(orchestratorAttached = false)
        assertEquals(CapabilityState.UNKNOWN, port.state(CapabilityId.MULTI_MODEL_ROUTING))
        assertEquals(CapabilityState.UNKNOWN, port.state(CapabilityId.FALLBACK_POLICY))
        assertEquals(CapabilityState.UNKNOWN, port.state(CapabilityId.TEXT_GENERATION))
    }

    @Test
    fun attached_catalogCellsAreConditionalNeverSupported() {
        val port = HostRoutingCapabilityPort(orchestratorAttached = true)
        assertEquals(CapabilityState.CONDITIONAL, port.state(CapabilityId.MULTI_MODEL_ROUTING))
        assertEquals(CapabilityState.CONDITIONAL, port.state(CapabilityId.REQUEST_LIFECYCLE))
        assertNotEquals(CapabilityState.SUPPORTED, port.state(CapabilityId.MULTI_MODEL_ROUTING))
        assertEquals(CapabilityState.UNKNOWN, port.state(CapabilityId.TEXT_GENERATION))
    }
}
