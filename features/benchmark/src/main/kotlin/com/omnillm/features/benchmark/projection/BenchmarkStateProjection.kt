package com.omnillm.features.benchmark.projection

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.benchmark.api.BenchmarkJobHandle
import com.omnillm.features.benchmark.api.BenchmarkPlan
import com.omnillm.features.benchmark.api.BenchmarkSnapshot
import com.omnillm.features.benchmark.api.MeasurementReport
import com.omnillm.features.benchmark.domain.BenchmarkScenarioTemplate
import com.omnillm.features.benchmark.domain.BenchmarkUiPhases
import com.omnillm.features.benchmark.domain.MeasurementProfile
import com.omnillm.features.benchmark.domain.MeasurementRun
import com.omnillm.features.benchmark.domain.MeasurementRunOutcomes
import com.omnillm.runtime.job.JobRecord

/**
 * Projects JOB + runs into benchmark UX phase
 * (UX-STATE-CATALOG, FEAT-BENCHMARK empty / loading / error / degraded / content).
 */
object BenchmarkStateProjection {

    private val JOB_STATES: Set<String> = StateMachines.JOB.states

    fun projectUiPhase(
        runs: List<MeasurementRun>,
        activeJob: JobRecord?,
        lastError: OmniError?,
        hasPlan: Boolean,
    ): String {
        activeJob?.let {
            require(it.state in JOB_STATES) {
                "unknown JOB state (fail closed): ${it.state}"
            }
        }

        if (lastError != null && activeJob == null &&
            runs.none { MeasurementRunOutcomes.isTerminal(it.outcome) }
        ) {
            return BenchmarkUiPhases.ERROR
        }

        if (activeJob != null) {
            when (activeJob.state) {
                "CANCELLED" -> return BenchmarkUiPhases.CANCELLED
                "FAILED" -> return BenchmarkUiPhases.ERROR
                "SUCCEEDED" -> {
                    val linked = runs.filter { it.jobId == activeJob.jobId.value }
                    val anyDegraded = linked.any {
                        it.outcome == MeasurementRunOutcomes.DEGRADED ||
                            it.outcome == MeasurementRunOutcomes.INVALID
                    }
                    val anyValid = linked.any { it.outcome == MeasurementRunOutcomes.VALID }
                    return when {
                        anyDegraded && !anyValid -> BenchmarkUiPhases.DEGRADED
                        anyValid || linked.any { MeasurementRunOutcomes.isTerminal(it.outcome) } ->
                            if (anyDegraded) BenchmarkUiPhases.DEGRADED
                            else BenchmarkUiPhases.CONTENT
                        else -> BenchmarkUiPhases.LOADING
                    }
                }
                else -> return BenchmarkUiPhases.LOADING
            }
        }

        val terminal = runs.filter { MeasurementRunOutcomes.isTerminal(it.outcome) }
        if (terminal.any { it.outcome == MeasurementRunOutcomes.CANCELLED } &&
            terminal.none { it.outcome == MeasurementRunOutcomes.VALID }
        ) {
            return BenchmarkUiPhases.CANCELLED
        }
        if (terminal.any {
                it.outcome == MeasurementRunOutcomes.FAILED ||
                    it.outcome == MeasurementRunOutcomes.INVALID
            } &&
            terminal.none { it.outcome == MeasurementRunOutcomes.VALID }
        ) {
            return if (terminal.any { it.outcome == MeasurementRunOutcomes.INVALID }) {
                BenchmarkUiPhases.DEGRADED
            } else {
                BenchmarkUiPhases.ERROR
            }
        }
        if (terminal.any { it.outcome == MeasurementRunOutcomes.DEGRADED }) {
            return BenchmarkUiPhases.DEGRADED
        }
        if (terminal.any { it.outcome == MeasurementRunOutcomes.VALID }) {
            return BenchmarkUiPhases.CONTENT
        }
        if (hasPlan) return BenchmarkUiPhases.PREVIEW
        return BenchmarkUiPhases.EMPTY
    }

    fun projectJobHandle(
        record: JobRecord,
        runId: String?,
        createdNew: Boolean = false,
    ): BenchmarkJobHandle =
        BenchmarkJobHandle(
            jobId = record.jobId.value,
            runId = runId,
            kind = record.kind.name,
            state = record.state,
            resourceVersion = record.resourceVersion,
            createdNew = createdNew,
            cancelRequested = record.cancelRequested,
            progressPhase = record.progress.currentPhase,
            sampleCount = record.progress.sampleCount,
            error = record.error,
        )

    /**
     * Current run per profile: unique max runSeq (FEAT-BENCHMARK acceptance §2).
     * Ties on timestamp must not drift selection — runSeq is the sole key.
     */
    fun currentRunsByProfile(runs: List<MeasurementRun>): Map<String, MeasurementRun> {
        val byProfile = linkedMapOf<String, MeasurementRun>()
        for (run in runs) {
            if (!MeasurementRunOutcomes.isTerminal(run.outcome) &&
                run.outcome != MeasurementRunOutcomes.RUNNING
            ) {
                continue
            }
            val existing = byProfile[run.profileId]
            if (existing == null || run.runSeq > existing.runSeq) {
                byProfile[run.profileId] = run
            } else if (run.runSeq == existing.runSeq && run.runId != existing.runId) {
                // Deterministic tie-break: lower runId wins so selection is stable.
                if (run.runId < existing.runId) {
                    byProfile[run.profileId] = run
                }
            }
        }
        return byProfile
    }

    fun projectSnapshot(
        snapshotVersion: Long,
        profiles: List<MeasurementProfile>,
        runs: List<MeasurementRun>,
        activeJob: JobRecord?,
        runIdForJob: String?,
        plan: BenchmarkPlan?,
        lastReport: MeasurementReport?,
        lastError: OmniError?,
    ): BenchmarkSnapshot {
        val phase = projectUiPhase(
            runs = runs,
            activeJob = activeJob,
            lastError = lastError,
            hasPlan = plan != null,
        )
        val degraded = runs
            .filter {
                it.outcome == MeasurementRunOutcomes.DEGRADED ||
                    it.outcome == MeasurementRunOutcomes.INVALID
            }
            .flatMap { it.deviationReasons }
            .distinct()
        return BenchmarkSnapshot(
            snapshotVersion = snapshotVersion,
            uiPhase = phase,
            plan = plan,
            activeJob = activeJob?.let { projectJobHandle(it, runIdForJob) },
            profiles = profiles,
            runs = runs.sortedWith(compareByDescending<MeasurementRun> { it.runSeq }),
            currentRunByProfile = currentRunsByProfile(runs),
            templates = BenchmarkScenarioTemplate.ALL,
            lastReport = lastReport,
            lastError = lastError,
            degradedReasons = degraded,
        )
    }

    fun uiPhaseLabelKey(phase: String): String = "benchmark.ui.phase.${phase.lowercase()}"
}
