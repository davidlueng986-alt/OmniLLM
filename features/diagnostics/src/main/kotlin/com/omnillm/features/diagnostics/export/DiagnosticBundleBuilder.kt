package com.omnillm.features.diagnostics.export

import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.EvidenceLabel
import com.omnillm.features.diagnostics.domain.BundleFileEntry
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticBundleStates
import com.omnillm.features.diagnostics.domain.DiagnosticReasonModel
import com.omnillm.features.diagnostics.domain.Inference
import com.omnillm.features.diagnostics.domain.ObservedFact
import com.omnillm.features.diagnostics.ports.DiagnosticSourcePort
import com.omnillm.runtime.observability.DiagnosticAllowlist
import com.omnillm.runtime.observability.Redactor
import java.nio.charset.StandardCharsets

/**
 * Builds redacted, content-addressed diagnostic bundle files
 * (FEAT-DIAGNOSTICS §2/§5, CORE-OBSERVABILITY §7).
 *
 * Policy:
 * - Field allowlist first; unknown fields excluded.
 * - Prompt / token / private path never exported.
 * - Partial collection must not produce READY.
 * - Integrity = SHA-256 over sorted pathRole + file digests.
 */
object DiagnosticBundleBuilder {

    const val MANIFEST_PATH_ROLE: String = "manifest"
    const val REASON_PATH_ROLE: String = "reason"
    /** FEAT-DIAGNOSTICS §5 — sealed allowlist schema for offline integrity / review. */
    const val REDACTION_ALLOWLIST_PATH_ROLE: String = "redaction_allowlist"
    private const val BUNDLE_DOMAIN: String = "omnillm.diagnostic-bundle.v1"

    private val DEFAULT_TTL_SECONDS: Int = 86_400 // retention-policy: 24h default

    /**
     * Estimate payload size for a category (preview only — not measured occupancy).
     */
    fun estimateCategoryBytes(
        category: DiagnosticAllowlist.Category,
        includeDetail: Boolean,
    ): Long {
        val base = when (category) {
            DiagnosticAllowlist.Category.MANIFEST -> 512L
            DiagnosticAllowlist.Category.RUNTIME_VERSIONS -> 1_024L
            DiagnosticAllowlist.Category.CONFIGURATION -> 2_048L
            DiagnosticAllowlist.Category.CAPABILITY_SNAPSHOT -> 4_096L
            DiagnosticAllowlist.Category.MODEL_ENGINE_IDS -> 2_048L
            DiagnosticAllowlist.Category.REQUEST_JOB_STATE -> 8_192L
            DiagnosticAllowlist.Category.RESOURCE_SNAPSHOT -> 4_096L
            DiagnosticAllowlist.Category.EVENTS_TRACES -> if (includeDetail) 64_000L else 16_000L
            DiagnosticAllowlist.Category.ERROR_CHAIN -> 4_096L
            DiagnosticAllowlist.Category.EVIDENCE_LABELS -> 1_024L
            DiagnosticAllowlist.Category.REPRODUCTION_HINTS -> 1_024L
            DiagnosticAllowlist.Category.FILE_DIGESTS -> 2_048L
            DiagnosticAllowlist.Category.CRASH_SUMMARY -> 4_096L
            DiagnosticAllowlist.Category.INTEGRITY_RESULTS -> 1_024L
        }
        return base
    }

