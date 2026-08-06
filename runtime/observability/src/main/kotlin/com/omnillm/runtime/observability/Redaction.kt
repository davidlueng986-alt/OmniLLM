package com.omnillm.runtime.observability

/**
 * Redaction helpers for logs, traces, and diagnostic bundles
 * (CORE-OBSERVABILITY §4/§7, SEC-PRIVACY, FEAT-DIAGNOSTICS §5).
 *
 * Policy: field allowlist first; unknown fields default-exclude.
 * Prompt / token / private path never appear in operational metrics or
 * default diagnostic output.
 */
object Redactor {

    const val REDACTED: String = "[REDACTED]"
    const val REDACTED_PATH: String = "[REDACTED_PATH]"
    const val REDACTED_TOKEN: String = "[REDACTED_TOKEN]"
    const val REDACTED_PROMPT: String = "[REDACTED_PROMPT]"

    /** Bearer / opaque token-like patterns. */
    private val BEARER_PATTERN =
        Regex("""(?i)\b(bearer\s+)[A-Za-z0-9\-._~+/]+=*""")
    private val JWT_PATTERN =
        Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val HEX_SECRET_PATTERN =
        Regex("""\b[0-9a-fA-F]{64}\b""") // may over-redact digests in free text; digests should use typed fields
    private val UNIX_ABS_PATH =
        Regex("""(?<![A-Za-z0-9_])(/((data|storage|sdcard|home|Users|private)(/[^\s"'<>|]+)+))""")
    private val WINDOWS_ABS_PATH =
        Regex("""(?i)\b([A-Z]:[\\/][^\s"'<>|]+)""")
    private val FILE_URI =
        Regex("""(?i)\bfile://[^\s"'<>]+""")

    fun containsSecretPattern(value: String): Boolean {
        if (BEARER_PATTERN.containsMatchIn(value)) return true
        if (JWT_PATTERN.containsMatchIn(value)) return true
        return false
    }

    fun containsPrivatePath(value: String): Boolean {
        if (FILE_URI.containsMatchIn(value)) return true
        if (WINDOWS_ABS_PATH.containsMatchIn(value)) return true
        if (UNIX_ABS_PATH.containsMatchIn(value)) return true
        return false
    }

    /**
     * Redact free-text for logs / diagnostic summaries.
     * Does not claim cryptographic scrubbing of all possible secrets;
     * unknown structured fields must still be allowlist-excluded.
     */
    fun redactFreeText(input: String, redactHexDigests: Boolean = false): String {
        var s = input
        s = BEARER_PATTERN.replace(s) { m -> m.groupValues[1] + REDACTED_TOKEN }
        s = JWT_PATTERN.replace(s, REDACTED_TOKEN)
        s = FILE_URI.replace(s, REDACTED_PATH)
        s = WINDOWS_ABS_PATH.replace(s, REDACTED_PATH)
        s = UNIX_ABS_PATH.replace(s, REDACTED_PATH)
        if (redactHexDigests) {
            s = HEX_SECRET_PATTERN.replace(s, REDACTED)
        }
        return s
    }

    /** Always drop raw prompt content. */
    fun redactPrompt(@Suppress("UNUSED_PARAMETER") prompt: String): String = REDACTED_PROMPT

    /** Always drop model generation output for default diagnostics. */
    fun redactOutput(@Suppress("UNUSED_PARAMETER") output: String): String = REDACTED

    fun redactToken(@Suppress("UNUSED_PARAMETER") token: String): String = REDACTED_TOKEN

    fun redactPath(@Suppress("UNUSED_PARAMETER") path: String): String = REDACTED_PATH

    /**
     * Project a structured map through an allowlist. Unknown keys are dropped
     * (fail closed for export — FEAT-DIAGNOSTICS §5).
     */
    fun projectAllowlist(
        fields: Map<String, String>,
        allowlist: Set<String>,
        redactValues: Boolean = true,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (key in allowlist.sorted()) {
            val raw = fields[key] ?: continue
            out[key] = if (redactValues) redactFreeText(raw) else raw
        }
        return out
    }

