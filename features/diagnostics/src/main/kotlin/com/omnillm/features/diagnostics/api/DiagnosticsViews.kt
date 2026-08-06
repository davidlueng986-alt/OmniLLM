package com.omnillm.features.diagnostics.api

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan
import com.omnillm.features.diagnostics.domain.DiagnosticReasonModel
import com.omnillm.features.diagnostics.domain.DiagnosticUiPhases

/**
 * Metric sample projected for diagnostics / dashboard with mandatory evidence label
 * (CORE-OBSERVABILITY, UX-STATE-CATALOG §6).
 */
data class EvidencedMetricView(
    val name: String,
    val value: Double?,
    val unit: String,
    val evidenceLabel: EvidenceLabel,
    val sampledAtEpochMs: Long,
    val source: String? = null,
    val methodVersion: String? = null,
    val dimensions: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "metric name must be non-blank" }
        require(unit.isNotBlank()) { "unit must be non-blank" }
        require(sampledAtEpochMs >= 0L) { "sampledAtEpochMs must be non-negative" }
        if (evidenceLabel == EvidenceLabel.UNKNOWN) {
            // UNKNOWN must never coerce to zero as a measured value.
        } else {
            require(value != null) {
                "non-UNKNOWN metric must carry a value (do not invent 0 for missing)"
            }
        }
        if (evidenceLabel == EvidenceLabel.REPORTED) {
            require(!source.isNullOrBlank()) { "REPORTED metrics must disclose source" }
        }
    }
}

/**
 * Job handle returned after claim-or-return create / cancel.
 */
data class DiagnosticJobHandle(
    val jobId: String,
    val bundleId: String?,
    val kind: String,
    val state: String,
    val resourceVersion: Long,
    val createdNew: Boolean,
    val cancelRequested: Boolean = false,
    val progressPhase: String? = null,
    val progressRatio: Double? = null,
    val error: OmniError? = null,
)

/**
 * Versioned redaction allowlist export for pre-export UX + bundle transparency
 * (FEAT-DIAGNOSTICS §5 — allowlist first; unknown fields excluded).
 */
data class RedactionAllowlistExport(
    val schemaVersion: String,
    val policy: String,
    val categoryCount: Int,
    val allowedFieldCount: Int,
    /** Category name → sorted field keys. */
    val fieldsByCategory: Map<String, List<String>>,
    /** Fields that must never appear in any category. */
    val neverExport: List<String>,
    /** Canonical text form suitable for bundle pathRole=redaction_allowlist. */
    val canonicalText: String,
) {
    init {
        require(schemaVersion.isNotBlank()) { "schemaVersion must be non-blank" }
        require(categoryCount >= 0) { "categoryCount must be non-negative" }
        require(allowedFieldCount >= 0) { "allowedFieldCount must be non-negative" }
        require(canonicalText.isNotBlank()) { "canonicalText must be non-blank" }
    }
}

/**
 * Full feature snapshot for the diagnostics screen.
 */
data class DiagnosticsSnapshot(
    val snapshotVersion: Long,
    val uiPhase: String,
    val exportPlan: DiagnosticExportPlan?,
    val activeJob: DiagnosticJobHandle?,
    val bundles: List<DiagnosticBundleSnapshot>,
    val metrics: List<EvidencedMetricView>,
    val serviceHealthLevel: String?,
    val serviceHealthEvidence: EvidenceLabel?,
    val degradedReasons: List<String>,
    val reason: DiagnosticReasonModel?,
    val lastError: OmniError?,
    val shareIrreversibleNoticeKey: String = "diagnostics.share.irreversible",
    /** Always present — allowlist transparency for export preview. */
    val redactionAllowlist: RedactionAllowlistExport? = null,
) {
    init {
        require(DiagnosticUiPhases.isKnown(uiPhase)) {
            "unknown diagnostics ui phase (fail closed): $uiPhase"
        }
        require(snapshotVersion >= 0L) { "snapshotVersion must be non-negative" }
    }

    val isEmpty: Boolean
        get() = uiPhase == DiagnosticUiPhases.EMPTY &&
            bundles.isEmpty() &&
            activeJob == null

    val isLoading: Boolean get() = uiPhase == DiagnosticUiPhases.LOADING
    val isDegraded: Boolean get() = uiPhase == DiagnosticUiPhases.DEGRADED
    val isError: Boolean get() = uiPhase == DiagnosticUiPhases.ERROR
}
