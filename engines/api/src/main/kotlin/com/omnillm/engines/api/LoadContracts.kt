package com.omnillm.engines.api

import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.ResourceEnvelope
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.CommitId
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.EngineBuildId
import com.omnillm.core.contracts.LoadKey
import com.omnillm.core.contracts.PlanId
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.contracts.RevisionLeaseId
import com.omnillm.core.identity.InstallationId
import com.omnillm.core.resource.AllocationHandleId
import com.omnillm.core.resource.Reservation
import com.omnillm.core.resource.ReservationId
import com.omnillm.core.state.domain.LoadedModelId

/**
 * Engine load SPI types (CORE-ENGINE).
 *
 * Plan has **no** domain mutation (ADR-002). Adapters implement [EngineLoadPort];
 * Model Manager / Orchestrator own Installation and LoadedModel aggregates.
 * Native engines are **not** implemented here — ports only.
 */

/** Bounded device descriptor for plan/probe (opaque platform facts). */
data class DeviceDescriptor(
    val deviceExecutionFingerprint: DeviceExecutionFingerprint,
    val abiList: List<String> = emptyList(),
    val notes: Map<String, String> = emptyMap(),
)

/**
 * Input to pure [EngineLoadPort.planLoad].
 * Does not open weights or mutate Installation/LoadedModel state.
 */
