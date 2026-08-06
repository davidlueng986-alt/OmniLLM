package com.omnillm.runtime

import com.omnillm.data.persistence.SessionLedgerPorts
import com.omnillm.runtime.session.DurableSessionManager
import com.omnillm.runtime.session.InMemorySessionManager
import com.omnillm.runtime.session.SessionManager

/**
 * Module façade for `:runtime:session` (CORE-SESSION / INV-006 / INV-007 / ADR-006).
 *
 * Types and SESSION states come from product `specs/` catalogs and CORE-SESSION;
 * do not invent enums outside those authorities.
 *
 * Production control plane uses [createDurableManager] with SQLDelight
 * [SessionLedgerPorts] so descriptors / poison / owner / epochs survive restart.
 * Native KV is never claimed durable without an engine snapshot protocol.
 */
object SessionModule {
    const val MODULE_PATH: String = ":runtime:session"

    /** Default in-process manager (unit tests / no durability). */
    fun createInMemoryManager(): SessionManager = InMemorySessionManager()

    /**
     * Process-crash durable manager (ADR-010 sole writer via [ports]).
     * Rehydrates non-CLOSED Session records on construct; free pool stays empty
     * until an explicit [SessionManager.offerToPool] of a live ACTIVE session.
     */
    fun createDurableManager(
        ports: SessionLedgerPorts,
        clock: () -> String = { java.time.Instant.now().toString() },
    ): SessionManager = DurableSessionManager(store = ports, clock = clock)
}
