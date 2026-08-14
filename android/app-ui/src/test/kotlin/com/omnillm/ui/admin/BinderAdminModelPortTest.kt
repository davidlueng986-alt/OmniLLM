package com.omnillm.ui.admin

import com.omnillm.features.admin.ports.EmptyAdminModelPort
import com.omnillm.features.admin.usecase.AdminFeatureApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-05 regression: the binder-path admin factory must NOT silently default to
 * [EmptyAdminModelPort] when the admin snapshot already carries real model
 * data — otherwise Admin home shows an empty model list while the plane has
 * installed models (fail-closed projections only for genuinely unavailable
 * operations, never for a live read surface).
 */
class BinderAdminModelPortTest {

    private val revisionHex = "a".repeat(64)

    private fun fakeAdmin(): FakeOmniAdmin {
        val model = ai.omnillm.api.OmniModelInfo().apply {
            modelRevisionId = revisionHex
            displayName = "SmolLM-fixture"
            installationState = "READY"
        }
        val snapshot = ai.omnillm.api.OmniAdminSnapshot().apply {
            snapshotVersion = 7L
            runtimeState = "READY"
            lanState = "DISABLED"
            models = arrayOf(model)
            activeJobs = emptyArray()
            settings = ai.omnillm.api.OmniSettingsSnapshot().apply { resourceVersion = 3L }
        }
        return FakeOmniAdmin(snapshot)
    }

    @Test
    fun binderPath_defaultModelPort_reflectsPlaneSnapshotModels() {
        val admin = fakeAdmin()
        // Production path: BinderAdminPorts.asFeaturePorts() called with NO explicit
        // model port (BinderAdminFeatureFactory.createFeatureApi) — must still project
        // the plane's model list, not the empty default.
        val ports = BinderAdminPorts(admin).asFeaturePorts()

        val summaries = ports.models.listRevisionSummaries()
        assertEquals(
            "default model port must reflect admin snapshot models (C-05)",
            listOf(revisionHex),
            summaries.map { it.modelRevisionId },
        )
        assertEquals("SmolLM-fixture", summaries.single().displayName)
        assertEquals("READY", summaries.single().installationState)

        val home = AdminFeatureApi(ports).getHome()
        assertEquals("Admin home must show the real model rows", 1, home.models.size)
        assertEquals(revisionHex, home.models.single().modelRevisionId)
    }

    @Test
    fun binderPath_explicitEmptyModelPort_remainsHonestEmpty() {
        // Explicit override must keep its fail-closed semantics (no weakening).
        val ports = BinderAdminPorts(fakeAdmin()).asFeaturePorts(models = EmptyAdminModelPort)
        assertTrue(ports.models.listRevisionSummaries().isEmpty())
    }
}
