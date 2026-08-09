package com.omnillm.android.runtimeservice.security

import com.omnillm.core.ports.security.SecurityProfile
import com.omnillm.runtime.policy.security.CryptoPrimitives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory

/**
 * TST-04: AndroidKeystoreMasterKey wrap/unwrap contract on the JVM.
 *
 * The cipher seam injects a Keystore-constraint fake ([KeystoreLikeCipherSpi])
 * that performs REAL AES-256-GCM crypto but — like Android Keystore with
 * randomizedEncryptionRequired(true) — REJECTS caller-provided IVs on ENCRYPT.
 * This is the exact regression for the device crash captured in
 * e2e-artifacts/session.json:
 *   `InvalidAlgorithmParameterException: Caller-provided IV not permitted in
 *    AndroidKeystoreMasterKey.encryptWithKeystore`
 */
class AndroidKeystoreMasterKeyTest {

    private fun tempDir(): File = createTempDirectory("omnillm-keystore-test").toFile()

    private fun secondKey(sameDir: File): AndroidKeystoreMasterKey =
        AndroidKeystoreMasterKey(blobDirProvider = { sameDir }).apply {
            ensureKeystore = {}
            keySource = { testAesKey }
            cipherFactory = { transformation -> keystoreLikeCipher(transformation) }
        }

    private fun blobFile(dir: File): File =
        File(dir, "omnillm_secret_broker_master.bin")

    // ----- wrap / unwrap round trip -----------------------------------------

    @Test
    fun wrapThenUnwrap_returnsSameMasterAcrossInstances() {
        val dir = tempDir()
        val first = keyWithDir(dir)
        val master1 = first.getOrCreateMasterKeyBytes()
        assertEquals(SecurityProfile.SECRET_KEY_BYTES, master1.size)

        // Second instance (fresh object, same dir + same Keystore key) unwraps.
        val second = secondKey(dir)
        val master2 = second.getOrCreateMasterKeyBytes()
        assertArrayEquals("master blob must unwrap to the SAME key", master1, master2)
    }

    @Test
    fun wrappedBlob_layoutIsNoncePrefixPlusCiphertext() {
        val dir = tempDir()
        val master = keyWithDir(dir).getOrCreateMasterKeyBytes()
        val blob = blobFile(dir)
        assertTrue("master blob must be written", blob.exists())
        val bytes = blob.readBytes()
        assertTrue(
            "blob must be nonce(${SecurityProfile.GCM_NONCE_BYTES}) + ct",
            bytes.size > SecurityProfile.GCM_NONCE_BYTES,
        )
        // The first GCM_NONCE_BYTES bytes are the cipher-generated nonce.
        val nonce = bytes.copyOfRange(0, SecurityProfile.GCM_NONCE_BYTES)
        assertNotEquals(
            "nonce must not be zeroed (real cipher-generated IV)",
            ByteArray(SecurityProfile.GCM_NONCE_BYTES).toList(),
            nonce.toList(),
        )
        // Unwrap via the stored nonce yields the master back.
        val ct = bytes.copyOfRange(SecurityProfile.GCM_NONCE_BYTES, bytes.size)
        val unwrapped = unwrapWithRealCipher(nonce, ct)
        assertArrayEquals(master, unwrapped)
    }

    // ----- cipher-IV regression (the device crash) --------------------------

    @Test
    fun encrypt_mustUseCipherGeneratedIv_neverCallerProvided() {
        val dir = tempDir()
        val key = keyWithDir(dir)
        // KeystoreLikeCipherSpi throws exactly like Android Keystore when a
        // caller-provided IV is passed on ENCRYPT. A regression would surface
        // here as InvalidAlgorithmParameterException during getOrCreateMasterKeyBytes.
        val master = key.getOrCreateMasterKeyBytes()
        assertNotNull(master)
    }

