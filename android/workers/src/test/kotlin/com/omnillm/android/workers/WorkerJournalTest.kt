package com.omnillm.android.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerJournalTest {

    @Test
    fun ringBufferEvictsOldest() {
        val buffer = WorkerJournalBuffer(capacity = 3)
        repeat(5) { i ->
            buffer.append(
                kind = WorkerJournalKind.COMMAND_ACCEPTED,
                runtimeEpoch = 1L,
                bootId = "boot",
                monotonicMs = i.toLong(),
                commitId = "c$i",
            )
        }
        val snap = buffer.snapshot()
        assertEquals(3, snap.size)
        assertEquals(2L, snap.first().sequence)
        assertEquals(4L, snap.last().sequence)
    }

    @Test
    fun findByCommitId() {
        val buffer = WorkerJournalBuffer()
        buffer.append(
            kind = WorkerJournalKind.SIDE_EFFECT_CLAIMED,
            runtimeEpoch = 1L,
            bootId = "boot",
            monotonicMs = 1L,
            commitId = "c1",
        )
        buffer.append(
            kind = WorkerJournalKind.TERMINAL_OK,
            runtimeEpoch = 1L,
            bootId = "boot",
            monotonicMs = 2L,
            commitId = "c1",
        )
        buffer.append(
            kind = WorkerJournalKind.TERMINAL_OK,
            runtimeEpoch = 1L,
            bootId = "boot",
            monotonicMs = 3L,
            commitId = "c2",
        )
        assertEquals(2, buffer.findByCommitId("c1").size)
        assertEquals(1, buffer.findByCommitId("c2").size)
        assertTrue(buffer.findByCommitId("missing").isEmpty())
    }
}
