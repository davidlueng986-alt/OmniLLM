package com.omnillm.core.ports.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D14a: ADR-010 single-writer policy (INV-001) — the control plane is the sole
 * domain-DB writer; transport/UI/worker roles must fail closed at the policy
 * boundary. Also pins the [ControlPlaneWriter] marker default.
 */
class SingleWriterPolicyTest {

    @Test
    fun forbiddenWriterRoles_matchDocumentedTable() {
        assertEquals(
            setOf(
                "app-ui",
                "http-gateway",
                "aidl-transport",
                "engine-worker",
                "isolated-parser",
                "companion-sandbox",
            ),
            SingleWriterPolicy.FORBIDDEN_WRITER_ROLES,
        )
    }

    @Test
    fun assertWriterAllowed_acceptsOnlyControlPlane() {
        // The sole allowed writer (ADR-010).
        SingleWriterPolicy.assertWriterAllowed("runtime-control-plane")

        for (role in SingleWriterPolicy.FORBIDDEN_WRITER_ROLES) {
            try {
                SingleWriterPolicy.assertWriterAllowed(role)
                fail("role '$role' must never hold a domain DB write connection")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("ADR-010"))
                assertTrue(e.message!!.contains("INV-001"))
                assertTrue(e.message!!.contains(role))
            }
        }
    }

    @Test
    fun constants_pinDocumentedIds() {
        assertEquals("runtime-control-plane", SingleWriterPolicy.WRITER_ROLE)
        assertEquals("ADR-010", SingleWriterPolicy.ADR)
        assertEquals("INV-001", SingleWriterPolicy.INVARIANT)
    }

    @Test
    fun controlPlaneWriter_defaultsToSoleWriterRole() {
        val writer = object : ControlPlaneWriter {}
        assertEquals("runtime-control-plane", writer.writerRole)
    }

    @Test
    fun claimLedgerTransaction_executesBlockAndPropagates() {
        val tx = object : ClaimLedgerTransaction {
            var entered = false
            override fun <T> inTransaction(block: () -> T): T {
                entered = true
                return block()
            }
        }
        val result = tx.inTransaction { 42 }
        assertEquals(42, result)
    }

    @Test
    fun bundledPorts_requireControlPlaneWriterMarker() {
        // Bundled ledger ports must advertise the sole-writer role so facades
        // (ARC-02) can refuse to hold them as domain writers.
        val ports = object : ContentReportLedgerPorts {
            override val reports = object : ContentReportRecordDao {
                override fun findByReportId(reportId: String) = null
                override fun findByPrincipalAndIdempotency(principalId: String, idempotencyKey: String) = null
                override fun listByPrincipal(principalId: String) = emptyList<ContentReportRecordRow>()
                override fun listAll() = emptyList<ContentReportRecordRow>()
                override fun upsert(row: ContentReportRecordRow) = Unit
                override fun delete(reportId: String) = false
            }
            override val grants = object : ContentReportGrantDao {
                override fun findByGrantId(grantId: String) = null
                override fun listByReportId(reportId: String) = emptyList<ContentReportGrantRow>()
                override fun upsert(row: ContentReportGrantRow) = Unit
                override fun delete(grantId: String) = false
            }
            override val receipts = object : ContentReportReceiptDao {
                override fun findByReportId(reportId: String) = null
                override fun findByReceiptId(receiptId: String) = null
                override fun upsert(row: ContentReportReceiptRow) = Unit
                override fun deleteByReportId(reportId: String) = false
            }
            override val tx = object : ClaimLedgerTransaction {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        }
        assertEquals("runtime-control-plane", ports.writerRole)

        val toolPorts = object : ToolProposalLedgerPorts {
            override val proposals = object : ToolProposalRecordDao {
                override fun findByProposalId(proposalId: String) = null
                override fun listByRequestId(requestId: String) = emptyList<ToolProposalRow>()
                override fun upsert(row: ToolProposalRow) = Unit
                override fun updateState(proposalId: String, state: String) = false
            }
            override val claims = object : ToolResultClaimDao {
                override fun find(proposalId: String, idempotencyKey: String) = null
                override fun upsert(row: ToolResultClaimRow) = Unit
            }
            override val tx = object : ClaimLedgerTransaction {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        }
        assertEquals("runtime-control-plane", toolPorts.writerRole)
    }
}
