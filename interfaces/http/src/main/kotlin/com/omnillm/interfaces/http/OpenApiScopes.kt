package com.omnillm.interfaces.http

/**
 * Required scopes per OpenAPI operationId (`x-omnillm-required-scope`).
 * Authority: `specs/openapi/omnillm.openapi.yaml` + `specs/access-control-catalog.yaml`.
 * Null means unauthenticated (minimal health / LAN pairing exchange only).
 */
object OpenApiScopes {
    /** operationId → required scope id, or null if security: [] */
    val REQUIRED: Map<String, String?> = mapOf(
        "getHealth" to null,
        "listModels" to "models.read",
        "createChatCompletion" to "inference.create",
        "createEmbedding" to "inference.create",
        "createAsyncInferenceRequest" to "inference.create",
        "getRequest" to "inference.read-own",
        "cancelRequest" to "inference.cancel",
        "streamRequestEvents" to "inference.read-own",
        "getCommand" to "commands.read-own",
        "createAsset" to "assets.create",
        "uploadAsset" to "assets.create",
        "commitAsset" to "assets.create",
        "getAsset" to "assets.read-own",
        "deleteAsset" to "assets.delete-own",
        "createJob" to "jobs.manage",
        "listOwnJobs" to "jobs.read-own",
        "getJob" to "jobs.read-own",
        "cancelJob" to "jobs.manage",
        "getMetricSummary" to "metrics.read-summary",
        "getMetricDetail" to "metrics.read-detail",
        "getSettings" to "settings.read",
        "patchSettings" to "settings.write",
        "listClients" to "clients.read",
        "revokeClient" to "clients.manage",
        "enableLan" to "lan.manage",
        "disableLan" to "lan.manage",
        "createLanPairingChallenge" to "lan.manage",
        "completeLanPairing" to null, // LAN TLS listener only; no bearer
        "createDiagnosticExport" to "diagnostics.export",
        "createContentReportProposal" to "content-reports.propose",
        "getContentReport" to "content-reports.read-own",
        "cancelContentReport" to "content-reports.manage-own",
        "discardContentReport" to "content-reports.manage-own",
        "getContentReportReceipt" to "content-reports.read-own",
        "issueLoopbackAdminToken" to "tokens.manage",
        "listTokens" to "tokens.manage",
        "revokeToken" to "tokens.manage",
    )

    fun requiredScope(operationId: String): String? =
        if (REQUIRED.containsKey(operationId)) REQUIRED[operationId]
        else error("Unknown operationId (fail closed INV-018): $operationId")

    fun isPublic(operationId: String): Boolean = requiredScope(operationId) == null
}