data class LoadInput(
    val requestId: RequestId,
    val principalId: PrincipalId,
    val installationId: InstallationId,
    val modelRevisionId: ModelRevisionId,
    val loadKey: LoadKey,
    val device: DeviceDescriptor,
    /** Opaque storage key for content-addressed ready install (not a client path). */
    val storageRootKey: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val templateEpoch: Long,
    val tokenizerEpoch: Long,
    /**
     * Privileged path-broker result after re-verify (INV-010).
     * Use `fixture:` / EXPERIMENTAL_FIXTURE for packaging exploratory loads.
     * Null with [modelFd] &lt; 0 and non-fixture [storageRootKey] ⇒ engine must fail closed
     * (no silent synthetic fixture for real install intent).
     */
    val resolvedModelPath: String? = null,
    /** Privileged broker-opened read-only FD (-1 unused). Preferred over path when set. */
    val modelFd: Int = -1,
) {
    init {
        require(storageRootKey.isNotEmpty()) { "storageRootKey must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(templateEpoch >= 0L) { "templateEpoch must be non-negative" }
        require(tokenizerEpoch >= 0L) { "tokenizerEpoch must be non-negative" }
    }
}

/**
 * Pure load plan returned by the engine (ADR-002 / CORE-ENGINE §3).
 * Opaque [planId] is server-issued; not a caller-mutable replay structure.
 */
data class LoadPlan(
    val planId: PlanId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val engineBuildId: EngineBuildId,
    val loadKey: LoadKey,
    val installationId: InstallationId,
    val modelRevisionId: ModelRevisionId,
    val resourceEnvelope: ResourceEnvelope,
    /** Catalog placement class label from SEC-PLACEMENT (string, not invented enum). */
    val proposedPlacementClass: String,
    val phaseCapabilityDigest: Sha256Digest,
    val canonicalInputDigest: Sha256Digest,
    val expiryMonotonic: Long,
    val runtimeEpoch: Long,
) {
    init {
        require(proposedPlacementClass.isNotEmpty()) { "proposedPlacementClass must be non-empty" }
        require(expiryMonotonic >= 0L) { "expiryMonotonic must be non-negative" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}

/**
 * Commit context for domain mutation after reservation (CORE-ENGINE §4).
 * Intent must be durable before worker delivery (DATA-OWNERSHIP).
 */
data class CommitContext(
    val commitId: CommitId,
    val requestId: RequestId,
    val principalId: PrincipalId,
    val reservationId: ReservationId,
    val revisionLeaseId: RevisionLeaseId,
    val issuerBootId: String,
    val runtimeEpoch: Long,
    val revocationEpoch: Long,
    val oneShotNonce: String,
    /** Fresh privileged-load re-verify ticket; never skip for privileged placement. */
    val privilegedLoadTicketId: String,
) {
    init {
        require(issuerBootId.isNotEmpty()) { "issuerBootId must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
        require(revocationEpoch >= 0L) { "revocationEpoch must be non-negative" }
        require(oneShotNonce.isNotEmpty()) { "oneShotNonce must be non-empty" }
        require(privilegedLoadTicketId.isNotEmpty()) { "privilegedLoadTicketId must be non-empty" }
    }
}

/**
 * Opaque handle after successful commitLoad — no native pointer across process (ENGINE-STANDARD).
 * LoadedModel aggregate ownership remains in Model Manager / control plane.
 */
data class LoadedModelHandle(
    val loadedModelId: LoadedModelId,
    val installationId: InstallationId,
    val engineBuildId: EngineBuildId,
    val loadKey: LoadKey,
    val allocationHandleId: AllocationHandleId,
    val placementClass: String,
    val runtimeEpoch: Long,
) {
    init {
        require(placementClass.isNotEmpty()) { "placementClass must be non-empty" }
        require(runtimeEpoch >= 0L) { "runtimeEpoch must be non-negative" }
    }
}

/** Queryable commit outcome for reply-loss recovery (ADR-004/005). */
data class CommitQueryState(
    val commitId: CommitId,
    /** Catalog COMMIT machine state ID (e.g. INTENT_RECORDED, COMMITTED). */
    val state: String,
    val loadedModelId: LoadedModelId? = null,
) {
    init {
        require(state.isNotEmpty()) { "state must be non-empty" }
    }
}

/**
 * Engine adapter load SPI — plan is pure; commit mutates only via runtime-owned side effects
 * delivered after durable intent (no direct DB/model-store writes by adapters).
 */
interface EngineLoadPort {
    val engineBuildId: EngineBuildId

    /**
     * Pure plan: resource envelope + phase capabilities. No domain mutation (ADR-002).
     */
    suspend fun planLoad(input: LoadInput): com.omnillm.core.canonical.generated.OmniResult<LoadPlan>

    /**
     * Commit load after reservation + privileged re-verify ticket.
     * Adapter must not write OmniLLM DB or model store (ENGINE-STANDARD §3).
     */
    suspend fun commitLoad(
        plan: LoadPlan,
        reservation: Reservation,
        commit: CommitContext,
    ): com.omnillm.core.canonical.generated.OmniResult<LoadedModelHandle>

    /** Query commit for reply-loss; never blind replay (ADR-004/005). */
    suspend fun queryCommit(
        commitId: CommitId,
    ): com.omnillm.core.canonical.generated.OmniResult<CommitQueryState>
}

/**
 * SEC-PLACEMENT placement class labels (authoritative names from product docs).
 * Stored as strings on wire/DB to avoid inventing catalog enums absent from YAML.
 */
object PlacementClassLabels {
    const val PRIVILEGED_TRUSTED: String = "PRIVILEGED_TRUSTED"
    const val CRASH_CONTAINED_TRUSTED: String = "CRASH_CONTAINED_TRUSTED"
    const val ISOLATED_CPU_UNTRUSTED: String = "ISOLATED_CPU_UNTRUSTED"
    const val EXTERNAL_UID_ACCELERATED: String = "EXTERNAL_UID_ACCELERATED"
    /** Fail-closed: cannot execute under required boundary. */
    const val TRUST_PLACEMENT_REQUIRED: String = "TRUST_PLACEMENT_REQUIRED"

    val ALL: Set<String> = setOf(
        PRIVILEGED_TRUSTED,
        CRASH_CONTAINED_TRUSTED,
        ISOLATED_CPU_UNTRUSTED,
        EXTERNAL_UID_ACCELERATED,
        TRUST_PLACEMENT_REQUIRED,
    )

    fun isKnown(label: String): Boolean = label in ALL

    fun isExecutable(label: String): Boolean =
        label != TRUST_PLACEMENT_REQUIRED && isKnown(label)
}
