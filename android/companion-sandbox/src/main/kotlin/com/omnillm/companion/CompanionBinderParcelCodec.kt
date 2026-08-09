package com.omnillm.companion

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * Parcel encode/decode for [CompanionBinderWire] (Android-only).
 * Kept separate from pure ticket validation for unit-testability of policy.
 */
object CompanionBinderParcelCodec {

    fun writeTicket(dest: Parcel, ticket: CompanionBinderWire.TicketWire) {
        dest.writeInt(ticket.protocolMajor)
        dest.writeInt(ticket.protocolMinor)
        dest.writeString(ticket.runtimeInstanceId)
        dest.writeLong(ticket.runtimeEpoch)
        dest.writeString(ticket.bootId)
        dest.writeString(ticket.operationId)
        dest.writeString(ticket.commitId)
        dest.writeString(ticket.engineBuildId)
        dest.writeStringList(ticket.modelContentIds)
        dest.writeString(ticket.backend)
        dest.writeString(ticket.resourceEnvelopeSummary)
        dest.writeString(ticket.operatingConstraintSummary)
        dest.writeLong(ticket.monotonicDeadlineMs)
        dest.writeString(ticket.nonce)
        dest.writeString(ticket.placementClass)
        dest.writeString(ticket.macHex)
    }

    fun readTicket(src: Parcel): CompanionBinderWire.TicketWire {
        val protocolMajor = src.readInt()
        val protocolMinor = src.readInt()
        val runtimeInstanceId = src.readString().orEmpty()
        val runtimeEpoch = src.readLong()
        val bootId = src.readString().orEmpty()
        val operationId = src.readString().orEmpty()
        val commitId = src.readString().orEmpty()
        val engineBuildId = src.readString().orEmpty()
        val modelContentIds = ArrayList<String>()
        src.readStringList(modelContentIds)
        val backend = src.readString().orEmpty()
        val resourceEnvelopeSummary = src.readString().orEmpty()
        val operatingConstraintSummary = src.readString().orEmpty()
        val monotonicDeadlineMs = src.readLong()
        val nonce = src.readString().orEmpty()
        val placementClass = src.readString().orEmpty()
        val macHex = src.readString().orEmpty()
        return CompanionBinderWire.TicketWire(
            protocolMajor = protocolMajor,
            protocolMinor = protocolMinor,
            runtimeInstanceId = runtimeInstanceId,
            runtimeEpoch = runtimeEpoch,
            bootId = bootId,
            operationId = operationId,
            commitId = commitId,
            engineBuildId = engineBuildId,
            modelContentIds = modelContentIds,
            backend = backend,
            resourceEnvelopeSummary = resourceEnvelopeSummary,
            operatingConstraintSummary = operatingConstraintSummary,
            monotonicDeadlineMs = monotonicDeadlineMs,
            nonce = nonce,
            placementClass = placementClass,
            macHex = macHex,
        )
    }

    fun writeStringMap(dest: Parcel, map: Map<String, String>) {
        dest.writeInt(map.size)
        for ((k, v) in map) {
            dest.writeString(k)
            dest.writeString(v)
        }
    }

    fun readStringMap(src: Parcel): Map<String, String> {
        val n = src.readInt()
        if (n < 0 || n > 256) return emptyMap()
        val out = LinkedHashMap<String, String>(n)
        repeat(n) {
            val k = src.readString().orEmpty()
            val v = src.readString().orEmpty()
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    fun writeResult(dest: Parcel, result: CompanionCommandResult) {
        when (result) {
            is CompanionCommandResult.Ok -> {
                dest.writeInt(CompanionBinderWire.RESULT_OK)
                dest.writeString(null)
                dest.writeString(null)
                writeStringMap(dest, result.attributes)
            }
            is CompanionCommandResult.HandshakeOk -> {
                dest.writeInt(CompanionBinderWire.RESULT_HANDSHAKE_OK)
                dest.writeString(null)
                dest.writeString(null)
                writeStringMap(dest, CompanionBinderWire.identityToMap(result.report))
            }
            is CompanionCommandResult.Rejected -> {
                dest.writeInt(CompanionBinderWire.RESULT_REJECTED)
                dest.writeString(result.errorCode)
                dest.writeString(result.message)
                writeStringMap(dest, emptyMap())
            }
        }
    }

    fun readResult(src: Parcel): CompanionCommandResult {
        val kind = src.readInt()
        val errorCode = src.readString()
        val message = src.readString()
        val attrs = readStringMap(src)
        return when (kind) {
            CompanionBinderWire.RESULT_OK -> CompanionCommandResult.Ok(attrs)
            CompanionBinderWire.RESULT_HANDSHAKE_OK -> {
                val report = CompanionBinderWire.identityFromMap(attrs)
                    ?: return CompanionCommandResult.Rejected(
                        "INVALID_REQUEST",
                        "malformed identity report",
                    )
                CompanionCommandResult.HandshakeOk(report)
            }
            CompanionBinderWire.RESULT_REJECTED ->
                CompanionCommandResult.Rejected(
                    errorCode = errorCode ?: "INVALID_REQUEST",
                    message = message,
                )
            else ->
                CompanionCommandResult.Rejected(
                    "INVALID_REQUEST",
                    "unknown result kind $kind",
                )
        }
    }

    fun writeAttachArgs(
        dest: Parcel,
        supervisor: IBinder,
        runtimeEpoch: Long,
        bootId: String,
        runtimeInstanceId: String,
    ) {
        dest.writeStrongBinder(supervisor)
        dest.writeLong(runtimeEpoch)
        dest.writeString(bootId)
        dest.writeString(runtimeInstanceId)
    }

    fun writeHandshakeArgs(
        dest: Parcel,
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
        runtimeInstanceId: String,
        ticket: CompanionBinderWire.TicketWire,
    ) {
        dest.writeLong(runtimeEpoch)
        dest.writeString(bootId)
        dest.writeString(requestId)
        dest.writeString(runtimeInstanceId)
        writeTicket(dest, ticket)
    }

    fun writeRegisterRoPfdArgs(
        dest: Parcel,
        token: String,
        pfd: ParcelFileDescriptor,
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
    ) {
        dest.writeString(token)
        dest.writeParcelable(pfd, 0)
        dest.writeLong(runtimeEpoch)
        dest.writeString(bootId)
        dest.writeString(requestId)
    }
}
