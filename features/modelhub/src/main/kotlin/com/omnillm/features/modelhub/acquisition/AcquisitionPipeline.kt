package com.omnillm.features.modelhub.acquisition

import com.omnillm.core.canonical.generated.BlobId
import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.data.modelstore.FilesystemModelStorePort
import com.omnillm.data.modelstore.ModelStorePort
import com.omnillm.data.modelstore.QuarantineKey
import com.omnillm.features.modelhub.api.AcquisitionDeclaredFile
import com.omnillm.features.modelhub.api.AcquisitionMaterializedFile
import com.omnillm.features.modelhub.api.AcquisitionProgressUpdate
import com.omnillm.features.modelhub.api.CancelAcquisitionSpec
import com.omnillm.features.modelhub.api.ModelCard
import com.omnillm.features.modelhub.api.ModelHubApi
import com.omnillm.features.modelhub.api.ModelHubCommandIdentity
import com.omnillm.features.modelhub.api.ModelHubJobHandle
import com.omnillm.features.modelhub.api.StartDownloadSpec
import com.omnillm.features.modelhub.api.StartImportSpec
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import com.omnillm.interfaces.admin.LocalUiPrincipal
import com.omnillm.runtime.policy.download.DownloadUrlPolicy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Software E2E acquisition pipeline (FEAT-MODELHUB §4–§6, SEC-INPUT, SEC-SUPPLY).
 *
 * Plan → claim job (via [ModelHubApi]) → HTTPS/SAF admit → quarantine materialize
 * → digest verify → atomic promote → READY. **No domain mutation in pure plan;**
 * this executor is control-plane / host-test only (ADR-010).
 *
 * Installation states observed along the path:
 * DISCOVERED → ACQUIRING → QUARANTINED → VERIFYING → COMPATIBILITY_CHECK → READY
 * (or REJECTED / CANCELLED).
 */
