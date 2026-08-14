package com.omnillm.engines.ortgenai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded cooperative-cancel registry (D19): cap + FIFO eviction + consume on
 * completion — memory stays bounded regardless of cancel spam.
 */
class BoundedCancelRegistryTest {

    @Test
    fun add_beyondCap_evictsOldestFifo() {
        val registry = BoundedCancelRegistry(maxTokens = 3)
        registry.add("a")
        registry.add("b")
        registry.add("c")
        registry.add("d")
        assertEquals(3, registry.size)
        assertFalse("oldest must be evicted", registry.contains("a"))
        assertTrue(registry.contains("b"))
        assertTrue(registry.contains("c"))
        assertTrue(registry.contains("d"))
    }

    @Test
    fun add_duplicateDoesNotGrowOrReorder() {
        val registry = BoundedCancelRegistry(maxTokens = 3)
        registry.add("a")
        registry.add("b")
        registry.add("a")
        registry.add("c")
        registry.add("d")
        // The duplicate "a" add did not reorder it: it is still the FIFO
        // head, so eviction removes "a" (not "b").
        assertEquals(3, registry.size)
        assertFalse(registry.contains("a"))
        assertTrue(registry.contains("b"))
        assertTrue(registry.contains("d"))
    }

    @Test
    fun consume_removesOnlyThatToken() {
        val registry = BoundedCancelRegistry(maxTokens = 3)
        registry.add("a")
        registry.add("b")
        registry.add("c")
        registry.consume("b")
        assertEquals(2, registry.size)
        assertFalse(registry.contains("b"))
        assertTrue(registry.contains("a"))
        assertTrue(registry.contains("c"))
    }

    @Test
    fun contains_reflectsAddAndConsume() {
        val registry = BoundedCancelRegistry(maxTokens = 3)
        assertFalse(registry.contains("x"))
        registry.add("x")
        assertTrue(registry.contains("x"))
        registry.consume("x")
        assertFalse(registry.contains("x"))
    }

    @Test
    fun clear_emptiesRegistry() {
        val registry = BoundedCancelRegistry(maxTokens = 3)
        registry.add("a")
        registry.add("b")
        registry.clear()
        assertEquals(0, registry.size)
        assertFalse(registry.contains("a"))
    }

    @Test
    fun capConstant_mirrorsNativePrecedent() {
        assertEquals(1024, BoundedCancelRegistry.MAX_CANCEL_TOKENS)
    }
}
