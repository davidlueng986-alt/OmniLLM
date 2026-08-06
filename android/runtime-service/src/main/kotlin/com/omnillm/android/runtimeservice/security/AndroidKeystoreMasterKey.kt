package com.omnillm.android.runtimeservice.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.omnillm.runtime.policy.security.CryptoPrimitives
import com.omnillm.runtime.policy.security.SecurityProfile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed master wrapping key (SEC-PROFILE keyLifecycle / recordEncryption).
 *
 * Holds a non-exportable AES-256-GCM Keystore key used to wrap the Secret Broker
 * master key blob on private app storage. Operational TOKEN_VERIFIER /
 * RECORD_ENCRYPTION keys are AES-GCM encrypted into SQLite via
 * [com.omnillm.runtime.policy.security.EncryptedBlobSecretKeyVault] — never
 * plaintext in DB. Workers / UI / companion never receive this alias.
 *
 * Backup: Keystore material and wrapped master blob are excluded from auto-backup
 * (see app data-extraction / backup rules).
 */
class AndroidKeystoreMasterKey(
    private val appContext: Context,
    private val alias: String = KEYSTORE_ALIAS,
) {
    /**
     * Returns the 256-bit master wrapping key for [EncryptedBlobSecretKeyVault].
     * Creates Keystore AES key + wrapped master blob on first call.
     */
    fun getOrCreateMasterKeyBytes(): ByteArray {
        ensureKeystoreAes()
        val wrappedFile = File(appContext.noBackupFilesDir, MASTER_BLOB_FILE)
        if (wrappedFile.exists() && wrappedFile.length() > SecurityProfile.GCM_NONCE_BYTES) {
            val wrapped = wrappedFile.readBytes()
            val nonce = wrapped.copyOfRange(0, SecurityProfile.GCM_NONCE_BYTES)
            val ct = wrapped.copyOfRange(SecurityProfile.GCM_NONCE_BYTES, wrapped.size)
            val plain = decryptWithKeystore(nonce, ct)
            if (plain != null && plain.size == SecurityProfile.SECRET_KEY_BYTES) {
                return plain
            }
            Log.w(TAG, "master blob decrypt failed — rotating (tokens must re-issue)")
            wrappedFile.delete()
        }
        val master = CryptoPrimitives.randomSecretKeyBytes()
        // Android Keystore AES-GCM with setRandomizedEncryptionRequired(true)
        // rejects caller-provided IVs on ENCRYPT — use cipher-generated IV.
        val (nonce, ct) = encryptWithKeystore(master)
        wrappedFile.parentFile?.mkdirs()
        wrappedFile.writeBytes(nonce + ct)
        return master
    }

    private fun ensureKeystoreAes() {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) return
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(SecurityProfile.SECRET_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        keyGen.generateKey()
        Log.i(TAG, "Android Keystore AES master wrap key created alias=$alias")
    }

    private fun keystoreSecretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: error("Keystore alias missing: $alias")
        return entry.secretKey
    }

    /**
     * Encrypt with Keystore-generated GCM IV (required when randomized encryption is on).
     * Returns (nonce, ciphertext+tag).
     */
    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreSecretKey())
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(plaintext)
        val nonce = cipher.iv
            ?: error("Keystore GCM encrypt produced no IV")
        require(nonce.size == SecurityProfile.GCM_NONCE_BYTES) {
            "unexpected GCM nonce size ${nonce.size}"
        }
        return nonce to ct
    }

    private fun decryptWithKeystore(nonce: ByteArray, ciphertext: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keystoreSecretKey(),
                GCMParameterSpec(SecurityProfile.GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }

    companion object {
        private const val TAG = "OmniKeystoreMaster"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "omnillm.secret_broker.master_wrap"
        private const val MASTER_BLOB_FILE = "omnillm_secret_broker_master.bin"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD: ByteArray =
            "profileId=${SecurityProfile.PROFILE_ID}\nrecordType=SECRET_BROKER_MASTER\n"
                .toByteArray(Charsets.UTF_8)
    }
}
