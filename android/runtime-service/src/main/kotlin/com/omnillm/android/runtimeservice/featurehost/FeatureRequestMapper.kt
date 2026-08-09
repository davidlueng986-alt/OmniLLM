package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.ModelRevisionId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.DeviceExecutionFingerprint
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.contracts.RequestId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.modelmanager.ModelManager
import com.omnillm.runtime.orchestrator.OrchestrationRequest
import com.omnillm.runtime.orchestrator.RoutingCandidate
import com.omnillm.runtime.orchestrator.RoutingPreference

/**
 * Shared request → routing-candidate shaping for all transport facades
 * (ARC-12). One authority for:
 *
 * - model string → [ModelRevisionId] normalization (lower-case hex or
 *   `sha256("model|…")` projection — identical to OpenAPI/AIDL wire meaning);
 * - real-installation resolution + [RoutingCandidate] construction
 *   (ARC-06: fail closed with CAPABILITY_UNSUPPORTED when the revision is not
 *   actually installed — never fabricate identities);
 * - [OrchestrationRequest] shaping (operation kind, capabilities, cost class,
 *   routing preference, epochs, deadline).
 *
 * The binder path (OmniRuntimeFacade) consumes this mapper; the HTTP path
 * (ControlPlaneHttpHandler) conversion is tracked as ARC-12 follow-up (patch
 * provided to the handler owner).
 */
object FeatureRequestMapper {

    /**
     * Normalize a client model string to a [ModelRevisionId].
     * Returns null when the input is blank or does not parse — callers map null
     * to INVALID_REQUEST (identical to prior per-facade behavior).
     */
    fun normalizeRevision(modelRaw: String): ModelRevisionId? {
        val hex = modelRaw.lowercase().let {
            if (it.matches(Regex("^[0-9a-f]{64}$"))) {
                it
            } else {
                com.omnillm.core.canonical.IdentityHashing.sha256Hex("model|$it")
            }
        }
        return try {
            ModelRevisionId.parse(hex)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve the REAL installation for [revision] and build the routing
     * candidate. Fails closed with CAPABILITY_UNSUPPORTED when the revision is
     * not installed (no synthetic identities, ARC-06); engine-attachment errors
     * from [ControlPlaneFeaturePorts.buildCandidate] pass through unchanged.
     */
    suspend fun resolveCandidate(
        binding: EngineExecuteBinding,
        modelManager: ModelManager,
        revision: ModelRevisionId,
        deviceFingerprint: DeviceExecutionFingerprint,
    ): OmniResult<RoutingCandidate> {
        val installation = ControlPlaneFeaturePorts.resolveInstallationOrNull(
            modelManager,
            revision,
        ) ?: return OmniResult.err(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "no installed model for requested revision (fail closed, ARC-06)",
                details = mapOf("modelRevisionId" to revision.hex),
            ),
        )
        return ControlPlaneFeaturePorts.buildCandidate(
            binding,
            revision,
            installation,
            deviceFingerprint,
        )
    }

    /**
     * Shape the canonical [OrchestrationRequest] (ADR-002 / INV-002..004).
     * Pure — no I/O, no domain mutation; Plan stays pure.
     */
    fun orchestrationRequest(
        requestId: RequestId,
        principalId: PrincipalId,
        idempotencyKey: IdempotencyKey,
        operationKind: String,
        canonicalRequestDigest: Sha256Digest,
        requiredCapabilities: Set<CapabilityId>,
        requestedRevisionId: ModelRevisionId,
        candidates: List<RoutingCandidate>,
        routing: RoutingPreference,
        costClass: String,
        runtimeEpoch: Long,
        revocationEpoch: Long = 0L,
        deadlineMonotonic: Long,
    ): OrchestrationRequest =
        OrchestrationRequest(
            requestId = requestId,
            principalId = principalId,
            idempotencyKey = idempotencyKey,
            operationKind = operationKind,
            canonicalRequestDigest = canonicalRequestDigest,
            requiredCapabilities = requiredCapabilities,
            requestedRevisionId = requestedRevisionId,
            candidates = candidates,
            routing = routing,
            costClass = costClass,
            runtimeEpoch = runtimeEpoch,
            revocationEpoch = revocationEpoch,
            deadlineMonotonic = deadlineMonotonic,
        )
}