    /**
     * Pseudonymize a principal for security-audit / cross-client summary
     * (retention-policy security-audit-events).
     */
    fun pseudonymizePrincipal(principalId: String, salt: String): String {
        require(principalId.isNotBlank()) { "principalId must be non-blank" }
        // Stable non-cryptographic fingerprint for local UI grouping only.
        // Production export should use HMAC with device-held key material.
        val material = "$salt|$principalId"
        var h = 0xcbf29ce484222325UL
        for (c in material) {
            h = h xor c.code.toULong()
            h *= 0x100000001b3UL
        }
        return "prin_" + h.toString(16).padStart(16, '0')
    }
}

/**
 * Field allowlist for diagnostic export categories (CORE-OBSERVABILITY §7,
 * FEAT-DIAGNOSTICS §2). Unknown fields are excluded by default.
 *
 * Categories match job diagnostic-export parameters loosely; concrete schema
 * versions are versioned constants for exporters.
 */
object DiagnosticAllowlist {

    const val SCHEMA_VERSION: String = "diagnostic-bundle-allowlist.v1"

    enum class Category {
        MANIFEST,
        RUNTIME_VERSIONS,
        CONFIGURATION,
        CAPABILITY_SNAPSHOT,
        MODEL_ENGINE_IDS,
        REQUEST_JOB_STATE,
        RESOURCE_SNAPSHOT,
        EVENTS_TRACES,
        ERROR_CHAIN,
        EVIDENCE_LABELS,
        REPRODUCTION_HINTS,
        FILE_DIGESTS,
        CRASH_SUMMARY,
        INTEGRITY_RESULTS,
    }

    /** Fields allowed per category. Values are field keys, not free prose. */
    val FIELDS_BY_CATEGORY: Map<Category, Set<String>> = mapOf(
        Category.MANIFEST to setOf(
            "bundleId",
            "schemaVersion",
            "createdAt",
            "expiresAt",
            "ownerPrincipalClass",
        ),
        Category.RUNTIME_VERSIONS to setOf(
            "appVersion",
            "runtimeBuildId",
            "platformOs",
            "platformApiLevel",
            "moduleDigests",
        ),
        Category.CONFIGURATION to setOf(
            "policyVersion",
            "runtimeEpoch",
            "resourceCapsSummary",
            "featureFlagsSummary",
        ),
        Category.CAPABILITY_SNAPSHOT to setOf(
            "capabilityId",
            "capabilityState",
            "reasonCode",
            "evidenceLabel",
            "evidenceId",
        ),
        Category.MODEL_ENGINE_IDS to setOf(
            "modelRevisionId",
            "artifactPackageId",
            "engineBuildId",
            "backend",
            "placement",
            "trustState",
        ),
        Category.REQUEST_JOB_STATE to setOf(
            "requestId",
            "jobId",
            "state",
            "phase",
            "errorCode",
            "principalClass",
            "operationKind",
        ),
        Category.RESOURCE_SNAPSHOT to setOf(
            "resourceDimension",
            "reserved",
            "allocated",
            "capacity",
            "safetyMargin",
            "evidenceLabel",
            "sampledAt",
            "sampleAgeMs",
        ),
        Category.EVENTS_TRACES to setOf(
            "correlationId",
            "sequence",
            "eventName",
            "phase",
            "reasonCode",
            "monotonicNs",
        ),
        Category.ERROR_CHAIN to setOf(
            "errorCode",
            "category",
            "retryable",
            "requiredClientAction",
            "phase",
        ),
        Category.EVIDENCE_LABELS to setOf(
            "evidenceLabel",
            "sampledAt",
            "source",
            "methodVersion",
            "measurementProfileId",
        ),
        Category.REPRODUCTION_HINTS to setOf(
            "operationKind",
            "backend",
            "deviceClass",
            "thermalStatus",
            "contextTokensBound",
        ),
        Category.FILE_DIGESTS to setOf(
            "pathRole",
            "sha256",
            "byteLength",
        ),
        Category.CRASH_SUMMARY to setOf(
            "processRole",
            "exitReason",
            "faultCode",
            "engineBuildId",
            "count",
            "lastSeenAt",
        ),
        Category.INTEGRITY_RESULTS to setOf(
            "checkId",
            "outcome",
            "digestExpected",
            "digestActual",
        ),
    )

