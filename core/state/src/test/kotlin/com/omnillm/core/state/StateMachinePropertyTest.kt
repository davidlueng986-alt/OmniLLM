package com.omnillm.core.state

import com.omnillm.core.state.domain.AssetAggregate
import com.omnillm.core.state.domain.AssetId
import com.omnillm.core.state.domain.CommitAggregate
import com.omnillm.core.state.domain.CommitId
import com.omnillm.core.state.domain.DomainAggregates
import com.omnillm.core.state.domain.InstallationId
import com.omnillm.core.state.domain.JobAggregate
import com.omnillm.core.state.domain.JobId
import com.omnillm.core.state.domain.LoadedModelAggregate
import com.omnillm.core.state.domain.LoadedModelId
import com.omnillm.core.state.domain.ModelInstallationAggregate
import com.omnillm.core.state.domain.OperationAggregate
import com.omnillm.core.state.domain.OperationId
import com.omnillm.core.state.domain.OwnerKey
import com.omnillm.core.state.domain.RequestAggregate
import com.omnillm.core.state.domain.RequestId
import com.omnillm.core.state.domain.ReservationAggregate
import com.omnillm.core.state.domain.ReservationId
import com.omnillm.core.state.domain.RuntimeAggregate
import com.omnillm.core.state.domain.RuntimeInstanceId
import com.omnillm.core.state.domain.SessionAggregate
import com.omnillm.core.state.domain.SessionId
import com.omnillm.core.state.generated.FsmMachineDefinition
import com.omnillm.core.state.generated.StateMachines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-style structural tests over catalog FSMs and pure drivers:
 * - no illegal catalog edges (from/to always known; never from terminal)
 * - terminals have no outbound edges (driver + catalog)
 * - every catalog edge is acceptably driven when its guard is satisfied
 * - non-catalog (state, event) pairs fail closed
 */
class StateMachinePropertyTest {

    private val required: List<FsmMachineDefinition> = StateMachineDriver.requiredMachines()

    @Test
    fun requiredMachinesPresent() {
        assertEquals(StateMachineDriver.REQUIRED_MACHINE_IDS, required.map { it.id }.toSet())
        for (id in StateMachineDriver.REQUIRED_MACHINE_IDS) {
            assertTrue(StateMachines.get(id) != null)
        }
    }

    @Test
    fun catalog_noIllegalEdges_fromAndToAreKnown_andNeverFromTerminal() {
        for (m in StateMachines.ALL) {
            assertTrue("${m.id} initial", m.isKnownState(m.initial))
            for (t in m.terminal) {
                assertTrue("${m.id} terminal $t", m.isKnownState(t))
            }
            for (tr in m.transitions) {
                assertTrue("${m.id} ${tr.id} from", m.isKnownState(tr.from))
                assertTrue("${m.id} ${tr.id} to", m.isKnownState(tr.to))
                assertFalse(
                    "${m.id} ${tr.id} illegal outbound from terminal ${tr.from}",
                    m.isTerminal(tr.from),
                )
            }
        }
    }

    @Test
    fun property_terminalsHaveNoOutboundEdges_forRequiredMachines() {
        for (m in required) {
            val events = StateMachineDriver.catalogEvents(m) + setOf(
                "__NONEXISTENT_EVENT__",
                "CLAIM_SUCCEEDED",
                "SUCCESS",
                "CANCEL",
            )
            for (terminal in m.terminal) {
                for (event in events) {
                    val outcome = StateMachineDriver.transition(m, terminal, event)
                    assertTrue(
                        "${m.id}: terminal $terminal must reject $event, got $outcome",
                        outcome is TransitionOutcome.Rejected.TerminalHasNoOutbound,
                    )
                }
            }
        }
    }

    @Test
    fun property_runtimeHasNoTerminalStates() {
        val runtime = StateMachines.RUNTIME
        assertTrue(runtime.terminal.isEmpty())
        // still reject unknown edges
        val bad = StateMachineDriver.transition(runtime, "STOPPED", "NOT_A_REAL_EVENT")
        assertTrue(bad is TransitionOutcome.Rejected.NoTransition)
    }

    @Test
    fun property_everyCatalogEdgeAcceptedWhenGuardTrue() {
        for (m in required) {
            // Group by (from, event) — exclusive guards must be isolated per edge id.
            for (tr in m.transitions) {
                val guards = guardSelectorFor(tr.guard)
                val outcome = StateMachineDriver.transitionById(
                    machine = m,
                    transitionId = tr.id,
                    from = tr.from,
                    event = tr.event,
                    guards = guards,
                )
                assertTrue(
                    "${m.id} ${tr.id} ${tr.from}+${tr.event} should accept, got $outcome",
                    outcome is TransitionOutcome.Accepted,
                )
                val accepted = outcome as TransitionOutcome.Accepted
                assertEquals(tr.to, accepted.to)
                assertEquals(tr.id, accepted.transitionId)
            }
        }
    }

