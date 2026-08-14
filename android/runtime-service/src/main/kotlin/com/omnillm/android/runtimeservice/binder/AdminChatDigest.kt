package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.canonical.IdentityHashing

/**
 * D23f: canonical input digest for the LOCAL_UI admin playground chat path.
 *
 * Mirrors [com.omnillm.android.runtimeservice.binder.OmniRuntimeFacade.Companion.chatDigest]
 * (COR-13): the digest covers the FULL message content — the old
 * `message.length` digest made same-length-different-content requests
 * collide (idempotent replay would silently substitute content).
 */
object AdminChatDigest {

    fun digest(
        requestId: String,
        idempotencyKey: String,
        model: String,
        message: String,
    ): String = IdentityHashing.sha256Hex(
        "admin-playground-chat|$requestId|$idempotencyKey|$model|$message",
    )
}
