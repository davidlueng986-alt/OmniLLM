package com.omnillm.runtime.requestregistry

import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.runtime.RequestRegistryModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE disconnect must **not** re-claim delivery or mint a new request (REL-RECOVERY, ADR-004/005/011).
 *
 * Normative chain:
 * 1. Client generates requestId + idempotencyKey and claims once.
 * 2. SSE may write frames to the socket — write ≠ application delivery.
 * 3. On disconnect / reply loss, client **queries** by requestId; re-claim with the same
 *    principal/operation/key/digest returns Existing — never a second New.
 * 4. Session default for SSE is STATELESS (no hidden session auto-reuse) — Q-005.
 *
 * Quality scenario: **Q-005**, recovery contract **REL-RECOVERY** §1–3.
 */
class SseDisconnectClaimSemanticsTest {

    private val digest = Sha256Digest.parse("c".repeat(64))
    private val principal = PrincipalId.parse("sse-client-1")
    private val clock = { "2026-08-04T00:00:00Z" }

    @Test
    fun sseDisconnect_doesNotClaimDelivery_queryOnly_q005() {
        val (registry, _, _) = RequestRegistryModule.createInMemory(clock)
        val requestId = RequestId.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val key = IdempotencyKey.parse("idem-sse-disconnect-1")

        // Accept / claim before any stream write (durable ledger authority).
        val first = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digest,
            requestId = requestId,
        )
        assertTrue(first is ClaimOutcome.New)
        assertTrue(first.isNew)
        assertEquals("RECEIVED", first.getOrNull()!!.state)

        // Simulate: SSE frames written then client disconnects mid-stream.
        // No terminal recorded → delivery is unproven; must not invent success.
        assertEquals(null, registry.queryRequestTerminal(requestId))

        // Reply-loss path: query durable row (do not mint a new requestId).
        val queried = registry.queryRequest(requestId)
        assertNotNull(queried)
        assertEquals(requestId.value, queried!!.requestId)
        assertEquals(digest.hex, queried.canonicalRequestDigest)
        assertEquals("RECEIVED", queried.state)

        // Re-claim with identical envelope returns Existing — not a second delivery claim.
        val reclaimed = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digest,
            requestId = requestId,
        )
        assertTrue(reclaimed is ClaimOutcome.Existing)
        assertFalse(reclaimed.isNew)
        assertEquals(requestId.value, reclaimed.getOrNull()!!.requestId)
    }

    @Test
    fun disconnect_thenBlindNewRequestId_isConflictOrSeparate_notSilentDelivery() {
        val (registry, _, _) = RequestRegistryModule.createInMemory(clock)
        val originalId = RequestId.parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val key = IdempotencyKey.parse("idem-sse-disconnect-2")

        val first = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digest,
            requestId = originalId,
        )
        assertTrue(first is ClaimOutcome.New)

        // Blind replay with a *new* requestId but same idempotency key + same digest
        // is a claim-key collision (different requestId) → Conflict (fail closed).
        val blind = registry.claim(
            principal = principal,
            operationKind = "CHAT",
            idempotencyKey = key,
            canonicalHash = digest,
            requestId = RequestId.parse("cccccccc-cccc-cccc-cccc-cccccccccccc"),
        )
        assertTrue(
            "blind new requestId under same key must not silently succeed as New",
            blind is ClaimOutcome.Conflict || blind is ClaimOutcome.Existing,
        )
        assertFalse(blind is ClaimOutcome.New)

        // Original remains queryable — the only durable outcome for the first claim.
        assertNotNull(registry.queryRequest(originalId))
    }
}
