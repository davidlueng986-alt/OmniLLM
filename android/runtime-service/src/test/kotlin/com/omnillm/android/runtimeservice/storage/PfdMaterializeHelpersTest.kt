package com.omnillm.android.runtimeservice.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TST-04: PfdMaterializeHelpers — the hermetic surface is the typed
 * [PfdMaterializeHelpers.importSpec] builder (ANDROID-STORAGE §2 strategy 2).
 *
 * The PFD/fstat/materialize paths require a real android.os.ParcelFileDescriptor
 * and are device-verified (androidTest); the security-field contract of the
 * SAF grant spec is pure and covered here.
 */
class PfdMaterializeHelpersTest {

    @Test
    fun importSpec_carriesExactlyTheSecurityFields() {
        val spec = PfdMaterializeHelpers.importSpec(
            uriToken = "content://com.omnillm.test/doc/42",
            grantFlags = 1,
            formatHint = "GGUF",
            ownerKey = "owner-1",
            expiryMonotonic = 9_999L,
            expectedByteLength = 1024L,
            expectedDigestHex = "a".repeat(64),
        )
        assertEquals("content://com.omnillm.test/doc/42", spec.uriToken)
        assertEquals(1, spec.grantFlags)
        assertEquals("GGUF", spec.formatHint)
        assertEquals("owner-1", spec.ownerKey)
        assertEquals(9_999L, spec.expiryMonotonic)
        assertEquals(1024L, spec.expectedByteLength)
        assertEquals("a".repeat(64), spec.expectedDigestHex)
    }

    @Test
    fun importSpec_defaultsIntegrityFieldsToNull() {
        val spec = PfdMaterializeHelpers.importSpec(
            uriToken = "content://uri/1",
            grantFlags = 3,
            formatHint = "GGUF",
            ownerKey = "owner-2",
            expiryMonotonic = 1_000L,
        )
        assertNull("integrity facts are optional and must stay null by default", spec.expectedByteLength)
        assertNull(spec.expectedDigestHex)
        assertEquals("owner-2", spec.ownerKey)
    }
}
