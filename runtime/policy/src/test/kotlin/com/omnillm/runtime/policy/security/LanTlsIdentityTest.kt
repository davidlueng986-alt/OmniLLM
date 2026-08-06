package com.omnillm.runtime.policy.security

import com.omnillm.core.canonical.generated.OmniResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLContext

class LanTlsIdentityTest {

    private var now = 1_700_000_000_000L
    private val broker = InMemorySecretBroker { now }

    @Test
    fun generate_ecdsaP256_spkiAndSslContext() {
        val mat = LanTlsIdentity.generate(nowEpochMs = now)
        assertEquals(64, mat.spkiSha256.length)
        assertTrue(mat.spkiSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(mat.isValidAt(now))
        assertNotNull(mat.certificate.publicKey)
        val ctx: SSLContext = LanTlsIdentity.createSslContext(mat)
        assertNotNull(ctx)
        assertTrue(ctx.protocol.startsWith("TLS"))
    }

    @Test
    fun wrapUnwrap_secretBroker_preservesSpki() {
        val mat = LanTlsIdentity.generate(nowEpochMs = now)
        val wrapped = LanTlsIdentity.wrapPrivateKey(broker, mat) as OmniResult.Ok
        assertEquals(mat.spkiSha256, wrapped.value.spkiSha256)
        val restored = LanTlsIdentity.unwrapToMaterial(broker, wrapped.value) as OmniResult.Ok
        assertEquals(mat.spkiSha256, restored.value.spkiSha256)
        assertTrue(restored.value.isValidAt(now))
    }

    @Test
    fun selfSignedCert_verifiesWithOwnPublicKey() {
        val mat = LanTlsIdentity.generate(nowEpochMs = now)
        mat.certificate.verify(mat.certificate.publicKey)
        assertFalse(mat.certificate.subjectX500Principal.name.isBlank())
    }
}
