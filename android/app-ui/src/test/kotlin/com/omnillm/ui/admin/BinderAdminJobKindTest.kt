package com.omnillm.ui.admin

import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.job.JobKind
import com.omnillm.runtime.job.JobParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CODE-03: binder job list must project the AIDL kind, not invent
 * DIAGNOSTIC_EXPORT for every row.
 */
class BinderAdminJobKindTest {

    private fun adminWithJob(kind: String, digest: String? = "c".repeat(64)): FakeOmniAdmin {
        val job = ai.omnillm.api.OmniJobInfo().apply {
            jobId = "job-1"
            this.kind = kind
            state = "RUNNING"
            resourceVersion = 2L
            canonicalSpecDigest = digest
            progress = 0.5
        }
        val snapshot = ai.omnillm.api.OmniAdminSnapshot().apply {
            snapshotVersion = 1L
            runtimeState = "READY"
            lanState = "DISABLED"
            models = emptyArray()
            activeJobs = arrayOf(job)
            settings = ai.omnillm.api.OmniSettingsSnapshot().apply { resourceVersion = 1L }
        }
        return FakeOmniAdmin(snapshot)
    }

    @Test
    fun downloadJob_projectsDownloadKind() {
        val records = BinderAdminPorts(adminWithJob("DOWNLOAD"))
            .getSnapshot(LocalUiPrincipal.ID)
            .activeJobs
        assertEquals(1, records.size)
        assertEquals(JobKind.DOWNLOAD, records.single().identity.kind)
        assertTrue(records.single().parameters is JobParameters.Download)
        assertEquals("c".repeat(64), records.single().identity.canonicalSpecDigest)
    }

    @Test
    fun deleteJob_projectsDeleteKind() {
        val records = BinderAdminPorts(adminWithJob("DELETE"))
            .getSnapshot(LocalUiPrincipal.ID)
            .activeJobs
        assertEquals(JobKind.DELETE, records.single().identity.kind)
        assertTrue(records.single().parameters is JobParameters.Delete)
    }

    @Test
    fun unknownKind_failClosedDropped() {
        val records = BinderAdminPorts(adminWithJob("NOT_A_CATALOG_KIND"))
            .getSnapshot(LocalUiPrincipal.ID)
            .activeJobs
        assertTrue("unknown kinds must not be rewritten as DIAGNOSTIC_EXPORT", records.isEmpty())
    }
}