    /**
     * Collect selected categories into redacted files and optionally seal.
     *
     * @param seal when false, state stays COLLECTING/SEALING without READY.
     */
    fun build(
        bundleId: String,
        jobId: String?,
        ownerPrincipalClass: String,
        categories: List<DiagnosticAllowlist.Category>,
        sources: DiagnosticSourcePort,
        includeDetail: Boolean,
        createdAtEpochMs: Long,
        ttlSeconds: Int = DEFAULT_TTL_SECONDS,
        seal: Boolean,
        shareIrreversibleDisclosed: Boolean,
        reason: DiagnosticReasonModel? = null,
    ): DiagnosticBundleSnapshot {
        require(bundleId.isNotBlank()) { "bundleId must be non-blank" }
        require(ttlSeconds in 60..604_800) { "ttlSeconds must be 60..604800" }

        val files = mutableListOf<BundleFileEntry>()
        val included = mutableListOf<String>()

        // Manifest shell (always when sealing or collecting).
        val manifestFields = linkedMapOf(
            "bundleId" to bundleId,
            "schemaVersion" to DiagnosticAllowlist.SCHEMA_VERSION,
            "createdAt" to createdAtEpochMs.toString(),
            "expiresAt" to (createdAtEpochMs + ttlSeconds * 1000L).toString(),
            "ownerPrincipalClass" to ownerPrincipalClass,
        )
        files += fileFromAllowlist(
            pathRole = MANIFEST_PATH_ROLE,
            category = DiagnosticAllowlist.Category.MANIFEST,
            fields = manifestFields,
        )
        included += DiagnosticAllowlist.Category.MANIFEST.name

        for (category in categories.distinct().sortedBy { it.name }) {
            if (category == DiagnosticAllowlist.Category.MANIFEST) continue
            val projected = collectCategory(category, sources, includeDetail)
            if (projected.isEmpty() && category != DiagnosticAllowlist.Category.FILE_DIGESTS) {
                // Still emit empty allowlisted shell only for known empty maps when sealing.
                continue
            }
            if (projected.isNotEmpty()) {
                files += fileFromAllowlist(
                    pathRole = category.name.lowercase(),
                    category = category,
                    fields = projected,
                )
                included += category.name
            }
        }

        // Evidence labels derived from metric samples.
        val evidenceRows = sources.metricSamples().mapIndexed { idx, sample ->
            mapOf(
                "evidenceLabel" to sample.evidenceLabel.name,
                "sampledAt" to sample.sampledAtEpochMs.toString(),
                "source" to (sample.source ?: ""),
                "methodVersion" to (sample.methodVersion ?: ""),
                "measurementProfileId" to (sample.dimensions["measurementProfileId"] ?: ""),
                "_row" to idx.toString(),
            )
        }
        if (evidenceRows.isNotEmpty() ||
            DiagnosticAllowlist.Category.EVIDENCE_LABELS in categories
        ) {
            val flat = flattenRows(evidenceRows)
            val projected = DiagnosticAllowlist.project(
                DiagnosticAllowlist.Category.EVIDENCE_LABELS,
                flat,
            )
            if (projected.isNotEmpty() || evidenceRows.isNotEmpty()) {
                // Multi-row: serialize as redacted lines, still field-allowlisted per row.
                val body = buildString {
                    for (row in evidenceRows) {
                        val p = DiagnosticAllowlist.project(
                            DiagnosticAllowlist.Category.EVIDENCE_LABELS,
                            row.filterKeys { it != "_row" },
                        )
                        append(encodeFields(p))
                        append('\n')
                    }
                }
                if (body.isNotBlank()) {
                    files += contentAddressed(body, DiagnosticAllowlist.Category.EVIDENCE_LABELS.name.lowercase())
                    if (DiagnosticAllowlist.Category.EVIDENCE_LABELS.name !in included) {
                        included += DiagnosticAllowlist.Category.EVIDENCE_LABELS.name
                    }
                }
            }
        }

        val effectiveReason = reason ?: deriveReason(sources, createdAtEpochMs)
        val reasonBody = encodeReason(effectiveReason)
        files += contentAddressed(reasonBody, REASON_PATH_ROLE)

        // Always include versioned redaction allowlist (FEAT-DIAGNOSTICS §5 transparency).
        val allowlistBody = DiagnosticAllowlist.exportSchemaCanonicalText()
        files += contentAddressed(allowlistBody, REDACTION_ALLOWLIST_PATH_ROLE)
        if (REDACTION_ALLOWLIST_PATH_ROLE !in included) {
            included += REDACTION_ALLOWLIST_PATH_ROLE
        }

        // Digests of files themselves.
        val digestFields = files.map { f ->
            mapOf(
                "pathRole" to f.pathRole,
                "sha256" to f.sha256,
                "byteLength" to f.byteLength.toString(),
            )
        }
        val digestsBody = buildString {
            for (row in digestFields) {
                val p = DiagnosticAllowlist.project(
                    DiagnosticAllowlist.Category.FILE_DIGESTS,
                    row,
                )
                append(encodeFields(p))
                append('\n')
            }
        }
        files += contentAddressed(digestsBody, DiagnosticAllowlist.Category.FILE_DIGESTS.name.lowercase())
        if (DiagnosticAllowlist.Category.FILE_DIGESTS.name !in included) {
            included += DiagnosticAllowlist.Category.FILE_DIGESTS.name
        }

        val estimated = files.sumOf { it.byteLength }
        val expiresAt = createdAtEpochMs + ttlSeconds * 1000L

        if (!seal) {
            return DiagnosticBundleSnapshot(
                bundleId = bundleId,
                jobId = jobId,
                ownerPrincipalClass = ownerPrincipalClass,
                state = DiagnosticBundleStates.COLLECTING,
                schemaVersion = DiagnosticAllowlist.SCHEMA_VERSION,
                createdAtEpochMs = createdAtEpochMs,
                expiresAtEpochMs = expiresAt,
                categoriesIncluded = included,
                files = files,
                manifestDigest = null,
                reason = effectiveReason,
                estimatedBytes = estimated,
                shareIrreversibleDisclosed = shareIrreversibleDisclosed,
            )
        }

        val manifestDigest = sealManifest(files)
        return DiagnosticBundleSnapshot(
            bundleId = bundleId,
            jobId = jobId,
            ownerPrincipalClass = ownerPrincipalClass,
            state = DiagnosticBundleStates.READY,
            schemaVersion = DiagnosticAllowlist.SCHEMA_VERSION,
            createdAtEpochMs = createdAtEpochMs,
            expiresAtEpochMs = expiresAt,
            categoriesIncluded = included,
            files = files,
            manifestDigest = manifestDigest,
            reason = effectiveReason,
            estimatedBytes = estimated,
            shareIrreversibleDisclosed = shareIrreversibleDisclosed,
        )
    }