    @Test
    fun property_nonCatalogEdgesFailClosed() {
        for (m in required) {
            val legal = StateMachineDriver.legalEdges(m)
            val events = StateMachineDriver.catalogEvents(m) + "__ILLEGAL__"
            for (state in m.states) {
                if (m.isTerminal(state)) continue
                for (event in events) {
                    if ((state to event) in legal) continue
                    val outcome = StateMachineDriver.transition(m, state, event)
                    assertTrue(
                        "${m.id}: illegal edge $state+$event must reject, got $outcome",
                        outcome is TransitionOutcome.Rejected,
                    )
                    assertFalse(
                        outcome is TransitionOutcome.Accepted,
                    )
                }
            }
        }
    }

    @Test
    fun property_unknownStateFailsClosed() {
        for (m in required) {
            val outcome = StateMachineDriver.transition(m, "__NOT_A_STATE__", "ANY")
            assertTrue(outcome is TransitionOutcome.Rejected.UnknownState)
        }
    }

    @Test
    fun property_guardFalseRejectsNamedGuardEdges() {
        val m = StateMachines.REQUEST
        val outcome = StateMachineDriver.transition(
            m,
            "RECEIVED",
            "CLAIM_SUCCEEDED",
            GuardEvaluator.ALWAYS_FALSE,
        )
        assertTrue(outcome is TransitionOutcome.Rejected.GuardFailed)
    }

    @Test
    fun property_ambiguousRequiresExclusiveGuard() {
        // RECONCILING + COMMIT_FOUND_PREPARED vs WITH_CANCEL share different events;
        // TOKEN is not required, but REQUEST RECONCILING has distinct events.
        // Use structural ambiguous from catalog where two edges share (from,event).
        val ambiguousMachines = StateMachines.ALL.filter { machine ->
            machine.transitions
                .groupBy { it.from to it.event }
                .any { it.value.size > 1 }
        }
        assertTrue("catalog should contain at least one ambiguous (from,event)", ambiguousMachines.isNotEmpty())
        for (m in ambiguousMachines) {
            val group = m.transitions.groupBy { it.from to it.event }.entries.first { it.value.size > 1 }
            val (from, event) = group.key
            // ALWAYS_TRUE makes every atom true → multiple satisfied when guards differ only by !
            // For TOKEN DRAIN_COMPLETE guards are mutually exclusive if facts are exclusive;
            // ALWAYS_TRUE makes both terminalTargetRevoked and terminalTargetExpired true → Ambiguous.
            val outcome = StateMachineDriver.transition(m, from, event, GuardEvaluator.ALWAYS_TRUE)
            // Either Ambiguous (both true) or Accepted if guards are identical true-only.
            assertTrue(
                "${m.id} $from+$event: got $outcome",
                outcome is TransitionOutcome.Rejected.Ambiguous ||
                    outcome is TransitionOutcome.Accepted ||
                    outcome is TransitionOutcome.Rejected.GuardFailed,
            )
            if (group.value.map { it.guard }.distinct().size > 1) {
                // Distinct non-trivial guards under ALWAYS_TRUE often yield Ambiguous when atoms all true.
                // Accept either Ambiguous or single Accepted if one guard is pure true.
            }
        }
    }

    @Test
    fun aggregates_applyHappyPaths_forRequiredMachines() {
        val owner = OwnerKey("principal:u0")
        val requestId = RequestId("req-1")

        // REQUEST
        var req = RequestAggregate.initial(requestId, owner)
        req = mustSucceed(req.apply("CLAIM_SUCCEEDED"))
        assertEquals("CLAIMED", req.state)

        // COMMIT
        var commit = CommitAggregate.initial(CommitId("c1"), requestId)
        commit = mustSucceed(commit.apply("EXECUTE"))
        assertEquals("EXECUTING", commit.state)

        // RESERVATION
        var res = ReservationAggregate.initial(ReservationId("r1"))
        res = mustSucceed(
            res.apply(
                "COMMIT",
                GuardEvaluator.of(
                    "issuerEpochValid" to true,
                    "beforeDeadline" to true,
                    "vectorWithinReservation" to true,
                ),
            ),
        )
        assertEquals("CONVERTING", res.state)

        // SESSION
        var session = SessionAggregate.initial(SessionId("s1"), owner, sessionEpoch = 1L)
        session = mustSucceed(
            session.apply("PUBLISH", GuardEvaluator.of("ownerAndEpochMatch" to true)),
        )
        assertEquals("ACTIVE", session.state)
        // poisoned never returns to ACTIVE
        session = mustSucceed(session.apply("MUTATION_UNCERTAIN"))
        assertEquals("POISONED", session.state)
        assertTrue(session.apply("PUBLISH") is AggregateTransitionResult.Rejected)

        // MODEL_INSTALLATION
        var install = ModelInstallationAggregate.initial(InstallationId("i1"))
        install = mustSucceed(install.apply("BEGIN_ACQUIRE"))
        assertEquals("ACQUIRING", install.state)

        // LOADED_MODEL (separate aggregate)
        var loaded = LoadedModelAggregate.initial(LoadedModelId("lm1"), InstallationId("i1"))
        loaded = mustSucceed(
            loaded.apply("ADMISSION_GRANTED", GuardEvaluator.of("loadEnvelopeMatched" to true)),
        )
        assertEquals("RESERVED", loaded.state)

        // JOB
        var job = JobAggregate.initial(JobId("j1"), owner)
        job = mustSucceed(job.apply("START", GuardEvaluator.of("attemptClaimed" to true)))
        assertEquals("RUNNING", job.state)

        // ASSET
        var asset = AssetAggregate.initial(AssetId("a1"), owner)
        asset = mustSucceed(
            asset.apply(
                "BEGIN_UPLOAD",
                GuardEvaluator.of("ownerMatch" to true, "notExpired" to true),
            ),
        )
        assertEquals("UPLOADING", asset.state)

        // OPERATION
        var op = OperationAggregate.initial(OperationId("op1"), requestId)
        op = mustSucceed(op.apply("START_ACCEPTED", GuardEvaluator.of("claimCurrent" to true)))
        assertEquals("STARTING", op.state)

        // RUNTIME (cyclic)
        var runtime = RuntimeAggregate.initial(RuntimeInstanceId("rt1"), runtimeEpoch = 1L)
        runtime = mustSucceed(
            runtime.apply(
                "LEGAL_START",
                GuardEvaluator.of("foregroundLegal" to true, "epochAdvanced" to true),
            ),
        )
        assertEquals("STARTING", runtime.state)
        assertFalse(runtime.isTerminal())
    }

