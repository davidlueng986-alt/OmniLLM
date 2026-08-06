package com.omnillm.data.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageLayoutTest {

    @Test
    fun quarantineSegments() {
        val segs = StorageLayout.quarantineFileRelativeSegments("job1", "attempt1", "weights")
        assertEquals(listOf("quarantine", "job1", "attempt1", "weights"), segs)
        assertEquals("quarantine/job1/attempt1/weights", StorageLayout.toHandle(segs))
    }

    @Test
    fun rejectsTraversalInRole() {
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.requireRole("../etc/passwd")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.requireRole("a/b")
        }
    }

    @Test
    fun rejectsBadTokens() {
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.requireToken("..", "jobId")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.requireToken("job/1", "jobId")
        }
    }

    @Test
    fun validatesRelativePath() {
        assertEquals("models/a.gguf", PathSafety.validateRelativePath("models/a.gguf"))
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.validateRelativePath("../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PathSafety.validateRelativePath("/abs")
        }
    }

    @Test
    fun blobSegmentsRequireDigest() {
        val hex = "a".repeat(64)
        assertEquals(
            listOf("model-store", "blobs", hex),
            StorageLayout.blobRelativeSegments(hex),
        )
        assertThrows(IllegalArgumentException::class.java) {
            StorageLayout.blobRelativeSegments("short")
        }
    }
}
