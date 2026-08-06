package com.omnillm.android.runtimeservice.companion

/**
 * Host mirror of companion Binder wire (SEC-EXTERNAL-SANDBOX §2–§5).
 *
 * Must stay field-order / transaction-code identical to companion
 * `com.omnillm.companion.CompanionBinderWire` without depending on the companion APK.
 */
object CompanionBinderWire {
    const val DESCRIPTOR: String = "com.omnillm.companion.ICompanionSandbox"

    // Transaction codes = IBinder.FIRST_CALL_TRANSACTION (1) + N
    const val TRANSACTION_ATTACH_SUPERVISOR: Int = 1
    const val TRANSACTION_HANDSHAKE: Int = 2
    const val TRANSACTION_REGISTER_RO_PFD: Int = 3
    const val TRANSACTION_CLOSE: Int = 4
    const val TRANSACTION_CANCEL: Int = 5
    const val TRANSACTION_QUERY: Int = 6
    const val TRANSACTION_GET_PROTOCOL_MAJOR: Int = 7
    const val TRANSACTION_GET_PROTOCOL_MINOR: Int = 8
    const val TRANSACTION_PROCESS_ROLE: Int = 9
    const val TRANSACTION_PID: Int = 10
    const val TRANSACTION_UID: Int = 11

    const val RESULT_OK: Int = 0
    const val RESULT_HANDSHAKE_OK: Int = 1
    const val RESULT_REJECTED: Int = 2
}
