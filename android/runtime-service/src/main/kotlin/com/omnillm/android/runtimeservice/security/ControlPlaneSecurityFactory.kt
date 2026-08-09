package com.omnillm.android.runtimeservice.security

import android.content.Context
import android.util.Log
import com.omnillm.data.persistence.ControlPlaneDatabase
import com.omnillm.runtime.PolicyModule
import com.omnillm.runtime.policy.security.EncryptedBlobSecretKeyVault
import com.omnillm.runtime.policy.security.VaultSecretBroker

/**
 * Builds the production control-plane security stack (ADR-010 / SEC-AUTH-NET).
 *
 * - Secret Broker keys: Android Keystore-wrapped master + AES-GCM encrypted
 *   operational key blobs in SQLite ([EncryptedBlobSecretKeyVault])
 * - Access tokens: SQLite HMAC-SHA-256 verifiers only (no plaintext)
 * - Pairing challenges: SQLite + encrypted secret ciphertext
 * - Revocation epochs: SQLite durable fence
 */
object ControlPlaneSecurityFactory {
    private const val TAG = "OmniSecurityFactory"

    /**
     * Production entry: full Android stack (Keystore-wrapped master key).
     * The Context is used ONLY to resolve the master blob directory.
     */
    fun createSecurityStack(
        appContext: Context,
        controlPlaneDb: ControlPlaneDatabase,
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): PolicyModule.SecurityStack =
        createSecurityStack(
            controlPlaneDb = controlPlaneDb,
            clockMs = clockMs,
            masterKeyBytes = {
                AndroidKeystoreMasterKey({ appContext.noBackupFilesDir }).getOrCreateMasterKeyBytes()
            },
        )

    /**
     * TST-04 seam: hermetic stack construction with an injected master wrapping
     * key (no Android Keystore / Context required). Production wiring stays on
     * the Context overload above.
     */
    fun createSecurityStack(
        controlPlaneDb: ControlPlaneDatabase,
        clockMs: () -> Long = { System.currentTimeMillis() },
        masterKeyBytes: () -> ByteArray,
    ): PolicyModule.SecurityStack {
        val secrets = controlPlaneDb.secrets
        val masterKey = masterKeyBytes()
        val vault = EncryptedBlobSecretKeyVault(
            store = secrets.keyBlobs,
            masterKeyBytes = masterKey,
            clockMs = clockMs,
        )
        val broker = VaultSecretBroker(
            vault = vault,
            clockMs = clockMs,
            bootstrapKeys = true,
        )
        runCatching {
            Log.i(
                TAG,
                "Secret Broker durable vault ready tokenKeyV=${broker.activeTokenKeyVersion()} " +
                    "storage=keystore-wrapped+sqlite",
            )
        }
        return PolicyModule.createSecurityStack(
            clockMs = clockMs,
            broker = broker,
            accessTokenStore = secrets.accessTokens,
            pairingStore = secrets.pairingChallenges,
            epochStore = secrets.revocationEpochs,
        )
    }
}
