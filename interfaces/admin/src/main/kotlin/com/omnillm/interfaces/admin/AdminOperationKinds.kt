package com.omnillm.interfaces.admin

/**
 * Durable command operation kinds for Admin mutations
 * (`specs/command-conformance-fixtures.yaml`).
 *
 * Claim key = (principalId, operationKind, idempotencyKey).
 * Do not invent synonyms — fail closed on unknown kinds at boundaries.
 */
object AdminOperationKinds {
    const val CREATE_JOB: String = "createJob"
    const val CANCEL_JOB: String = "cancelJob"
    const val PATCH_SETTINGS: String = "patchSettings"
    const val SUBMIT_CONTENT_REPORT: String = "submitContentReport"
    const val REVIEW_CONTENT_REPORT: String = "reviewContentReport"

    val ALL: Set<String> = setOf(
        CREATE_JOB,
        CANCEL_JOB,
        PATCH_SETTINGS,
        SUBMIT_CONTENT_REPORT,
        REVIEW_CONTENT_REPORT,
    )
}