    @Test
    fun encryptWithCallerProvidedIv_isRejectedByTheKeystoreConstraint() {
        // Prove the fake enforces the Android Keystore constraint itself:
        // passing a GCMParameterSpec on ENCRYPT must fail — otherwise the
        // regression test above would be vacuous.
        val cipher = keystoreLikeCipher("AES/GCM/NoPadding")
        assertThrows(InvalidAlgorithmParameterException::class.java) {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                testAesKey,
                javax.crypto.spec.GCMParameterSpec(SecurityProfile.GCM_TAG_BITS, ByteArray(12)),
            )
        }
    }

    // ----- failure paths -----------------------------------------------------

    @Test
    fun corruptedNonce_failsDecrypt_andRotatesBlob() {
        val dir = tempDir()
        val master1 = keyWithDir(dir).getOrCreateMasterKeyBytes()
        val blob = blobFile(dir)
        val bytes = blob.readBytes()
        // Flip bits inside the nonce region — GCM authentication must fail.
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        blob.writeBytes(bytes)

        // Next call: decrypt fails -> blob deleted -> NEW master written.
        val master2 = keyWithDir(dir).getOrCreateMasterKeyBytes()
        assertEquals(SecurityProfile.SECRET_KEY_BYTES, master2.size)
        assertNotEquals(
            "rotation must produce a DIFFERENT master (old tokens invalid)",
            master1.toList(),
            master2.toList(),
        )
        // Blob rewritten with the new master; unwraps to master2.
        val bytes2 = blob.readBytes()
        val nonce2 = bytes2.copyOfRange(0, SecurityProfile.GCM_NONCE_BYTES)
        val ct2 = bytes2.copyOfRange(SecurityProfile.GCM_NONCE_BYTES, bytes2.size)
        assertArrayEquals(master2, unwrapWithRealCipher(nonce2, ct2))
    }

    @Test
    fun truncatedBlob_ignored_andRecreated() {
        val dir = tempDir()
        val master1 = keyWithDir(dir).getOrCreateMasterKeyBytes()
        // Corrupt to something shorter than nonce+1 → treated as absent.
        blobFile(dir).writeBytes(ByteArray(4))
        val master2 = keyWithDir(dir).getOrCreateMasterKeyBytes()
        assertEquals(SecurityProfile.SECRET_KEY_BYTES, master2.size)
        assertNotEquals(master1.toList(), master2.toList())
    }

    @Test
    fun wrongKeystoreKey_cannotUnwrap_existingBlob_rotates() {
        val dir = tempDir()
        val master1 = keyWithDir(dir).getOrCreateMasterKeyBytes()
        // Same dir + blob, but a DIFFERENT Keystore key (alias rotation /
        // key loss): the old blob must not unwrap — rotation is honest, not a
        // silent reuse of stale ciphertext.
        val otherKey = AndroidKeystoreMasterKey(blobDirProvider = { dir }).apply {
            ensureKeystore = {}
            keySource = { SecretKeySpec(ByteArray(32) { 0x42 }, "AES") }
            cipherFactory = { transformation -> keystoreLikeCipher(transformation) }
        }
        val master2 = otherKey.getOrCreateMasterKeyBytes()
        assertNotEquals(master1.toList(), master2.toList())
        assertTrue(blobFile(dir).length() > SecurityProfile.GCM_NONCE_BYTES)
    }

    @Test
    fun missingKeystoreKey_propagatesFailure() {
        val dir = tempDir()
        val broken = AndroidKeystoreMasterKey(blobDirProvider = { dir }).apply {
            ensureKeystore = {}
            keySource = { error("Keystore alias missing") }
            cipherFactory = { transformation -> keystoreLikeCipher(transformation) }
        }
        assertThrows(IllegalStateException::class.java) {
            broken.getOrCreateMasterKeyBytes()
        }
    }

    // ----- helpers -----------------------------------------------------------

    private fun keyWithDir(dir: File): AndroidKeystoreMasterKey =
        AndroidKeystoreMasterKey(blobDirProvider = { dir }).apply {
            ensureKeystore = {}
            keySource = { testAesKey }
            cipherFactory = { transformation -> keystoreLikeCipher(transformation) }
        }

    private val testAesKey: SecretKey =
        SecretKeySpec(CryptoPrimitives.randomSecretKeyBytes(), "AES")

    private fun unwrapWithRealCipher(nonce: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            testAesKey,
            javax.crypto.spec.GCMParameterSpec(SecurityProfile.GCM_TAG_BITS, nonce),
        )
        cipher.updateAAD(AndroidKeystoreMasterKeyTestAAD)
        return cipher.doFinal(ct)
    }
}

/** AAD used by the production key (mirrors AndroidKeystoreMasterKey.AAD). */
internal val AndroidKeystoreMasterKeyTestAAD: ByteArray =
    "profileId=${SecurityProfile.PROFILE_ID}\nrecordType=SECRET_BROKER_MASTER\n"
        .toByteArray(Charsets.UTF_8)

/**
 * CipherSpi wrapper that performs REAL AES-GCM via a delegate cipher but
 * enforces the Android Keystore constraint: caller-provided IVs on ENCRYPT are
 * rejected (randomizedEncryptionRequired). Deliberately mirrors the device
 * crash message from e2e-artifacts/session.json.
 */
class KeystoreLikeCipherSpi : CipherSpi() {

    private val delegate: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    override fun engineSetMode(mode: String?) {}

    override fun engineSetPadding(padding: String?) {}

    override fun engineGetBlockSize(): Int = delegate.blockSize

    override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)

    override fun engineGetIV(): ByteArray = delegate.iv

    override fun engineGetParameters(): AlgorithmParameters = delegate.parameters

    override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        if (opmode == Cipher.ENCRYPT_MODE && params != null) {
            throw InvalidAlgorithmParameterException(
                "Caller-provided IV not permitted in AndroidKeystoreMasterKey.encryptWithKeystore",
            )
        }
        if (params != null) delegate.init(opmode, key, params, random) else delegate.init(opmode, key, random)
    }

    override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) {
        delegate.init(opmode, key, random)
    }

    override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) {
        if (opmode == Cipher.ENCRYPT_MODE && params != null) {
            throw InvalidAlgorithmParameterException(
                "Caller-provided IV not permitted in AndroidKeystoreMasterKey.encryptWithKeystore",
            )
        }
        if (params != null) delegate.init(opmode, key, params, random) else delegate.init(opmode, key, random)
    }

    override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray =
        delegate.update(input, inputOffset, inputLen)

    override fun engineUpdate(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
        delegate.update(input, inputOffset, inputLen, output, outputOffset)

    override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int): ByteArray =
        delegate.doFinal(input, inputOffset, inputLen)

    override fun engineDoFinal(input: ByteArray, inputOffset: Int, inputLen: Int, output: ByteArray, outputOffset: Int): Int =
        delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)

    override fun engineUpdateAAD(input: ByteArray, offset: Int, len: Int) {
        delegate.updateAAD(input, offset, len)
    }

    override fun engineUpdateAAD(input: ByteBuffer) {
        delegate.updateAAD(input)
    }
}

/** Provider that routes AES/GCM/NoPadding to [KeystoreLikeCipherSpi]. */
class KeystoreLikeProvider : Provider("OmniTestKeystoreLike", 1.0, "test-only") {
    init {
        put("Cipher.AES/GCM/NoPadding", KeystoreLikeCipherSpi::class.java.name)
    }
}

private fun keystoreLikeCipher(transformation: String): Cipher =
    Cipher.getInstance(transformation, KeystoreLikeProvider())
