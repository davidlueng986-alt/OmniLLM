package com.omnillm.features.lan.domain

/**
 * Response field allowlist for LAN principals (FEAT-LAN §4, SEC-AUTH-NET §7).
 *
 * Infer-only clients must not see private paths, full driver dumps, other clients'
 * activity, token metadata, or raw crash detail.
 */
object ResponseFieldAllowlist {

    /** Fields always safe for any authenticated LAN client with inference scopes. */
    val INFERENCE_BASE: Set<String> = setOf(
        "request_id",
        "state",
        "error",
        "created_at",
        "finished_at",
        "model_revision_id",
        "engine_build_id",
        "backend",
        "output_text",
        "usage",
    )

    val MODELS_READ: Set<String> = setOf(
        "model_id",
        "model_revision_id",
        "display_name",
        "capabilities",
        "trust_class",
        "installation_state",
    )

    val METRICS_SUMMARY: Set<String> = setOf(
        "name",
        "value",
        "unit",
        "evidence_label",
        "sampled_at",
    )

    /** Never exposed on LAN regardless of scope. */
    val NEVER_ON_LAN: Set<String> = setOf(
        "private_path",
        "absolute_path",
        "driver_dump",
        "full_driver_dump",
        "token_plaintext",
        "token_hmac",
        "token_metadata",
        "other_client_activity",
        "raw_crash_detail",
        "stack_trace",
        "keystore_alias",
        "pairing_secret",
        "tls_private_key",
    )

    fun fieldsForScopes(scopes: Collection<String>): Set<String> {
        val out = linkedSetOf<String>()
        out += INFERENCE_BASE
        if ("models.read" in scopes) out += MODELS_READ
        // metrics.read-summary is not LanGrantable; keep empty unless ever granted via admin profile mistake — still never detail dumps.
        if ("metrics.read-summary" in scopes) out += METRICS_SUMMARY
        return out
    }

    /**
     * Filter a response map. Unknown sensitive fields fail closed (dropped).
     * [NEVER_ON_LAN] always removed.
     */
    fun filter(scopes: Collection<String>, fields: Map<String, String>): Map<String, String> {
        val allow = fieldsForScopes(scopes)
        return fields.filterKeys { key ->
            key !in NEVER_ON_LAN && key in allow
        }
    }

    fun isForbidden(field: String): Boolean = field in NEVER_ON_LAN
}
