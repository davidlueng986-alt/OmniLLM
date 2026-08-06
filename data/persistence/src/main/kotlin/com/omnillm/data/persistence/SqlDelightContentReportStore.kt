package com.omnillm.data.persistence

/**
 * SQLDelight-backed [ContentReportLedgerPorts] for control-plane sole writer (ADR-010).
 *
 * Tables: `content_reports`, `content_report_consent_grants`, `content_report_receipts`.
 * Open only via [ControlPlaneDatabase] in the `:runtime` process.
 *
 * Report stream ≠ telemetry — this store never co-mingles with observability sinks.
 */
class SqlDelightContentReportStore(
    private val database: OmniLlmDatabase,
    override val writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : ContentReportLedgerPorts {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
    }

    override val reports: ContentReportRecordDao = object : ContentReportRecordDao {
        override fun findByReportId(reportId: String): ContentReportRecordRow? =
            database.contentReportsQueries
                .selectByReportId(reportId)
                .executeAsOneOrNull()
                ?.toReportRow()

        override fun findByPrincipalAndIdempotency(
            principalId: String,
            idempotencyKey: String,
        ): ContentReportRecordRow? =
            database.contentReportsQueries
                .selectByPrincipalAndIdempotency(principalId, idempotencyKey)
                .executeAsOneOrNull()
                ?.toReportRow()

        override fun listByPrincipal(principalId: String): List<ContentReportRecordRow> =
            database.contentReportsQueries
                .listByPrincipal(principalId)
                .executeAsList()
                .map { it.toReportRow() }

        override fun listAll(): List<ContentReportRecordRow> =
            database.contentReportsQueries
                .listAll()
                .executeAsList()
                .map { it.toReportRow() }

        override fun upsert(row: ContentReportRecordRow) {
            require(row.state in ContentReportLedgerStates.ALL) {
                "unknown content report state: ${row.state}"
            }
            require(row.category in ContentReportLedgerCategories.ALL) {
                "unknown content report category: ${row.category}"
            }
            database.contentReportsQueries.upsertReport(
                report_id = row.reportId,
                principal_id = row.principalId,
                proposal_command_id = row.proposalCommandId,
                idempotency_key = row.idempotencyKey,
                resource_version = row.resourceVersion,
                state = row.state,
                category = row.category,
                app_build = row.appBuild,
                model_revision_id = row.modelRevisionId,
                engine_build_id = row.engineBuildId,
                backend = row.backend,
                local_policy_version = row.localPolicyVersion,
                output_digest = row.outputDigest,
                user_locale = row.userLocale,
                encrypted_proposal = row.encryptedProposal,
                canonical_payload_digest = row.canonicalPayloadDigest,
                encrypted_payload = row.encryptedPayload,
                cancel_pending = if (row.cancelPending) 1L else 0L,
                expires_at = row.expiresAt,
                error_code = row.errorCode,
                active_grant_id = row.activeGrantId,
                receipt_id = row.receiptId,
                receipt_accepted_at = row.receiptAcceptedAt,
                receipt_status_url = row.receiptStatusUrl,
                payload_created_at = row.payloadCreatedAt,
                created_at = row.createdAt,
                updated_at = row.updatedAt,
            )
        }

        override fun delete(reportId: String): Boolean {
            if (findByReportId(reportId) == null) return false
            database.contentReportsQueries.deleteByReportId(reportId)
            return true
        }
    }

    override val grants: ContentReportGrantDao = object : ContentReportGrantDao {
        override fun findByGrantId(grantId: String): ContentReportGrantRow? =
            database.contentReportConsentGrantsQueries
                .selectByGrantId(grantId)
                .executeAsOneOrNull()
                ?.toGrantRow()

        override fun listByReportId(reportId: String): List<ContentReportGrantRow> =
            database.contentReportConsentGrantsQueries
                .listByReportId(reportId)
                .executeAsList()
                .map { it.toGrantRow() }

        override fun upsert(row: ContentReportGrantRow) {
            require(row.state in ContentReportGrantStates.ALL) {
                "unknown grant state: ${row.state}"
            }
            database.contentReportConsentGrantsQueries.upsertGrant(
                grant_id = row.grantId,
                report_id = row.reportId,
                principal_id = row.principalId,
                canonical_payload_digest = row.canonicalPayloadDigest,
                warning_policy_version = row.warningPolicyVersion,
                local_user_profile_id = row.localUserProfileId,
                nonce = row.nonce,
                state = row.state,
                issued_at = row.issuedAt,
                expires_at = row.expiresAt,
                consumed_at = row.consumedAt,
            )
        }

        override fun delete(grantId: String): Boolean {
            if (findByGrantId(grantId) == null) return false
            database.contentReportConsentGrantsQueries.deleteByGrantId(grantId)
            return true
        }
    }

    override val receipts: ContentReportReceiptDao = object : ContentReportReceiptDao {
        override fun findByReportId(reportId: String): ContentReportReceiptRow? =
            database.contentReportReceiptsQueries
                .selectByReportId(reportId)
                .executeAsOneOrNull()
                ?.toReceiptRow()

        override fun findByReceiptId(receiptId: String): ContentReportReceiptRow? =
            database.contentReportReceiptsQueries
                .selectByReceiptId(receiptId)
                .executeAsOneOrNull()
                ?.toReceiptRow()

        override fun upsert(row: ContentReportReceiptRow) {
            database.contentReportReceiptsQueries.upsertReceipt(
                receipt_id = row.receiptId,
                report_id = row.reportId,
                accepted_at = row.acceptedAt,
                status_url = row.statusUrl,
                response_digest = row.responseDigest,
                last_queried_at = row.lastQueriedAt,
            )
        }

        override fun deleteByReportId(reportId: String): Boolean {
            if (findByReportId(reportId) == null) return false
            database.contentReportReceiptsQueries.deleteByReportId(reportId)
            return true
        }
    }

    override val tx: ClaimLedgerTransaction = object : ClaimLedgerTransaction {
        override fun <T> inTransaction(block: () -> T): T =
            database.transactionWithResult { block() }
    }
}

