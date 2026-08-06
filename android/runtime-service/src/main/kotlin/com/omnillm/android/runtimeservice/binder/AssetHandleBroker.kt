package com.omnillm.android.runtimeservice.binder

import ai.omnillm.api.CommandResult
import ai.omnillm.api.OmniAssetCreateRequest
import ai.omnillm.api.OmniAssetInfo
import ai.omnillm.api.OmniAssetUploadRequest
import ai.omnillm.api.OmniCommandRequest
import android.os.ParcelFileDescriptor
import com.omnillm.android.runtimeservice.storage.PfdMaterializeHelpers
import com.omnillm.core.canonical.IdentityHashing
import com.omnillm.core.canonical.generated.CanonicalEncoding
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.canonical.generated.Sha256Digest
import com.omnillm.core.contracts.AssetHandleId
import com.omnillm.core.contracts.CommandId
import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.data.modelstore.MaterializeBounds
import com.omnillm.runtime.requestregistry.ClaimOutcome
import com.omnillm.runtime.requestregistry.CommandLedger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AssetHandle broker shared by AIDL (PFD) and HTTP (upload) paths
 * (CORE-INTERFACE §8–9, ANDROID-BINDER §6, ASSET state machine).
 *
 * AIDL: SDK hands PFD → service dups, fstats, bounded materialize, then same
 * owner/TTL/READY/pin semantics as HTTP AssetHandle.
 *
 * No filesystem paths are exposed on the wire (ASSET invariant).
 */
