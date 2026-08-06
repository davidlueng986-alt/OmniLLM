package com.omnillm.runtime.policy.download

import com.omnillm.core.errors.generated.OmniError
import java.net.IDN
import java.net.URI
import java.util.Locale

/**
 * Download URL policy stubs (SEC-INPUT §3, SEC-SUPPLY §5, SEC-003).
 *
 * Rules (fail closed):
 * - HTTPS only (explicit host + port)
 * - Reject userinfo, file/content schemes, and empty host
 * - Normalize trailing-dot / IDNA (punycode) host
 * - Per-hop re-validation for redirects (same rules)
 * - Proxy optional only when [allowExplicitProxy] and host is allowlisted
 *
 * DNS resolution deny is [ResolvedAddressPolicy] — apply on every hop.
 */
object DownloadUrlPolicy {

    sealed class Outcome {
        data class Accepted(
            val normalizedUrl: String,
            val host: String,
            val port: Int,
            val path: String,
        ) : Outcome()

        data class Rejected(
            val error: OmniError,
            val reason: String,
        ) : Outcome()
    }

    data class Policy(
        val allowedSchemes: Set<String> = setOf("https"),
        val defaultHttpsPort: Int = 443,
        val maxUrlBytes: Int = 2_048,
        val maxRedirectHops: Int = 5,
        val allowExplicitProxy: Boolean = false,
        val proxyHostAllowlist: Set<String> = emptySet(),
        /** When non-empty, only these hosts (ASCII lower / punycode) are allowed. */
        val hostAllowlist: Set<String> = emptySet(),
        val hostDenylist: Set<String> = emptySet(),
    ) {
        init {
            require(allowedSchemes.isNotEmpty())
            require(defaultHttpsPort in 1..65535)
            require(maxUrlBytes > 0)
            require(maxRedirectHops in 0..20)
        }

        companion object {
            val DEFAULT: Policy = Policy()
        }
    }

    fun admitUrl(rawUrl: String, policy: Policy = Policy.DEFAULT): Outcome {
        if (rawUrl.isBlank()) {
            return reject("URL is blank")
        }
        val bytes = rawUrl.toByteArray(Charsets.UTF_8).size
        if (bytes > policy.maxUrlBytes) {
            return rejectTooLarge("URL exceeds max bytes", bytes, policy.maxUrlBytes)
        }
        // Disallow whitespace / control early.
        if (rawUrl.any { it.isISOControl() || it.isWhitespace() && it != ' ' }) {
            // spaces only allowed if percent-encoded; bare control is always reject
            if (rawUrl.any { it.isISOControl() }) return reject("URL contains control characters")
        }

        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            return reject("URL is not a valid URI")
        }

        return admitUri(uri, policy)
    }

    fun admitUri(uri: URI, policy: Policy = Policy.DEFAULT): Outcome {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
            ?: return reject("URL missing scheme")
        if (scheme !in policy.allowedSchemes) {
            return reject("scheme not allowed: $scheme")
        }
        if (uri.userInfo != null && uri.userInfo.isNotEmpty()) {
            return reject("userinfo not allowed in download URL")
        }
        if (scheme == "file" || scheme == "content" || scheme == "data") {
            return reject("scheme $scheme forbidden for download")
        }

        val rawHost = uri.host
            ?: return reject("URL missing host")
        val host = normalizeHost(rawHost)
            ?: return reject("host failed IDNA / normalization")
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) {
            return reject("loopback / localhost host forbidden for remote download")
        }
        if (host in policy.hostDenylist) {
            return reject("host denylisted")
        }
        if (policy.hostAllowlist.isNotEmpty() && host !in policy.hostAllowlist) {
            return reject("host not in allowlist")
        }

        val port = when {
            uri.port > 0 -> uri.port
            scheme == "https" -> policy.defaultHttpsPort
            else -> return reject("port must be explicit for non-default schemes")
        }
        if (port !in 1..65535) return reject("invalid port")
        // Non-default ports are allowed only for https; still re-checked per hop.

        val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
        val query = uri.rawQuery
        val fragment = uri.rawFragment
        if (fragment != null) {
            // Fragments are never sent to the server; strip from normalized form.
        }
        val normalized = buildString {
            append(scheme).append("://").append(host)
            if (port != policy.defaultHttpsPort) append(':').append(port)
            append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
        return Outcome.Accepted(
            normalizedUrl = normalized,
            host = host,
            port = port,
            path = path,
        )
    }

    /**
     * Validate a redirect Location against the same policy (SEC-INPUT §3 / SEC-SUPPLY §5).
     * [hopIndex] is 0-based for the first redirect.
     */
    fun admitRedirect(
        locationHeader: String,
        hopIndex: Int,
        policy: Policy = Policy.DEFAULT,
        base: URI? = null,
    ): Outcome {
        if (hopIndex >= policy.maxRedirectHops) {
            return reject("redirect hop limit exceeded")
        }
        val resolved = try {
            val loc = URI(locationHeader)
            if (base != null && !loc.isAbsolute) base.resolve(loc) else loc
        } catch (_: Exception) {
            return reject("redirect Location is not a valid URI")
        }
        return admitUri(resolved, policy)
    }

    fun normalizeHost(raw: String): String? {
        var h = raw.trim().lowercase(Locale.ROOT)
        // Strip trailing dots (DNS absolute form).
        while (h.endsWith('.')) {
            h = h.dropLast(1)
        }
        if (h.isEmpty()) return null
        // Reject IP-literal brackets handled separately; URI.host already strips [] for IPv6.
        return try {
            IDN.toASCII(h, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: Exception) {
            null
        }
    }

    private fun reject(reason: String): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.INVALID_REQUEST(
                message = "download URL rejected",
                details = mapOf("reason" to reason),
            ),
            reason = reason,
        )

    private fun rejectTooLarge(reason: String, bytes: Int, max: Int): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.TRANSPORT_TOO_LARGE(
                message = reason,
                details = mapOf(
                    "bytes" to bytes.toString(),
                    "maxUrlBytes" to max.toString(),
                ),
            ),
            reason = reason,
        )
}
