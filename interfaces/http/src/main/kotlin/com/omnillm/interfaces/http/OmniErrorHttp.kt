package com.omnillm.interfaces.http

import com.omnillm.core.errors.ErrorMapping
import com.omnillm.core.errors.TransportDeliveryGuarantee
import com.omnillm.core.errors.generated.OmniError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * HTTP projection of catalog [OmniError] (ADR-011 / INV-013).
 *
 * When [respond] is used for pre-stream or JSON paths, errors may carry
 * [TransportDeliveryGuarantee] labels via [ErrorMapping.withTransportDelivery]
 * so clients do not treat socket writes as application ACKs.
 */
object OmniErrorHttp {
    fun toDto(error: OmniError): OmniErrorDto {
        val details = if (error.details.isEmpty()) {
            JsonObject(emptyMap())
        } else {
            JsonObject(error.details.mapValues { JsonPrimitive(it.value) })
        }
        return OmniErrorDto(
            code = error.code.code,
            message = error.message ?: error.code.code,
            retryable = error.retryable,
            details = details,
        )
    }

    fun statusOf(error: OmniError): HttpStatusCode =
        HttpStatusCode.fromValue(error.httpStatus.coerceIn(400, 599))

    fun encode(error: OmniError): String =
        HttpJson.codec.encodeToString(toDto(error))

    /**
     * Project [error] for JSON / pre-stream HTTP. Defaults to [TransportDeliveryGuarantee.HTTP_JSON]
     * when no transport detail is present (SSE pre-stream should pass [TransportDeliveryGuarantee.HTTP_SSE]).
     */
    fun withDefaultHttpDelivery(
        error: OmniError,
        transport: TransportDeliveryGuarantee = TransportDeliveryGuarantee.HTTP_JSON,
    ): OmniError =
        if (ErrorMapping.transportDeliveryOf(error) != null) {
            error
        } else {
            ErrorMapping.withTransportDelivery(error, transport)
        }

    suspend fun respond(
        call: ApplicationCall,
        error: OmniError,
        transport: TransportDeliveryGuarantee = TransportDeliveryGuarantee.HTTP_JSON,
    ) {
        val annotated = withDefaultHttpDelivery(error, transport)
        call.respondText(
            text = encode(annotated),
            contentType = ContentType.Application.Json,
            status = statusOf(annotated),
        )
    }
}
