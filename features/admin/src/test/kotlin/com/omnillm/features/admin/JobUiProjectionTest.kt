package com.omnillm.features.admin

import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.state.domain.JobId
import com.omnillm.core.state.generated.StateMachines
import com.omnillm.features.admin.projection.JobUiAction
import com.omnillm.features.admin.projection.JobUiProjection
import com.omnillm.features.admin.projection.UiSeverity
import com.omnillm.runtime.job.JobIdentity
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import com.omnillm.runtime.job.JobProgress
import com.omnillm.runtime.job.JobRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure FSM → UX projection tests (no control-plane I/O).
 */
class JobUiProjectionTest {

    private val digest = "d".repeat(64)

    private fun record(state: String, cancelRequested: Boolean = false): JobRecord =
        JobRecord(
            identity = JobIdentity(
                jobId = JobId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                principalId = PrincipalId.parse("LOCAL_UI"),
                kind = JobKind.DOWNLOAD,
                idempotencyKey = IdempotencyKey.parse("proj-1"),
                canonicalSpecDigest = digest,
            ),
            parameters = JobParameters.Download(sourceUrl = "https://example.com/m"),
            state = state,
            resourceVersion = 1L,
            currentAttemptNo = if (state == "QUEUED") null else 1,
            attempts = emptyList(),
            checkpoint = null,
            progress = JobProgress(),
            pauseReason = com.omnillm.runtime.job.JobPauseReason.fromState(state),
            error = null,
            events = emptyList(),
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
            cancelRequested = cancelRequested,
        )

    @Test
    fun everyCatalogState_isKnown_and_projectsLabel() {
        for (state in StateMachines.JOB.states) {
            val ui = JobUiProjection.projectListItem(record(state))
            assertEquals(state, ui.state)
            assertTrue("label for $state", ui.labelKey.startsWith("job."))
            assertFalse("no unknown for $state", ui.labelKey == "job.unknown-state")
        }
    }

    @Test
    fun terminalStates_noCancel() {
        for (state in StateMachines.JOB.terminal) {
            val ui = JobUiProjection.projectListItem(record(state))
            assertFalse(JobUiAction.CANCEL in ui.allowedActions)
            assertTrue(JobUiProjection.isTerminal(state))
        }
    }

    @Test
    fun pausedStates_allowCancel() {
        for (state in listOf(
            "PAUSED_WAITING_INPUT",
            "PAUSED_WAITING_NETWORK",
            "PAUSED_WAITING_FOREGROUND",
        )) {
            val ui = JobUiProjection.projectListItem(record(state))
            assertTrue(state, JobUiAction.CANCEL in ui.allowedActions)
            assertEquals(UiSeverity.WARNING, ui.severity)
        }
    }

    @Test
    fun runningCancelRequested_showsWaitSafeStop() {
        val ui = JobUiProjection.projectListItem(record("RUNNING", cancelRequested = true))
        assertTrue(JobUiAction.WAIT_SAFE_STOP in ui.allowedActions)
        assertFalse(JobUiAction.CANCEL in ui.allowedActions)
    }

    @Test
    fun unknownTotal_progressRatioNull() {
        val detail = JobUiProjection.projectDetail(record("RUNNING"))
        assertNull(detail.list.progressRatio)
        assertNull(detail.totalBytesKnown)
    }

    @Test
    fun unknownState_failClosed() {
        val ui = JobUiProjection.projectListItem(record("QUEUED").copy(state = "NOT_IN_CATALOG"))
        // JobRecord itself may not validate state; projection must fail closed.
        assertEquals("job.unknown-state", ui.labelKey)
        assertEquals(UiSeverity.ERROR, ui.severity)
        assertTrue(ui.allowedActions.isEmpty())
    }
}
