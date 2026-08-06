package com.omnillm.interfaces.http

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.runtime.policy.input.JsonParseLimits
import com.omnillm.runtime.policy.input.JsonTreeBudget

/**
 * HTTP body pre-admission hooks (SEC-INPUT §1).
 *
 * Apply [admitRawSize] before decompression/parse and [admitParsedTree]
 * after a streaming parser produces a Map/List tree. Unknown fields still
 * count toward node budgets inside [JsonTreeBudget].
 *
 * Full Ktor Content-Length / Content-Encoding plugin wiring is host-side;
 * this object is the shared pure gate used by the gateway and tests.
 */
object JsonBodyAdmission {

    fun admitRawSize(
        compressedBytes: Long?,
        decompressedBytes: Long,
        limits: JsonParseLimits = JsonParseLimits.DEFAULT,
    ): OmniError? =
        when (val r = JsonTreeBudget.admitRawSize(compressedBytes, decompressedBytes, limits)) {
            is JsonTreeBudget.Outcome.Accepted -> null
            is JsonTreeBudget.Outcome.Rejected -> r.error
        }

    fun admitParsedTree(
        value: Any?,
        limits: JsonParseLimits = JsonParseLimits.DEFAULT,
    ): OmniError? =
        when (val r = JsonTreeBudget.admitTree(value, limits)) {
            is JsonTreeBudget.Outcome.Accepted -> null
            is JsonTreeBudget.Outcome.Rejected -> r.error
        }
}