class AssetHandleBroker(
    private val commandLedger: CommandLedger,
    private val quarantineDir: File,
    private val bounds: MaterializeBounds = MaterializeBounds.DEFAULT,
) {
    private val assets = ConcurrentHashMap<String, AssetRecord>()
    private val lock = Any()

    init {
        if (!quarantineDir.exists()) {
            quarantineDir.mkdirs()
        }
    }

    fun create(
        principal: PrincipalId,
        request: OmniAssetCreateRequest,
    ): OmniAssetInfo {
        val assetIdRaw = request.assetId?.trim().orEmpty()
        val command = request.command
            ?: return assetInfoFailed(
                assetId = assetIdRaw,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "command required",
            )
        if (assetIdRaw.isEmpty()) {
            return assetInfoFailed(
                assetId = "",
                code = OmniErrorCode.INVALID_REQUEST,
                message = "assetId required",
            )
        }
        val assetId = try {
            AssetHandleId.parse(assetIdRaw)
        } catch (_: IllegalArgumentException) {
            return assetInfoFailed(
                assetId = assetIdRaw,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "invalid assetId",
            )
        }
        if (request.maxBytes <= 0L) {
            return assetInfoFailed(
                assetId = assetIdRaw,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "maxBytes must be positive",
            )
        }
        if (request.maxBytes > bounds.maxFileBytes) {
            return assetInfoFailed(
                assetId = assetIdRaw,
                code = OmniErrorCode.TRANSPORT_TOO_LARGE,
                message = "maxBytes exceeds policy cap",
            )
        }
        if (request.ttlSeconds <= 0L) {
            return assetInfoFailed(
                assetId = assetIdRaw,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "ttlSeconds must be positive",
            )
        }
        val expectedSha = request.expectedSha256?.lowercase()
        if (expectedSha != null && !CanonicalEncoding.isDigestHex64(expectedSha)) {
            return assetInfoFailed(
                assetId = assetIdRaw,
                code = OmniErrorCode.INVALID_REQUEST,
                message = "expectedSha256 must be sha256 hex",
            )
        }

        val claimResult = claimCommand(
            principal = principal,
            command = command,
            operationKind = OP_CREATE_ASSET,
            digestFallback = IdentityHashing.sha256Hex(
                """{"op":"CREATE_ASSET","assetId":"$assetIdRaw","maxBytes":${request.maxBytes}}""",
            ),
        )
        val commandId = when (claimResult) {
            is CommandClaim.Conflict ->
                return assetInfoFailed(
                    assetId = assetIdRaw,
                    code = OmniErrorCode.IDEMPOTENCY_CONFLICT,
                    message = claimResult.message,
                )
            is CommandClaim.ExistingTerminal ->
                return get(principal, assetIdRaw)
            is CommandClaim.Ok -> claimResult.commandId
        }

        val now = System.currentTimeMillis()
        val expires = now + request.ttlSeconds * 1000L
        val record = AssetRecord(
            assetId = assetId.value,
            ownerPrincipalId = principal.value,
            purpose = request.purpose.orEmpty().ifBlank { "multimodal" },
            maxBytes = request.maxBytes,
            contentTypeHint = request.contentTypeHint,
            expectedSha256 = expectedSha,
            state = "CREATED",
            bytes = 0L,
            sha256 = null,
            expiresAtEpochMillis = expires,
            resourceVersion = 1L,
            materializePath = null,
            pinCount = 0,
            createdAtEpochMillis = now,
        )
        val prev = assets.putIfAbsent(assetId.value, record)
        if (prev != null) {
            if (prev.ownerPrincipalId != principal.value) {
                failCommand(commandId, OmniErrorCode.FORBIDDEN)
                return assetInfoFailed(
                    assetId = assetIdRaw,
                    code = OmniErrorCode.FORBIDDEN,
                    message = "assetId owned by another principal",
                )
            }
            // Idempotent create of same handle.
            succeedCommand(commandId, assetIdRaw)
            return toInfo(prev)
        }
        succeedCommand(commandId, assetIdRaw)
        return toInfo(record)
    }

    fun upload(
        principal: PrincipalId,
        request: OmniAssetUploadRequest,
        content: ParcelFileDescriptor?,
    ): CommandResult {
        val assetId = request.assetId.orEmpty()
        val command = request.command
        if (content == null) {
            content // keep compiler happy
            return BinderErrors.failedCommand(
                commandId = command?.commandId.orEmpty(),
                code = OmniErrorCode.INVALID_REQUEST,
                message = "content PFD required",
            )
        }
        if (command == null) {
            safeClose(content)
            return BinderErrors.failedCommand(
                commandId = "",
                code = OmniErrorCode.INVALID_REQUEST,
                message = "command required",
            )
        }

        val claimResult = claimCommand(
            principal = principal,
            command = command,
            operationKind = OP_UPLOAD_ASSET,
            digestFallback = IdentityHashing.sha256Hex(
                """{"op":"UPLOAD_ASSET","assetId":"$assetId"}""",
            ),
        )
        val commandId = when (claimResult) {
            is CommandClaim.Conflict -> {
                safeClose(content)
                return BinderErrors.failedCommand(
                    commandId = command.commandId.orEmpty(),
                    code = OmniErrorCode.IDEMPOTENCY_CONFLICT,
                    message = claimResult.message,
                )
            }
            is CommandClaim.ExistingTerminal -> {
                safeClose(content)
                return commandResultFromExisting(claimResult)
            }
            is CommandClaim.Ok -> claimResult.commandId
        }

        val record = assets[assetId]
        if (record == null) {
            safeClose(content)
            failCommand(commandId, OmniErrorCode.NOT_FOUND)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.NOT_FOUND,
                message = "asset not found",
            )
        }
        if (record.ownerPrincipalId != principal.value) {
            safeClose(content)
            failCommand(commandId, OmniErrorCode.FORBIDDEN)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.FORBIDDEN,
                message = "not asset owner",
            )
        }
        if (isExpired(record)) {
            safeClose(content)
            transition(record.assetId) { it.copy(state = "EXPIRED", resourceVersion = it.resourceVersion + 1) }
            failCommand(commandId, OmniErrorCode.ASSET_EXPIRED)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.ASSET_EXPIRED,
                message = "asset TTL elapsed",
            )
        }
        if (record.state != "CREATED" && record.state != "UPLOADING") {
            safeClose(content)
            failCommand(commandId, OmniErrorCode.STATE_CONFLICT)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.STATE_CONFLICT,
                message = "asset state ${record.state} rejects upload",
            )
        }

        transition(assetId) { it.copy(state = "UPLOADING", resourceVersion = it.resourceVersion + 1) }

        val expectedBytes = if (request.hasExpectedBytes) request.expectedBytes else null
        if (expectedBytes != null && expectedBytes > record.maxBytes) {
            safeClose(content)
            transition(assetId) { it.copy(state = "REJECTED", resourceVersion = it.resourceVersion + 1) }
            failCommand(commandId, OmniErrorCode.TRANSPORT_TOO_LARGE)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.TRANSPORT_TOO_LARGE,
                message = "expectedBytes exceeds maxBytes",
            )
        }

        val outFile = File(quarantineDir, "asset-${record.assetId}.bin")
        val cancel = AtomicBoolean(false)
        val materialize = try {
            PfdMaterializeHelpers.copyPfdBounded(
                pfd = content,
                role = "asset",
                materializeHandle = record.assetId,
                expectedByteLength = expectedBytes,
                expectedDigestHex = request.expectedSha256?.lowercase() ?: record.expectedSha256,
                bounds = bounds.copy(maxFileBytes = minOf(bounds.maxFileBytes, record.maxBytes)),
                allowPipe = true,
                cancel = cancel,
                outputFactory = { FileOutputStream(outFile) },
            )
        } finally {
            safeClose(content)
        }

        materialize.fold(
            onSuccess = { outcome ->
                if (outcome.byteLength > record.maxBytes) {
                    outFile.delete()
                    transition(assetId) {
                        it.copy(state = "REJECTED", resourceVersion = it.resourceVersion + 1)
                    }
                    failCommand(commandId, OmniErrorCode.TRANSPORT_TOO_LARGE)
                    return BinderErrors.failedCommand(
                        commandId = command.commandId,
                        code = OmniErrorCode.TRANSPORT_TOO_LARGE,
                        message = "materialized size exceeds maxBytes",
                    )
                }
                val expected = record.expectedSha256 ?: request.expectedSha256?.lowercase()
                if (expected != null && expected != outcome.sha256Hex) {
                    outFile.delete()
                    transition(assetId) {
                        it.copy(state = "REJECTED", resourceVersion = it.resourceVersion + 1)
                    }
                    failCommand(commandId, OmniErrorCode.INVALID_REQUEST)
                    return BinderErrors.failedCommand(
                        commandId = command.commandId,
                        code = OmniErrorCode.INVALID_REQUEST,
                        message = "digest mismatch",
                    )
                }
                // UPLOADING → VERIFYING (materialize done); commit promotes to READY.
                transition(assetId) {
                    it.copy(
                        state = "VERIFYING",
                        bytes = outcome.byteLength,
                        sha256 = outcome.sha256Hex,
                        materializePath = outFile.absolutePath,
                        resourceVersion = it.resourceVersion + 1,
                    )
                }
                succeedCommand(commandId, assetId)
                return CommandResult().apply {
                    this.commandId = command.commandId
                    this.state = "SUCCEEDED"
                    this.resourceVersion = assets[assetId]?.resourceVersion ?: 0L
                    this.affectedResourceId = assetId
                    this.resultSchemaId = null
                    this.resultCanonicalJson = null
                    this.error = null
                }
            },
            onFailure = { err ->
                outFile.delete()
                transition(assetId) {
                    it.copy(state = "REJECTED", resourceVersion = it.resourceVersion + 1)
                }
                val code = when (err) {
                    is com.omnillm.data.modelstore.MaterializeException ->
                        OmniErrorCode.fromCode(err.error.toCatalogCode()) ?: OmniErrorCode.INTERNAL
                    else -> OmniErrorCode.INTERNAL
                }
                failCommand(commandId, code)
                return BinderErrors.failedCommand(
                    commandId = command.commandId,
                    code = code,
                    message = err.message ?: "upload materialize failed",
                )
            },
        )
    }

    fun commit(principal: PrincipalId, assetId: String?, command: OmniCommandRequest?): OmniAssetInfo {
        val id = assetId.orEmpty()
        if (command == null) {
            return assetInfoFailed(id, OmniErrorCode.INVALID_REQUEST, "command required")
        }
        val claimResult = claimCommand(
            principal = principal,
            command = command,
            operationKind = OP_COMMIT_ASSET,
            digestFallback = IdentityHashing.sha256Hex("""{"op":"COMMIT_ASSET","assetId":"$id"}"""),
        )
        val commandId = when (claimResult) {
            is CommandClaim.Conflict ->
                return assetInfoFailed(id, OmniErrorCode.IDEMPOTENCY_CONFLICT, claimResult.message)
            is CommandClaim.ExistingTerminal ->
                return get(principal, id)
            is CommandClaim.Ok -> claimResult.commandId
        }

        val record = assets[id]
            ?: run {
                failCommand(commandId, OmniErrorCode.NOT_FOUND)
                return assetInfoFailed(id, OmniErrorCode.NOT_FOUND, "asset not found")
            }
        if (record.ownerPrincipalId != principal.value) {
            failCommand(commandId, OmniErrorCode.FORBIDDEN)
            return assetInfoFailed(id, OmniErrorCode.FORBIDDEN, "not asset owner")
        }
        if (isExpired(record)) {
            transition(id) { it.copy(state = "EXPIRED", resourceVersion = it.resourceVersion + 1) }
            failCommand(commandId, OmniErrorCode.ASSET_EXPIRED)
            return assetInfoFailed(id, OmniErrorCode.ASSET_EXPIRED, "asset TTL elapsed")
        }
        if (record.state != "VERIFYING") {
            if (record.state == "READY") {
                succeedCommand(commandId, id)
                return toInfo(record)
            }
            failCommand(commandId, OmniErrorCode.STATE_CONFLICT)
            return assetInfoFailed(id, OmniErrorCode.STATE_CONFLICT, "state ${record.state} rejects commit")
        }
        if (record.sha256.isNullOrBlank() || record.materializePath.isNullOrBlank()) {
            transition(id) { it.copy(state = "REJECTED", resourceVersion = it.resourceVersion + 1) }
            failCommand(commandId, OmniErrorCode.INVALID_REQUEST)
            return assetInfoFailed(id, OmniErrorCode.INVALID_REQUEST, "missing materialize evidence")
        }
        // MIME sniff + purpose allowlist are TODO; digest/size already verified at upload.
        val ready = transition(id) {
            it.copy(state = "READY", resourceVersion = it.resourceVersion + 1)
        }!!
        succeedCommand(commandId, id)
        return toInfo(ready)
    }

    fun get(principal: PrincipalId, assetId: String?): OmniAssetInfo {
        val id = assetId.orEmpty()
        val record = assets[id]
            ?: return assetInfoFailed(id, OmniErrorCode.NOT_FOUND, "asset not found")
        if (record.ownerPrincipalId != principal.value) {
            return assetInfoFailed(id, OmniErrorCode.FORBIDDEN, "not asset owner")
        }
        if (isExpired(record) && record.state != "PINNED") {
            val expired = transition(id) {
                it.copy(state = "EXPIRED", resourceVersion = it.resourceVersion + 1)
            }!!
            return toInfo(expired).also {
                it.error = BinderErrors.omniError(OmniErrorCode.ASSET_EXPIRED, "asset TTL elapsed")
            }
        }
        return toInfo(record)
    }

    fun delete(principal: PrincipalId, assetId: String?, command: OmniCommandRequest?): CommandResult {
        val id = assetId.orEmpty()
        if (command == null) {
            return BinderErrors.failedCommand(
                commandId = "",
                code = OmniErrorCode.INVALID_REQUEST,
                message = "command required",
            )
        }
        val claimResult = claimCommand(
            principal = principal,
            command = command,
            operationKind = OP_DELETE_ASSET,
            digestFallback = IdentityHashing.sha256Hex("""{"op":"DELETE_ASSET","assetId":"$id"}"""),
        )
        val commandId = when (claimResult) {
            is CommandClaim.Conflict ->
                return BinderErrors.failedCommand(
                    commandId = command.commandId.orEmpty(),
                    code = OmniErrorCode.IDEMPOTENCY_CONFLICT,
                    message = claimResult.message,
                )
            is CommandClaim.ExistingTerminal ->
                return commandResultFromExisting(claimResult)
            is CommandClaim.Ok -> claimResult.commandId
        }
        val record = assets[id]
            ?: run {
                failCommand(commandId, OmniErrorCode.NOT_FOUND)
                return BinderErrors.failedCommand(
                    commandId = command.commandId,
                    code = OmniErrorCode.NOT_FOUND,
                    message = "asset not found",
                )
            }
        if (record.ownerPrincipalId != principal.value) {
            failCommand(commandId, OmniErrorCode.FORBIDDEN)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.FORBIDDEN,
                message = "not asset owner",
            )
        }
        if (record.state == "PINNED" || record.pinCount > 0) {
            failCommand(commandId, OmniErrorCode.STATE_CONFLICT)
            return BinderErrors.failedCommand(
                commandId = command.commandId,
                code = OmniErrorCode.STATE_CONFLICT,
                message = "asset pinned to request",
            )
        }
        record.materializePath?.let { File(it).delete() }
        transition(id) { it.copy(state = "DELETED", resourceVersion = it.resourceVersion + 1) }
        succeedCommand(commandId, id)
        return CommandResult().apply {
            this.commandId = command.commandId
            this.state = "SUCCEEDED"
            this.resourceVersion = assets[id]?.resourceVersion ?: 0L
            this.affectedResourceId = id
            this.resultSchemaId = null
            this.resultCanonicalJson = null
            this.error = null
        }
    }

    /** Pin READY asset to an accepted request (AST-006). */
    fun pin(principal: PrincipalId, assetId: String, requestId: String): OmniResult<AssetRecord> {
        val record = assets[assetId]
            ?: return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.NOT_FOUND(
                    message = "asset not found",
                    details = mapOf("assetId" to assetId),
                ),
            )
        if (record.ownerPrincipalId != principal.value) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.FORBIDDEN(message = "not asset owner"),
            )
        }
        if (isExpired(record)) {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.ASSET_EXPIRED(message = "asset TTL elapsed"),
            )
        }
        if (record.state != "READY" && record.state != "PINNED") {
            return OmniResult.err(
                com.omnillm.core.errors.generated.OmniError.ASSET_NOT_READY(
                    message = "asset state ${record.state}",
                ),
            )
        }
        val next = transition(assetId) {
            it.copy(
                state = "PINNED",
                pinCount = it.pinCount + 1,
                resourceVersion = it.resourceVersion + 1,
            )
        }!!
        return OmniResult.ok(next)
    }

    private fun isExpired(record: AssetRecord): Boolean =
        System.currentTimeMillis() > record.expiresAtEpochMillis

    private fun transition(assetId: String, transform: (AssetRecord) -> AssetRecord): AssetRecord? {
        synchronized(lock) {
            val cur = assets[assetId] ?: return null
            val next = transform(cur)
            assets[assetId] = next
            return next
        }
    }

    private fun toInfo(record: AssetRecord): OmniAssetInfo {
        val info = OmniAssetInfo()
        info.assetId = record.assetId
        info.state = record.state
        info.bytes = record.bytes
        info.sha256 = record.sha256
        info.expiresAtEpochMillis = record.expiresAtEpochMillis
        info.resourceVersion = record.resourceVersion
        info.error = null
        return info
    }

    private fun assetInfoFailed(assetId: String, code: OmniErrorCode, message: String): OmniAssetInfo {
        val info = OmniAssetInfo()
        info.assetId = assetId
        info.state = "REJECTED"
        info.bytes = 0L
        info.sha256 = null
        info.expiresAtEpochMillis = 0L
        info.resourceVersion = 0L
        info.error = BinderErrors.omniError(code, message)
        return info
    }

    private sealed class CommandClaim {
        data class Ok(val commandId: CommandId) : CommandClaim()
        data class Conflict(val message: String) : CommandClaim()
        data class ExistingTerminal(
            val commandId: String,
            val state: String,
            val resourceVersion: Long,
            val errorCode: String?,
            val affectedResourceId: String?,
        ) : CommandClaim()
    }

    private fun claimCommand(
        principal: PrincipalId,
        command: OmniCommandRequest,
        operationKind: String,
        digestFallback: String,
    ): CommandClaim {
        if (command.commandId.isNullOrBlank() || command.idempotencyKey.isNullOrBlank()) {
            return CommandClaim.Conflict("commandId and idempotencyKey required")
        }
        val commandId = try {
            CommandId.parse(command.commandId)
        } catch (_: IllegalArgumentException) {
            return CommandClaim.Conflict("commandId must be UUID")
        }
        val idem = try {
            IdempotencyKey.parse(command.idempotencyKey)
        } catch (_: IllegalArgumentException) {
            return CommandClaim.Conflict("invalid idempotencyKey")
        }
        val digestHex = command.canonicalInputDigest?.takeIf { it.isNotBlank() }?.lowercase()
            ?: digestFallback
        val digest = try {
            Sha256Digest.parse(digestHex)
        } catch (_: IllegalArgumentException) {
            return CommandClaim.Conflict("canonicalInputDigest must be sha256 hex")
        }
        return when (
            val claim = commandLedger.claim(
                principal = principal,
                operationKind = operationKind,
                idempotencyKey = idem,
                canonicalHash = digest,
                commandId = commandId,
                expectedVersion = if (command.hasExpectedVersion) command.expectedVersion else null,
            )
        ) {
            is ClaimOutcome.Conflict ->
                CommandClaim.Conflict(claim.error.message ?: "idempotency conflict")
            is ClaimOutcome.Existing -> {
                val row = claim.value
                if (row.state in setOf("SUCCEEDED", "FAILED", "CANCELLED", "UNCERTAIN")) {
                    CommandClaim.ExistingTerminal(
                        commandId = row.commandId,
                        state = row.state,
                        resourceVersion = row.resourceVersion,
                        errorCode = row.errorCode,
                        affectedResourceId = row.affectedResourceId,
                    )
                } else {
                    CommandClaim.Ok(commandId)
                }
            }
            is ClaimOutcome.New -> CommandClaim.Ok(commandId)
        }
    }

    private fun succeedCommand(commandId: CommandId, affected: String) {
        commandLedger.recordResult(
            commandId = commandId,
            state = "SUCCEEDED",
            affectedResourceId = affected,
        )
    }

    private fun failCommand(commandId: CommandId, code: OmniErrorCode) {
        commandLedger.recordResult(
            commandId = commandId,
            state = "FAILED",
            errorCode = code.code,
        )
    }

    private fun commandResultFromExisting(existing: CommandClaim.ExistingTerminal): CommandResult {
        val result = CommandResult()
        result.commandId = existing.commandId
        result.state = existing.state
        result.resourceVersion = existing.resourceVersion
        result.affectedResourceId = existing.affectedResourceId
        result.resultSchemaId = null
        result.resultCanonicalJson = null
        result.error = existing.errorCode?.let {
            val code = OmniErrorCode.fromCode(it) ?: OmniErrorCode.INTERNAL
            BinderErrors.omniError(code, "prior command result")
        }
        return result
    }

    private fun safeClose(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val OP_CREATE_ASSET: String = "CREATE_ASSET"
        const val OP_UPLOAD_ASSET: String = "UPLOAD_ASSET"
        const val OP_COMMIT_ASSET: String = "COMMIT_ASSET"
        const val OP_DELETE_ASSET: String = "DELETE_ASSET"
    }
}

data class AssetRecord(
    val assetId: String,
    val ownerPrincipalId: String,
    val purpose: String,
    val maxBytes: Long,
    val contentTypeHint: String?,
    val expectedSha256: String?,
    val state: String,
    val bytes: Long,
    val sha256: String?,
    val expiresAtEpochMillis: Long,
    val resourceVersion: Long,
    val materializePath: String?,
    val pinCount: Int,
    val createdAtEpochMillis: Long,
)
