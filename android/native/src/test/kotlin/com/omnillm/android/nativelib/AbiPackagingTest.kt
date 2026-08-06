package com.omnillm.android.nativelib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbiPackagingTest {

    @Test
    fun productionAbisAre64Bit() {
        assertTrue(AbiPackaging.DEFAULT_ABIS.contains(AbiPackaging.ARM64_V8A))
        assertTrue(AbiPackaging.DEFAULT_ABIS.contains(AbiPackaging.X86_64))
        assertFalse(AbiPackaging.DEFAULT_ABIS.contains(AbiPackaging.ARMEABI_V7A))
    }

    @Test
    fun knownAbiCheck() {
        assertTrue(AbiPackaging.isKnownAbi("arm64-v8a"))
        assertFalse(AbiPackaging.isKnownAbi("mips"))
        assertTrue(AbiPackaging.isProductionAbi("arm64-v8a"))
        assertFalse(AbiPackaging.isProductionAbi("armeabi-v7a"))
    }

    @Test
    fun linkerFlagsMention16kb() {
        assertTrue(NativePackagingNotes.linkerFlags().any { it.contains("16384") })
        assertTrue(NativePackagingNotes.REQUIRED_ALIGNMENT == 16384)
    }
}
