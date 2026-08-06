package com.omnillm.features.contentreport.domain

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.ContentReportCategory
import com.omnillm.core.canonical.generated.ContentReportState
import com.omnillm.core.errors.generated.OmniError
import java.nio.charset.StandardCharsets

/**
 * Canonical [ContentReportPayload] (specs/canonical-types.yaml).
 *
 * Optional excerpts are **user-selected only** — never auto-filled from full
 * prompt/output. Free-text fields are capped at 4096 UTF-8 bytes.
 *
 * Report is pinned to exact [modelRevisionId] + [engineBuildId]; silent
 * cross-revision rewrite is forbidden (routing / INV identity).
 */
data class ContentReportPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val reportId: String,
    val category: ContentReportCategory,
    val createdAt: String,
    val appBuild: String,
    val modelRevisionId: String,
    val engineBuildId: String,
    val backend: String,
    val localPolicyVersion: String,
    val outputDigest: String,
    val userLocale: String,
    val description: String? = null,
    val promptExcerpt: String? = null,
    val outputExcerpt: String? = null,
    val diagnosticSummary: String? = null,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "schemaVersion must be $SCHEMA_VERSION"
        }
        require(reportId.isNotBlank()) { "reportId must be non-blank" }
        require(appBuild.isNotBlank()) { "appBuild must be non-blank" }
        require(HEX64.matches(modelRevisionId)) {
            "modelRevisionId must be lower-case 64-char hex"
        }
        require(engineBuildId.isNotBlank()) { "engineBuildId must be non-blank" }
        require(backend.isNotBlank()) { "backend must be non-blank" }
        require(localPolicyVersion.isNotBlank()) { "localPolicyVersion must be non-blank" }
        require(HEX64.matches(outputDigest)) {
            "outputDigest must be lower-case 64-char hex"
        }
        require(userLocale.isNotBlank()) { "userLocale must be non-blank" }
        requireUtf8Cap(description, "description")
        requireUtf8Cap(promptExcerpt, "promptExcerpt")
        requireUtf8Cap(outputExcerpt, "outputExcerpt")
        requireUtf8Cap(diagnosticSummary, "diagnosticSummary")
    }

    /** RFC8785-style compact JSON of required + present optional fields (sorted keys). */
    fun toCanonicalJson(): String {
        val fields = linkedMapOf<String, String>()
        fields["schemaVersion"] = schemaVersion.toString()
        fields["reportId"] = reportId
        fields["category"] = category.name
        fields["createdAt"] = createdAt
        fields["appBuild"] = appBuild
        fields["modelRevisionId"] = modelRevisionId
        fields["engineBuildId"] = engineBuildId
        fields["backend"] = backend
        fields["localPolicyVersion"] = localPolicyVersion
        fields["outputDigest"] = outputDigest
        fields["userLocale"] = userLocale
        description?.let { fields["description"] = it }
        promptExcerpt?.let { fields["promptExcerpt"] = it }
        outputExcerpt?.let { fields["outputExcerpt"] = it }
        diagnosticSummary?.let { fields["diagnosticSummary"] = it }
        // Deterministic key order for digest stability.
        val ordered = fields.toSortedMap()
        return buildString {
            append('{')
            ordered.entries.forEachIndexed { index, (k, v) ->
                if (index > 0) append(',')
                append('"').append(escapeJson(k)).append('"').append(':')
                if (k == "schemaVersion") {
                    append(v)
                } else {
                    append('"').append(escapeJson(v)).append('"')
                }
            }
            append('}')
        }
    }

    fun canonicalDigestHex(): String =
        IdentityHashing.contentReportPayloadDigest(toCanonicalJson()).hex

    /** Preview rows shown before consent (FEAT-AI-CONTENT-REPORT §2). */
    fun previewFields(includeOptionalExcerpts: Boolean = true): List<PayloadFieldPreview> {
        val required = listOf(
            PayloadFieldPreview("reportId", reportId, required = true, sensitive = false),
            PayloadFieldPreview("category", category.name, required = true, sensitive = false),
            PayloadFieldPreview("createdAt", createdAt, required = true, sensitive = false),
            PayloadFieldPreview("appBuild", appBuild, required = true, sensitive = false),
            PayloadFieldPreview("modelRevisionId", modelRevisionId, required = true, sensitive = false),
            PayloadFieldPreview("engineBuildId", engineBuildId, required = true, sensitive = false),
            PayloadFieldPreview("backend", backend, required = true, sensitive = false),
            PayloadFieldPreview("localPolicyVersion", localPolicyVersion, required = true, sensitive = false),
            PayloadFieldPreview("outputDigest", outputDigest, required = true, sensitive = false),
            PayloadFieldPreview("userLocale", userLocale, required = true, sensitive = false),
        )
        if (!includeOptionalExcerpts) return required
        val optional = buildList {
            description?.let {
                add(PayloadFieldPreview("description", it, required = false, sensitive = true))
            }
            promptExcerpt?.let {
                add(PayloadFieldPreview("promptExcerpt", it, required = false, sensitive = true))
            }
            outputExcerpt?.let {
                add(PayloadFieldPreview("outputExcerpt", it, required = false, sensitive = true))
            }
            diagnosticSummary?.let {
                add(
                    PayloadFieldPreview(
                        "diagnosticSummary",
                        it,
                        required = false,
                        sensitive = true,
                    ),
                )
            }
        }
        return required + optional
    }

    /** Default payload excludes full prompt/output — only digests + metadata. */
    fun isMinimizedDefault(): Boolean =
        promptExcerpt == null && outputExcerpt == null && description == null

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val MAX_UTF8_BYTES: Int = 4096
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parseCategory(raw: String): ContentReportCategory? =
            ContentReportCategory.fromCatalogName(raw.trim())

        /**
         * Inverse of [toCanonicalJson] for durable blob rehydrate (control-plane store).
         * Fail closed on missing required fields or unknown category.
         */
        fun fromCanonicalJson(json: String): ContentReportPayload {
            val fields = parseCanonicalObject(json)
            val categoryName = fields["category"]
                ?: error("content report payload missing category")
            val category = ContentReportCategory.fromCatalogName(categoryName)
                ?: error("unknown content report category: $categoryName")
            return ContentReportPayload(
                schemaVersion = fields["schemaVersion"]?.toIntOrNull() ?: SCHEMA_VERSION,
                reportId = fields["reportId"] ?: error("missing reportId"),
                category = category,
                createdAt = fields["createdAt"] ?: error("missing createdAt"),
                appBuild = fields["appBuild"] ?: error("missing appBuild"),
                modelRevisionId = (fields["modelRevisionId"] ?: error("missing modelRevisionId"))
                    .lowercase(),
                engineBuildId = fields["engineBuildId"] ?: error("missing engineBuildId"),
                backend = fields["backend"] ?: error("missing backend"),
                localPolicyVersion = fields["localPolicyVersion"]
                    ?: error("missing localPolicyVersion"),
                outputDigest = (fields["outputDigest"] ?: error("missing outputDigest")).lowercase(),
                userLocale = fields["userLocale"] ?: error("missing userLocale"),
                description = fields["description"],
                promptExcerpt = fields["promptExcerpt"],
                outputExcerpt = fields["outputExcerpt"],
                diagnosticSummary = fields["diagnosticSummary"],
            )
        }

        /**
         * Minimal parser for our compact sorted-key JSON object (string values + int schemaVersion).
         */
        private fun parseCanonicalObject(json: String): Map<String, String> {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "content report payload must be a JSON object"
            }
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            if (body.isEmpty()) return emptyMap()
            val out = linkedMapOf<String, String>()
            var i = 0
            fun skipWs() {
                while (i < body.length && body[i].isWhitespace()) i++
            }
            fun readString(): String {
                require(i < body.length && body[i] == '"') { "expected string" }
                i++
                val sb = StringBuilder()
                while (i < body.length) {
                    val c = body[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            require(i < body.length) { "truncated escape" }
                            when (val e = body[i++]) {
                                '"', '\\', '/' -> sb.append(e)
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    require(i + 4 <= body.length) { "bad unicode escape" }
                                    val hex = body.substring(i, i + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                                else -> sb.append(e)
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                error("unterminated string")
            }
            while (i < body.length) {
                skipWs()
                if (i >= body.length) break
                val key = readString()
                skipWs()
                require(i < body.length && body[i] == ':') { "expected ':'" }
                i++
                skipWs()
                val value = when {
                    i < body.length && body[i] == '"' -> readString()
                    else -> {
                        val start = i
                        while (i < body.length && body[i] != ',' && !body[i].isWhitespace()) i++
                        body.substring(start, i)
                    }
                }
                out[key] = value
                skipWs()
                if (i < body.length && body[i] == ',') {
                    i++
                }
            }
            return out
        }

        private fun requireUtf8Cap(value: String?, label: String) {
            if (value == null) return
            val bytes = value.toByteArray(StandardCharsets.UTF_8).size
            require(bytes <= MAX_UTF8_BYTES) {
                "$label exceeds $MAX_UTF8_BYTES UTF-8 bytes (got $bytes)"
            }
        }

        private fun escapeJson(s: String): String =
            buildString(s.length + 8) {
                for (ch in s) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (ch.code < 0x20) {
                                append("\\u").append("%04x".format(ch.code))
                            } else {
                                append(ch)
                            }
                        }
                    }
                }
            }
    }
}

