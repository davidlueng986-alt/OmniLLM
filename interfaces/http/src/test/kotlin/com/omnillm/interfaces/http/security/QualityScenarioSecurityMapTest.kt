package com.omnillm.interfaces.http.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit mapping from quality-scenario IDs to negative/integration suites.
 *
 * Authority: `specs/quality-scenarios.yaml`, `specs/ux-acceptance.yaml`,
 * SEC-THREAT, REL-RECOVERY, ANDROID-BASELINE.
 *
 * This is a documentation assertion so CI fails if the map drifts empty.
 * Actual behavioral coverage lives in the listed test classes.
 */
class QualityScenarioSecurityMapTest {

    private data class Mapping(
        val scenarioId: String,
        val statement: String,
        val testClasses: List<String>,
    )

    private val map = listOf(
        Mapping(
            scenarioId = "Q-005",
            statement = "SSE disconnect never reuses unprovable hidden session state",
            testClasses = listOf(
                "SseDisconnectClaimSemanticsTest",
                "HttpSecurityNegativeIntegrationTest",
                "PoisonedSessionNotReusedNegativeTest",
            ),
        ),
        Mapping(
            scenarioId = "Q-007",
            statement = "revocation fences active, queued and pooled state",
            testClasses = listOf(
                "AidlCallerNegativeSecurityTest",
                "RevokedTokenNegativeSecurityTest",
                "HttpSecurityNegativeIntegrationTest",
            ),
        ),
        Mapping(
            scenarioId = "Q-010",
            statement = "URL/redirect/DNS/PFD/JSON input remains bounded and fail-closed",
            testClasses = listOf(
                "HttpSecurityNegativeIntegrationTest",
                "UnknownCapabilityNegativeTest",
            ),
        ),
        Mapping(
            scenarioId = "Q-014",
            statement = "exported runtime binder cannot yield admin binder",
            testClasses = listOf(
                "AidlCallerNegativeSecurityTest",
                "RuntimeServiceStartSmokeTest",
                "RuntimeServiceInstrumentedSmokeTest",
            ),
        ),
        Mapping(
            scenarioId = "Q-016",
            statement = "engine capability is never reused outside its envelope",
            testClasses = listOf(
                "UnknownCapabilityNegativeTest",
            ),
        ),
        Mapping(
            scenarioId = "UX-FIRST-SUCCESS",
            statement = "first success journey includes one recovery path",
            testClasses = listOf(
                "RuntimeServiceStartSmokeTest",
                "RuntimeServiceInstrumentedSmokeTest",
            ),
        ),
    )

    @Test
    fun qualityScenarioMap_coversRequiredNegatives() {
        val ids = map.map { it.scenarioId }.toSet()
        assertTrue(ids.containsAll(setOf("Q-005", "Q-007", "Q-010", "Q-014")))
        for (entry in map) {
            assertTrue(entry.scenarioId.isNotBlank())
            assertTrue(entry.testClasses.isNotEmpty())
            assertTrue(entry.statement.isNotBlank())
        }
        assertEquals(6, map.size)
    }

    @Test
    fun requiredNegatives_documentedInMap() {
        val joined = map.flatMap { it.testClasses }.toSet()
        // Task-required negative surfaces.
        assertTrue(joined.any { it.contains("AidlCaller") })
        assertTrue(joined.any { it.contains("RevokedToken") || it.contains("HttpSecurity") })
        assertTrue(joined.any { it.contains("HttpSecurity") }) // oversize JSON
        assertTrue(joined.any { it.contains("UnknownCapability") })
        assertTrue(joined.any { it.contains("PoisonedSession") })
        assertTrue(joined.any { it.contains("SseDisconnect") })
    }
}