    /**
     * Fields that must never appear in any category (default deny list for
     * double-checks; allowlist still primary).
     */
    val NEVER_EXPORT: Set<String> = setOf(
        "prompt",
        "rawPrompt",
        "output",
        "rawOutput",
        "token",
        "tokenSecret",
        "tokenHash",
        "bearerToken",
        "licenseAcceptanceIdentity",
        "privatePath",
        "modelPrivatePath",
        "absolutePath",
        "nativeLogRaw",
        "embedding",
        "audioContent",
        "imageContent",
    )

    fun isAllowed(category: Category, field: String): Boolean {
        if (field in NEVER_EXPORT) return false
        return FIELDS_BY_CATEGORY[category]?.contains(field) == true
    }

    fun project(
        category: Category,
        fields: Map<String, String>,
    ): Map<String, String> {
        val allow = FIELDS_BY_CATEGORY[category] ?: emptySet()
        val filtered = fields.filterKeys { it in allow && it !in NEVER_EXPORT }
        return Redactor.projectAllowlist(filtered, allow)
    }

    fun allAllowedFields(): Set<String> =
        FIELDS_BY_CATEGORY.values.flatten().toSet() - NEVER_EXPORT

    /**
     * Export the redaction allowlist as a versioned, shareable schema document
     * (FEAT-DIAGNOSTICS §5 — field allowlist first; unknown fields excluded).
     *
     * This is pure projection: no secrets, paths, or prompt/output material.
     * Used for pre-export UX preview and sealed bundle transparency.
     */
    fun exportSchemaDocument(): Map<String, Any> {
        val categories = FIELDS_BY_CATEGORY.entries
            .sortedBy { it.key.name }
            .associate { (cat, fields) ->
                cat.name to mapOf(
                    "fields" to fields.sorted(),
                    "fieldCount" to fields.size,
                )
            }
        return linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "policy" to "allowlist-first; unknown fields excluded; never-export double-check",
            "categories" to categories,
            "neverExport" to NEVER_EXPORT.sorted(),
            "allowedFieldCount" to allAllowedFields().size,
            "categoryCount" to FIELDS_BY_CATEGORY.size,
        )
    }

    /**
     * Canonical JSON-ish encoding of [exportSchemaDocument] for bundle files
     * and HTTP projection (stable key order).
     */
    fun exportSchemaCanonicalText(): String {
        val doc = exportSchemaDocument()
        return buildString {
            append("schemaVersion=").append(doc["schemaVersion"]).append('\n')
            append("policy=").append(doc["policy"]).append('\n')
            append("categoryCount=").append(doc["categoryCount"]).append('\n')
            append("allowedFieldCount=").append(doc["allowedFieldCount"]).append('\n')
            append("neverExport=").append((doc["neverExport"] as List<*>).joinToString(",")).append('\n')
            @Suppress("UNCHECKED_CAST")
            val categories = doc["categories"] as Map<String, Map<String, Any>>
            for ((name, body) in categories) {
                val fields = (body["fields"] as List<*>).joinToString(",")
                append("category.").append(name).append(".fields=").append(fields).append('\n')
            }
        }
    }
}

/**
 * Telemetry field allowlist (SEC-PRIVACY §3).
 *
 * Outbound telemetry is **opt-in / default off**. Only aggregated events on this
 * allowlist may leave the device. Never include prompt, output, model file path,
 * token, full IP, stable hardware identifier, or reconstructable personal content.
 *
 * AI content reports are a **separate** stream (SEC-PRIVACY §7) — not telemetry.
 */