data class PayloadFieldPreview(
    val fieldName: String,
    val displayValue: String,
    val required: Boolean,
    val sensitive: Boolean,
)

/**
 * One-time [ConsentGrant] issued only after trusted local UI review
 * (specs/canonical-types.yaml, non-exported admin AIDL).
 */
data class ConsentGrant(
    val grantId: String,
    val principalId: String,
    val reportId: String,
    val canonicalPayloadDigest: String,
    val warningPolicyVersion: String,
    val localUserProfileId: String,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val nonce: String,
    val state: ConsentGrantState = ConsentGrantState.ISSUED,
    val consumedAtEpochMs: Long? = null,
) {
    init {
        require(grantId.isNotBlank()) { "grantId must be non-blank" }
        require(principalId.isNotBlank()) { "principalId must be non-blank" }
        require(reportId.isNotBlank()) { "reportId must be non-blank" }
        require(HEX64.matches(canonicalPayloadDigest)) {
            "canonicalPayloadDigest must be lower-case 64-char hex"
        }
        require(warningPolicyVersion.isNotBlank()) { "warningPolicyVersion must be non-blank" }
        require(localUserProfileId.isNotBlank()) { "localUserProfileId must be non-blank" }
        require(nonce.isNotBlank()) { "nonce must be non-blank" }
        require(expiresAtEpochMs > issuedAtEpochMs) { "expiresAt must be after issuedAt" }
        if (state == ConsentGrantState.CONSUMED) {
            require(consumedAtEpochMs != null) { "consumed grants require consumedAt" }
        } else {
            require(consumedAtEpochMs == null) { "non-consumed grants must not have consumedAt" }
        }
    }

    fun isExpired(nowMs: Long): Boolean =
        state == ConsentGrantState.EXPIRED || nowMs >= expiresAtEpochMs

    fun isConsumable(nowMs: Long): Boolean =
        state == ConsentGrantState.ISSUED && !isExpired(nowMs)

    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
    }
}

