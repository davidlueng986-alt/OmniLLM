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

    fun createSecurityStack(
        appContext: Context,
        controlPlaneDb: ControlPlaneDatabase,
        clockMs: () -> Long = { System.currentTimeMillis() },
    ): PolicyModule.SecurityStack {
        val secrets = controlPlaneDb.secrets
        val masterKey = AndroidKeystoreMasterKey(appContext).getOrCreateMasterKeyBytes()
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
        Log.i(
            TAG,
            "Secret Broker durable vault ready tokenKeyV=${broker.activeTokenKeyVersion()} " +
                "storage=keystore-wrapped+sqlite",
        )
        return PolicyModule.createSecurityStack(
            clockMs = clockMs,
            broker = broker,
            accessTokenStore = secrets.accessTokens,
            pairingStore = secrets.pairingChallenges,
            epochStore = secrets.revocationEpochs,
        )
    }
}
