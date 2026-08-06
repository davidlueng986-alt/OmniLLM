package com.omnillm.features.diagnostics.projection

import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.diagnostics.api.EvidencedMetricView
import com.omnillm.runtime.observability.EvidenceSemantics
import com.omnillm.runtime.observability.MetricSample

/**
 * Projects metric samples into UI views with non-mixed evidence labels
 * (FEAT-DIAGNOSTICS acceptance §4, CORE-OBSERVABILITY §5).
 */
object MetricEvidenceProjection {

    fun projectAll(samples: List<MetricSample>): List<EvidencedMetricView> =
        samples.map { DiagnosticStateProjection.projectMetric(it) }

    /**
     * Whether a numeric string may be shown. UNKNOWN never presents as "0 normal".
     */
    fun displayNumberOrNull(view: EvidencedMetricView): Double? {
        if (!EvidenceSemantics.allowsNumericDisplay(view.evidenceLabel)) return null
        return view.value
    }

    fun requiresSampledAtBadge(label: EvidenceLabel): Boolean =
        EvidenceSemantics.requiresSampledAt(label)

    fun mayDriveHardAdmission(label: EvidenceLabel): Boolean =
        EvidenceSemantics.mayDriveHardAdmission(label)

    fun descriptionKey(label: EvidenceLabel): String =
        EvidenceSemantics.descriptionKey(label)

    /**
     * Fail closed: do not merge two samples of different evidence labels into
     * one "measured" number.
     */
    fun assertLabelsNotMixed(samples: List<EvidencedMetricView>, name: String) {
        val labels = samples.filter { it.name == name }.map { it.evidenceLabel }.toSet()
        require(labels.size <= 1) {
            "mixed evidence labels for metric $name: $labels (FEAT-DIAGNOSTICS §3/§4)"
        }
    }
}
