package com.omnillm.companion

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Companion sandbox service — different package/UID acceleration host
 * (SEC-EXTERNAL-SANDBOX, ADR-007).
 *
 * - Ticket handshake before any load
 * - Read-only PFD registration only (dup + ownership close)
 * - Supervisor death ⇒ fence, clear request data, [Process.killProcess]
 * - Never opens main-app private paths, DB, or secrets
 *
 * Cross-process surface: [CompanionBinderWire] via [onTransact].
 * Native accelerated load is not fully wired; commit path validates placement and
 * FD policy then returns capability-stub terminal until engine packs bind.
 */
class CompanionSandboxService : Service() {

    private val claimedNonces = ConcurrentHashMap.newKeySet<String>()
    private val fdRegistry = CompanionFdRegistry<ParcelFileDescriptor> { pfd ->
        try {
            pfd.close()
        } catch (_: Exception) {
            // ignore
        }
    }
    private val exiting = AtomicBoolean(false)
    private val bound = AtomicBoolean(false)
    private val processInstanceId = UUID.randomUUID().toString()
    private lateinit var fence: CompanionSupervisorFence
    /**
     * Per-instance ticket MAC key (SEC-07), generated at attachSupervisor
     * (pairing) and delivered to the host in the attach result attributes
     * (`sessionMacKeyHex`). Null until attach; cleared on fence/close.
     */
    private var ticketMacKey: ByteArray? = null

