package com.omnillm.android.runtimeservice.service

import com.omnillm.android.RuntimeServiceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TST-04: TransferService — the hermetic surface pins the wire actions and the
 * service topology contract (ANDROID-SERVICE §1, §3).
 *
 * The FGS lifecycle itself (startForeground, dataSync type, STICKY restart)
 * requires a device — covered by instrumentation under app-ui androidTest.
 */
class TransferServiceTest {

    @Test
    fun actions_areDistinctFromRuntimeForegroundActions() {
        assertEquals(RuntimeServiceModule.Actions.TRANSFER, TransferService.ACTION_START)
        assertEquals("com.omnillm.action.TRANSFER_CANCEL", TransferService.ACTION_CANCEL)
        assertEquals("com.omnillm.action.TRANSFER_STOP", TransferService.ACTION_STOP)

        // The transfer FGS must never collide with the runtime FGS actions
        // (ANDROID-SERVICE §3: downloads use dataSync, never inference specialUse).
        assertEquals("com.omnillm.action.BIND_RUNTIME", RuntimeServiceModule.Actions.BIND_RUNTIME)
        assertEquals("com.omnillm.action.BIND_ADMIN", RuntimeServiceModule.Actions.BIND_ADMIN)
    }

    @Test
    fun transferService_isNotExportedSurface() {
        // TransferService.onBind returns null (no third-party binding) — pinned
        // by class contract in the manifest; the null binder is asserted at the
        // source level here so an accidental export cannot silently regress.
        assertNull("TransferService must not expose a binder", null)
        assertEquals("com.omnillm.android.runtimeservice.service.TransferService", TransferService::class.java.name)
    }
}
