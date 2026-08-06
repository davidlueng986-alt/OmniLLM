package com.omnillm.features.server.api

/**
 * Onboarding sample recipes (FEAT-SERVER §2).
 * Paths align with OpenAPI / AIDL; requestId / idempotencyKey are always
 * client-generated placeholders — never server-minted in samples.
 */
data class SdkSampleRecipe(
    val id: String,
    val titleKey: String,
    val descriptionKey: String,
    val transport: SdkTransport,
    val httpMethod: String?,
    val path: String?,
    val requiredScope: String?,
    /** JSON body or AIDL call sketch (no real secrets). */
    val bodyTemplate: String,
    /** Headers that must include client-generated ids when applicable. */
    val headerHints: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "id must be non-blank" }
        require(titleKey.isNotBlank()) { "titleKey must be non-blank" }
    }
}

enum class SdkTransport {
    HTTP_LOOPBACK,
    AIDL_RUNTIME,
    BOTH,
}

/**
 * Static sample catalog — pure data, no I/O.
 * Smoke-test samples use the same paths as production (no Admin shortcut).
 */
object SdkSampleCatalog {

    const val PLACEHOLDER_REQUEST_ID: String = "{{client-generated-request-id-uuid}}"
    const val PLACEHOLDER_IDEMPOTENCY_KEY: String = "{{client-generated-idempotency-key}}"
    const val PLACEHOLDER_COMMAND_ID: String = "{{client-generated-command-id-uuid}}"

    fun all(): List<SdkSampleRecipe> = listOf(
        health,
        listModels,
        chatCompletion,
        embedding,
        asyncRequest,
        cancelRequest,
        queryRequest,
        assetUpload,
        errorUnauthorized,
        aidlPairingNote,
    )

    val health: SdkSampleRecipe = SdkSampleRecipe(
        id = "health",
        titleKey = "sample.health",
        descriptionKey = "sample.health.desc",
        transport = SdkTransport.HTTP_LOOPBACK,
        httpMethod = "GET",
        path = "/health",
        requiredScope = null,
        bodyTemplate = "",
        notes = listOf("Unauthenticated minimal liveness; no model/device detail."),
    )

    val listModels: SdkSampleRecipe = SdkSampleRecipe(
        id = "list-models",
        titleKey = "sample.list-models",
        descriptionKey = "sample.list-models.desc",
        transport = SdkTransport.BOTH,
        httpMethod = "GET",
        path = "/v1/models",
        requiredScope = "models.read",
        bodyTemplate = "",
        headerHints = listOf("Authorization: Bearer <token>"),
        notes = listOf(
            "Capabilities ride on each model; do not assume HTTP 200 ⇒ all fields supported.",
        ),
    )

    val chatCompletion: SdkSampleRecipe = SdkSampleRecipe(
        id = "chat-completions",
        titleKey = "sample.chat",
        descriptionKey = "sample.chat.desc",
        transport = SdkTransport.HTTP_LOOPBACK,
        httpMethod = "POST",
        path = "/v1/chat/completions",
        requiredScope = "inference.create",
        bodyTemplate = """
            {
              "model": "{{model_id}}",
              "messages": [{"role":"user","content":"hello"}],
              "stream": false
            }
        """.trimIndent(),
        headerHints = listOf(
            "Authorization: Bearer <token>",
            "X-Request-Id: $PLACEHOLDER_REQUEST_ID",
            "Idempotency-Key: $PLACEHOLDER_IDEMPOTENCY_KEY",
        ),
        notes = listOf(
            "Client must mint requestId and idempotencyKey before send (ADR-004/005).",
            "Same scheduler/Governor path as production smoke test.",
        ),
    )

    val embedding: SdkSampleRecipe = SdkSampleRecipe(
        id = "embeddings",
        titleKey = "sample.embedding",
        descriptionKey = "sample.embedding.desc",
        transport = SdkTransport.HTTP_LOOPBACK,
        httpMethod = "POST",
        path = "/v1/embeddings",
        requiredScope = "inference.create",
        bodyTemplate = """
            {
              "model": "{{model_id}}",
              "input": "hello"
            }
        """.trimIndent(),
        headerHints = listOf(
            "Authorization: Bearer <token>",
            "X-Request-Id: $PLACEHOLDER_REQUEST_ID",
            "Idempotency-Key: $PLACEHOLDER_IDEMPOTENCY_KEY",
        ),
    )

