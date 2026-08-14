package com.omnillm.core.ports.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D14a: `:core:ports` ledger row port shapes (ARC-02) — durable rows shared by
 * `:runtime:request-registry`, `:data:persistence` and transport facades.
 * Failing validation must fail closed at construction time.
 */
class CorePortsLedgerModelsTest {

    // ----- Command ledger states --------------------------------------------

    @Test
    fun commandLedgerStates_terminalIsSubsetOfAll() {
        assertEquals(
            setOf("RECEIVED", "CLAIMED", "RUNNING", "RECONCILING", "SUCCEEDED", "FAILED", "CANCELLED", "UNCERTAIN"),
            CommandLedgerStates.ALL,
        )
        assertEquals(
            setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNCERTAIN"),
            CommandLedgerStates.TERMINAL,
        )
        assertTrue(CommandLedgerStates.TERMINAL.all { it in CommandLedgerStates.ALL })
        assertTrue("RECONCILING must not be terminal", "RECONCILING" !in CommandLedgerStates.TERMINAL)
    }

    @Test
    fun idempotentCommandClaimRow_defaultsAndConstruction() {
        val row = IdempotentCommandClaimRow(
            commandId = "cmd-1",
            principalId = "principal:u0",
            operationKind = "CHAT",
            idempotencyKey = "idem-1",
            canonicalInputDigest = "a".repeat(64),
            state = "CLAIMED",
            affectedResourceId = null,
            resultJson = null,
            errorCode = null,
            reconciliationDisposition = null,
            expiresAt = null,
            createdAt = "2026-08-03T00:00:00Z",
            updatedAt = "2026-08-03T00:00:00Z",
        )
        assertEquals(0L, row.resourceVersion)
        assertTrue(row.state in CommandLedgerStates.ALL)
        assertFalse(row.state in CommandLedgerStates.TERMINAL)
    }

    // ----- Content report ledger states / rows ------------------------------

    @Test
    fun contentReportLedgerStates_terminalIsSubsetOfAll() {
        assertTrue(ContentReportLedgerStates.TERMINAL.all { it in ContentReportLedgerStates.ALL })
        assertEquals(
            setOf("HATE_HARASSMENT", "SEXUAL_CONTENT", "CHILD_SAFETY", "VIOLENCE_SELF_HARM", "ILLEGAL_ACTIVITY", "DECEPTION_IMPERSONATION", "PRIVACY_PERSONAL_DATA", "DANGEROUS_ADVICE", "OTHER"),
            ContentReportLedgerCategories.ALL,
        )
        assertEquals(
            setOf("ISSUED", "CONSUMED", "EXPIRED", "REVOKED"),
            ContentReportGrantStates.ALL,
        )
    }

    private fun reportRow(
        state: String = "DRAFT",
        category: String = "HATE_HARASSMENT",
        encryptedProposal: ByteArray = ByteArray(4) { 1 },
        encryptedPayload: ByteArray? = null,
    ) = ContentReportRecordRow(
        reportId = "report-1",
        principalId = "principal:u0",
        proposalCommandId = "cmd-1",
        idempotencyKey = "idem-1",
        resourceVersion = 0L,
        state = state,
        category = category,
        appBuild = "1.0.0",
        modelRevisionId = "a".repeat(64),
        engineBuildId = "llama-cpp@1",
        backend = "llama-cpp",
        localPolicyVersion = "policy-v1",
        outputDigest = "b".repeat(64),
        userLocale = "en-US",
        encryptedProposal = encryptedProposal,
        encryptedPayload = encryptedPayload,
        expiresAt = "2026-08-04T00:00:00Z",
        payloadCreatedAt = "2026-08-03T00:00:00Z",
        createdAt = "2026-08-03T00:00:00Z",
        updatedAt = "2026-08-03T00:00:00Z",
    )

