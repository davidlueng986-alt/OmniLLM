package com.omnillm.interfaces.http

/**
 * Path templates and operationIds from `specs/openapi/omnillm.openapi.yaml`.
 * Keep in lockstep with the YAML; prefer codegen when available.
 */
object OpenApiPaths {
    const val HEALTH: String = "/health"
    const val V1_MODELS: String = "/v1/models"
    const val V1_CHAT_COMPLETIONS: String = "/v1/chat/completions"
    const val V1_EMBEDDINGS: String = "/v1/embeddings"

    const val OMNI_REQUESTS: String = "/omni/v1/requests"
    const val OMNI_REQUEST_BY_ID: String = "/omni/v1/requests/{requestId}"
    const val OMNI_REQUEST_CANCEL: String = "/omni/v1/requests/{requestId}/cancel"
    const val OMNI_REQUEST_EVENTS: String = "/omni/v1/requests/{requestId}/events"
    const val OMNI_COMMAND_BY_ID: String = "/omni/v1/commands/{commandId}"

    const val OMNI_ASSETS: String = "/omni/v1/assets"
    const val OMNI_ASSET_BY_ID: String = "/omni/v1/assets/{assetId}"
    const val OMNI_ASSET_CONTENT: String = "/omni/v1/assets/{assetId}/content"
    const val OMNI_ASSET_COMMIT: String = "/omni/v1/assets/{assetId}/commit"

    const val OMNI_JOBS: String = "/omni/v1/jobs"
    const val OMNI_JOB_BY_ID: String = "/omni/v1/jobs/{jobId}"
    const val OMNI_JOB_CANCEL: String = "/omni/v1/jobs/{jobId}/cancel"

    const val OMNI_METRICS_SUMMARY: String = "/omni/v1/metrics/summary"
    const val OMNI_METRICS_DETAIL: String = "/omni/v1/metrics/detail"
    const val OMNI_SETTINGS: String = "/omni/v1/settings"
    const val OMNI_CLIENTS: String = "/omni/v1/clients"
    const val OMNI_CLIENT_REVOKE: String = "/omni/v1/clients/{clientId}/revoke"

    const val OMNI_LAN_ENABLE: String = "/omni/v1/lan/enable"
    const val OMNI_LAN_DISABLE: String = "/omni/v1/lan/disable"
    const val OMNI_LAN_PAIRING_CHALLENGES: String = "/omni/v1/lan/pairing-challenges"
    const val OMNI_PAIRING_EXCHANGES: String = "/omni/pairing/v1/exchanges"

    const val OMNI_DIAGNOSTICS_EXPORTS: String = "/omni/v1/diagnostics/exports"
    const val OMNI_CONTENT_REPORTS: String = "/omni/v1/content-reports"
    const val OMNI_CONTENT_REPORT_BY_ID: String = "/omni/v1/content-reports/{reportId}"
    const val OMNI_CONTENT_REPORT_CANCEL: String = "/omni/v1/content-reports/{reportId}/cancel"
    const val OMNI_CONTENT_REPORT_DISCARD: String = "/omni/v1/content-reports/{reportId}/discard"
    const val OMNI_CONTENT_REPORT_RECEIPT: String = "/omni/v1/content-reports/{reportId}/receipt"

    const val OMNI_TOKENS: String = "/omni/v1/tokens"
    const val OMNI_TOKEN_REVOKE: String = "/omni/v1/tokens/{tokenId}/revoke"

    /** All operationIds declared in the OpenAPI paths map (scaffold inventory). */
    val OPERATION_IDS: Set<String> = setOf(
        "getHealth",
        "listModels",
        "createChatCompletion",
        "createEmbedding",
        "getRequest",
        "cancelRequest",
        "getCommand",
        "streamRequestEvents",
        "createAsset",
        "uploadAsset",
        "commitAsset",
        "getAsset",
        "deleteAsset",
        "createJob",
        "listOwnJobs",
        "getJob",
        "cancelJob",
        "getMetricSummary",
        "getMetricDetail",
        "getSettings",
        "patchSettings",
        "listClients",
        "revokeClient",
        "enableLan",
        "disableLan",
        "createDiagnosticExport",
        "createContentReportProposal",
        "getContentReport",
        "cancelContentReport",
        "discardContentReport",
        "getContentReportReceipt",
        "createAsyncInferenceRequest",
        "issueLoopbackAdminToken",
        "listTokens",
        "revokeToken",
        "createLanPairingChallenge",
        "completeLanPairing",
    )
}
