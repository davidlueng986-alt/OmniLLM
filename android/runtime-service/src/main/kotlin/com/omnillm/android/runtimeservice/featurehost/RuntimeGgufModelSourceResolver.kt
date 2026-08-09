package com.omnillm.android.runtimeservice.featurehost

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.contracts.Plan
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.data.modelstore.ReadyContentPort
import com.omnillm.data.modelstore.StorageLayout
import com.omnillm.runtime.modelmanager.ports.InstallationRepository
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

/**
 * Resolves a READY installation's GGUF bytes for the llama-cpp adapter
 * (privileged load path, CORE-MODEL §6 / INV-010 / SEC-PLACEMENT §4).
 *
 * Runs only inside the `:runtime` control plane:
 * - Looks up a READY installation for the plan's revision
 * - Opens the actual ready content and re-verifies content identity against
 *   the real file bytes (never trusts the DB READY flag alone)
 * - Returns an in-process absolute path; paths never cross to clients
 *
 * No trust elevation: resolving a model does not raise source trust or mark
 * qualification cells SUPPORTED (ADR-009 / INV-008).
 */
class RuntimeGgufModelSourceResolver(
    private val filesRoot: Path,
    private val installations: InstallationRepository,
    private val readyContent: ReadyContentPort,
) : LlamaCppInferenceEngineAdapter.ModelSourceResolver {

    override suspend fun resolve(plan: Plan): OmniResult<LlamaCppInferenceEngineAdapter.ResolvedModelSource> {
        val snap = installations.findByRevision(plan.modelRevisionId)
            .firstOrNull { it.isReady() && it.storageRootKey != null }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "no READY installation for revision",
                    details = mapOf("modelRevisionId" to plan.modelRevisionId.hex),
                ),
            )

        val storageRootKey = snap.storageRootKey!!
        val opened = readyContent.openReadOnly(
            com.omnillm.core.identity.InstallationId.ofValidated(snap.installationId.value),
            storageRootKey,
        )
        val fds = when (opened) {
            is OmniResult.Err -> return opened
            is OmniResult.Ok -> opened.value
        }
        // INV-010: content identity re-verified against actual opened files.
        val verified = readyContent.verifyOpenFds(fds)
        when (verified) {
            is OmniResult.Err -> return verified
            is OmniResult.Ok -> {
                if (!verified.value.ok) {
                    return OmniResult.err(
                        OmniError.INVALID_REQUEST(
                            message = "privileged load re-verify failed",
                            details = mapOf(
                                "installationId" to snap.installationId.value,
                                "reason" to (verified.value.failureReason ?: "identity"),
                            ),
                        ),
                    )
                }
            }
        }

        // Weights role is the primary model file; fall back to largest role file.
        val target = fds.firstOrNull { it.role.equals("WEIGHTS", ignoreCase = true) }
            ?: fds.maxByOrNull { it.byteLength }
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(message = "no ready content file for installation"),
            )

        val segments = StorageLayout.installationRelativeSegments(snap.installationId.value) +
            target.role
        val path = segments.fold(filesRoot) { acc, s -> acc.resolve(s) }
        // Fresh regular-file check (NOFOLLOW) — refuse symlink/special paths.
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS) || path.isSymbolicLink()) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "ready content is not a regular file",
                    details = mapOf("role" to target.role),
                ),
            )
        }
        // Byte length sanity against the identity check.
        val actual = try {
            Files.size(path)
        } catch (_: Exception) {
            -1L
        }
        if (actual != target.byteLength) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "ready content size mismatch",
                    details = mapOf(
                        "role" to target.role,
                        "expected" to target.byteLength.toString(),
                        "actual" to actual.toString(),
                    ),
                ),
            )
        }

        // Privileged load by in-process absolute path: the native layer opens and
        // verifies the actual file in the runtime's own trust domain. Paths never
        // cross to clients (INV-001 / ADR-010).
        return OmniResult.ok(
            LlamaCppInferenceEngineAdapter.ResolvedModelSource(
                storageRootKey = storageRootKey,
                resolvedModelPath = path.toAbsolutePath().toString(),
                modelFd = -1,
            ),
        )
    }

    companion object {
        /** Convenience for tests: ensure layout exists. */
        fun ensureFilesystemLayout(filesRoot: Path) {
            Files.createDirectories(
                listOf(StorageLayout.MODEL_STORE, StorageLayout.BLOBS)
                    .fold(filesRoot) { acc, s -> acc.resolve(s) },
            )
        }
    }
}