    private val binder = object : Binder(), CompanionSandboxBinder {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CompanionBinderWire.DESCRIPTOR)
                return true
            }
            data.enforceInterface(CompanionBinderWire.DESCRIPTOR)
            when (code) {
                CompanionBinderWire.TRANSACTION_ATTACH_SUPERVISOR -> {
                    val supervisor = data.readStrongBinder()
                    val epoch = data.readLong()
                    val bootId = data.readString().orEmpty()
                    val instanceId = data.readString().orEmpty()
                    val result = if (supervisor == null) {
                        CompanionCommandResult.Rejected("INVALID_REQUEST", "supervisor binder required")
                    } else {
                        this@CompanionSandboxService.attachSupervisor(
                            supervisor,
                            epoch,
                            bootId,
                            instanceId,
                        )
                    }
                    reply?.writeNoException()
                    if (reply != null) CompanionBinderParcelCodec.writeResult(reply, result)
                    return true
                }
                CompanionBinderWire.TRANSACTION_HANDSHAKE -> {
                    val epoch = data.readLong()
                    val bootId = data.readString().orEmpty()
                    val requestId = data.readString().orEmpty()
                    val instanceId = data.readString().orEmpty()
                    val ticketWire = CompanionBinderParcelCodec.readTicket(data)
                    val cmd = CompanionCommand.Handshake(
                        runtimeEpoch = epoch,
                        bootId = bootId,
                        requestId = requestId,
                        runtimeInstanceId = instanceId,
                        ticket = ticketWire.toTicket(),
                    )
                    val result = this@CompanionSandboxService.dispatch(cmd)
                    reply?.writeNoException()
                    if (reply != null) CompanionBinderParcelCodec.writeResult(reply, result)
                    return true
                }
                CompanionBinderWire.TRANSACTION_REGISTER_RO_PFD -> {
                    val token = data.readString().orEmpty()
                    @Suppress("DEPRECATION")
                    val pfd = data.readParcelable<ParcelFileDescriptor>(
                        ParcelFileDescriptor::class.java.classLoader,
                    )
                    val epoch = data.readLong()
                    val bootId = data.readString().orEmpty()
                    val requestId = data.readString().orEmpty()
                    val result = if (pfd == null) {
                        CompanionCommandResult.Rejected("INVALID_REQUEST", "pfd required")
                    } else {
                        this@CompanionSandboxService.registerReadOnlyPfd(
                            token,
                            pfd,
                            epoch,
                            bootId,
                            requestId,
                        )
                    }
                    reply?.writeNoException()
                    if (reply != null) CompanionBinderParcelCodec.writeResult(reply, result)
                    return true
                }
                CompanionBinderWire.TRANSACTION_CLOSE -> {
                    val epoch = data.readLong()
                    val bootId = data.readString().orEmpty()
                    val requestId = data.readString().orEmpty()
                    val result = this@CompanionSandboxService.dispatch(
                        CompanionCommand.Close(
                            runtimeEpoch = epoch,
                            bootId = bootId,
                            requestId = requestId,
                        ),
                    )
                    reply?.writeNoException()
                    if (reply != null) CompanionBinderParcelCodec.writeResult(reply, result)
                    return true
                }
                CompanionBinderWire.TRANSACTION_CANCEL -> {
                    val epoch = data.readLong()
                    val bootId = data.readString().orEmpty()
                    val requestId = data.readString().orEmpty()
                    val operationId = data.readString().orEmpty()
                    val result = this@CompanionSandboxService.dispatch(
                        CompanionCommand.Cancel(
                            runtimeEpoch = epoch,
                            bootId = bootId,
                            requestId = requestId,
                            operationId = operationId,
                        ),
                    )
                    reply?.writeNoException()
                    if (reply != null) CompanionBinderParcelCodec.writeResult(reply, result)
                    return true
                }
                CompanionBinderWire.TRANSACTION_QUERY -> {
                    val epoch = data.readLong()
                    val bootId = data.readString().orEmpty()
                    val requestId = data.readString().orEmpty()
                    val result = this@CompanionSandboxService.dispatch(
                        CompanionCommand.Query(
                            runtimeEpoch = epoch,
                            bootId = bootId,
                            requestId = requestId,
                        ),
                    )
                    reply?.writeNoException()
                    if (reply != null) CompanionBinderParcelCodec.writeResult(reply, result)
                    return true
                }
                CompanionBinderWire.TRANSACTION_GET_PROTOCOL_MAJOR -> {
                    reply?.writeNoException()
                    reply?.writeInt(CompanionSandboxModule.PROTOCOL_MAJOR)
                    return true
                }
                CompanionBinderWire.TRANSACTION_GET_PROTOCOL_MINOR -> {
                    reply?.writeNoException()
                    reply?.writeInt(CompanionSandboxModule.PROTOCOL_MINOR)
                    return true
                }
                CompanionBinderWire.TRANSACTION_PROCESS_ROLE -> {
                    reply?.writeNoException()
                    reply?.writeString(CompanionSandboxModule.PROCESS_ROLE)
                    return true
                }
                CompanionBinderWire.TRANSACTION_PID -> {
                    reply?.writeNoException()
                    reply?.writeInt(Process.myPid())
                    return true
                }
                CompanionBinderWire.TRANSACTION_UID -> {
                    reply?.writeNoException()
                    reply?.writeInt(Process.myUid())
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        override fun attachSupervisor(
            supervisorBinder: IBinder,
            runtimeEpoch: Long,
            bootId: String,
            runtimeInstanceId: String,
        ): CompanionCommandResult =
            this@CompanionSandboxService.attachSupervisor(
                supervisorBinder,
                runtimeEpoch,
                bootId,
                runtimeInstanceId,
            )

        override fun dispatch(command: CompanionCommand): CompanionCommandResult =
            this@CompanionSandboxService.dispatch(command)

        override fun registerReadOnlyPfd(
            token: String,
            pfd: ParcelFileDescriptor,
            runtimeEpoch: Long,
            bootId: String,
            requestId: String,
        ): CompanionCommandResult =
            this@CompanionSandboxService.registerReadOnlyPfd(
                token,
                pfd,
                runtimeEpoch,
                bootId,
                requestId,
            )

        override fun processRole(): String = CompanionSandboxModule.PROCESS_ROLE

        override fun pid(): Int = Process.myPid()

        override fun uid(): Int = Process.myUid()
    }

    override fun onCreate() {
        super.onCreate()
        fence = CompanionSupervisorFence {
            onSupervisorDied()
        }
        Log.i(TAG, "CompanionSandboxService created pid=${Process.myPid()} uid=${Process.myUid()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        // Intent must not carry tokens/paths/secrets (ANDROID-SERVICE).
        return binder
    }

    override fun onDestroy() {
        fence.detach()
        fdRegistry.releaseAll()
        super.onDestroy()
    }

    private fun attachSupervisor(
        supervisorBinder: IBinder,
        runtimeEpoch: Long,
        bootId: String,
        runtimeInstanceId: String,
    ): CompanionCommandResult {
        if (exiting.get() || fence.isFenced) {
            return CompanionCommandResult.Rejected("WORKER_DIED", "companion fenced or exiting")
        }
        return when (fence.attach(supervisorBinder, runtimeEpoch, bootId, runtimeInstanceId)) {
            CompanionFenceAttachResult.Attached -> {
                bound.set(false) // still need ticket handshake
                // SEC-07: establish per-instance pairing key; host uses it to MAC tickets.
                val key = SandboxTicketMac.randomKeyBytes()
                ticketMacKey = key
                CompanionCommandResult.Ok(
                    mapOf(
                        "pid" to Process.myPid().toString(),
                        "processInstanceId" to processInstanceId,
                        "sessionMacKeyHex" to SandboxTicketMac.encodeHex(key),
                    ),
                )
            }
            CompanionFenceAttachResult.AlreadyFenced ->
                CompanionCommandResult.Rejected("WORKER_DIED", "already fenced")
            CompanionFenceAttachResult.SupervisorAlreadyDead ->
                CompanionCommandResult.Rejected("WORKER_DIED", "supervisor already dead")
        }
    }

    private fun dispatch(command: CompanionCommand): CompanionCommandResult {
        if (exiting.get()) {
            return CompanionCommandResult.Rejected("WORKER_DIED", "companion exiting")
        }
        val session = sessionView()
        val gateReject = CompanionCommandGate.gate(
            command = command,
            session = session,
            claimedNonces = claimedNonces,
            nowMonotonicMs = SystemClock.elapsedRealtime(),
            ticketMacKey = ticketMacKey,
        )
        if (gateReject != null) return gateReject

        return when (command) {
            is CompanionCommand.Handshake -> handleHandshake(command)
            is CompanionCommand.RegisterReadOnlyFd ->
                CompanionCommandResult.Rejected(
                    "INVALID_REQUEST",
                    "use registerReadOnlyPfd binder entry for PFD adoption",
                )
            is CompanionCommand.Load -> handleLoad(command)
            is CompanionCommand.Cancel -> CompanionCommandResult.Ok(mapOf("cancelled" to "true"))
            is CompanionCommand.Query -> CompanionCommandResult.Ok(mapOf("status" to "idle"))
            is CompanionCommand.Close -> handleClose()
        }
    }

    private fun handleHandshake(command: CompanionCommand.Handshake): CompanionCommandResult {
        // Epoch/instance must match supervisor attach.
        if (!fence.validateEpoch(command.runtimeEpoch, command.bootId)) {
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "attach epoch mismatch")
        }
        if (fence.currentRuntimeInstanceId != command.runtimeInstanceId) {
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "runtime instance mismatch")
        }
        // Ticket fields must match attach context (replay / cross-artifact fail closed).
        val ticket = command.ticket
        if (ticket.runtimeEpoch != command.runtimeEpoch ||
            ticket.bootId != command.bootId ||
            ticket.runtimeInstanceId != command.runtimeInstanceId
        ) {
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "ticket context mismatch")
        }
        claimedNonces.add(ticket.nonce)
        bound.set(true)
        return CompanionCommandResult.HandshakeOk(buildIdentityReport())
    }

    private fun registerReadOnlyPfd(
        token: String,
        pfd: ParcelFileDescriptor,
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
    ): CompanionCommandResult {
        if (exiting.get() || fence.isFenced) {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
            return CompanionCommandResult.Rejected("WORKER_DIED", "companion fenced")
        }
        if (!bound.get()) {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "handshake required")
        }
        if (!fence.validateEpoch(runtimeEpoch, bootId)) {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "epoch fence failed")
        }
        CompanionFdPolicy.rejectReasonForToken(token)?.let { reason ->
            try {
                pfd.close()
            } catch (_: Exception) {
            }
            return CompanionCommandResult.Rejected("INVALID_REQUEST", reason)
        }
        // Adopt dup; caller may close original. Read-only semantics enforced by host
        // opening mode; companion never re-opens by path.
        val adopted = try {
            ParcelFileDescriptor.dup(pfd.fileDescriptor)
        } catch (_: Exception) {
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "dup failed")
        }
        if (!fdRegistry.register(token, adopted)) {
            return CompanionCommandResult.Rejected("INVALID_REQUEST", "fd register failed")
        }
        @Suppress("UNUSED_VARIABLE")
        val _req = requestId
        return CompanionCommandResult.Ok(
            mapOf("token" to token, "mode" to CompanionFdPolicy.MODE_READ_ONLY),
        )
    }

    private fun handleLoad(command: CompanionCommand.Load): CompanionCommandResult {
        for (token in command.contentFdTokens) {
            if (fdRegistry.get(token) == null) {
                return CompanionCommandResult.Rejected("INVALID_REQUEST", "unknown fd token")
            }
        }
        // Native accelerated path not wired: fail closed with catalog error.
        // Placement was valid; backend/engine binding is not yet present.
        return CompanionCommandResult.Rejected(
            errorCode = "CAPABILITY_UNSUPPORTED",
            message = "companion accelerator backend not bound in this build",
        )
    }

    private fun handleClose(): CompanionCommandResult {
        fdRegistry.releaseAll()
        bound.set(false)
        ticketMacKey = null
        fence.stopAccepting()
        return CompanionCommandResult.Ok(mapOf("closed" to "true"))
    }

    private fun onSupervisorDied() {
        if (!exiting.compareAndSet(false, true)) return
        Log.w(TAG, "Supervisor died — fencing companion and exiting")
        bound.set(false)
        ticketMacKey = null
        fdRegistry.releaseAll()
        // SEC-EXTERNAL-SANDBOX §6: self-terminate; host marks models/sessions LOST.
        Process.killProcess(Process.myPid())
    }

    private fun sessionView(): CompanionSessionView =
        object : CompanionSessionView {
            override val isAcceptingHandshake: Boolean
                get() = fence.isAccepting && !bound.get() && !exiting.get()
            override val isBound: Boolean
                get() = bound.get()
            override val isFenced: Boolean
                get() = fence.isFenced
            override val isExiting: Boolean
                get() = exiting.get()

            override fun validateEpoch(runtimeEpoch: Long, bootId: String): Boolean =
                fence.validateEpoch(runtimeEpoch, bootId)
        }

    private fun buildIdentityReport(): CompanionIdentityReport {
        val pm = packageManager
        val pkg = packageName
        val pInfo = pm.getPackageInfo(pkg, 0)
        val versionName = pInfo.versionName ?: "0"
        @Suppress("DEPRECATION")
        val versionCode = pInfo.versionCode.toLong()
        val signer = resolveSignerDigest(pm, pkg)
        return CompanionIdentityReport(
            packageName = pkg,
            packageVersionName = versionName,
            packageVersionCode = versionCode,
            signerDigestHex = signer,
            processInstanceId = processInstanceId,
            pid = Process.myPid(),
            protocolMajor = CompanionSandboxModule.PROTOCOL_MAJOR,
            protocolMinor = CompanionSandboxModule.PROTOCOL_MINOR,
            uid = Process.myUid(),
        )
    }

    private fun resolveSignerDigest(pm: PackageManager, pkg: String): String {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            }
            val bytes: ByteArray? = if (android.os.Build.VERSION.SDK_INT >= 28) {
                val sigs = info.signingInfo?.apkContentsSigners
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (bytes == null) {
                "unknown"
            } else {
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                digest.joinToString("") { b -> "%02x".format(b) }
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    private companion object {
        private const val TAG = "OmniCompanion"
    }
}

/**
 * Local binder surface for same-process tests.
 * Production host uses [CompanionBinderWire] transact via [CompanionSandboxProxy].
 */
interface CompanionSandboxBinder {
    fun attachSupervisor(
        supervisorBinder: IBinder,
        runtimeEpoch: Long,
        bootId: String,
        runtimeInstanceId: String,
    ): CompanionCommandResult

    fun dispatch(command: CompanionCommand): CompanionCommandResult

    fun registerReadOnlyPfd(
        token: String,
        pfd: ParcelFileDescriptor,
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
    ): CompanionCommandResult

    fun processRole(): String
    fun pid(): Int
    fun uid(): Int
}