    @Test
    fun contentReportRecordRow_validRowAndValidation() {
        val row = reportRow(state = "QUEUED_OFFLINE", encryptedProposal = ByteArray(2))
        assertTrue(row.state in ContentReportLedgerStates.ALL)
        assertFalse(row.hasEncryptedPayloadBytes)
        assertTrue(reportRow(encryptedProposal = ByteArray(0)).hasEncryptedPayloadBytes == false)
        assertTrue(reportRow().copy(encryptedPayload = ByteArray(3)).hasEncryptedPayloadBytes)

        try {
            reportRow().copy(reportId = "")
            fail("blank reportId must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("reportId"))
        }
        try {
            reportRow().copy(state = "BOGUS")
            fail("unknown content report state must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown content report state"))
        }
        try {
            reportRow().copy(category = "BOGUS")
            fail("unknown category must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unknown content report category"))
        }
        try {
            reportRow().copy(resourceVersion = -1L)
            fail("negative resourceVersion must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }
    }

    @Test
    fun contentReportRecordRow_equalityUsesByteContentEquals() {
        val a = reportRow(encryptedProposal = ByteArray(4) { 9 }, encryptedPayload = ByteArray(2) { 1 })
        val b = reportRow(encryptedProposal = ByteArray(4) { 9 }, encryptedPayload = ByteArray(2) { 1 })
        assertEquals("row equality must compare bytes by content", a, b)
        assertEquals("hashCode must agree across equal rows", a.hashCode(), b.hashCode())
        assertFalse(a == reportRow(encryptedProposal = ByteArray(4) { 8 }))
    }

    @Test
    fun contentReportGrantRow_consumedRequiresConsumedAt() {
        val grant = ContentReportGrantRow(
            grantId = "g-1",
            reportId = "report-1",
            principalId = "principal:u0",
            canonicalPayloadDigest = "c".repeat(64),
            warningPolicyVersion = "w-v1",
            localUserProfileId = "u1",
            nonce = "nonce-1",
            state = "ISSUED",
            issuedAt = "2026-08-03T00:00:00Z",
            expiresAt = "2026-08-04T00:00:00Z",
        )
        assertEquals("ISSUED", grant.state)
        try {
            grant.copy(state = "CONSUMED", consumedAt = null)
            fail("CONSUMED grant without consumedAt must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("consumedAt"))
        }
        assertEquals(grant.copy(state = "CONSUMED", consumedAt = "2026-08-03T01:00:00Z").state, "CONSUMED")
    }

    @Test
    fun contentReportReceiptRow_digestMustBe64Hex() {
        val receipt = ContentReportReceiptRow(
            receiptId = "r-1",
            reportId = "report-1",
            acceptedAt = "2026-08-03T00:00:00Z",
            statusUrl = "https://localhost/receipts/r-1",
            responseDigest = "d".repeat(64),
        )
        assertEquals(64, receipt.responseDigest.length)
        try {
            receipt.copy(responseDigest = "short")
            fail("short responseDigest must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("64-char hex"))
        }
        try {
            receipt.copy(statusUrl = "")
            fail("blank statusUrl must fail closed")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ----- Tool proposal ledger ---------------------------------------------

    @Test
    fun toolProposalLedgerRowsAndStates() {
        assertEquals(
            setOf("PROPOSED", "HOST_CLAIMED", "RESULT_COMMITTED", "CONFLICT", "UNCERTAIN", "CANCELLED"),
            ToolProposalLedgerStates.ALL,
        )
        val row = ToolProposalRow(
            proposalId = "p-1",
            requestId = "req-1",
            toolId = "web-search",
            schemaDigest = "e".repeat(64),
            argumentsJson = """{"q":"x"}""",
            attempt = 0L,
            state = "PROPOSED",
            createdAtEpochMs = 1_000L,
        )
        assertTrue(row.state in ToolProposalLedgerStates.ALL)

        val claim = ToolResultClaimRow(
            proposalId = "p-1",
            idempotencyKey = "idem-1",
            requestId = "req-1",
            attempt = 0L,
            resultPayloadDigest = "f".repeat(64),
            isError = false,
            submittedAtEpochMs = 1_100L,
        )
        assertFalse(claim.isError)
    }
}
