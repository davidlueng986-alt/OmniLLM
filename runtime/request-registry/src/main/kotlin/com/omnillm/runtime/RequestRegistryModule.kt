package com.omnillm.runtime

import com.omnillm.data.persistence.ClaimLedgerPorts
import com.omnillm.data.persistence.CommitLedgerPorts
import com.omnillm.data.persistence.InMemoryClaimLedgerStore
import com.omnillm.data.persistence.InMemoryCommitLedgerStore
import com.omnillm.runtime.requestregistry.CommandLedger
import com.omnillm.runtime.requestregistry.CommitLedger
import com.omnillm.runtime.requestregistry.RequestRegistry

/**
 * Module marker and factory for `:runtime:request-registry`.
 *
 * Authority:
 * - ADR-004 / ADR-005 (client-generated ids, claim-or-return, query on reply loss)
 * - CORE-ORCHESTRATOR Request Registry
 * - DATA-OWNERSHIP / REL-RECOVERY durable ledgers
 * - specs/database/omnillm-schema.sql
 *   (`inference_requests*`, `idempotent_commands`, `commit_records`,
 *   `prepared_operations`, `commit_resource_bindings`)
 *
 * Types and states come from product `specs/` catalogs — do not invent enums.
 */
object RequestRegistryModule {
    const val MODULE_PATH: String = ":runtime:request-registry"

    /**
     * Build registry + command ledger against control-plane [ports].
     * Production binds SQLDelight DAOs ([com.omnillm.data.persistence.SqlDelightClaimLedgerStore]);
     * unit tests may use [InMemoryClaimLedgerStore].
     */
    fun create(
        ports: ClaimLedgerPorts,
        clock: () -> String = { java.time.Instant.now().toString() },
    ): Pair<RequestRegistry, CommandLedger> =
        RequestRegistry(ports, clock) to CommandLedger(ports, clock)

    /** Build commit recovery ledger (INTENT before worker). */
    fun createCommitLedger(
        ports: CommitLedgerPorts,
        clock: () -> String = { java.time.Instant.now().toString() },
    ): CommitLedger = CommitLedger(ports, clock)

    /**
     * Production / durable wiring: claim + commit ports from [ControlPlaneDatabase].
     * Prefer this over [createInMemoryWithCommits] for live RuntimeControlPlane.
     */
    fun createWithCommits(
        claims: ClaimLedgerPorts,
        commits: CommitLedgerPorts,
        clock: () -> String = { java.time.Instant.now().toString() },
    ): DurableLedgers {
        val (registry, commands) = create(claims, clock)
        return DurableLedgers(
            requestRegistry = registry,
            commandLedger = commands,
            commitLedger = createCommitLedger(commits, clock),
            claimPorts = claims,
            commitPorts = commits,
        )
    }

    /** Convenience for unit tests without a real DB. */
    fun createInMemory(
        clock: () -> String = { "2026-08-03T00:00:00Z" },
    ): Triple<RequestRegistry, CommandLedger, InMemoryClaimLedgerStore> {
        val store = InMemoryClaimLedgerStore()
        val (registry, commands) = create(store, clock)
        return Triple(registry, commands, store)
    }

    /**
     * Full in-memory control-plane claim + commit ledgers.
     * **Test-only / not process-crash durable.**
     */
    fun createInMemoryWithCommits(
        clock: () -> String = { "2026-08-03T00:00:00Z" },
    ): InMemoryLedgers {
        val claims = InMemoryClaimLedgerStore()
        val commits = InMemoryCommitLedgerStore()
        val (registry, commands) = create(claims, clock)
        return InMemoryLedgers(
            requestRegistry = registry,
            commandLedger = commands,
            commitLedger = createCommitLedger(commits, clock),
            claimStore = claims,
            commitStore = commits,
        )
    }
}

/** Bundle of durable (or injected) claim + commit ledgers. */
data class DurableLedgers(
    val requestRegistry: RequestRegistry,
    val commandLedger: CommandLedger,
    val commitLedger: CommitLedger,
    val claimPorts: ClaimLedgerPorts,
    val commitPorts: CommitLedgerPorts,
)

/** Bundle of in-memory ledgers for unit tests only. */
data class InMemoryLedgers(
    val requestRegistry: RequestRegistry,
    val commandLedger: CommandLedger,
    val commitLedger: CommitLedger,
    val claimStore: InMemoryClaimLedgerStore,
    val commitStore: InMemoryCommitLedgerStore,
)
