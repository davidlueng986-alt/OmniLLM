package com.omnillm.android.runtimeservice.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.omnillm.engines.api.PlacementClassLabels
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-side companion bind client (SEC-EXTERNAL-SANDBOX §2–§3, ADR-007).
 *
 * Also known as **HostCompanionClient** in product language.
 *
 * Responsibilities:
 * - Probe package install / different UID / same-signer via [CompanionPackageProbe]
 * - Explicit-component bind with signature permission (manifest-declared)
 * - Ticket handshake + supervisor death link via [CompanionSandboxProxy]
 * - Host RO PFD grant table ([HostPfdGrantTable]) before remote register
 * - Never rewrite untrusted acceleration to same-UID worker on bind failure
 *
 * When companion APK is not installed / bind fails / handshake fails ⇒
 * [HostCompanionResult.Unavailable] or placement [TRUST_PLACEMENT_REQUIRED],
 * never same-UID sandbox fallback.
 */
class CompanionHostClient(
    private val appContext: Context,
    private val probe: CompanionPackageProbe = CompanionPackageProbe(appContext),
) {
    private val bound = AtomicBoolean(false)
    private val binderRef = AtomicReference<IBinder?>(null)
    private val lastAvailability = AtomicReference(
        CompanionAvailability.unavailable("not probed"),
    )
    private val handshakeDone = AtomicBoolean(false)
    private val lastIdentity = AtomicReference<HostIdentityValidation.IdentityReport?>(null)
    private val grantTable = HostPfdGrantTable()
    /** Supervisor death marker held by host process (SEC-EXTERNAL-SANDBOX §6). */
    private val supervisorBinder = Binder()
    private val bindLatch = AtomicReference<CountDownLatch?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binderRef.set(service)
            bound.set(service != null)
            bindLatch.get()?.countDown()
            Log.i(TAG, "companion bound component=$name")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binderRef.set(null)
            bound.set(false)
            handshakeDone.set(false)
            lastIdentity.set(null)
            grantTable.releaseAll()
            Log.w(TAG, "companion disconnected component=$name")
        }

        override fun onBindingDied(name: ComponentName?) {
            binderRef.set(null)
            bound.set(false)
            handshakeDone.set(false)
            lastIdentity.set(null)
            grantTable.releaseAll()
            Log.w(TAG, "companion binding died component=$name")
        }

        override fun onNullBinding(name: ComponentName?) {
            binderRef.set(null)
            bound.set(false)
            bindLatch.get()?.countDown()
            Log.w(TAG, "companion null binding component=$name")
        }
    }

    /** Latest availability snapshot (probe + optional bind reachability). */
    fun availability(): CompanionAvailability = lastAvailability.get()

    /** Host grant table (request-scoped RO PFDs only). */
    fun pfdGrantTable(): HostPfdGrantTable = grantTable

    fun lastHandshakeIdentity(): HostIdentityValidation.IdentityReport? = lastIdentity.get()

    fun isHandshakeComplete(): Boolean = handshakeDone.get()

    /**
     * PackageManager probe + optional bind. Safe to call from control plane only.
     * Missing / disabled / same-UID / signer mismatch ⇒ unavailable (fail closed).
     */
    fun refreshAvailability(attemptBind: Boolean = true): CompanionAvailability {
        val base = probe.probe()
        if (!base.isAvailable) {
            lastAvailability.set(base)
            unbindQuietly()
            return base
        }
        if (!attemptBind) {
            lastAvailability.set(base)
            return base
        }
        val bindOk = bindExplicit(base.packageName)
        val updated = base.copy(
            bindReachable = bindOk,
            detail = if (bindOk) {
                base.detail
            } else {
                listOfNotNull(
                    base.detail.takeIf { it.isNotEmpty() },
                    "bind failed or protocol not reachable",
                ).joinToString("; ")
            },
        )
        lastAvailability.set(updated)
        return updated
    }

    /**
     * Resolve placement for untrusted accelerator workloads.
     * Never returns same-UID security sandbox when companion is missing.
     */
    fun resolveUntrustedAcceleratorPlacement(
        authenticityOk: Boolean,
        engineCodeTrusted: Boolean,
        engineStabilitySufficient: Boolean = true,
        isolatedCpuSupported: Boolean = false,
        phaseQualificationKnown: Boolean = true,
        requireBindReachable: Boolean = true,
    ): CompanionPlacementGate.Result {
        val companion = refreshAvailability(attemptBind = requireBindReachable)
        val gated = if (requireBindReachable && companion.isAvailable && !companion.bindReachable) {
            CompanionPlacementGate.Result(
                placementClass = PlacementClassLabels.TRUST_PLACEMENT_REQUIRED,
                executable = false,
                errorCode = CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                reason = "companion installed but bind not reachable; " +
                    "never same-UID security sandbox fallback",
            )
        } else {
            CompanionPlacementGate.resolve(
                authenticityOk = authenticityOk,
                engineCodeTrusted = engineCodeTrusted,
                engineStabilitySufficient = engineStabilitySufficient,
                requiresAccelerator = true,
                isolatedCpuSupported = isolatedCpuSupported,
                companion = companion,
                phaseQualificationKnown = phaseQualificationKnown,
            )
        }
        return gated
    }

    /**
     * Attach supervisor + ticket handshake (SEC-EXTERNAL-SANDBOX §3, §6).
     *
     * Order: bind → attachSupervisor (death link) → handshake ticket → validate identity.
     */
    fun dispatchHandshake(ticket: HostSandboxExecutionTicket): HostCompanionResult {
        val av = refreshAvailability(attemptBind = true)
        if (!av.installed) {
            return HostCompanionResult.Unavailable(
                errorCode = CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                message = "companion APK not installed; never same-UID sandbox",
            )
        }
        if (!av.isAvailable || !av.bindReachable || binderRef.get() == null) {
            return HostCompanionResult.Unavailable(
                errorCode = CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                message = "companion unavailable for handshake",
            )
        }
        if (ticket.placementClass != CompanionHostConstants.PLACEMENT_EXTERNAL_UID_ACCELERATED) {
            return HostCompanionResult.Rejected(
                errorCode = CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                message = "host ticket placement must be EXTERNAL_UID_ACCELERATED",
            )
        }

        val remote = binderRef.get()
            ?: return HostCompanionResult.Unavailable(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "binder lost before handshake",
            )
        val proxy = CompanionSandboxProxy(remote)

        // Protocol major probe (fail closed on mismatch before ticket).
        val remoteMajor = proxy.protocolMajor()
        val remoteMinor = proxy.protocolMinor()
        if (remoteMajor < 0 ||
            !CompanionPackageRules.protocolCompatible(remoteMajor, remoteMinor)
        ) {
            return HostCompanionResult.Rejected(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion protocol major/minor incompatible " +
                    "(remote=$remoteMajor.$remoteMinor host=" +
                    "${CompanionHostConstants.PROTOCOL_MAJOR}.${CompanionHostConstants.PROTOCOL_MINOR})",
            )
        }

        val attach = proxy.attachSupervisor(
            supervisor = supervisorBinder,
            runtimeEpoch = ticket.runtimeEpoch,
            bootId = ticket.bootId,
            runtimeInstanceId = ticket.runtimeInstanceId,
        )
        if (attach.kind == CompanionBinderWire.RESULT_REJECTED) {
            return HostCompanionResult.Rejected(
                attach.errorCode ?: CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                attach.message ?: "attachSupervisor rejected",
            )
        }

        val requestId = "hs-" + ticket.operationId
        val hs = proxy.handshake(
            runtimeEpoch = ticket.runtimeEpoch,
            bootId = ticket.bootId,
            requestId = requestId,
            runtimeInstanceId = ticket.runtimeInstanceId,
            ticket = ticket,
        )

        val expectedPackages = setOf(
            av.packageName,
            CompanionHostConstants.COMPANION_PACKAGE,
            CompanionHostConstants.COMPANION_PACKAGE_DEBUG,
        )
        val interpreted = HostIdentityValidation.interpretHandshakeReply(
            resultKind = hs.kind,
            errorCode = hs.errorCode,
            message = hs.message,
            attributes = hs.attributes,
            expectedPackageNames = expectedPackages,
            mainAppUid = probe.mainAppUidOrNull(),
        )
        when (interpreted) {
            is HostCompanionResult.HandshakeOk -> {
                handshakeDone.set(true)
                lastIdentity.set(interpreted.identity)
                lastAvailability.set(
                    av.copy(
                        bindReachable = true,
                        protocolCompatible = true,
                        detail = "handshake ok processInstance=" +
                            (interpreted.identity?.processInstanceId ?: ""),
                    ),
                )
            }
            else -> {
                handshakeDone.set(false)
                lastIdentity.set(null)
            }
        }
        return interpreted
    }

    /**
     * Grant + register a read-only PFD with companion (SEC-EXTERNAL-SANDBOX §4).
     * Host table records the grant first; remote adopt uses opaque token only.
     */
    fun registerReadOnlyPfd(
        token: String,
        pfd: ParcelFileDescriptor,
        runtimeEpoch: Long,
        bootId: String,
        requestId: String,
    ): HostCompanionResult {
        if (!handshakeDone.get()) {
            return HostCompanionResult.Rejected(
                "INVALID_REQUEST",
                "handshake required before PFD grant",
            )
        }
        val remote = binderRef.get()
            ?: return HostCompanionResult.Unavailable(
                CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                "companion binder unavailable",
            )
        when (
            val g = grantTable.grant(
                token = token,
                mode = HostPfdGrantTable.MODE_READ_ONLY,
                requestId = requestId,
                runtimeEpoch = runtimeEpoch,
                bootId = bootId,
                nowMonotonicMs = SystemClock.elapsedRealtime(),
            )
        ) {
            is HostPfdGrantTable.GrantResult.Rejected ->
                return HostCompanionResult.Rejected(g.errorCode, g.message)
            is HostPfdGrantTable.GrantResult.Ok -> Unit
        }
        val wire = CompanionSandboxProxy(remote).registerReadOnlyPfd(
            token = token,
            pfd = pfd,
            runtimeEpoch = runtimeEpoch,
            bootId = bootId,
            requestId = requestId,
        )
        return when (wire.kind) {
            CompanionBinderWire.RESULT_OK ->
                HostCompanionResult.Ok(wire.attributes)
            CompanionBinderWire.RESULT_REJECTED -> {
                grantTable.revoke(token)
                HostCompanionResult.Rejected(
                    wire.errorCode ?: "INVALID_REQUEST",
                    wire.message ?: "registerReadOnlyPfd rejected",
                )
            }
            else -> {
                grantTable.revoke(token)
                HostCompanionResult.Rejected(
                    CompanionHostConstants.ERROR_TRUST_PLACEMENT_REQUIRED,
                    "unexpected registerReadOnlyPfd result",
                )
            }
        }
    }

    fun closeSession(runtimeEpoch: Long, bootId: String, requestId: String = "close"): HostCompanionResult {
        val remote = binderRef.get()
        grantTable.releaseAll()
        handshakeDone.set(false)
        lastIdentity.set(null)
        if (remote == null) {
            return HostCompanionResult.Ok(mapOf("closed" to "local"))
        }
        val wire = CompanionSandboxProxy(remote).close(runtimeEpoch, bootId, requestId)
        return when (wire.kind) {
            CompanionBinderWire.RESULT_OK -> HostCompanionResult.Ok(wire.attributes)
            CompanionBinderWire.RESULT_REJECTED ->
                HostCompanionResult.Rejected(
                    wire.errorCode ?: "INVALID_REQUEST",
                    wire.message,
                )
            else -> HostCompanionResult.Ok(mapOf("closed" to "best-effort"))
        }
    }

    fun unbind() {
        grantTable.releaseAll()
        handshakeDone.set(false)
        lastIdentity.set(null)
        unbindQuietly()
        lastAvailability.set(CompanionAvailability.unavailable("unbound"))
    }

    private fun bindExplicit(companionPackage: String): Boolean {
        if (bound.get() && binderRef.get() != null) return true
        return try {
            val latch = CountDownLatch(1)
            bindLatch.set(latch)
            val intent = Intent(CompanionHostConstants.ACTION_BIND_SANDBOX).apply {
                component = ComponentName(
                    companionPackage,
                    CompanionHostConstants.SERVICE_CLASS,
                )
                // No tokens/paths/secrets in Intent extras (ANDROID-SERVICE).
            }
            val ok = appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE,
            )
            if (!ok) {
                bindLatch.set(null)
                return false
            }
            // Wait briefly for onServiceConnected (unit tests may inject binder).
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            bindLatch.set(null)
            bound.get() && binderRef.get() != null
        } catch (e: Exception) {
            Log.w(TAG, "bindExplicit failed: ${e.javaClass.simpleName}")
            bindLatch.set(null)
            false
        }
    }

    private fun unbindQuietly() {
        try {
            if (bound.get() || binderRef.get() != null) {
                appContext.unbindService(connection)
            }
        } catch (_: Exception) {
            // already unbound
        }
        bound.set(false)
        binderRef.set(null)
        handshakeDone.set(false)
    }

    /**
     * Test / same-process hook: inject an already-bound companion binder without
     * PackageManager install. Production always uses [bindExplicit].
     */
    fun injectBoundBinderForTests(service: IBinder) {
        binderRef.set(service)
        bound.set(true)
        lastAvailability.set(CompanionAvailability.available(bindReachable = true))
    }

    companion object {
        private const val TAG = "OmniCompanionHost"
        private const val BIND_TIMEOUT_MS: Long = 3_000L

        /** Product alias for host companion client. */
        fun create(appContext: Context): CompanionHostClient = CompanionHostClient(appContext)
    }
}

/** Product-facing alias (ticket language: HostCompanionClient). */
typealias HostCompanionClient = CompanionHostClient

sealed class HostCompanionResult {
    data class Unavailable(val errorCode: String, val message: String) : HostCompanionResult()
    data class Rejected(val errorCode: String, val message: String?) : HostCompanionResult()
    data class Ok(val attributes: Map<String, String> = emptyMap()) : HostCompanionResult()
    data class HandshakeOk(
        val attributes: Map<String, String> = emptyMap(),
        val identity: HostIdentityValidation.IdentityReport? = null,
    ) : HostCompanionResult()
}


