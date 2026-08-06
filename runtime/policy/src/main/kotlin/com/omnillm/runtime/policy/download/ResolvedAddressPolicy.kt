package com.omnillm.runtime.policy.download

import com.omnillm.core.errors.generated.OmniError
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Locale

/**
 * Per-resolution / per-hop IP deny policy (SEC-INPUT §3, SEC-003).
 *
 * Apply after every DNS resolution and after every redirect re-resolution
 * (DNS rebinding defense). Handles IPv4-mapped IPv6.
 *
 * This is a pure classifier over [InetAddress] — no network I/O.
 */
object ResolvedAddressPolicy {

    sealed class Outcome {
        data class Accepted(val addressLiteral: String) : Outcome()
        data class Rejected(val error: OmniError, val reason: String) : Outcome()
    }

    data class Policy(
        val denyLoopback: Boolean = true,
        val denyPrivate: Boolean = true,
        val denyLinkLocal: Boolean = true,
        val denyMulticast: Boolean = true,
        val denyMetadataEndpoints: Boolean = true,
        val denyUnspecified: Boolean = true,
        /** Extra deny prefixes as dotted IPv4 strings (e.g. "100.64.0.0/10" not parsed — use exacts). */
        val extraDeniedExactIps: Set<String> = emptySet(),
    ) {
        companion object {
            val DEFAULT: Policy = Policy()
        }
    }

    /**
     * Well-known cloud metadata endpoints denied by default.
     */
    val METADATA_IPV4: Set<String> = setOf(
        "169.254.169.254", // AWS / GCP / Azure IMDS
        "169.254.170.2",   // AWS ECS task metadata
        "10.255.255.254",  // some cloud agent endpoints
    )

    fun admit(address: InetAddress, policy: Policy = Policy.DEFAULT): Outcome {
        val literal = address.hostAddress?.lowercase(Locale.ROOT)
            ?: return reject("unresolvable address literal")

        if (literal in policy.extraDeniedExactIps) {
            return reject("address explicitly denied")
        }

        // Unwrap IPv4-mapped IPv6 (::ffff:a.b.c.d).
        val effective: InetAddress = when (address) {
            is Inet6Address -> {
                if (address.isIPv4CompatibleAddress || isIpv4Mapped(address)) {
                    val bytes = address.address
                    InetAddress.getByAddress(bytes.copyOfRange(bytes.size - 4, bytes.size))
                } else {
                    address
                }
            }
            else -> address
        }

        val effectiveLiteral = effective.hostAddress?.lowercase(Locale.ROOT) ?: literal

        if (policy.denyUnspecified && (effective.isAnyLocalAddress || effectiveLiteral == "0.0.0.0" ||
                effectiveLiteral == "0:0:0:0:0:0:0:0" || effectiveLiteral == "::")
        ) {
            return reject("unspecified address denied")
        }
        if (policy.denyLoopback && (effective.isLoopbackAddress || effectiveLiteral.startsWith("127."))) {
            return reject("loopback address denied")
        }
        if (policy.denyLinkLocal && effective.isLinkLocalAddress) {
            return reject("link-local address denied")
        }
        if (policy.denyMulticast && effective.isMulticastAddress) {
            return reject("multicast address denied")
        }
        if (policy.denyPrivate && isPrivate(effective)) {
            return reject("private address denied")
        }
        if (policy.denyMetadataEndpoints) {
            if (effectiveLiteral in METADATA_IPV4) {
                return reject("metadata endpoint denied")
            }
            // IPv6 unique-local and site-local already covered by private/link-local checks.
        }
        return Outcome.Accepted(addressLiteral = effectiveLiteral)
    }

    fun admitAll(addresses: List<InetAddress>, policy: Policy = Policy.DEFAULT): Outcome {
        if (addresses.isEmpty()) return reject("empty resolution set")
        // All resolved addresses must pass (fail closed on any bad A/AAAA).
        for (a in addresses) {
            when (val r = admit(a, policy)) {
                is Outcome.Rejected -> return r
                is Outcome.Accepted -> Unit
            }
        }
        val first = addresses.first().hostAddress ?: return reject("unresolvable address literal")
        return Outcome.Accepted(addressLiteral = first.lowercase(Locale.ROOT))
    }

    /**
     * Parse a literal IP without DNS. Returns null if not a numeric literal.
     */
    fun parseLiteral(ip: String): InetAddress? =
        try {
            // getByName on a pure numeric literal does not perform DNS.
            if (ip.any { it.isLetter() && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' }) {
                // hostnames contain letters outside hex — skip
                null
            } else {
                InetAddress.getByName(ip)
            }
        } catch (_: Exception) {
            null
        }

    private fun isIpv4Mapped(address: Inet6Address): Boolean {
        val b = address.address
        if (b.size != 16) return false
        // ::ffff:0:0/96
        for (i in 0 until 10) if (b[i] != 0.toByte()) return false
        return b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
    }

    private fun isPrivate(address: InetAddress): Boolean {
        if (address.isSiteLocalAddress) return true
        return when (address) {
            is Inet4Address -> {
                val b = address.address
                val a0 = b[0].toInt() and 0xff
                val a1 = b[1].toInt() and 0xff
                when {
                    a0 == 10 -> true
                    a0 == 172 && a1 in 16..31 -> true
                    a0 == 192 && a1 == 168 -> true
                    a0 == 100 && a1 in 64..127 -> true // CGNAT 100.64/10
                    a0 == 198 && (a1 == 18 || a1 == 19) -> true // benchmarking
                    else -> false
                }
            }
            is Inet6Address -> {
                // fc00::/7 unique local
                val b = address.address
                (b[0].toInt() and 0xfe) == 0xfc
            }
            else -> false
        }
    }

    private fun reject(reason: String): Outcome.Rejected =
        Outcome.Rejected(
            error = OmniError.INVALID_REQUEST(
                message = "resolved address denied",
                details = mapOf("reason" to reason),
            ),
            reason = reason,
        )
}
