package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.OmniModelPage
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.features.modelhub.api.ModelCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COR-02/TST-02 regression: listModels projection never throws and never mints
 * invented error codes ??a principal-gate failure or internal projection error
 * maps to a clean, empty page (fail closed).
 */
class OmniModelPageProjectionTest {

    private fun card(revisionHex: String, displayName: String, state: String): ModelCard =
        ModelCard(
            modelRevisionId = revisionHex,
            artifactPackageId = "b".repeat(64),
            installationId = null,
            displayName = displayName,
            installationState = state,
            loadedModelState = null,
            acquisitionChannel = "SIGNED_CATALOG",
            licenseStatus = "UNKNOWN",
            authenticityOk = null,
            compatibilityStatus = "NOT_CHECKED",
            placementClass = null,
            performanceRecorded = false,
            pinned = false,
            liveReferenceCount = 0,
        )

    private fun revisionHex(seed: Int): String =
        seed.toString(16).padStart(64, '0')

    @Test
    fun okResult_mapsBoundedPageWithCursor() {
        val cards = (1..3).map { card(revisionHex(it), "Model $it", "READY") }
        val page = OmniRuntimeFacade.buildModelPage(
            pageToken = null,
            pageSize = 2,
            snapshotVersion = 7L,
        ) { OmniResult.ok(cards) }

        assertEquals(2, page.items.size)
        assertEquals("Model 1", page.items[0].displayName)
        assertEquals("Model 2", page.items[1].displayName)
        assertEquals("2", page.nextPageToken)
        assertEquals(7L, page.snapshotVersion)
    }

    @Test
    fun pageTokenResumesFromCursor() {
        val cards = (1..5).map { card(revisionHex(it), "Model $it", "READY") }
        val page = OmniRuntimeFacade.buildModelPage(
            pageToken = "2",
            pageSize = 2,
            snapshotVersion = 1L,
        ) { OmniResult.ok(cards) }

        assertEquals(2, page.items.size)
        assertEquals("Model 3", page.items[0].displayName)
        assertEquals("Model 4", page.items[1].displayName)
        assertEquals("4", page.nextPageToken)
    }

    @Test
    fun errResult_cleanEmptyPage_noException() {
        val page = OmniRuntimeFacade.buildModelPage(
            pageToken = null,
            pageSize = 10,
            snapshotVersion = 0L,
        ) {
            OmniResult.err(com.omnillm.core.errors.generated.OmniError.FORBIDDEN(message = "models.read denied"))
        }
        assertEquals(0, page.items.size)
        assertNull(page.nextPageToken)
    }

    @Test
    fun throwingProjection_cleanEmptyPage_noException() {
        // COR-02 regression: a principal-gate IllegalArgumentException (or any
        // internal failure) inside listInstalled must NOT escape the binder call.
        val page = OmniRuntimeFacade.buildModelPage(
            pageToken = null,
            pageSize = 10,
            snapshotVersion = 0L,
        ) {
            throw IllegalArgumentException("ModelHubApi accepts LOCAL_UI principal only")
        }
        assertEquals(0, page.items.size)
        assertNull(page.nextPageToken)
    }

    @Test
    fun pageSizeIsBounded() {
        val cards = (1..100).map { card(revisionHex(it), "Model $it", "READY") }
        val page = OmniRuntimeFacade.buildModelPage(
            pageToken = null,
            pageSize = 500,
            snapshotVersion = 0L,
        ) { OmniResult.ok(cards) }
        assertTrue("pageSize must be bounded to MAX_MODEL_PAGE_SIZE", page.items.size <= 50)
        assertEquals("50", page.nextPageToken)
    }
}
