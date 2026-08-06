package com.omnillm.android.runtimeservice.companion

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException

/**
 * Host proxy for companion [CompanionBinderWire] transactions.
 * Independent of companion APK types — parcel field order must match.
 */
class CompanionSandboxProxy(
    private val remote: IBinder,
) {
    data class WireResult(
        val kind: Int,
        val errorCode: String?,
        val message: String?,
        val attributes: Map<String, String>,
    )

    fun attachSupervisor(
        supervisor: IBinder,
        runtimeEpoch: Long,
        bootId: String,
        runtimeInstanceId: String,
    ): WireResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionBinderWire.DESCRIPTOR)
            data.writeStrongBinder(supervisor)
            data.writeLong(runtimeEpoch)
            data.writeString(bootId)
            data.writeString(runtimeInstanceId)
            remote.transact(CompanionBinderWire.TRANSACTION_ATTACH_SUPERVISOR, data, reply, 0)
            reply.readException()
            readResult(reply)
        } catch (e: RemoteException) {
            rejected("WORKER_DIED", "attach remote: ${e.javaClass.simpleName}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun handshake(
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
        runtimeInstanceId: String,
        ticket: HostSandboxExecutionTicket,
    ): WireResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionBinderWire.DESCRIPTOR)
            data.writeLong(runtimeEpoch)
            data.writeString(bootId)
            data.writeString(requestId)
            data.writeString(runtimeInstanceId)
            writeTicket(data, ticket)
            remote.transact(CompanionBinderWire.TRANSACTION_HANDSHAKE, data, reply, 0)
            reply.readException()
            readResult(reply)
        } catch (e: RemoteException) {
            rejected("WORKER_DIED", "handshake remote: ${e.javaClass.simpleName}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun registerReadOnlyPfd(
        token: String,
        pfd: ParcelFileDescriptor,
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
    ): WireResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionBinderWire.DESCRIPTOR)
            data.writeString(token)
            data.writeParcelable(pfd, 0)
            data.writeLong(runtimeEpoch)
            data.writeString(bootId)
            data.writeString(requestId)
            remote.transact(CompanionBinderWire.TRANSACTION_REGISTER_RO_PFD, data, reply, 0)
            reply.readException()
            readResult(reply)
        } catch (e: RemoteException) {
            rejected("WORKER_DIED", "registerRoPfd remote: ${e.javaClass.simpleName}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun close(runtimeEpoch: Long, bootId: String, requestId: String): WireResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionBinderWire.DESCRIPTOR)
            data.writeLong(runtimeEpoch)
            data.writeString(bootId)
            data.writeString(requestId)
            remote.transact(CompanionBinderWire.TRANSACTION_CLOSE, data, reply, 0)
            reply.readException()
            readResult(reply)
        } catch (e: RemoteException) {
            rejected("WORKER_DIED", "close remote: ${e.javaClass.simpleName}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun protocolMajor(): Int = intQuery(CompanionBinderWire.TRANSACTION_GET_PROTOCOL_MAJOR)

    fun protocolMinor(): Int = intQuery(CompanionBinderWire.TRANSACTION_GET_PROTOCOL_MINOR)

    fun processRole(): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionBinderWire.DESCRIPTOR)
            remote.transact(CompanionBinderWire.TRANSACTION_PROCESS_ROLE, data, reply, 0)
            reply.readException()
            reply.readString().orEmpty()
        } catch (_: RemoteException) {
            ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun intQuery(code: Int): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(CompanionBinderWire.DESCRIPTOR)
            remote.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (_: RemoteException) {
            -1
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun writeTicket(dest: Parcel, ticket: HostSandboxExecutionTicket) {
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
    }

    private fun readResult(src: Parcel): WireResult {
        val kind = src.readInt()
        val errorCode = src.readString()
        val message = src.readString()
        val n = src.readInt()
        val attrs = LinkedHashMap<String, String>()
        if (n in 0..256) {
            repeat(n) {
                val k = src.readString().orEmpty()
                val v = src.readString().orEmpty()
                if (k.isNotEmpty()) attrs[k] = v
            }
        }
        return WireResult(kind, errorCode, message, attrs)
    }

    private fun rejected(code: String, msg: String): WireResult =
        WireResult(CompanionBinderWire.RESULT_REJECTED, code, msg, emptyMap())
}