class AcquisitionPipeline(
    private val modelHub: ModelHubApi,
    private val modelStore: ModelStorePort,
    private val urlPolicy: DownloadUrlPolicy.Policy = DownloadUrlPolicy.Policy.DEFAULT,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val deadlineMonotonic: Long = Long.MAX_VALUE / 4,
) {

    data class ExecuteResult(
        val job: ModelHubJobHandle,
        val card: ModelCard?,
        val installationState: String?,
        val phases: List<String>,
    )

    /**
     * Catalog pin download: admit HTTPS URL → open source → materialize → READY.
     */
    suspend fun executePinnedDownload(
        installationId: String,
        jobId: String,
        modelRevisionId: String,
        artifactPackageId: String,
        sourceUrl: String,
        expectedSha256: String,
        expectedBytes: Long,
        displayName: String,
        command: ModelHubCommandIdentity,
        /** Injected source; when null, [PinnedDownloadResolver] selects fixture / fails closed. */
        sourceOverride: ArtifactByteSource? = null,
        cancel: AtomicBoolean = AtomicBoolean(false),
        role: String = FixtureArtifact.ROLE_WEIGHTS,
    ): OmniResult<ExecuteResult> {
        val phases = mutableListOf<String>()

        // SEC-INPUT §3 / SEC-SUPPLY §5 — HTTPS policy before any materialize.
        val resolved = when (val r = PinnedDownloadResolver.resolve(sourceUrl, urlPolicy)) {
            is PinnedDownloadResolver.Outcome.Rejected -> {
                // Still allow explicit override for hermetic tests after policy check failure
                // only when caller injects source *and* URL was accepted? Fail closed on reject.
                return OmniResult.err(r.error)
            }
            is PinnedDownloadResolver.Outcome.Ready -> r
        }
        phases += "HTTPS_ADMITTED"
        val source = sourceOverride ?: resolved.source

        if (cancel.get()) {
            return OmniResult.err(OmniError.CANCELLED(message = "cancelled before start"))
        }

        val handle = when (
            val started = modelHub.startDownload(
                LocalUiPrincipal.ID,
                StartDownloadSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = modelRevisionId,
                    artifactPackageId = artifactPackageId,
                    sourceUrl = resolved.normalizedUrl,
                    expectedSha256 = expectedSha256,
                    expectedBytes = expectedBytes,
                    displayName = displayName,
                    command = command,
                ),
            )
        ) {
            is OmniResult.Ok -> started.value
            is OmniResult.Err -> return started
        }
        phases += "DISCOVERED_QUEUED"

        if (cancel.get()) {
            return cancelAndReport(jobId, command, phases, handle)
        }

        val declared = listOf(
            AcquisitionDeclaredFile(
                role = role,
                blobId = expectedSha256.lowercase(),
                byteLength = expectedBytes,
            ),
        )
        when (val begun = modelHub.beginAcquisitionAttempt(jobId, declared, deadlineMonotonic)) {
            is OmniResult.Err -> return begun
            is OmniResult.Ok -> Unit
        }
        phases += "ACQUIRING"

        if (cancel.get()) {
            return cancelAndReport(jobId, command, phases, handle)
        }

        val materialize = materializeDeclared(
            jobId = jobId,
            declared = declared,
            source = source,
            cancel = cancel,
            phases = phases,
        )
        if (materialize is OmniResult.Err) {
            if (materialize.error.code == OmniErrorCode.CANCELLED) {
                return cancelAndReport(jobId, command, phases, handle)
            }
            modelHub.failAcquisition(jobId, materialize.error.message ?: "materialize failed")
            return materialize
        }
        val mat = (materialize as OmniResult.Ok).value

        modelHub.updateAcquisitionProgress(
            AcquisitionProgressUpdate(
                jobId = jobId,
                networkBytes = expectedBytes,
                materializedBytes = expectedBytes,
                verifiedBytes = 0L,
                totalBytesKnown = expectedBytes,
                currentPhase = "QUARANTINED",
            ),
        )
        phases += "QUARANTINED"

        if (cancel.get()) {
            return cancelAndReport(jobId, command, phases, handle)
        }

        // FS store already recorded files during stream copy — pass empty to avoid double record.
        val filesForComplete = if (mat.alreadyRecordedOnStore) emptyList() else mat.files
        val card = when (val done = modelHub.completeAcquisitionMaterialize(jobId, filesForComplete)) {
            is OmniResult.Ok -> done.value
            is OmniResult.Err -> {
                modelHub.failAcquisition(jobId, done.error.message ?: "promote failed")
                return done
            }
        }
        phases += "VERIFYING"
        phases += "PROMOTED_READY"

        return OmniResult.ok(
            ExecuteResult(
                job = handle.copy(state = "SUCCEEDED"),
                card = card,
                installationState = card.installationState,
                phases = phases,
            ),
        )
    }

    /**
     * SAF / local import: stream → quarantine → verify → promote.
     * Trust stays untrusted (channel LOCAL_IMPORT); compatibility ≠ authenticity.
     *
     * C-03 dry-load gate: when the import is LABELED gguf ([expectedFormat]), the
     * first bytes are PARSED as a GGUF header before any quarantine materialize —
     * a non-GGUF / truncated / corrupt file fails closed (INVALID_REQUEST) and the
     * job + installation are rejected. Header parse only (24 bytes); engine-level
     * "load without execution" is not exposed by llama-cpp and is a device-wave item.
     */
    suspend fun executeSafImport(
        installationId: String,
        jobId: String,
        modelRevisionId: String,
        artifactPackageId: String,
        assetId: String,
        expectedSha256: String,
        expectedBytes: Long,
        displayName: String,
        command: ModelHubCommandIdentity,
        source: ArtifactByteSource,
        cancel: AtomicBoolean = AtomicBoolean(false),
        role: String = FixtureArtifact.ROLE_WEIGHTS,
        expectedFormat: String? = "fixture",
    ): OmniResult<ExecuteResult> {
        val phases = mutableListOf("SAF_IMPORT")

        val handle = when (
            val started = modelHub.startImport(
                LocalUiPrincipal.ID,
                StartImportSpec(
                    jobId = jobId,
                    installationId = installationId,
                    modelRevisionId = modelRevisionId,
                    artifactPackageId = artifactPackageId,
                    assetId = assetId,
                    expectedFormat = expectedFormat,
                    expectedSha256 = expectedSha256,
                    displayName = displayName,
                    command = command,
                ),
            )
        ) {
            is OmniResult.Ok -> started.value
            is OmniResult.Err -> return started
        }
        phases += "DISCOVERED_QUEUED"

        if (cancel.get()) {
            return cancelAndReport(jobId, command, phases, handle)
        }

        val declared = listOf(
            AcquisitionDeclaredFile(
                role = role,
                blobId = expectedSha256.lowercase(),
                byteLength = expectedBytes,
            ),
        )
        when (val begun = modelHub.beginAcquisitionAttempt(jobId, declared, deadlineMonotonic)) {
            is OmniResult.Err -> return begun
            is OmniResult.Ok -> Unit
        }
        phases += "ACQUIRING"

        // C-03: parse dry-load before any quarantine materialize — the gguf LABEL
        // alone must not make a file installable. Fail closed on non-GGUF bytes.
        if (expectedFormat?.trim()?.equals("gguf", ignoreCase = true) == true) {
            val header = dryLoadGgufHeader(source = source, cancel = cancel)
            if (header is OmniResult.Err) {
                modelHub.failAcquisition(jobId, header.error.message ?: "gguf header rejected")
                phases += "GGUF_HEADER_REJECTED"
                return header
            }
            phases += "GGUF_HEADER_VALIDATED"
        }

        val materialize = materializeDeclared(
            jobId = jobId,
            declared = declared,
            source = source,
            cancel = cancel,
            phases = phases,
        )
        if (materialize is OmniResult.Err) {
            if (materialize.error.code == OmniErrorCode.CANCELLED) {
                return cancelAndReport(jobId, command, phases, handle)
            }
            modelHub.failAcquisition(jobId, materialize.error.message ?: "materialize failed")
            return materialize
        }
        val mat = (materialize as OmniResult.Ok).value
        phases += "QUARANTINED"

        val filesForComplete = if (mat.alreadyRecordedOnStore) emptyList() else mat.files
        val card = when (val done = modelHub.completeAcquisitionMaterialize(jobId, filesForComplete)) {
            is OmniResult.Ok -> done.value
            is OmniResult.Err -> {
                modelHub.failAcquisition(jobId, done.error.message ?: "promote failed")
                return done
            }
        }
        phases += "PROMOTED_READY"

        return OmniResult.ok(
            ExecuteResult(
                job = handle.copy(state = "SUCCEEDED"),
                card = card,
                installationState = card.installationState,
                phases = phases,
            ),
        )
    }

    /**
     * Cancel an in-flight acquisition job (JOB CANCEL + installation REJECTED when ACQUIRING).
     */
    suspend fun cancelJob(
        jobId: String,
        command: ModelHubCommandIdentity,
        requestOnly: Boolean = false,
    ): OmniResult<ModelHubJobHandle> =
        modelHub.cancelAcquisition(
            LocalUiPrincipal.ID,
            CancelAcquisitionSpec(jobId = jobId, command = command, requestOnly = requestOnly),
        )

    private data class MaterializeBundle(
        val files: List<AcquisitionMaterializedFile>,
        /** True when FilesystemQuarantineStore already recorded during stream copy. */
        val alreadyRecordedOnStore: Boolean,
    )

    private suspend fun materializeDeclared(
        jobId: String,
        declared: List<AcquisitionDeclaredFile>,
        source: ArtifactByteSource,
        cancel: AtomicBoolean,
        phases: MutableList<String>,
    ): OmniResult<MaterializeBundle> {
        val qKey = QuarantineKey(jobId = jobId, attemptId = "a1")
        val fsStore = modelStore as? FilesystemModelStorePort
        val alreadyOnStore = fsStore != null

        // When the store is a real FS quarantine, stream into it so digest verify is real.
        // In-memory fakes record handles without bytes — still exercise FSM via ModelHub.
        val out = ArrayList<AcquisitionMaterializedFile>(declared.size)
        for (d in declared) {
            if (cancel.get()) {
                return OmniResult.err(OmniError.CANCELLED(message = "cancelled during materialize"))
            }
            val stream = when (val opened = source.open(d.role, cancel)) {
                is OmniResult.Ok -> opened.value
                is OmniResult.Err -> return opened
            }
            stream.use { input ->
                if (fsStore != null) {
                    val blob = BlobId.parse(d.blobId)
                    val mat = fsStore.materializeFromStream(
                        key = qKey,
                        role = d.role,
                        expectedBlobId = blob,
                        expectedByteLength = d.byteLength,
                        input = input,
                        cancel = cancel,
                    )
                    when (mat) {
                        is OmniResult.Err -> return mat
                        is OmniResult.Ok -> {
                            val rec = mat.value.files.lastOrNull { it.role == d.role }
                                ?: return OmniResult.err(
                                    OmniError.INTERNAL(message = "materialize missing record"),
                                )
                            out += AcquisitionMaterializedFile(
                                role = rec.role,
                                expectedBlobId = rec.expectedBlobId.hex,
                                expectedByteLength = rec.expectedByteLength,
                                materializeHandle = rec.materializeHandle,
                                shardIndex = rec.shardIndex,
                            )
                        }
                    }
                } else {
                    // In-memory ModelStorePort: drain + hash to enforce digest policy in tests
                    // that inject wrong expectedSha256.
                    val read = input.readBytes()
                    if (read.size.toLong() != d.byteLength) {
                        return OmniResult.err(
                            OmniError.INVALID_REQUEST(
                                message = "byte length mismatch",
                                details = mapOf(
                                    "expected" to d.byteLength.toString(),
                                    "actual" to read.size.toString(),
                                ),
                            ),
                        )
                    }
                    val digest = com.omnillm.core.canonical.IdentityHashing.sha256Hex(read)
                    if (digest != d.blobId.lowercase()) {
                        return OmniResult.err(
                            OmniError.INVALID_REQUEST(
                                message = "digest mismatch",
                                details = mapOf(
                                    "expected" to d.blobId,
                                    "actual" to digest,
                                ),
                            ),
                        )
                    }
                    val handle = "mem/$jobId/${d.role}"
                    // Do not record here — completeAcquisitionMaterialize will record once.
                    out += AcquisitionMaterializedFile(
                        role = d.role,
                        expectedBlobId = d.blobId,
                        expectedByteLength = d.byteLength,
                        materializeHandle = handle,
                    )
                }
            }
            phases += "MATERIALIZED_${d.role}"
        }
        return OmniResult.ok(MaterializeBundle(files = out, alreadyRecordedOnStore = alreadyOnStore))
    }

    /**
     * C-03 dry-load: open the declared weights stream once and parse the GGUF
     * header without materializing/executing. Returns the parsed header on
     * success; INVALID_REQUEST (mapped) on any parse failure.
     */
    private fun dryLoadGgufHeader(
        source: ArtifactByteSource,
        cancel: AtomicBoolean,
    ): OmniResult<GgufHeaderValidator.Header> {
        val stream = when (val opened = source.open(FixtureArtifact.ROLE_WEIGHTS, cancel)) {
            is OmniResult.Ok -> opened.value
            is OmniResult.Err -> return opened
        }
        stream.use { input ->
            return when (val outcome = GgufHeaderValidator.validate(input)) {
                is GgufHeaderValidator.Outcome.Valid -> OmniResult.ok(outcome.header)
                is GgufHeaderValidator.Outcome.Invalid -> OmniResult.err(
                    OmniError.INVALID_REQUEST(
                        message = "import rejected: file is not a valid GGUF model",
                        details = mapOf(
                            "reason" to outcome.reason,
                            "field" to outcome.field,
                            "actual" to outcome.actual,
                            "expectedFormat" to "gguf",
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun cancelAndReport(
        jobId: String,
        command: ModelHubCommandIdentity,
        phases: MutableList<String>,
        prior: ModelHubJobHandle,
    ): OmniResult<ExecuteResult> {
        phases += "CANCEL_REQUESTED"
        val cancelled = when (val c = cancelJob(jobId, command)) {
            is OmniResult.Ok -> c.value
            is OmniResult.Err -> return c
        }
        phases += "CANCELLED"
        return OmniResult.ok(
            ExecuteResult(
                job = cancelled,
                card = null,
                installationState = null,
                phases = phases,
            ),
        )
    }

    companion object {
        fun newJobId(): String = UUID.randomUUID().toString()
        fun newInstallationId(): String = UUID.randomUUID().toString()

        fun command(idempotencyKey: String, digestHex: String = "e".repeat(64)): ModelHubCommandIdentity =
            ModelHubCommandIdentity(
                commandId = UUID.randomUUID().toString(),
                idempotencyKey = idempotencyKey,
                canonicalInputDigest = digestHex.lowercase(),
            )
    }
}
