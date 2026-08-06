package com.omnillm.interfaces.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for request/command idempotency claim shape (ADR-004/005).
 *
 * Field names follow OpenAPI `CommandRequest` / `AsyncInferenceRequest`
 * (snake_case on the wire). Canonical catalog: `specs/canonical-types.yaml#CommandRequest`.
 *
 * Claim keys (not invented here):
 * - Command: (principalId, operationKind, idempotencyKey) — see schema `idempotent_commands`
 * - Request: (principalId, operationKind, idempotencyKey) — see schema `inference_requests`
 * Client generates commandId/requestId and idempotencyKey **before** send; on reply loss query, do not blindly replay.
 */

/** OpenAPI `#/components/schemas/CommandRequest` */
@Serializable
data class CommandRequestDto(
    @SerialName("command_id") val commandId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("canonical_input_digest") val canonicalInputDigest: String,
    @SerialName("expected_version") val expectedVersion: Long? = null,
)

/** OpenAPI `#/components/schemas/AsyncInferenceRequest` (claim fields only; payloads later). */
@Serializable
data class AsyncInferenceRequestClaimDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("operation") val operation: String,
)

/** OpenAPI `#/components/schemas/AcceptedRequest` */
@Serializable
data class AcceptedRequestDto(
    @SerialName("request_id") val requestId: String,
    @SerialName("state") val state: String,
    @SerialName("query_url") val queryUrl: String? = null,
    @SerialName("events_url") val eventsUrl: String? = null,
)

/** OpenAPI `#/components/schemas/CommandResult` (subset for claim-or-return). */
@Serializable
data class CommandResultDto(
    @SerialName("command_id") val commandId: String,
    @SerialName("state") val state: String,
    @SerialName("resource_version") val resourceVersion: Long,
    @SerialName("affected_resource_id") val affectedResourceId: String? = null,
)

/**
 * Structural validators for claim envelopes. Full digest/UUID rules belong with
 * `:core:canonical` once encoding utilities land; these only enforce required presence
 * and documented length/pattern bounds from OpenAPI.
 */
object ClaimShape {
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private val UUID =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isValidCommandRequest(dto: CommandRequestDto): Boolean {
        if (dto.commandId.isBlank() || !UUID.matches(dto.commandId)) return false
        if (dto.idempotencyKey.isBlank() || dto.idempotencyKey.length > 128) return false
        if (!SHA256_HEX.matches(dto.canonicalInputDigest)) return false
        if (dto.expectedVersion != null && dto.expectedVersion < 0) return false
        return true
    }

    fun isValidAsyncInferenceClaim(dto: AsyncInferenceRequestClaimDto): Boolean {
        if (dto.requestId.isBlank() || !UUID.matches(dto.requestId)) return false
        if (dto.idempotencyKey.isBlank() || dto.idempotencyKey.length > 128) return false
        if (dto.operation != "CHAT" && dto.operation != "EMBEDDING") return false
        return true
    }
}
