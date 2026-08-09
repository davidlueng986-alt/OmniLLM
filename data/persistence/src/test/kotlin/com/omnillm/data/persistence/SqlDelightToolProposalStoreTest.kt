package com.omnillm.data.persistence
import com.omnillm.core.ports.ledger.ToolResultClaimRow
import com.omnillm.core.ports.ledger.ToolProposalRow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SqlDelightToolProposalStoreTest {

    @Test
    fun put_get_list_and_claim_survive() {
        val db = ControlPlaneDatabase.openInMemory()
        try {
            val store = db.toolProposals
            store.proposals.upsert(
                ToolProposalRow(
                    proposalId = "p1",
                    requestId = "r1",
                    toolId = "search",
                    schemaDigest = "d".repeat(64),
                    argumentsJson = """{"q":"x"}""",
                    attempt = 1L,
                    state = "PROPOSED",
                    createdAtEpochMs = 100L,
                ),
            )
            val got = store.proposals.findByProposalId("p1")
            assertNotNull(got)
            assertEquals("search", got!!.toolId)
            assertEquals(1, store.proposals.listByRequestId("r1").size)

            store.proposals.updateState("p1", "RESULT_COMMITTED")
            assertEquals("RESULT_COMMITTED", store.proposals.findByProposalId("p1")!!.state)

            store.claims.upsert(
                ToolResultClaimRow(
                    proposalId = "p1",
                    idempotencyKey = "idem-1",
                    requestId = "r1",
                    attempt = 1L,
                    resultPayloadDigest = "e".repeat(64),
                    isError = false,
                    submittedAtEpochMs = 200L,
                ),
            )
            val claim = store.claims.find("p1", "idem-1")
            assertNotNull(claim)
            assertEquals("r1", claim!!.requestId)
            assertNull(store.claims.find("p1", "missing"))
        } finally {
            db.close()
        }
    }
}