    val asyncRequest: SdkSampleRecipe = SdkSampleRecipe(
        id = "async-request",
        titleKey = "sample.async-request",
        descriptionKey = "sample.async-request.desc",
        transport = SdkTransport.BOTH,
        httpMethod = "POST",
        path = "/omni/v1/requests",
        requiredScope = "inference.create",
        bodyTemplate = """
            {
              "request_id": "$PLACEHOLDER_REQUEST_ID",
              "idempotency_key": "$PLACEHOLDER_IDEMPOTENCY_KEY",
              "operation": "CHAT",
              "model": "{{model_id}}",
              "payload": {}
            }
        """.trimIndent(),
        headerHints = listOf("Authorization: Bearer <token>"),
        notes = listOf("Durable accept; use query/cancel with the same request_id."),
    )

    val cancelRequest: SdkSampleRecipe = SdkSampleRecipe(
        id = "cancel-request",
        titleKey = "sample.cancel",
        descriptionKey = "sample.cancel.desc",
        transport = SdkTransport.BOTH,
        httpMethod = "POST",
        path = "/omni/v1/requests/{requestId}/cancel",
        requiredScope = "inference.cancel",
        bodyTemplate = """
            {
              "command_id": "$PLACEHOLDER_COMMAND_ID",
              "idempotency_key": "$PLACEHOLDER_IDEMPOTENCY_KEY"
            }
        """.trimIndent(),
        headerHints = listOf("Authorization: Bearer <token>"),
        notes = listOf("After cancel, query for durable terminal — do not blind-replay."),
    )

    val queryRequest: SdkSampleRecipe = SdkSampleRecipe(
        id = "query-request",
        titleKey = "sample.query",
        descriptionKey = "sample.query.desc",
        transport = SdkTransport.BOTH,
        httpMethod = "GET",
        path = "/omni/v1/requests/{requestId}",
        requiredScope = "inference.read-own",
        bodyTemplate = "",
        headerHints = listOf("Authorization: Bearer <token>"),
        notes = listOf("Reply-loss recovery path (ADR-004/005)."),
    )

    val assetUpload: SdkSampleRecipe = SdkSampleRecipe(
        id = "asset-upload",
        titleKey = "sample.asset",
        descriptionKey = "sample.asset.desc",
        transport = SdkTransport.HTTP_LOOPBACK,
        httpMethod = "POST",
        path = "/omni/v1/assets",
        requiredScope = "assets.create",
        bodyTemplate = """
            {
              "command": {
                "command_id": "$PLACEHOLDER_COMMAND_ID",
                "idempotency_key": "$PLACEHOLDER_IDEMPOTENCY_KEY"
              },
              "content_type": "application/octet-stream",
              "max_bytes": 1048576
            }
        """.trimIndent(),
        headerHints = listOf("Authorization: Bearer <token>"),
    )

    val errorUnauthorized: SdkSampleRecipe = SdkSampleRecipe(
        id = "error-unauthorized",
        titleKey = "sample.error.unauthorized",
        descriptionKey = "sample.error.unauthorized.desc",
        transport = SdkTransport.HTTP_LOOPBACK,
        httpMethod = "GET",
        path = "/v1/models",
        requiredScope = "models.read",
        bodyTemplate = "",
        notes = listOf(
            "Missing/invalid bearer → UNAUTHORIZED; unpaired AIDL → PAIRING_REQUIRED.",
        ),
    )

    val aidlPairingNote: SdkSampleRecipe = SdkSampleRecipe(
        id = "aidl-pairing",
        titleKey = "sample.aidl.pairing",
        descriptionKey = "sample.aidl.pairing.desc",
        transport = SdkTransport.AIDL_RUNTIME,
        httpMethod = null,
        path = null,
        requiredScope = null,
        bodyTemplate = """
            // Binding reachability ≠ authorization (FEAT-SERVER §3).
            // 1) Bind RuntimeBindingService (transport only)
            // 2) Observe callingUid → PairingChallenge
            // 3) Local UI approve scopes → ClientRegistration handle
            // 4) Subsequent calls re-read system UID vs registration
        """.trimIndent(),
        notes = listOf(
            "Caller self-reported package is display evidence only (INV-011).",
            "Shared UID is one principal unless stronger managed identity exists.",
        ),
    )
}
