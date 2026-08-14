package com.omnillm.runtime.orchestrator

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.ResourceVector
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.runtime.OrchestratorModule
import com.omnillm.runtime.RequestRegistryModule
import com.omnillm.runtime.governor.ResourceGovernor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * C-01 (GA hardening): production wiring must hand the Orchestrator a durable
 * [com.omnillm.runtime.requestregistry.CommitLedger].
 *
 * [OrchestratorModule.create] is the entry WaveAWiring calls — it must accept
 * and forward `commitLedger` so INTENT_RECORDED / outcome rows are durable
 * before worker side effects and survive a control-plane restart
 * (REL-RECOVERY RR-001..RR-007).
 *
 * RED on current code: the module drops the ledger (no parameter) — this test
 * does not compile, proving the production wiring gap.
 */
class OrchestratorCommitLedgerDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = { "2026-08-10T00:00:00Z" }

    private fun governor(): ResourceGovernor =
        ResourceGovernor(
            capacity = ResourceVector(cpuAnonBytes = 100_000L, nativeThreads = 32L),
            safetyMargin = ResourceVector(cpuAnonBytes = 100L, nativeThreads = 1L),
            issuerBootId = "boot-c01",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
            idSource = { UUID.randomUUID().toString() },
        )

    @Test
    fun fullCommitPath_intentSurvivesOrchestratorRestart() = runBlocking {
        val file = tmp.newFile("orchestrator-commit-durability.db")
        val engine = FakeInferenceEngine()
        val req = orchestrationRequest(deadline = 10_000L)

        // "First process": SQLite-backed claim+commit ledgers + module-wired orchestrator.
        val db1 = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        val ledgers1 = RequestRegistryModule.createWithCommits(
            claims = db1.claims,
            commits = db1.commits,
            clock = clock,
        )
        val orchestrator1 = OrchestratorModule.create(
            registry = ledgers1.requestRegistry,
            governor = governor(),
            engine = engine,
            capabilities = AllSupportedCapabilities(),
            issuerBootId = "boot-c01",
            runtimeEpoch = 1L,
            clockMonotonic = { 1_000L },
            commitLedger = ledgers1.commitLedger,
        )

        val submitted = orchestrator1.submit(req)
        assertTrue(submitted is OmniResult.Ok)
        val terminal = orchestrator1.pumpAll(maxSteps = 64).lastOrNull()
        assertNotNull(terminal)
        assertTrue(terminal!! is OmniResult.Ok)
        assertEquals("COMPLETED", (terminal as OmniResult.Ok).value.state)

        val commitId = engine.lastCommit!!.commitId
        val row = ledgers1.commitLedger.queryCommit(commitId)
        assertNotNull(row)
        assertEquals("RESULT_RECORDED", row!!.state)
        db1.close()

        // "Restart": fresh DB handle + ledgers on the same file — durable rows
        // must remain queryable (never blind re-execute).
        val db2 = ControlPlaneDatabase.openJdbcFile(file, clock = clock)
        val ledgers2 = RequestRegistryModule.createWithCommits(
            claims = db2.claims,
            commits = db2.commits,
            clock = clock,
        )
        val after = ledgers2.commitLedger.queryCommit(commitId)
        assertNotNull("durable intent must survive orchestrator restart", after)
        assertEquals("RESULT_RECORDED", after!!.state)
        db2.close()
    }
}
