package com.omnillm.features.contentreport

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.contentreport.api.ContentReportApi
import com.omnillm.features.contentreport.api.ContentReportCommandIdentity
import com.omnillm.features.contentreport.domain.ContentReportPolicy.CallerSurface
import com.omnillm.features.contentreport.ports.InMemoryContentReportStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COR-23g regression: createProposal claim is atomic — concurrent proposals for
 * the same reportId resolve to exactly one winner. No last-writer-wins
 * overwrite; every caller receives a consistent record (reply-loss reconcile).
 */
class ContentReportAtomicProposalTest {

    private fun specFor(
        reportId: String,
        idemKey: String,
        description: String,
    ) = proposalSpec(
        reportId = reportId,
        command = ContentReportCommandIdentity(
            commandId = uuid("cmd-$idemKey"),
            idempotencyKey = idemKey,
        ),
        description = description,
    )

    @Test
    fun concurrentSameReportId_claimsExactlyOneRecord() = runBlocking {
        val store = InMemoryContentReportStore()
        val api: ContentReportApi = service(ports(store = store))
        val reportId = uuid("race")

        coroutineScope {
            val results = (1..20).map { i ->
                async {
                    api.createProposal(
                        principal = principal(),
                        surface = CallerSurface.LOCAL_TRUSTED_UI,
                        profileAuthenticated = true,
                        accessProfileId = "LOCAL_ADMIN",
                        spec = specFor(reportId, "idem-race-$i", "proposal-$i"),
                    )
                }
            }.awaitAll()

            val views = results.map { result ->
                assertTrue("every caller must get a clean result, got: $result", result is OmniResult.Ok)
                (result as OmniResult.Ok).value
            }
            // All callers converge on the same reportId / same record.
            assertEquals(20, views.size)
            views.forEach { assertEquals(reportId, it.reportId) }
        }

        val stored = store.listAll()
        assertEquals("exactly one record may survive the race (COR-23g)", 1, stored.size)
        assertEquals(reportId, stored[0].reportId)
        // The surviving record is one of the proposals (no fabricated merge).
        assertTrue(
            "survivor description must be one of the racing proposals",
            (1..20).any { stored[0].payload?.description == "proposal-$it" },
        )
    }

    @Test
    fun sequentialSameReportIdDifferentKeys_noOverwrite() = runBlocking {
        val store = InMemoryContentReportStore()
        val api: ContentReportApi = service(ports(store = store))
        val reportId = uuid("seq")

        val first = api.createProposal(
            principal = principal(),
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            profileAuthenticated = true,
            accessProfileId = "LOCAL_ADMIN",
            spec = specFor(reportId, "idem-seq-1", "first"),
        )
        assertTrue(first is OmniResult.Ok)

        // Second proposal with the SAME reportId but a DIFFERENT idempotency
        // key must reconcile to the existing record — never overwrite it.
        val second = api.createProposal(
            principal = principal(),
            surface = CallerSurface.LOCAL_TRUSTED_UI,
            profileAuthenticated = true,
            accessProfileId = "LOCAL_ADMIN",
            spec = specFor(reportId, "idem-seq-2", "second-writer"),
        )
        assertTrue(second is OmniResult.Ok)
        assertEquals(reportId, (second as OmniResult.Ok).value.reportId)

        val stored = store.listAll()
        assertEquals(1, stored.size)
        assertEquals("first", stored[0].payload?.description)
    }
}
