package com.omnillm.core.canonical

import com.omnillm.core.canonical.generated.AccessControlCatalog
import com.omnillm.core.canonical.generated.AccessProfile
import com.omnillm.core.canonical.generated.AccessScope
import com.omnillm.core.canonical.generated.CapabilityCatalog
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.errors.generated.OmniError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ResourceVectorAndCatalogTest {

    @Test
    fun resourceVectorDimensionsMatchCatalog() {
        assertEquals(10, ResourceVector.DIMENSION_NAMES.size)
        assertTrue(ResourceVector.DIMENSION_NAMES.contains("cpuAnonBytes"))
        assertTrue(ResourceVector.DIMENSION_NAMES.contains("networkBytesInFlight"))
    }

    @Test
    fun resourceVectorCheckedArithmetic() {
        val a = ResourceVector(cpuAnonBytes = 10L, nativeThreads = 1L)
        val b = ResourceVector(cpuAnonBytes = 5L, nativeThreads = 2L)
        val sum = a.plus(b)
        assertEquals(15L, sum.cpuAnonBytes)
        assertEquals(3L, sum.nativeThreads)
        assertTrue(sum.dominates(a))
    }

    @Test
    fun resourceEnvelopePeakMustDominateSteady() {
        val steady = ResourceVector(cpuAnonBytes = 100L)
        val peak = ResourceVector(cpuAnonBytes = 100L, gpuDedicatedBytes = 1L)
        ResourceEnvelope(steady, peak)
        assertThrows(IllegalArgumentException::class.java) {
            ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 100L),
                peak = ResourceVector(cpuAnonBytes = 50L),
            )
        }
    }

    @Test
    fun accessControlProfilesAndScopes() {
        assertTrue(
            AccessControlCatalog.profileAllowsScope(
                AccessProfile.LOCAL_ADMIN,
                AccessScope.models_manage,
            ),
        )
        assertTrue(
            AccessControlCatalog.profileAllowsScope(
                AccessProfile.APP_CLIENT,
                AccessScope.inference_create,
            ),
        )
        assertFalse(
            AccessControlCatalog.profileAllowsScope(
                AccessProfile.APP_CLIENT,
                AccessScope.tokens_manage,
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            AccessControlCatalog.requireKnownScope("not.a.scope")
        }
    }

    @Test
    fun capabilityCatalogFailClosed() {
        assertEquals("TEXT_GENERATION", CapabilityId.TEXT_GENERATION.id)
        val deps = CapabilityCatalog.get(CapabilityId.TOOL_CALLING).dependsOn
        assertEquals(listOf(CapabilityId.STRUCTURED_OUTPUT), deps)
        assertThrows(IllegalStateException::class.java) {
            CapabilityCatalog.requireKnown("MADE_UP_CAPABILITY")
        }
    }

    @Test
    fun omniResultMapAndFlatMap() {
        val ok: OmniResult<Int> = OmniResult.ok(2)
        val mapped = ok.map { it * 3 }
        assertEquals(6, (mapped as OmniResult.Ok).value)

        val err: OmniResult<Int> = OmniResult.err(OmniError.FORBIDDEN())
        assertTrue(err.map { it + 1 }.isErr)
    }
}
