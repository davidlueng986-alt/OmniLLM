package com.omnillm.runtime.policy.download

import com.omnillm.core.errors.generated.OmniErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DownloadUrlAndDnsPolicyTest {

    @Test
    fun httpsUrl_accepted() {
        val r = DownloadUrlPolicy.admitUrl("https://cdn.example.com/models/m.gguf")
        assertTrue(r is DownloadUrlPolicy.Outcome.Accepted)
        val ok = r as DownloadUrlPolicy.Outcome.Accepted
        assertEquals("cdn.example.com", ok.host)
        assertEquals(443, ok.port)
    }

    @Test
    fun httpAndFileAndUserinfo_rejected() {
        assertTrue(DownloadUrlPolicy.admitUrl("http://example.com/a") is DownloadUrlPolicy.Outcome.Rejected)
        assertTrue(DownloadUrlPolicy.admitUrl("file:///tmp/x") is DownloadUrlPolicy.Outcome.Rejected)
        assertTrue(
            DownloadUrlPolicy.admitUrl("https://user:pass@example.com/a") is DownloadUrlPolicy.Outcome.Rejected,
        )
        assertTrue(
            DownloadUrlPolicy.admitUrl("https://localhost/a") is DownloadUrlPolicy.Outcome.Rejected,
        )
    }

    @Test
    fun redirect_respectsHopLimit() {
        val policy = DownloadUrlPolicy.Policy(maxRedirectHops = 1)
        val hop0 = DownloadUrlPolicy.admitRedirect(
            "https://mirror.example.com/m.gguf",
            hopIndex = 0,
            policy = policy,
        )
        assertTrue(hop0 is DownloadUrlPolicy.Outcome.Accepted)
        val hop1 = DownloadUrlPolicy.admitRedirect(
            "https://mirror.example.com/m2.gguf",
            hopIndex = 1,
            policy = policy,
        )
        assertTrue(hop1 is DownloadUrlPolicy.Outcome.Rejected)
    }

    @Test
    fun resolvedAddress_deniesLoopbackPrivateMetadata() {
        val loop = ResolvedAddressPolicy.admit(InetAddress.getByName("127.0.0.1"))
        assertTrue(loop is ResolvedAddressPolicy.Outcome.Rejected)

        val priv = ResolvedAddressPolicy.admit(InetAddress.getByName("10.0.0.5"))
        assertTrue(priv is ResolvedAddressPolicy.Outcome.Rejected)

        val meta = ResolvedAddressPolicy.admit(InetAddress.getByName("169.254.169.254"))
        assertTrue(meta is ResolvedAddressPolicy.Outcome.Rejected)

        // Public address (TEST-NET-1 documentation range may still be "public" classifier).
        // Use 8.8.8.8 as a well-known public resolver IP for unit classification only.
        val pub = ResolvedAddressPolicy.admit(InetAddress.getByName("8.8.8.8"))
        assertTrue(pub is ResolvedAddressPolicy.Outcome.Accepted)
    }

    @Test
    fun rangePolicy_halfOpenAndOverlap() {
        val a = RangeDownloadPolicy.admitRange(0, 100, contentLength = 1000)
        assertTrue(a is RangeDownloadPolicy.Outcome.Accepted)

        val over = RangeDownloadPolicy.admitRange(0, 2000, contentLength = 1000)
        assertTrue(over is RangeDownloadPolicy.Outcome.Rejected)

        val existing = listOf(ByteRange(0, 100))
        val overlap = RangeDownloadPolicy.admitRange(50, 150, contentLength = 1000, existingRanges = existing)
        assertTrue(overlap is RangeDownloadPolicy.Outcome.Rejected)
        assertEquals(
            OmniErrorCode.INVALID_REQUEST,
            (overlap as RangeDownloadPolicy.Outcome.Rejected).error.code,
        )
    }
}