object TelemetryAllowlist {

    const val SCHEMA_VERSION: String = "telemetry-event-allowlist.v1"

    /** Master switch default — product must not enable outbound without user control. */
    const val DEFAULT_ENABLED: Boolean = false

    enum class EventName {
        APP_START_AGGREGATE,
        REQUEST_OUTCOME_AGGREGATE,
        JOB_OUTCOME_AGGREGATE,
        CAPABILITY_SNAPSHOT_AGGREGATE,
        HEALTH_TRANSITION_AGGREGATE,
        CRASH_COUNT_AGGREGATE,
    }

    /** Fields permitted on any outbound telemetry event body. */
    val ALLOWED_FIELDS: Set<String> = setOf(
        "schemaVersion",
        "eventName",
        "eventVersion",
        "sampledAt",
        "sampleRate",
        "appVersion",
        "runtimeBuildId",
        "platformOs",
        "platformApiLevel",
        "deviceClass",
        "count",
        "errorCode",
        "phase",
        "operationKind",
        "capabilityId",
        "capabilityState",
        "healthLevel",
        "processRole",
        "durationBucketMs",
        "evidenceLabel",
    )

    /** Absolute deny — double-check even if mistakenly listed. */
    val NEVER_TELEMETRY: Set<String> = setOf(
        "prompt",
        "rawPrompt",
        "output",
        "rawOutput",
        "token",
        "tokenSecret",
        "bearerToken",
        "pairingSecret",
        "privatePath",
        "modelPrivatePath",
        "absolutePath",
        "modelFilePath",
        "fullIp",
        "ipAddress",
        "stableHardwareId",
        "androidId",
        "advertisingId",
        "embedding",
        "audioContent",
        "imageContent",
        "conversationHistory",
        "nativeLogRaw",
        "licenseAcceptanceIdentity",
    )

    fun isAllowedEvent(eventName: String): Boolean =
        EventName.entries.any { it.name == eventName }

    fun isAllowedField(field: String): Boolean =
        field in ALLOWED_FIELDS && field !in NEVER_TELEMETRY

    /**
     * Project an event map; drops unknown keys and never-export fields.
     * Returns null if event name is not on the allowlist (fail closed).
     */
    fun projectEvent(
        eventName: String,
        fields: Map<String, String>,
        includeDefaults: Boolean = true,
    ): Map<String, String>? {
        if (!isAllowedEvent(eventName)) return null
        val base = if (includeDefaults) {
            linkedMapOf(
                "schemaVersion" to SCHEMA_VERSION,
                "eventName" to eventName,
            )
        } else {
            linkedMapOf()
        }
        for ((k, v) in fields) {
            if (isAllowedField(k)) base[k] = Redactor.redactFreeText(v)
        }
        // Force eventName from argument.
        base["eventName"] = eventName
        base["schemaVersion"] = SCHEMA_VERSION
        return base
    }
}

/**
 * Native / crash log line redaction before diagnostic inclusion (SEC-PRIVACY §4).
 * Unknown structured fields are excluded by [DiagnosticAllowlist]; this scrubber
 * handles free-text native logs that still pass category admission.
 */
object NativeLogRedactor {
    fun redactLine(line: String): String = Redactor.redactFreeText(line, redactHexDigests = false)

    fun redactLines(lines: List<String>, maxLines: Int = 500, maxTotalBytes: Int = 256 * 1024): List<String> {
        val out = ArrayList<String>(minOf(lines.size, maxLines))
        var bytes = 0
        for (line in lines) {
            if (out.size >= maxLines) break
            val redacted = redactLine(line)
            val b = redacted.toByteArray(Charsets.UTF_8).size
            if (bytes + b > maxTotalBytes) break
            out += redacted
            bytes += b
        }
        return out
    }
}

