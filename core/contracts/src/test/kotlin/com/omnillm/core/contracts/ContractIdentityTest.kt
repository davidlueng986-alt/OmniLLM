package com.omnillm.core.contracts

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.resource.ReservationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractIdentityTest {

    private val digest0 =
        Sha256Digest.parse("0000000000000000000000000000000000000000000000000000000000000000")
    private val revision =
        ModelRevisionId.parse("d3085c5444f76901c1dcb6a5bba90ce53643a9091574d47ebbe0dd3b4314ae5a")

    @Test
    fun uuidIds_parseAndReject() {
        val req = RequestId.parse("11111111-1111-1111-1111-111111111111")
        val commit = CommitId.parse("22222222-2222-2222-2222-222222222222")
        val command = CommandId.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertEquals("11111111-1111-1111-1111-111111111111", req.value)
        assertEquals("22222222-2222-2222-2222-222222222222", commit.value)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", command.value)
        assertThrows(IllegalArgumentException::class.java) {
            RequestId.parse("not-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommitId.parse("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandId.parse("not-uuid")
        }
    }

    @Test
    fun opaqueIds_nonEmpty() {
        val plan = PlanId.parse("plan-abc")
        val prep = PreparedOperationId.parse("prep-xyz")
        assertEquals("plan-abc", plan.value)
        assertEquals("prep-xyz", prep.value)
        assertThrows(IllegalArgumentException::class.java) {
            PlanId.parse("")
        }
    }

    @Test
    fun idempotencyKey_maxBytes() {
        val ok = IdempotencyKey.parse("k".repeat(128))
        assertEquals(128, ok.value.length)
        assertThrows(IllegalArgumentException::class.java) {
            IdempotencyKey.parse("k".repeat(129))
        }
    }

    @Test
    fun planIsPure_noMutationFieldsBeyondAnalysis() {
        val plan = Plan(
            planId = PlanId.parse("plan-1"),
            requestId = RequestId.parse("11111111-1111-1111-1111-111111111111"),
            principalId = PrincipalId.parse("principal-1"),
            modelRevisionId = revision,
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            deviceExecutionFingerprint = DeviceExecutionFingerprint.parse("dev-fp-1"),
            canonicalInputDigest = digest0,
            resourceEnvelope = ResourceEnvelope(
                steady = ResourceVector(cpuAnonBytes = 10L),
                peak = ResourceVector(cpuAnonBytes = 20L),
            ),
            expiryMonotonic = 99L,
            runtimeEpoch = 1L,
        )
        // ADR-002: plan carries envelope for admission, not allocation ownership.
        assertEquals(20L, plan.resourceEnvelope.peak.cpuAnonBytes)
        assertEquals(null, plan.sourceSessionEpoch)
    }

    @Test
    fun commitAndPreparedOperation_bindIds() {
        val commit = Commit(
            commitId = CommitId.parse("22222222-2222-2222-2222-222222222222"),
            planId = PlanId.parse("plan-1"),
            requestId = RequestId.parse("11111111-1111-1111-1111-111111111111"),
            principalId = PrincipalId.parse("principal-1"),
            reservationId = ReservationId.parse("rsv-1"),
            revisionLeaseId = RevisionLeaseId.parse("lease-1"),
            issuerBootId = "boot-1",
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            sourceSessionEpoch = null,
            engineBuildId = EngineBuildId.parse("engine-build-1"),
            canonicalInputDigest = digest0,
            oneShotNonce = "nonce-1",
        )
        val prepared = PreparedOperation(
            preparedOperationId = PreparedOperationId.parse("prep-1"),
            operationId = "op-1",
            requestId = commit.requestId,
            commitId = commit.commitId,
            principalId = commit.principalId,
            reservationId = commit.reservationId,
            revisionLeaseId = commit.revisionLeaseId,
            issuerBootId = commit.issuerBootId,
            runtimeEpoch = commit.runtimeEpoch,
            revocationEpoch = commit.revocationEpoch,
            sourceSessionEpoch = null,
            targetSessionId = null,
            canonicalInputDigest = digest0,
        )
        assertEquals(commit.commitId, prepared.commitId)
        assertEquals(commit.reservationId, prepared.reservationId)
    }

    @Test
    fun requestIdentity_claimShape() {
        val identity = RequestIdentity(
            requestId = RequestId.parse("11111111-1111-1111-1111-111111111111"),
            principalId = PrincipalId.parse("principal-1"),
            idempotencyKey = IdempotencyKey.parse("idem-1"),
            operationKind = "inference.create",
            canonicalInputDigest = digest0,
        )
        assertEquals("inference.create", identity.operationKind)
    }

    @Test
    fun contractResult_aliasesOmniResult() {
        val ok: ContractResult<Int> = OmniResult.ok(7)
        assertTrue(ok.isOk)
        assertEquals(7, ok.getOrNull())
    }
}
