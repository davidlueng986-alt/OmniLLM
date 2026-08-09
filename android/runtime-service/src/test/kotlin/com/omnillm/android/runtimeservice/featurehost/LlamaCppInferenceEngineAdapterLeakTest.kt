package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.android.runtimeservice.controlplane.EnginePackAttachment
import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.Commit
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.Plan
import com.omnillm.core.contracts.PreparedOperation
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.orchestrator.CostClassLabels
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * COR-16 regression: [LlamaCppInferenceEngineAdapter] bookkeeping must stay
 * bounded across completed chats and concurrent operations must share exactly
 * one refcounted native load (no per-request growth, no teardown race).
 */
class LlamaCppInferenceEngineAdapterLeakTest {

    private val digest = Sha256Digest.parse("ab".repeat(32))
    private val revision = ModelRevisionId.parse("cd".repeat(32))
    private val device = DeviceExecutionFingerprint.parse("device-fp-leak")
    private val principal = PrincipalId.parse("principal-leak-test")

    private fun candidate(build: EngineBuildId): RoutingCandidate =
        RoutingCandidate(
            candidateId = "primary",
            modelRevisionId = revision,
            installationId = InstallationId.parse("550e8400-e29b-41d4-a716-446655440000"),
            engineBuildId = build,
            backend = "cpu",
            placementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            loadKeyDigest = digest,
            isPrimary = true,
            deviceExecutionFingerprint = device,
        )

    private fun request(build: EngineBuildId): OrchestrationRequest =
        OrchestrationRequest(
            requestId = RequestId.parse(UUID.randomUUID().toString()),
            principalId = principal,
            idempotencyKey = IdempotencyKey.parse("idem-${UUID.randomUUID()}"),
            operationKind = "CHAT",
            canonicalRequestDigest = digest,
            requiredCapabilities = setOf(CapabilityId.TEXT_GENERATION),
            requestedRevisionId = revision,
            candidates = listOf(candidate(build)),
            routing = RoutingPreference(
                minimumPlacementClass = PlacementClassLabels.CRASH_CONTAINED_TRUSTED,
            ),
            costClass = CostClassLabels.GENERATION,
            runtimeEpoch = 1L,
            revocationEpoch = 0L,
            deadlineMonotonic = 1_000_000L,
        )

    private fun reservationFor(plan: Plan): Reservation =
        Reservation(
            reservationId = ReservationId.parse("res-${UUID.randomUUID().toString().take(8)}"),
            principalId = plan.principalId.value,
            issuerBootId = "boot-leak-test",
            runtimeEpoch = plan.runtimeEpoch,
            nonce = "nonce-${UUID.randomUUID().toString().take(8)}",
            deadlineMonotonic = plan.expiryMonotonic,
            envelope = plan.resourceEnvelope,
        )

    private fun commitFor(plan: Plan, reservation: Reservation): Commit =
        Commit(
            commitId = CommitId.parse(UUID.randomUUID().toString()),
            planId = plan.planId,
            requestId = plan.requestId,
            principalId = plan.principalId,
            reservationId = reservation.reservationId,
            revisionLeaseId = RevisionLeaseId.parse("lease-1"),
            issuerBootId = "boot-leak-test",
            runtimeEpoch = plan.runtimeEpoch,
            revocationEpoch = 0L,
            sourceSessionEpoch = null,
            engineBuildId = plan.engineBuildId,
            canonicalInputDigest = plan.canonicalInputDigest,
            oneShotNonce = "nonce-${UUID.randomUUID()}",
        )

    private suspend fun planOf(adapter: LlamaCppInferenceEngineAdapter, build: EngineBuildId): Plan {
        val req = request(build)
        return (adapter.planInference(req, req.candidates.first()) as OmniResult.Ok).value.plan
    }

    private suspend fun commitOf(adapter: LlamaCppInferenceEngineAdapter, plan: Plan): PreparedOperation {
        val reservation = reservationFor(plan)
        return (adapter.commitInference(plan, reservation, commitFor(plan, reservation)) as OmniResult.Ok).value
    }

    private suspend fun complete(adapter: LlamaCppInferenceEngineAdapter, prepared: PreparedOperation) {
        val started = adapter.start(prepared, prepared.operationId, 1L)
        assertTrue("start should succeed: $started", started is OmniResult.Ok)
        val batch = adapter.nextEvents(prepared, 0L)
        assertTrue("nextEvents should succeed: $batch", batch is OmniResult.Ok)
        assertNotNull("terminal must be delivered", (batch as OmniResult.Ok).value.terminal)
    }

    private fun adapter(): Pair<LlamaCppInferenceEngineAdapter, EngineBuildId> {
        val pack = EnginePackAttachment.attachForTest(
            includeStubEngine = true,
            deviceFingerprint = device,
        )
        return LlamaCppInferenceEngineAdapter(pack.llamaCppEngine!!) to pack.llamaCppEngine!!.engineBuildId
    }

    @Test
    fun completedChats_doNotGrowAdapterBookkeeping() = runBlocking {
        val (adapter, build) = adapter()
        repeat(5) {
            assertEquals("no residual bindings before chat", 0, adapter.activeLoadedPortsCount())
            assertEquals(0, adapter.activeOperationLeaseCount())
            assertEquals(0, adapter.sharedLeaseCount())

            val plan = planOf(adapter, build)
            val prepared = commitOf(adapter, plan)
            assertEquals("bound during chat", 1, adapter.activeLoadedPortsCount())
            assertEquals(1, adapter.activeOperationLeaseCount())
            assertEquals(1, adapter.sharedLeaseCount())

            complete(adapter, prepared)
        }
        assertEquals("loadedPorts must not grow across chats", 0, adapter.activeLoadedPortsCount())
        assertEquals(0, adapter.activeOperationLeaseCount())
        assertEquals(0, adapter.sharedLeaseCount())
        assertEquals(0, adapter.sharedLeaseUsers())
    }

    @Test
    fun concurrentChats_shareSinglePortAndReleaseAfterLastUser() = runBlocking {
        val (adapter, build) = adapter()
        val a = async { commitOf(adapter, planOf(adapter, build)) }
        val b = async { commitOf(adapter, planOf(adapter, build)) }
        val pa = a.await()
        val pb = b.await()

        // Concurrent commits must share exactly one native load (refcounted).
        assertEquals("one shared slot for both operations", 1, adapter.sharedLeaseCount())
        assertEquals("two users on the shared slot", 2, adapter.sharedLeaseUsers())
        assertEquals(2, adapter.activeLoadedPortsCount())

        coroutineScope {
            launch { complete(adapter, pa) }
            launch { complete(adapter, pb) }
        }
        assertEquals("last-user release must tear the shared slot down", 0, adapter.sharedLeaseCount())
        assertEquals(0, adapter.sharedLeaseUsers())
        assertEquals(0, adapter.activeLoadedPortsCount())
        assertEquals(0, adapter.activeOperationLeaseCount())
    }
}