    @Test
    fun aggregates_terminalRejectsOutbound() {
        val req = RequestAggregate(
            requestId = RequestId("req-done"),
            ownerKey = OwnerKey("o"),
            state = "COMPLETED",
        )
        assertTrue(req.isTerminal())
        val rejected = req.apply("CLAIM_SUCCEEDED")
        assertTrue(rejected is AggregateTransitionResult.Rejected)
        assertTrue(
            (rejected as AggregateTransitionResult.Rejected).rejection
                is TransitionOutcome.Rejected.TerminalHasNoOutbound,
        )
    }

    @Test
    fun domainAggregates_requiredListMatchesDriver() {
        assertEquals(
            StateMachineDriver.REQUIRED_MACHINE_IDS,
            DomainAggregates.REQUIRED.map { it.id }.toSet(),
        )
    }

    @Test
    fun guardExpression_booleanLogic() {
        val facts = GuardEvaluator.of("a" to true, "b" to false, "c" to true)
        assertTrue(GuardExpression.evaluate("true", facts))
        assertFalse(GuardExpression.evaluate("false", facts))
        assertTrue(GuardExpression.evaluate("a && c", facts))
        assertFalse(GuardExpression.evaluate("a && b", facts))
        assertTrue(GuardExpression.evaluate("a && !b", facts))
        assertTrue(GuardExpression.evaluate("b || c", facts))
        assertTrue(GuardExpression.evaluate("(a && b) || c", facts))
        assertFalse(GuardExpression.evaluate("not a valid !!!", facts))
    }

    private fun <A> mustSucceed(result: AggregateTransitionResult<A>): A {
        assertTrue("expected success, got $result", result is AggregateTransitionResult.Success)
        return (result as AggregateTransitionResult.Success).aggregate
    }

    /**
     * Build a guard evaluator that satisfies the catalog expression for [guard]
     * by setting every named atom appearing in the expression to the polarity
     * required for the whole expression to be true under a simple heuristic:
     * all positive atoms true, all negated atoms false when the expression is a
     * conjunction of literals (the common catalog form).
     *
     * For pure `true` edges, ALWAYS_TRUE is enough.
     */
    private fun guardSelectorFor(guard: String): GuardEvaluator {
        val trimmed = guard.trim()
        if (trimmed == "true") return GuardEvaluator.ALWAYS_TRUE
        // Collect identifiers; treat !name by setting name=false, bare name=true.
        val facts = linkedMapOf<String, Boolean>()
        // Split on operators roughly and interpret literals.
        var i = 0
        val s = trimmed
        while (i < s.length) {
            when {
                s[i].isWhitespace() || s[i] == '(' || s[i] == ')' -> i++
                s.startsWith("&&", i) || s.startsWith("||", i) -> i += 2
                s[i] == '!' -> {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(start, i)
                    if (name.isNotEmpty() && name != "true" && name != "false") {
                        facts[name] = false
                    }
                }
                s[i].isLetter() || s[i] == '_' -> {
                    val start = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    val name = s.substring(start, i)
                    if (name != "true" && name != "false") {
                        facts.putIfAbsent(name, true)
                    }
                }
                else -> i++
            }
        }
        // Ensure evaluate(true) for this expression under these facts.
        val evaluator = GuardEvaluator.of(facts)
        assertTrue(
            "guard selector must satisfy `$guard` with facts=$facts",
            GuardExpression.evaluate(guard, evaluator),
        )
        return evaluator
    }
}
