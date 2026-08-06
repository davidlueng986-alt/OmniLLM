package com.omnillm.runtime

import com.omnillm.data.persistence.JobLedgerPorts
import com.omnillm.runtime.job.InMemoryJobStore
import com.omnillm.runtime.job.JobManager
import com.omnillm.runtime.job.JobStore
import com.omnillm.runtime.job.SqlDelightJobStore

/**
 * Module entry for `:runtime:job-manager` (FEAT-ADMIN).
 *
 * Recoverable long work: download / import / benchmark / delete (plus
 * diagnostic export / content report kinds). Control plane sole writer.
 *
 * Production binds [createDurableManager] with [JobLedgerPorts] from
 * [com.omnillm.data.persistence.ControlPlaneDatabase.jobs] (ADR-010).
 * [createManager] / [InMemoryJobStore] remain for unit tests only.
 */
object JobManagerModule {
    const val MODULE_PATH: String = ":runtime:job-manager"

    /** Catalog machine id for job FSM. */
    const val JOB_MACHINE_ID: String = "JOB"

    /** In-memory store (unit tests / bootstrap fixtures). Not process-crash durable. */
    fun createManager(store: JobStore = InMemoryJobStore()): JobManager =
        JobManager(store = store)

    /**
     * Process-crash durable manager (SQLDelight / SQLite via [ports]).
     * Use [ControlPlaneDatabase.jobs] from the runtime control plane attach path.
     */
    fun createDurableManager(
        ports: JobLedgerPorts,
        clock: () -> Long = { System.currentTimeMillis() },
    ): JobManager =
        JobManager(
            store = SqlDelightJobStore(ports = ports),
            clock = clock,
        )
}