    /**
     * Verify integrity of a sealed bundle (FEAT-DIAGNOSTICS acceptance §2).
     * Any file mutation ⇒ verification failure.
     */
    fun verifyIntegrity(bundle: DiagnosticBundleSnapshot): Boolean {
        if (bundle.manifestDigest == null) return false
        if (bundle.files.isEmpty()) return false
        val expected = sealManifest(bundle.files)
        return expected == bundle.manifestDigest
    }

    /**
     * Simulate tampering by recomputing digest with a mutated file set.
     */
    fun sealManifest(files: List<BundleFileEntry>): String {
        val sorted = files.sortedWith(compareBy({ it.pathRole }, { it.sha256 }))
        val payload = buildString {
            for (f in sorted) {
                append(f.pathRole)
                append('|')
                append(f.sha256)
                append('|')
                append(f.byteLength)
                append('\n')
            }
        }
        return IdentityHashing.identityDigestHex(BUNDLE_DOMAIN, payload)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun collectCategory(
        category: DiagnosticAllowlist.Category,
        sources: DiagnosticSourcePort,
        includeDetail: Boolean,
    ): Map<String, String> {
        val raw: Map<String, String> = when (category) {
            DiagnosticAllowlist.Category.MANIFEST -> emptyMap()
            DiagnosticAllowlist.Category.RUNTIME_VERSIONS -> sources.runtimeVersionFields()
            DiagnosticAllowlist.Category.CONFIGURATION -> sources.configurationFields()
            DiagnosticAllowlist.Category.CAPABILITY_SNAPSHOT ->
                flattenRows(sources.capabilitySnapshotFields())
            DiagnosticAllowlist.Category.MODEL_ENGINE_IDS ->
                flattenRows(sources.modelEngineIdFields())
            DiagnosticAllowlist.Category.REQUEST_JOB_STATE ->
                flattenRows(sources.requestJobStateFields())
            DiagnosticAllowlist.Category.RESOURCE_SNAPSHOT ->
                flattenRows(sources.resourceSnapshotFields())
            DiagnosticAllowlist.Category.EVENTS_TRACES ->
                flattenTraces(sources.recentTraces(if (includeDetail) 64 else 16))
            DiagnosticAllowlist.Category.ERROR_CHAIN ->
                flattenRows(sources.errorChainFields())
            DiagnosticAllowlist.Category.EVIDENCE_LABELS -> emptyMap()
            DiagnosticAllowlist.Category.REPRODUCTION_HINTS -> sources.reproductionHints()
            DiagnosticAllowlist.Category.FILE_DIGESTS -> emptyMap()
            DiagnosticAllowlist.Category.CRASH_SUMMARY ->
                flattenRows(sources.crashSummaryFields())
            DiagnosticAllowlist.Category.INTEGRITY_RESULTS -> emptyMap()
        }
        // Drop never-export keys even if a buggy source injects them.
        val sanitized = raw.filterKeys { it !in DiagnosticAllowlist.NEVER_EXPORT }
        return DiagnosticAllowlist.project(category, sanitized)
    }

    private fun flattenRows(rows: List<Map<String, String>>): Map<String, String> {
        if (rows.isEmpty()) return emptyMap()
        // Prefer first row for single-map categories; multi-row path uses serialize separately.
        return rows.first()
    }

    private fun flattenTraces(traces: List<com.omnillm.runtime.observability.RequestTrace>): Map<String, String> {
        val first = traces.firstOrNull() ?: return emptyMap()
        val ev = first.events.firstOrNull()
        return mapOf(
            "correlationId" to first.correlationId,
            "sequence" to (ev?.sequence?.toString() ?: "0"),
            "eventName" to (ev?.name ?: "trace"),
            "phase" to (ev?.phase ?: ""),
            "reasonCode" to (ev?.attributes?.get("reasonCode") ?: ""),
            "monotonicNs" to (ev?.monotonicNs?.toString() ?: "0"),
        ).filterValues { it.isNotBlank() || it == "0" }
    }

    private fun fileFromAllowlist(
        pathRole: String,
        category: DiagnosticAllowlist.Category,
        fields: Map<String, String>,
    ): BundleFileEntry {
        val projected = DiagnosticAllowlist.project(category, fields)
        val body = encodeFields(projected)
        return contentAddressed(body, pathRole)
    }

    private fun contentAddressed(body: String, pathRole: String): BundleFileEntry {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val digest = IdentityHashing.sha256Hex(bytes)
        return BundleFileEntry(
            pathRole = pathRole,
            sha256 = digest,
            byteLength = bytes.size.toLong(),
            redactedUtf8 = body,
        )
    }

    private fun encodeFields(fields: Map<String, String>): String =
        fields.entries
            .sortedBy { it.key }
            .joinToString("\n") { (k, v) ->
                val safe = Redactor.redactFreeText(v)
                "$k=$safe"
            }

    private fun encodeReason(reason: DiagnosticReasonModel): String = buildString {
        append("reasonCode=").append(reason.reasonCode).append('\n')
        reason.confidence?.let { append("confidence=").append(it).append('\n') }
        for (f in reason.observedFacts) {
            append("fact.")
            append(f.key)
            append('=')
            append(Redactor.redactFreeText(f.value))
            append("|evidence=")
            append(f.evidenceLabel.name)
            append("|sampledAt=")
            append(f.sampledAtEpochMs)
            f.source?.let {
                append("|source=")
                append(Redactor.redactFreeText(it))
            }
            append('\n')
        }
        for (inf in reason.inferences) {
            append("inference.")
            append(inf.key)
            append('=')
            append(Redactor.redactFreeText(inf.statement))
            append("|evidence=")
            append(inf.evidenceLabel.name)
            inf.confidence?.let {
                append("|confidence=")
                append(it)
            }
            append('\n')
        }
        for (alt in reason.excludedAlternatives) {
            append("excluded=").append(Redactor.redactFreeText(alt)).append('\n')
        }
        for (act in reason.suggestedSafeActions) {
            append("action=").append(Redactor.redactFreeText(act)).append('\n')
        }
    }

    private fun deriveReason(
        sources: DiagnosticSourcePort,
        nowEpochMs: Long,
    ): DiagnosticReasonModel {
        val health = sources.serviceHealth()
        val facts = mutableListOf<ObservedFact>()
        if (health != null) {
            facts += ObservedFact(
                key = "service.health",
                value = health.level.name,
                evidenceLabel = health.evidenceLabel,
                sampledAtEpochMs = health.sampledAtEpochMs,
                source = health.source,
            )
            for ((i, code) in health.reasonCodes.withIndex()) {
                facts += ObservedFact(
                    key = "service.reason.$i",
                    value = code,
                    evidenceLabel = health.evidenceLabel,
                    sampledAtEpochMs = health.sampledAtEpochMs,
                    source = health.source,
                )
            }
        }
        for (sample in sources.metricSamples().take(8)) {
            facts += ObservedFact(
                key = "metric.${sample.name}",
                value = sample.value.toString(),
                evidenceLabel = sample.evidenceLabel,
                sampledAtEpochMs = sample.sampledAtEpochMs,
                source = sample.source,
            )
        }
        val inferences = mutableListOf<Inference>()
        if (health != null && health.level.name == "DEGRADED") {
            inferences += Inference(
                key = "service.degraded",
                statement = "service is degraded; check affected capabilities",
                evidenceLabel = EvidenceLabel.ESTIMATED,
                confidence = 0.7,
            )
        }
        return DiagnosticReasonModel(
            reasonCode = health?.reasonCodes?.firstOrNull() ?: "diagnostics.export.snapshot",
            observedFacts = facts,
            inferences = inferences,
            confidence = if (inferences.isNotEmpty()) 0.7 else null,
            excludedAlternatives = listOf("silent-engine-raw-log-export"),
            suggestedSafeActions = health?.recommendedActions
                ?: listOf("review-categories", "share-only-if-needed"),
        )
    }
}
