package com.omnillm.runtime.modelmanager

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.resource.Reservation
import com.omnillm.core.state.AggregateTransitionResult
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.engines.api.CommitContext
import com.omnillm.engines.api.EngineLoadPort
import com.omnillm.engines.api.LoadInput
import com.omnillm.engines.api.LoadPlan
import com.omnillm.engines.api.PlacementClassLabels
import com.omnillm.runtime.modelmanager.domain.LoadedModelSnapshot
import com.omnillm.runtime.modelmanager.guards.LoadedModelGuardAtoms
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import com.omnillm.runtime.modelmanager.ports.LoadedModelRepository
import com.omnillm.runtime.modelmanager.ports.PrivilegedLoadTicket
import com.omnillm.runtime.modelmanager.ports.ReferenceSnapshotPort

/**
 * Coordinates LOADED_MODEL lifecycle separately from Installation (CORE-MODEL §1.2).
 *
 * Plan → Reserve → Commit → Execute shape:
 * - [planLoad] is pure engine analysis (no domain mutation)
 * - admission creates PLANNED → RESERVED
 * - [commitLoad] requires privileged re-verify ticket then engine commitLoad
 * - drain / unload remain independent of installation FSM rows
 */
class LoadCoordinator(
    private val installations: InstallationRepository,
    private val loadedModels: LoadedModelRepository,
    private val engine: EngineLoadPort,
    private val privilegedGate: PrivilegedLoadGate,
    private val references: ReferenceSnapshotPort,
) {

    /**
     * Pure plan via engine port. Does **not** create LoadedModel rows (ADR-002).
     * Installation must be READY with valid revision lease held by caller.
     */
    suspend fun planLoad(input: LoadInput): OmniResult<LoadPlan> {
        val install = installations.get(
            InstallationId(input.installationId.value),
        ) ?: return OmniResult.err(
            OmniError.NOT_FOUND(
                message = "installation not found",
                details = mapOf("installationId" to input.installationId.value),
            ),
        )
        if (!install.allowsNewLoad()) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "installation not READY for load planning",
                    details = mapOf("state" to install.state),
                ),
            )
        }
        if (install.modelRevisionId.hex != input.modelRevisionId.hex) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "modelRevisionId mismatch with installation",
                ),
            )
        }
        // Engine plan — pure.
        return engine.planLoad(input)
    }

    /**
     * After governor admission: create LoadedModel PLANNED → RESERVED.
     */
    suspend fun admit(
        loadedModelId: LoadedModelId,
        installationId: InstallationId,
        loadKey: LoadKey,
        plan: LoadPlan,
        loadEnvelopeMatched: Boolean,
    ): OmniResult<LoadedModelSnapshot> {
        if (!PlacementClassLabels.isExecutable(plan.proposedPlacementClass)) {
            return failAdmission(
                loadedModelId,
                installationId,
                loadKey,
                plan,
                "placement not executable",
            )
        }
        if (!loadEnvelopeMatched) {
            return failAdmission(
                loadedModelId,
                installationId,
                loadKey,
                plan,
                "load envelope not matched",
            )
        }

        val install = installations.get(installationId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "installation not found"),
            )
        if (!install.allowsNewLoad()) {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "installation not READY",
                    details = mapOf("state" to install.state),
                ),
            )
        }

        var snap = LoadedModelSnapshot.planned(
            loadedModelId = loadedModelId,
            installationId = installationId,
            loadKey = loadKey,
            engineBuildId = plan.engineBuildId,
            placementClass = plan.proposedPlacementClass,
            runtimeEpoch = plan.runtimeEpoch,
        )

        val guards = LoadedModelGuardAtoms.evaluator(loadEnvelopeMatched = true)
        snap = when (val r = snap.aggregate.apply("ADMISSION_GRANTED", guards)) {
            is AggregateTransitionResult.Success -> snap.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.ADMISSION_REJECTED(message = "ADMISSION_GRANTED rejected"),
                )
        }
        return persist(snap)
    }

    private suspend fun failAdmission(
        loadedModelId: LoadedModelId,
        installationId: InstallationId,
        loadKey: LoadKey,
        plan: LoadPlan,
        reason: String,
    ): OmniResult<LoadedModelSnapshot> {
        var snap = LoadedModelSnapshot.planned(
            loadedModelId = loadedModelId,
            installationId = installationId,
            loadKey = loadKey,
            engineBuildId = plan.engineBuildId,
            placementClass = plan.proposedPlacementClass,
            runtimeEpoch = plan.runtimeEpoch,
        )
        snap = when (val r = snap.aggregate.apply("ADMISSION_REJECTED")) {
            is AggregateTransitionResult.Success -> snap.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(OmniError.ADMISSION_REJECTED(message = reason))
        }
        return when (val s = loadedModels.save(snap)) {
            is OmniResult.Ok -> OmniResult.err(OmniError.ADMISSION_REJECTED(message = reason))
            is OmniResult.Err -> s
        }
    }

    /**
     * RESERVED → LOADING → LOADED with privileged re-verify + engine commitLoad.
     */
    suspend fun commitLoad(
        loadedModelId: LoadedModelId,
        plan: LoadPlan,
        reservation: Reservation,
        commit: CommitContext,
        placementQualified: Boolean,
        loadEnvelopeMatched: Boolean,
        revocationEpoch: Long,
    ): OmniResult<LoadedModelSnapshot> {
        val current = loadedModels.get(loadedModelId)
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "loaded model not found",
                    details = mapOf("loadedModelId" to loadedModelId.value),
                ),
            )
        if (current.state != "RESERVED") {
            return OmniResult.err(
                OmniError.STATE_CONFLICT(
                    message = "commitLoad requires RESERVED",
                    details = mapOf("state" to current.state),
                ),
            )
        }

        // Privileged re-verify on actual FDs (INV-010) — ticket required in CommitContext.
        val ticket = when (
            val t = privilegedGate.issueTicket(
                installationId = current.installationId,
                engineBuildId = plan.engineBuildId.value,
                revocationEpoch = revocationEpoch,
            )
        ) {
            is OmniResult.Ok -> t.value
            is OmniResult.Err -> {
                return markLoadFailed(current, t.error)
            }
        }
        if (commit.privilegedLoadTicketId.isEmpty()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(message = "privilegedLoadTicketId required"),
            )
        }

        // Prefer fresh ticket; if caller pre-issued, IDs must match or re-bind.
        val boundCommit = if (commit.privilegedLoadTicketId == ticket.ticketId) {
            commit
        } else {
            commit.copy(privilegedLoadTicketId = ticket.ticketId)
        }

        val beginGuards = LoadedModelGuardAtoms.evaluator(placementQualified = placementQualified)
        var snap = when (val r = current.aggregate.apply("BEGIN_LOAD", beginGuards)) {
            is AggregateTransitionResult.Success -> current.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.TRUST_PLACEMENT_REQUIRED(
                        message = "placement not qualified for load",
                    ),
                )
        }
        when (val s = loadedModels.save(snap)) {
            is OmniResult.Err -> return s
            is OmniResult.Ok -> Unit
        }

        // Engine side effect after durable intent is caller's responsibility;
        // here we invoke the port (adapter must not write DB).
        val handle = when (val h = engine.commitLoad(plan, reservation, boundCommit)) {
            is OmniResult.Ok -> h.value
            is OmniResult.Err -> return markLoadFailed(snap, h.error)
        }

        val successGuards = LoadedModelGuardAtoms.evaluator(loadEnvelopeMatched = loadEnvelopeMatched)
        snap = when (val r = snap.aggregate.apply("LOAD_SUCCEEDED", successGuards)) {
            is AggregateTransitionResult.Success ->
                snap.copy(
                    aggregate = r.aggregate,
                    allocationHandleId = handle.allocationHandleId,
                    placementClass = handle.placementClass,
                )
            is AggregateTransitionResult.Rejected ->
                return markLoadFailed(
                    snap,
                    OmniError.INTERNAL(message = "LOAD_SUCCEEDED guard failed"),
                )
        }
        return persist(snap)
    }

    /**
     * Convenience: re-verify then expose ticket for orchestrator to put on CommitContext.
     */
    suspend fun preparePrivilegedTicket(
        installationId: InstallationId,
        engineBuildId: String,
        revocationEpoch: Long,
    ): OmniResult<PrivilegedLoadTicket> =
        privilegedGate.issueTicket(installationId, engineBuildId, revocationEpoch)

    suspend fun requestDrain(loadedModelId: LoadedModelId): OmniResult<LoadedModelSnapshot> {
        val current = loadedModels.get(loadedModelId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "loaded model not found"))
        val applied = when (val r = current.aggregate.apply("DRAIN_REQUESTED")) {
            is AggregateTransitionResult.Success -> current.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "cannot drain",
                        details = mapOf("state" to current.state),
                    ),
                )
        }
        return persist(applied)
    }

    /** When installation drains, all LOADED models for it must drain (CORE-MODEL §1.2). */
    suspend fun drainForInstallation(installationId: InstallationId): OmniResult<List<LoadedModelSnapshot>> {
        val list = loadedModels.findByInstallation(installationId)
        val out = mutableListOf<LoadedModelSnapshot>()
        for (lm in list) {
            if (lm.state == "LOADED") {
                when (val r = requestDrain(lm.loadedModelId)) {
                    is OmniResult.Ok -> out.add(r.value)
                    is OmniResult.Err -> return r
                }
            }
        }
        return OmniResult.ok(out)
    }

    suspend fun quiesceToUnload(loadedModelId: LoadedModelId): OmniResult<LoadedModelSnapshot> {
        val current = loadedModels.get(loadedModelId)
            ?: return OmniResult.err(OmniError.NOT_FOUND(message = "loaded model not found"))
        val refs = references.loadedModelReferences(loadedModelId)
        val guards = LoadedModelGuardAtoms.evaluator(references = refs)
        val unloading = when (val r = current.aggregate.apply("QUIESCENT", guards)) {
            is AggregateTransitionResult.Success -> current.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(
                    OmniError.STATE_CONFLICT(
                        message = "not quiescent",
                        details = mapOf("references" to refs.total.toString()),
                    ),
                )
        }
        when (val s = loadedModels.save(unloading)) {
            is OmniResult.Err -> return s
            is OmniResult.Ok -> Unit
        }
        val unloaded = when (val r = unloading.aggregate.apply("RESOURCE_BARRIER")) {
            is AggregateTransitionResult.Success ->
                unloading.copy(aggregate = r.aggregate, allocationHandleId = null)
            is AggregateTransitionResult.Rejected ->
                return OmniResult.err(OmniError.STATE_CONFLICT(message = "resource barrier failed"))
        }
        return persist(unloaded)
    }

    private suspend fun markLoadFailed(
        current: LoadedModelSnapshot,
        cause: OmniError,
    ): OmniResult<LoadedModelSnapshot> {
        var snap = when (val r = current.aggregate.apply("LOAD_FAILED")) {
            is AggregateTransitionResult.Success -> current.copy(aggregate = r.aggregate)
            is AggregateTransitionResult.Rejected -> return OmniResult.err(cause)
        }
        when (val s = loadedModels.save(snap)) {
            is OmniResult.Err -> return s
            is OmniResult.Ok -> Unit
        }
        snap = when (val r = snap.aggregate.apply("FAILURE_RELEASE_BARRIER")) {
            is AggregateTransitionResult.Success ->
                snap.copy(aggregate = r.aggregate, allocationHandleId = null)
            is AggregateTransitionResult.Rejected -> return OmniResult.err(cause)
        }
        loadedModels.save(snap)
        return OmniResult.err(cause)
    }

    private suspend fun persist(snap: LoadedModelSnapshot): OmniResult<LoadedModelSnapshot> =
        when (val s = loadedModels.save(snap)) {
            is OmniResult.Ok -> OmniResult.ok(snap)
            is OmniResult.Err -> s
        }
}
