package com.omnillm.features.routing

import com.omnillm.features.routing.domain.AliasResolution
import com.omnillm.features.routing.domain.AliasResolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alias fixed at accept (FEAT-ROUTING §1). */
class AliasResolutionTest {

    @Test
    fun alias_resolvesOnceToRevision() {
        val rev = revision('1')
        val table = mapOf("default" to rev, "fast" to revision('2'))
        val r = AliasResolution.resolveAtAccept("default", table)
        assertTrue(r is AliasResolveResult.Ok)
        val ok = r as AliasResolveResult.Ok
        assertEquals(rev.hex, ok.revisionId.hex)
        assertTrue(ok.resolvedFromAlias)
    }

    @Test
    fun bareRevisionHex_accepted() {
        val rev = revision('a')
        val r = AliasResolution.resolveAtAccept(rev.hex, emptyMap())
        assertTrue(r is AliasResolveResult.Ok)
        assertFalse((r as AliasResolveResult.Ok).resolvedFromAlias)
        assertEquals(rev.hex, r.revisionId.hex)
    }

    @Test
    fun unknownAlias_failClosed() {
        val r = AliasResolution.resolveAtAccept("unknown-alias", mapOf("default" to revision('1')))
        assertTrue(r is AliasResolveResult.Invalid)
    }

    @Test
    fun empty_failClosed() {
        val r = AliasResolution.resolveAtAccept("  ", emptyMap())
        assertTrue(r is AliasResolveResult.Invalid)
    }
}