/** Grant row states from schema content_report_consent_grants. */
enum class ConsentGrantState {
    ISSUED,
    CONSUMED,
    EXPIRED,
    REVOKED,
    ;

    companion object {
        fun fromCatalogName(name: String): ConsentGrantState? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * Durable report aggregate projection held by the feature pack (in-memory or
 * backed by control-plane store). Sensitive payload is wiped on terminal
 * convergence (retention-policy: ai-content-report-drafts).
 */
data class ContentReportRecord(
    val reportId: String,
    val principalId: String,
    val proposalCommandId: String,
    val idempotencyKey: String,
    val state: ContentReportState,
    val category: ContentReportCategory,
    val payload: ContentReportPayload?,
    val canonicalPayloadDigest: String?,
    val frozenPayload: ContentReportPayload?,
    val activeGrantId: String?,
    val cancelPending: Boolean,
    val expiresAtEpochMs: Long,
    val resourceVersion: Long,
    val receiptId: String?,
    val receiptAcceptedAt: String?,
    val receiptStatusUrl: String?,
    val error: OmniError?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    /** Encrypted-at-rest flag for queue; true while sensitive bytes retained. */
    val hasEncryptedPayload: Boolean,
) {
    init {
        require(reportId.isNotBlank())
        require(principalId.isNotBlank())
        require(resourceVersion >= 0L)
    }

    val isTerminal: Boolean
        get() = state in TERMINAL_STATES

    fun retainsSensitivePayload(): Boolean = when (state) {
        ContentReportState.SUBMITTED,
        ContentReportState.FAILED_FINAL,
        ContentReportState.DISCARDED,
        ContentReportState.EXPIRED,
        -> false
        ContentReportState.SUBMITTING,
        ContentReportState.CANCELLING,
        ContentReportState.RECONCILING,
        -> hasEncryptedPayload
        else -> hasEncryptedPayload || payload != null || frozenPayload != null
    }

    companion object {
        val TERMINAL_STATES: Set<ContentReportState> = setOf(
            ContentReportState.SUBMITTED,
            ContentReportState.FAILED_FINAL,
            ContentReportState.DISCARDED,
            ContentReportState.EXPIRED,
        )
    }
}

data class ContentReportReceipt(
    val receiptId: String,
    val reportId: String,
    val acceptedAt: String,
    val statusUrl: String,
) {
    init {
        require(receiptId.isNotBlank())
        require(reportId.isNotBlank())
        require(statusUrl.isNotBlank())
    }
}
