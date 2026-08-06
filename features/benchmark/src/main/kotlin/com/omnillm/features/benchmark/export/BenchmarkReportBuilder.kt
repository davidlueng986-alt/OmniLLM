package com.omnillm.features.benchmark.export

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.features.benchmark.api.MeasurementReport
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.features.benchmark.policy.BenchmarkAuthPolicy
import com.omnillm.runtime.observability.MetricClass

/**
 * Builds a sealed measurement report (FEAT-BENCHMARK §5).
 *
 * - Default excludes prompt/output raw text
 * - Always MetricClass.MEASUREMENT — must not be ingested as operational telemetry
 * - Includes profile, run summary, metric sketch, environment, digests
 * - QR share fields are secret-free (FEAT-LAN / SEC)
 */
object BenchmarkReportBuilder {

    fun build(
        reportId: String,
        profile: MeasurementProfile,
        runs: List<MeasurementRun>,
        includePromptOutput: Boolean,
        createdAtEpochMs: Long,
        connectionEpoch: Long = 0L,
        serverSpkiSha256: String? = null,
    ): MeasurementReport {
        require(runs.all { it.profileId == profile.profileId() }) {
            "all runs must match profile id"
        }
        // Rollup-eligible set is informational only in digests.
        val validCount = runs.count { MeasurementRunOutcomes.isRollupEligible(it.outcome) }
        val invalidCount = runs.count {
            it.outcome == MeasurementRunOutcomes.INVALID ||
                it.outcome == MeasurementRunOutcomes.DEGRADED
        }

        val digests = linkedMapOf(
            "profileId" to profile.profileId(),
            "canonicalProfile" to IdentityHashing.sha256Hex(profile.toCanonicalJson()),
            "runSet" to IdentityHashing.sha256Hex(
                runs.sortedBy { it.runSeq }
                    .joinToString("|") { "${it.runId}:${it.runSeq}:${it.outcome}" },
            ),
            "validRunCount" to validCount.toString(),
            "nonValidRunCount" to invalidCount.toString(),
        )
        runs.forEach { run ->
            run.metrics?.interTokenLatency?.sketchDigest?.let { d ->
                digests["sketch:${run.runId}"] = d
            }
        }

        // report != telemetry fence
        val metricClass = MetricClass.MEASUREMENT

        val qr = BenchmarkAuthPolicy.safeReportQrFields(
            reportId = reportId,
            serverSpkiSha256 = serverSpkiSha256,
            expiresAtEpochMs = createdAtEpochMs + DEFAULT_QR_TTL_MS,
            connectionEpoch = connectionEpoch,
        )

        return MeasurementReport(
            reportId = reportId,
            profileId = profile.profileId(),
            canonicalProfileJson = profile.toCanonicalJson(),
            runs = runs.sortedBy { it.runSeq },
            digests = digests,
            includePromptOutput = includePromptOutput,
            metricClass = metricClass,
            schemaVersion = SCHEMA_VERSION,
            createdAtEpochMs = createdAtEpochMs,
            qrShareFields = qr,
        )
    }

    /**
     * Assert report is not operational telemetry (fail closed for mis-wiring).
     */
    fun assertNotTelemetry(report: MeasurementReport) {
        require(report.metricClass == MetricClass.MEASUREMENT) {
            "measurement report must never be OPERATIONAL telemetry"
        }
        require(report.qrShareFields.keys.none { k ->
            val n = k.lowercase()
            n.contains("token") || n.contains("secret") || n.contains("verifier")
        }) {
            "report QR must not carry secrets"
        }
    }

    const val SCHEMA_VERSION: String = "1"
    const val DEFAULT_QR_TTL_MS: Long = 15 * 60 * 1000L
}
