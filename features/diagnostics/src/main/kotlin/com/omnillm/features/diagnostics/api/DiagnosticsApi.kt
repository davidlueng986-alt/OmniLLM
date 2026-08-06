package com.omnillm.features.diagnostics.api

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.features.diagnostics.domain.DiagnosticBundleSnapshot
import com.omnillm.features.diagnostics.domain.DiagnosticExportPlan

/**
 * Public Diagnostics API for LOCAL_UI / Admin composition (FEAT-DIAGNOSTICS).
 *
 * Composes platform capabilities (DIAGNOSTIC_REASONING, REQUEST_TRACE,
 * SERVICE_HEALTH, EVIDENCE_LABELING, JOB_LIFECYCLE, …) without redefining
 * Request / Session / Trust semantics (FEATURE-SYSTEM §1).
 *
 * All mutations go through Job Manager ports; this feature never writes the
 * domain DB itself (ADR-010 / INV-001). Bundle sealing is pure in-memory
 * until a runtime host persists content-addressed files.
 */
interface DiagnosticsApi {

    /** Full diagnostics screen snapshot (empty / loading / error / degraded / content). */
    suspend fun getSnapshot(principal: PrincipalId): OmniResult<DiagnosticsSnapshot>

    /**
     * Pure plan of categories, sensitivity, estimated size, TTL, encryption
     * options (ADR-002 — no domain mutation).
     */
    fun planExport(
        principal: PrincipalId,
        includeDetail: Boolean = false,
        selectedCategories: List<String> = emptyList(),
    ): OmniResult<DiagnosticExportPlan>

    /**
     * Claim DIAGNOSTIC_EXPORT job and begin collection.
     * Client generates jobId / bundleId / idempotencyKey before send (ADR-004/005).
     */
    suspend fun startExport(
        principal: PrincipalId,
        spec: StartExportSpec,
    ): OmniResult<DiagnosticJobHandle>

    /** Cancel non-terminal export job (JOB cancel edges). */
    suspend fun cancelExport(
        principal: PrincipalId,
        spec: CancelExportSpec,
    ): OmniResult<DiagnosticJobHandle>

    /** Query job after reply loss — never re-create blindly. */
    suspend fun queryExportJob(
        principal: PrincipalId,
        jobId: String,
    ): OmniResult<DiagnosticJobHandle>

    /** Get sealed or in-progress bundle by id. */
    suspend fun getBundle(
        principal: PrincipalId,
        bundleId: String,
    ): OmniResult<DiagnosticBundleSnapshot>

    /** List non-deleted bundles owned by principal class. */
    suspend fun listBundles(principal: PrincipalId): OmniResult<List<DiagnosticBundleSnapshot>>

    /**
     * Runtime-host pipeline: collect redacted categories and seal manifest.
     * Partial seals never mark READY (FEAT-DIAGNOSTICS acceptance §3).
     */
    suspend fun collectAndSeal(
        jobId: String,
        forceFailClosed: Boolean = false,
    ): OmniResult<DiagnosticBundleSnapshot>

    /** Expire or delete a READY/FAILED/CANCELLED bundle (drain share first). */
    suspend fun deleteBundle(
        principal: PrincipalId,
        spec: DeleteBundleSpec,
    ): OmniResult<DiagnosticBundleSnapshot>

    /**
     * Mint client-generated requestId + idempotencyKey for an inference that
     * diagnostics may attach (ADR-004/005). Does not submit inference.
     */
    fun newClientInferenceIdentity(
        idempotencyKey: String? = null,
    ): ClientInferenceIdentity

    /** Metric samples with evidence labels for the diagnostics metrics strip. */
    fun listEvidencedMetrics(principal: PrincipalId): OmniResult<List<EvidencedMetricView>>

    /**
     * Export the versioned redaction field allowlist (FEAT-DIAGNOSTICS §5).
     * Pure projection — no domain mutation; no secrets.
     */
    fun exportRedactionAllowlist(principal: PrincipalId): OmniResult<RedactionAllowlistExport>
}
