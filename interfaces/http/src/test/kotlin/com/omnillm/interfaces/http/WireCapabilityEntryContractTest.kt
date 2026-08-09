package com.omnillm.interfaces.http

import com.omnillm.core.canonical.generated.CapabilityId
import com.omnillm.core.canonical.generated.CapabilityState
import com.omnillm.core.canonical.generated.EvidenceLabel
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API-11 contract test: CapabilityEntry uses capability_id / state /
 * evidence_label / reason_code per spec (:3002-3030) — the old id/version
 * names leaked non-catalog fields and dropped the evidence label.
 */
class WireCapabilityEntryContractTest {

    @Test
    fun capabilityEntry_wireUsesCapabilityIdEvidenceLabelReasonCode() {
        val dto = CapabilityEntryDto(
            capabilityId = CapabilityId.TOOL_CALLING,
            state = CapabilityState.CONDITIONAL,
            evidenceLabel = EvidenceLabel.REPORTED,
            reasonCode = "engine-build-unsupported",
        )
        val json = HttpJson.codec.encodeToString(CapabilityEntryDto.serializer(), dto)
        assertTrue("capability_id required: $json", json.contains("\"capability_id\":\"TOOL_CALLING\""))
        assertTrue("evidence_label required: $json", json.contains("\"evidence_label\":\"REPORTED\""))
        assertTrue("reason_code must serialize: $json", json.contains("\"reason_code\":\"engine-build-unsupported\""))
        assertTrue("legacy id must NOT leak: $json", !json.contains("\"id\""))
        assertTrue("legacy version must NOT leak: $json", !json.contains("version"))
    }
}
