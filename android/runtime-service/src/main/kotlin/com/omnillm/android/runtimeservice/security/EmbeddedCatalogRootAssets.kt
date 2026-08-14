package com.omnillm.android.runtimeservice.security

import android.content.res.AssetManager
import com.omnillm.runtime.modelmanager.supply.EmbeddedCatalogRoot
import java.io.IOException

/**
 * C-14: embedded catalog root provisioning seam (SEC-SUPPLY §1).
 *
 * The release / signing pipeline drops the root payload at
 * `assets/catalog/root-v1.bin`; the runtime finds it here. Absent today ⇒
 * [lookup] returns null ⇒ bootstrap rejects and every supply-chain decision
 * fails closed (documented in
 * `com.omnillm.runtime.modelmanager.supply.ProductionSupplyChainHooks`) —
 * privileged loads stay `TRUST_PLACEMENT_REQUIRED` until a root is
 * provisioned, then the real checks fire with no code change.
 */
object EmbeddedCatalogRootAssets {

    const val ROOT_ASSET_PATH: String = "catalog/root-v1.bin"
    const val ROOT_SCHEMA_MAJOR_VERSION: Int = 1
    const val ROOT_VERSION: Long = 1L

    fun lookup(assets: AssetManager): EmbeddedCatalogRoot? =
        try {
            assets.open(ROOT_ASSET_PATH).use { input ->
                val bytes = input.readBytes()
                EmbeddedCatalogRoot.fromBytes(
                    metadataBytes = bytes,
                    schemaMajorVersion = ROOT_SCHEMA_MAJOR_VERSION,
                    rootVersion = ROOT_VERSION,
                )
            }
        } catch (_: IOException) {
            null
        }
}
