package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.OmniError
import ai.omnillm.api.OmniErrorDetail
import com.omnillm.core.errors.TransportDeliveryGuarantee
import com.omnillm.core.errors.generated.OmniErrorCode

/**
 * Maps catalog [OmniErrorCode] values into AIDL [OmniError] / [CommandResult].
 * Unknown codes are never invented (INV-018).
 *
 * Stream / unary delivery labels from [TransportDeliveryGuarantee] are attached
 * to details when callers pass them (INV-013) — delivery is not a new error code.
 */
object BinderErrors {

    fun omniError(
        code: OmniErrorCode,
        message: String,
        details: Map<String, String> = emptyMap(),
        transport: TransportDeliveryGuarantee? = null,
    ): OmniError {
        val merged = details.toMutableMap()
        if (transport != null) {
            merged.putIfAbsent(TransportDeliveryGuarantee.DETAIL_KEY, transport.label)
            merged.putIfAbsent(TransportDeliveryGuarantee.DETAIL_NOTES_KEY, transport.notes)
        }
        val err = OmniError()
        err.code = code.code
        err.message = message
        err.retryable = code.retryable
        err.details = merged.map { (k, v) ->
            OmniErrorDetail().apply {
                key = k
                valueType = "string"
                stringValue = v
                longValue = 0L
                doubleValue = 0.0
                boolValue = false
            }
        }.toTypedArray()
        return err
    }

    fun failedCommand(
        commandId: String,
        code: OmniErrorCode,
        message: String,
        details: Map<String, String> = emptyMap(),
    ): CommandResult {
        val result = CommandResult()
        result.commandId = commandId.ifBlank { "00000000-0000-0000-0000-000000000000" }
        result.state = "FAILED"
        result.resourceVersion = 0L
        result.affectedResourceId = null
        result.resultSchemaId = null
        result.resultCanonicalJson = null
        result.error = omniError(code, message, details)
        return result
    }

    fun forbidden(message: String): OmniError =
        omniError(OmniErrorCode.FORBIDDEN, message)

    fun pairingRequired(message: String): OmniError =
        omniError(OmniErrorCode.PAIRING_REQUIRED, message)

    fun stateConflict(message: String): OmniError =
        omniError(OmniErrorCode.STATE_CONFLICT, message)

    fun admissionRejected(message: String): OmniError =
        omniError(OmniErrorCode.ADMISSION_REJECTED, message)

    fun notFound(message: String): OmniError =
        omniError(OmniErrorCode.NOT_FOUND, message)

    fun internal(message: String): OmniError =
        omniError(OmniErrorCode.INTERNAL, message)
}
