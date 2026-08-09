package com.omnillm.interfaces.http

import com.omnillm.interfaces.http.auth.HttpPrincipal

/**
 * Control-plane facade consumed by the HTTP gateway (ADR-011).
 *
 * Transport only authenticates, enforces size/scope, encodes, and projects streams.
 * **No engine selection, no Session mutation, no DB writes** in the gateway —
 * all domain work is delegated here (ADR-010 / INV-001).
 *
 * Implementations live under `:android:runtime-service` (or pure test fakes).
 */
interface OmniHttpHandlerPort {

    // --- Health (minimal unauth; no model/device detail) ---
    suspend fun getHealth(): HttpHandlerResult<HealthDto>

    // --- Models / capabilities (capabilities ride on ModelInfo) ---
    suspend fun listModels(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<ModelPageDto>

    // --- Inference (OpenAI-compat sync + durable async) ---
    suspend fun createChatCompletion(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): HttpHandlerResult<ChatCompletionResponseDto>

    suspend fun createChatCompletionStream(
        principal: HttpPrincipal,
        request: OpenAIChatRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): SseHandlerResult

    suspend fun createEmbedding(
        principal: HttpPrincipal,
        request: OpenAIEmbeddingRequestDto,
        requestIdHeader: String?,
        idempotencyKeyHeader: String?,
    ): HttpHandlerResult<EmbeddingResponseDto>

    suspend fun createAsyncInferenceRequest(
        principal: HttpPrincipal,
        request: AsyncInferenceRequestDto,
    ): HttpHandlerResult<AcceptedRequestDto>

    suspend fun getRequest(
        principal: HttpPrincipal,
        requestId: String,
    ): HttpHandlerResult<RequestStateDto>

    suspend fun cancelRequest(
        principal: HttpPrincipal,
        requestId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    /**
     * SSE for durable request events. Pre-stream errors as [SseHandlerResult.PreStreamError];
     * post-stream failures as terminal events. Stateless Session default.
     */
    suspend fun streamRequestEvents(
        principal: HttpPrincipal,
        requestId: String,
        afterSeq: Long?,
    ): SseHandlerResult

    // --- Commands ---
    suspend fun getCommand(
        principal: HttpPrincipal,
        commandId: String,
    ): HttpHandlerResult<CommandResultDto>

    // --- Assets lifecycle ---
    suspend fun createAsset(
        principal: HttpPrincipal,
        request: AssetCreateRequestDto,
    ): HttpHandlerResult<AssetInfoDto>

    /**
     * OpenAPI `PUT /omni/v1/assets/{assetId}/content` — multipart AssetUploadRequest.
     * [command] is the parsed CommandRequest part (idempotency claim; null only for
     * legacy raw-byte callers); [expectedSha256] / [expectedBytes] are enforced
     * when present.
     */
    suspend fun uploadAsset(
        principal: HttpPrincipal,
        assetId: String,
        body: ByteArray,
        contentLength: Long?,
        command: CommandRequestDto?,
        expectedSha256: String? = null,
        expectedBytes: Long? = null,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun commitAsset(
        principal: HttpPrincipal,
        assetId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun getAsset(
        principal: HttpPrincipal,
        assetId: String,
    ): HttpHandlerResult<AssetInfoDto>

    /**
     * OpenAPI `DELETE /omni/v1/assets/{assetId}`: requires a [CommandRequestDto]
     * body (idempotency claim) and succeeds with 204.
     */
    suspend fun deleteAsset(
        principal: HttpPrincipal,
        assetId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    // --- Jobs ---
    suspend fun createJob(
        principal: HttpPrincipal,
        request: JobSpecDto,
    ): HttpHandlerResult<JobInfoDto>

    suspend fun listOwnJobs(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<JobPageDto>

    suspend fun getJob(
        principal: HttpPrincipal,
        jobId: String,
    ): HttpHandlerResult<JobInfoDto>

    suspend fun cancelJob(
        principal: HttpPrincipal,
        jobId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    // --- Metrics / settings / clients (admin-ish HTTP projection) ---
    suspend fun getMetricSummary(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto>

    suspend fun getMetricDetail(principal: HttpPrincipal): HttpHandlerResult<MetricSummaryDto>

    suspend fun getSettings(principal: HttpPrincipal): HttpHandlerResult<SettingsSnapshotDto>

    /**
     * OpenAPI `PATCH /omni/v1/settings` projects [CommandResultDto] (spec
     * 200 → CommandResult). The patched snapshot remains readable via
     * [getSettings].
     */
    suspend fun patchSettings(
        principal: HttpPrincipal,
        request: SettingsPatchDto,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun listClients(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<ClientPageDto>

    suspend fun revokeClient(
        principal: HttpPrincipal,
        clientId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    // --- LAN ---
    suspend fun enableLan(
        principal: HttpPrincipal,
        request: LanEnableRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun disableLan(
        principal: HttpPrincipal,
        request: LanEnableRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun createLanPairingChallenge(
        principal: HttpPrincipal,
        request: LanPairingChallengeCreateRequestDto,
    ): HttpHandlerResult<JsonRawBody>

    // Pairing exchange is LAN-only and unauthenticated at HTTP bearer layer.
    suspend fun completeLanPairing(body: String): HttpHandlerResult<JsonRawBody>

    // --- Diagnostics ---
    suspend fun createDiagnosticExport(
        principal: HttpPrincipal,
        request: DiagnosticExportRequestDto,
    ): HttpHandlerResult<JobInfoDto>

    // --- AI content report proposal surfaces (exported HTTP only) ---
    suspend fun createContentReportProposal(
        principal: HttpPrincipal,
        request: ContentReportProposalRequestDto,
    ): HttpHandlerResult<ContentReportInfoDto>

    suspend fun getContentReport(
        principal: HttpPrincipal,
        reportId: String,
    ): HttpHandlerResult<ContentReportInfoDto>

    suspend fun cancelContentReport(
        principal: HttpPrincipal,
        reportId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun discardContentReport(
        principal: HttpPrincipal,
        reportId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>

    suspend fun getContentReportReceipt(
        principal: HttpPrincipal,
        reportId: String,
    ): HttpHandlerResult<ContentReportReceiptDto>

    // --- Tokens (loopback only) ---
    suspend fun issueLoopbackAdminToken(
        principal: HttpPrincipal,
        request: TokenIssueRequestDto,
    ): HttpHandlerResult<TokenIssueResultDto>

    suspend fun listTokens(
        principal: HttpPrincipal,
        pageToken: String?,
    ): HttpHandlerResult<TokenPageDto>

    suspend fun revokeToken(
        principal: HttpPrincipal,
        tokenId: String,
        command: CommandRequestDto,
    ): HttpHandlerResult<CommandResultDto>
}

/** Opaque JSON body when schema is transport-local (pairing secrets). */
data class JsonRawBody(val json: String, val status: Int = 200)
