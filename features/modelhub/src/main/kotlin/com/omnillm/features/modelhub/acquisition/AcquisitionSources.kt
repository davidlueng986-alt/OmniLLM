package com.omnillm.features.modelhub.acquisition

import com.omnillm.core.canonical.generated.OmniResult
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.modelhub.catalog.FixtureArtifact
import com.omnillm.runtime.policy.download.DownloadUrlPolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opaque byte source for acquisition materialize (SEC-INPUT §5, FEAT-MODELHUB §4–§5).
 *
 * Implementations must not expose client filesystem paths to workers — only streams
 * / handles owned by the control plane (ADR-010).
 */
fun interface ArtifactByteSource {
    /**
     * Open a bounded input stream for one declared role.
     * Caller closes the stream. [cancel] may be observed by long copies.
     */
    fun open(role: String, cancel: AtomicBoolean): OmniResult<InputStream>
}

/** Offline fixture weights (host tests + software E2E without network). */
class FixtureArtifactSource : ArtifactByteSource {
    override fun open(role: String, cancel: AtomicBoolean): OmniResult<InputStream> {
        if (role != FixtureArtifact.ROLE_WEIGHTS) {
            return OmniResult.err(
                OmniError.INVALID_REQUEST(
                    message = "unknown fixture role",
                    details = mapOf("role" to role),
                ),
            )
        }
        if (cancel.get()) {
            return OmniResult.err(OmniError.CANCELLED(message = "fixture open cancelled"))
        }
        return OmniResult.ok(ByteArrayInputStream(FixtureArtifact.PAYLOAD_BYTES.copyOf()))
    }
}

/**
 * SAF / PFD import source: control plane supplies a stream already materialize-ready
 * (after dup/fstat). UI never opens the path itself for install (INV-001).
 */
class StreamArtifactSource(
    private val streamsByRole: Map<String, () -> InputStream>,
) : ArtifactByteSource {
    override fun open(role: String, cancel: AtomicBoolean): OmniResult<InputStream> {
        if (cancel.get()) {
            return OmniResult.err(OmniError.CANCELLED(message = "import open cancelled"))
        }
        val factory = streamsByRole[role]
            ?: return OmniResult.err(
                OmniError.NOT_FOUND(
                    message = "no stream for role",
                    details = mapOf("role" to role),
                ),
            )
        return try {
            OmniResult.ok(factory())
        } catch (e: Exception) {
            OmniResult.err(
                OmniError.INTERNAL(message = "stream open failed: ${e.javaClass.simpleName}"),
            )
        }
    }
}

/**
 * Resolves a pinned HTTPS URL to an [ArtifactByteSource] under DownloadUrlPolicy.
 *
 * Offline fixture URL maps to [FixtureArtifactSource] without network.
 * Unknown hosts fail closed (no silent cross-revision fallback).
 */
object PinnedDownloadResolver {

    sealed class Outcome {
        data class Ready(val source: ArtifactByteSource, val normalizedUrl: String) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    fun resolve(
        sourceUrl: String,
        policy: DownloadUrlPolicy.Policy = DownloadUrlPolicy.Policy.DEFAULT,
        /** Optional host allowlist for fixture domain (host tests). */
        allowFixtureHost: Boolean = true,
    ): Outcome {
        val admit = DownloadUrlPolicy.admitUrl(sourceUrl, policy)
        if (admit is DownloadUrlPolicy.Outcome.Rejected) {
            return Outcome.Rejected(admit.error, admit.reason)
        }
        val accepted = admit as DownloadUrlPolicy.Outcome.Accepted

        if (accepted.normalizedUrl == FixtureArtifact.PINNED_HTTPS_URL ||
            sourceUrl.trim() == FixtureArtifact.PINNED_HTTPS_URL
        ) {
            if (!allowFixtureHost && accepted.host != "fixtures.omnillm.local") {
                return Outcome.Rejected(
                    OmniError.INVALID_REQUEST(message = "fixture host not allowed"),
                    "fixture host denied",
                )
            }
            // Fixture host is not a public CDN — offline software path only.
            return Outcome.Ready(FixtureArtifactSource(), accepted.normalizedUrl)
        }

        // Real network download is out of software E2E scope here: fail closed
        // unless a custom source factory is injected by the control plane host.
        return Outcome.Rejected(
            OmniError.CAPABILITY_UNSUPPORTED(
                message = "network download executor not attached for host",
                details = mapOf(
                    "host" to accepted.host,
                    "hint" to "use offline fixture URL or inject ArtifactByteSource",
                ),
            ),
            "no network executor",
        )
    }
}
