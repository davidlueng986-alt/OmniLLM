package com.omnillm.runtime

/**
 * Module marker for `:runtime:governor`.
 *
 * Multi-dimensional resource reservation, pressure admission, and eviction
 * release barrier (CORE-RESOURCE / ADR-003 / ARCH conservation).
 *
 * Implementation: [com.omnillm.runtime.governor.ResourceGovernor],
 * [com.omnillm.runtime.governor.ResourceLedger].
 *
 * Types and states come from product `specs/` catalogs — do not invent enums.
 */
object GovernorModule {
    const val MODULE_PATH: String = ":runtime:governor"
}
