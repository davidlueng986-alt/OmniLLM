package com.omnillm.core.contracts

/**
 * Module marker for `:core:contracts`.
 *
 * ADR-002: Plan → Reservation → Commit → Execute.
 * Plan performs no domain mutation. Opaque request/plan/commit/prepared
 * identities and pure contract envelopes live here.
 */
object ContractsModule {
    const val MODULE_PATH: String = ":core:contracts"
}
