package com.omnillm.features.diagnostics.domain

import com.omnillm.core.canonical.generated.EvidenceLabel

/**
 * Stable diagnostic reason model (FEAT-DIAGNOSTICS §3).
 *
 * Inferences must never be written as measured facts. Worker self-report,
 * estimated envelope, and platform observation stay separate via [EvidenceLabel].
 */
data class DiagnosticReasonModel(
    /** Stable reason code (catalog / error-aligned, not free prose). */
    val reasonCode: String,
    /** Observed facts only — each fact carries its own evidence label. */
    val observedFacts: List<ObservedFact> = emptyList(),
    /** Inferences derived from facts; never mixed into observedFacts. */
    val inferences: List<Inference> = emptyList(),
    /** Aggregate confidence in [0.0, 1.0] when any ESTIMATED material is used. */
    val confidence: Double? = null,
    val excludedAlternatives: List<String> = emptyList(),
    val suggestedSafeActions: List<String> = emptyList(),
) {
    init {
        require(reasonCode.isNotBlank()) { "reasonCode must be non-blank" }
        confidence?.let {
            require(it in 0.0..1.0) { "confidence must be in [0.0, 1.0]" }
        }
        // Inferences must not reuse fact keys that claim MEASURED when they are not.
        for (inf in inferences) {
            require(inf.evidenceLabel != EvidenceLabel.MEASURED) {
                "inference cannot claim MEASURED evidence (FEAT-DIAGNOSTICS §3)"
            }
        }
    }
}

data class ObservedFact(
    val key: String,
    val value: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val source: String? = null,
) {
    init {
        require(key.isNotBlank()) { "fact key must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) {
                "REPORTED facts must disclose source"
            }
        }
    }
}

data class Inference(
    val key: String,
    val statement: String,
    val evidenceLabel: EvidenceLabel,
    val confidence: Double? = null,
) {
    init {
        require(key.isNotBlank()) { "inference key must be non-blank" }
        require(statement.isNotBlank()) { "inference statement must be non-blank" }
        require(evidenceLabel != EvidenceLabel.MEASURED) {
            "inference cannot claim MEASURED"
        }
        confidence?.let {
            require(it in 0.0..1.0) { "confidence must be in [0.0, 1.0]" }
        }
    }
}