private fun Content_reports.toReportRow(): ContentReportRecordRow =
    ContentReportRecordRow(
        reportId = report_id,
        principalId = principal_id,
        proposalCommandId = proposal_command_id,
        idempotencyKey = idempotency_key,
        resourceVersion = resource_version,
        state = state,
        category = category,
        appBuild = app_build,
        modelRevisionId = model_revision_id,
        engineBuildId = engine_build_id,
        backend = backend,
        localPolicyVersion = local_policy_version,
        outputDigest = output_digest,
        userLocale = user_locale,
        encryptedProposal = encrypted_proposal,
        canonicalPayloadDigest = canonical_payload_digest,
        encryptedPayload = encrypted_payload,
        cancelPending = cancel_pending != 0L,
        expiresAt = expires_at,
        errorCode = error_code,
        activeGrantId = active_grant_id,
        receiptId = receipt_id,
        receiptAcceptedAt = receipt_accepted_at,
        receiptStatusUrl = receipt_status_url,
        payloadCreatedAt = payload_created_at,
        createdAt = created_at,
        updatedAt = updated_at,
    )

private fun Content_report_consent_grants.toGrantRow(): ContentReportGrantRow =
    ContentReportGrantRow(
        grantId = grant_id,
        reportId = report_id,
        principalId = principal_id,
        canonicalPayloadDigest = canonical_payload_digest,
        warningPolicyVersion = warning_policy_version,
        localUserProfileId = local_user_profile_id,
        nonce = nonce,
        state = state,
        issuedAt = issued_at,
        expiresAt = expires_at,
        consumedAt = consumed_at,
    )

private fun Content_report_receipts.toReceiptRow(): ContentReportReceiptRow =
    ContentReportReceiptRow(
        receiptId = receipt_id,
        reportId = report_id,
        acceptedAt = accepted_at,
        statusUrl = status_url,
        responseDigest = response_digest,
        lastQueriedAt = last_queried_at,
    )
