package com.omnillm.runtime.modelmanager.guards

import com.omnillm.core.state.GuardEvaluator
import com.omnillm.runtime.modelmanager.ports.LiveReferences

/**
 * Catalog guard atoms for MODEL_INSTALLATION, LOADED_MODEL, REVISION_LEASE.
 * Runtime supplies facts; StateMachineDriver evaluates expressions.
 */
object InstallationGuardAtoms {
    const val BYTES_MATERIALIZED: String = "bytesMaterialized"
    const val IDENTITY_VERIFIED: String = "identityVerified"
    const val TRUST_EVALUATED: String = "trustEvaluated"
    const val NO_LIVE_REFERENCES: String = "noLiveReferences"

    fun evaluator(
        bytesMaterialized: Boolean = false,
        identityVerified: Boolean = false,
        trustEvaluated: Boolean = false,
        references: LiveReferences = LiveReferences(),
    ): GuardEvaluator = GuardEvaluator.of(
        BYTES_MATERIALIZED to bytesMaterialized,
        IDENTITY_VERIFIED to identityVerified,
        TRUST_EVALUATED to trustEvaluated,
        NO_LIVE_REFERENCES to references.isZero,
    )
}

object LoadedModelGuardAtoms {
    const val LOAD_ENVELOPE_MATCHED: String = "loadEnvelopeMatched"
    const val PLACEMENT_QUALIFIED: String = "placementQualified"
    const val NO_LIVE_REFERENCES: String = "noLiveReferences"

    fun evaluator(
        loadEnvelopeMatched: Boolean = false,
        placementQualified: Boolean = false,
        references: LiveReferences = LiveReferences(),
    ): GuardEvaluator = GuardEvaluator.of(
        LOAD_ENVELOPE_MATCHED to loadEnvelopeMatched,
        PLACEMENT_QUALIFIED to placementQualified,
        NO_LIVE_REFERENCES to references.isZero,
    )
}

object RevisionLeaseGuardAtoms {
    const val NO_REFERENCES: String = "noReferences"

    fun evaluator(referenceCount: Int): GuardEvaluator =
        GuardEvaluator.of(NO_REFERENCES to (referenceCount == 0))
}
