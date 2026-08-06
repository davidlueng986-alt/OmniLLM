package com.omnillm.runtime.job

import com.omnillm.core.contracts.IdempotencyKey
import com.omnillm.core.contracts.PrincipalId
import com.omnillm.core.errors.generated.OmniError
import com.omnillm.core.errors.generated.OmniErrorCode
import com.omnillm.core.state.domain.JobId
import com.omnillm.data.persistence.JobAttemptLedgerRow
import com.omnillm.data.persistence.JobEventLedgerRow
import com.omnillm.data.persistence.JobLedgerPorts
import com.omnillm.data.persistence.JobLedgerRow
import com.omnillm.data.persistence.SingleWriterPolicy
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * SQLDelight / SQLite [JobStore] (ADR-010 sole writer via [JobLedgerPorts]).
 *
 * Production path: opened only through [ControlPlaneDatabase.jobs] in `:runtime`.
 * Job state, attempts, events, and checkpoint are written in one transaction
 * (FEAT-ADMIN §3 / DATA-OWNERSHIP).
 *
 * Survives process restart: reopen the same SQLite file and query by id/claim.
 */
class SqlDelightJobStore(
    private val ports: JobLedgerPorts,
    writerRole: String = SingleWriterPolicy.WRITER_ROLE,
) : JobStore {

    init {
        SingleWriterPolicy.assertWriterAllowed(writerRole)
        require(ports.writerRole == writerRole) {
            "JobLedgerPorts writerRole mismatch: ${ports.writerRole} vs $writerRole"
        }
    }

    override fun findById(jobId: JobId): JobRecord? {
        val header = ports.jobs.findByJobId(jobId.value) ?: return null
        return assemble(header)
    }

    override fun findByClaim(
        principalId: String,
        kind: JobKind,
        idempotencyKey: String,
    ): JobRecord? {
        val header = ports.jobs.findByClaimKey(principalId, kind.name, idempotencyKey)
            ?: return null
        return assemble(header)
    }

    override fun putNew(record: JobRecord): JobRecord {
        val id = record.identity.jobId.value
        require(ports.jobs.findByJobId(id) == null) { "jobId already present: $id" }
        val claim = ports.jobs.findByClaimKey(
            record.identity.principalId.value,
            record.identity.kind.name,
            record.identity.idempotencyKey.value,
        )
        require(claim == null) { "claim key already present" }
        ports.tx.inTransaction {
            ports.jobs.insert(record.toHeaderRow())
            for (attempt in record.attempts) {
                ports.attempts.insert(attempt.toAttemptRow(id))
            }
            for (event in record.events) {
                ports.events.insert(event.toEventRow(id))
            }
        }
        return record
    }

    override fun replace(record: JobRecord): JobRecord {
        val id = record.identity.jobId.value
        require(ports.jobs.findByJobId(id) != null) { "unknown jobId: $id" }
        ports.tx.inTransaction {
            // Child rows first so a partial crash still has a consistent header after reopen;
            // events reference attempts by number only (no FK in SQLDelight subset).
            ports.events.deleteByJobId(id)
            ports.attempts.deleteByJobId(id)
            check(ports.jobs.update(record.toHeaderRow())) {
                "job update failed for $id"
            }
            for (attempt in record.attempts) {
                ports.attempts.insert(attempt.toAttemptRow(id))
            }
            for (event in record.events) {
                ports.events.insert(event.toEventRow(id))
            }
        }
        return record
    }

    override fun listByPrincipal(principalId: String): List<JobRecord> =
        ports.jobs.listByPrincipal(principalId).map { assemble(it) }

    override fun listActive(): List<JobRecord> =
        ports.jobs.listActive().map { assemble(it) }

    private fun assemble(header: JobLedgerRow): JobRecord {
        val body = JobBodyCodec.decode(header.canonicalSpecJson)
        val attempts = ports.attempts.listByJobId(header.jobId).map { it.toAttemptRecord() }
        val events = ports.events.listByJobId(header.jobId).map { it.toEventRecord() }
        val checkpoint = header.checkpointJson?.let { JobCheckpointCodec.decode(it) }
        return JobRecord(
            identity = JobIdentity(
                jobId = JobId(header.jobId),
                principalId = PrincipalId.parse(header.principalId),
                kind = JobKind.requireFromCatalogName(header.jobKind),
                idempotencyKey = IdempotencyKey.parse(header.idempotencyKey),
                canonicalSpecDigest = body.canonicalSpecDigest,
            ),
            parameters = body.parameters,
            state = header.state,
            resourceVersion = header.resourceVersion,
            currentAttemptNo = body.currentAttemptNo,
            attempts = attempts,
            checkpoint = checkpoint,
            progress = body.progress,
            pauseReason = JobPauseReason.fromState(header.state),
            error = body.error,
            events = events,
            createdAtEpochMs = parseEpochMs(header.createdAt),
            updatedAtEpochMs = parseEpochMs(header.updatedAt),
            cancelRequested = body.cancelRequested,
        )
    }

    private fun JobRecord.toHeaderRow(): JobLedgerRow =
        JobLedgerRow(
            jobId = identity.jobId.value,
            principalId = identity.principalId.value,
            jobKind = identity.kind.name,
            idempotencyKey = identity.idempotencyKey.value,
            canonicalSpecJson = JobBodyCodec.encode(
                JobBody(
                    canonicalSpecDigest = identity.canonicalSpecDigest,
                    parameters = parameters,
                    currentAttemptNo = currentAttemptNo,
                    progress = progress,
                    error = error,
                    cancelRequested = cancelRequested,
                ),
            ),
            state = state,
            resourceVersion = resourceVersion,
            checkpointJson = checkpoint?.let { JobCheckpointCodec.encode(it) },
            createdAt = formatEpochMs(createdAtEpochMs),
            updatedAt = formatEpochMs(updatedAtEpochMs),
        )

    companion object {
        internal fun formatEpochMs(epochMs: Long): String =
            Instant.ofEpochMilli(epochMs).toString()

        internal fun parseEpochMs(value: String): Long =
            Instant.parse(value).toEpochMilli()
    }
}

// ---------------------------------------------------------------------------
// Row ↔ domain helpers
// ---------------------------------------------------------------------------

private fun JobAttemptRecord.toAttemptRow(jobId: String): JobAttemptLedgerRow =
    JobAttemptLedgerRow(
        jobId = jobId,
        attemptNo = attemptNo,
        state = state,
        startedAt = SqlDelightJobStore.formatEpochMs(startedAtEpochMs),
        endedAt = endedAtEpochMs?.let { SqlDelightJobStore.formatEpochMs(it) },
    )

private fun JobAttemptLedgerRow.toAttemptRecord(): JobAttemptRecord =
    JobAttemptRecord(
        attemptNo = attemptNo,
        state = state,
        startedAtEpochMs = SqlDelightJobStore.parseEpochMs(startedAt),
        endedAtEpochMs = endedAt?.let { SqlDelightJobStore.parseEpochMs(it) },
    )

private fun JobEventRecord.toEventRow(jobId: String): JobEventLedgerRow =
    JobEventLedgerRow(
        eventId = eventId,
        jobId = jobId,
        attemptNo = attemptNo,
        eventKind = eventKind,
        payloadJson = if (payload.isEmpty()) null else JobPayloadCodec.encodeMap(payload),
        occurredAt = SqlDelightJobStore.formatEpochMs(occurredAtEpochMs),
    )

private fun JobEventLedgerRow.toEventRecord(): JobEventRecord =
    JobEventRecord(
        eventId = eventId,
        attemptNo = attemptNo,
        eventKind = eventKind,
        payload = payloadJson?.let { JobPayloadCodec.decodeMap(it) } ?: emptyMap(),
        occurredAtEpochMs = SqlDelightJobStore.parseEpochMs(occurredAt),
    )

// ---------------------------------------------------------------------------
// JSON codecs (canonical_spec_json / checkpoint_json / event payload)
// ---------------------------------------------------------------------------

private data class JobBody(
    val canonicalSpecDigest: String,
    val parameters: JobParameters,
    val currentAttemptNo: Int?,
    val progress: JobProgress,
    val error: OmniError?,
    val cancelRequested: Boolean,
)

private object JobJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
    }
}

private object JobBodyCodec {
    fun encode(body: JobBody): String =
        JobJson.format.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("canonicalSpecDigest", body.canonicalSpecDigest)
                putNullable("currentAttemptNo", body.currentAttemptNo)
                put("cancelRequested", body.cancelRequested)
                put("parameters", JobParametersCodec.encode(body.parameters))
                put("progress", JobProgressCodec.encode(body.progress))
                put("error", body.error?.let { JobErrorCodec.encode(it) } ?: JsonNull)
            },
        )

    fun decode(json: String): JobBody {
        val root = JobJson.format.parseToJsonElement(json).jsonObject
        val digest = root.getValue("canonicalSpecDigest").jsonPrimitive.content
        val currentAttempt = root.optionalInt("currentAttemptNo")
        val cancelRequested = root.booleanOrFalse("cancelRequested")
        val parameters = JobParametersCodec.decode(root.getValue("parameters").jsonObject)
        val progress = root["progress"]?.jsonObject?.let { JobProgressCodec.decode(it) }
            ?: JobProgress()
        val error = root["error"]?.let { el ->
            if (el is JsonNull) null else JobErrorCodec.decode(el.jsonObject)
        }
        return JobBody(
            canonicalSpecDigest = digest,
            parameters = parameters,
            currentAttemptNo = currentAttempt,
            progress = progress,
            error = error,
            cancelRequested = cancelRequested,
        )
    }
}

private object JobParametersCodec {
    fun encode(parameters: JobParameters): JsonObject = when (parameters) {
        is JobParameters.Download -> buildJsonObject {
            put("type", "DOWNLOAD")
            put("sourceUrl", parameters.sourceUrl)
            putNullable("expectedSha256", parameters.expectedSha256)
            putNullable("expectedBytes", parameters.expectedBytes)
            putNullable("targetName", parameters.targetName)
        }
        is JobParameters.Import -> buildJsonObject {
            put("type", "IMPORT")
            put("assetId", parameters.assetId)
            putNullable("expectedFormat", parameters.expectedFormat)
            putNullable("expectedSha256", parameters.expectedSha256)
        }
        is JobParameters.Benchmark -> buildJsonObject {
            put("type", "BENCHMARK")
            put("modelRevisionId", parameters.modelRevisionId)
            put("engineBuildId", parameters.engineBuildId)
            put("backend", parameters.backend)
            put("measurementProfileId", parameters.measurementProfileId)
            putNullable("iterations", parameters.iterations)
        }
        is JobParameters.Delete -> buildJsonObject {
            put("type", "DELETE")
            put("resourceKind", parameters.resourceKind.name)
            put("resourceId", parameters.resourceId)
            put("expectedResourceVersion", parameters.expectedResourceVersion)
            put("forceAfterDrain", parameters.forceAfterDrain)
        }
        is JobParameters.DiagnosticExport -> buildJsonObject {
            put("type", "DIAGNOSTIC_EXPORT")
            put("includeDetail", parameters.includeDetail)
            putJsonArray("categories") {
                for (c in parameters.categories) add(JsonPrimitive(c))
            }
            putNullable("ttlSeconds", parameters.ttlSeconds)
        }
        is JobParameters.ContentReport -> buildJsonObject {
            put("type", "CONTENT_REPORT")
            put("reportId", parameters.reportId)
        }
    }

    fun decode(obj: JsonObject): JobParameters {
        val type = obj.getValue("type").jsonPrimitive.content
        return when (type) {
            "DOWNLOAD" -> JobParameters.Download(
                sourceUrl = obj.getValue("sourceUrl").jsonPrimitive.content,
                expectedSha256 = obj.optionalString("expectedSha256"),
                expectedBytes = obj.optionalLong("expectedBytes"),
                targetName = obj.optionalString("targetName"),
            )
            "IMPORT" -> JobParameters.Import(
                assetId = obj.getValue("assetId").jsonPrimitive.content,
                expectedFormat = obj.optionalString("expectedFormat"),
                expectedSha256 = obj.optionalString("expectedSha256"),
            )
            "BENCHMARK" -> JobParameters.Benchmark(
                modelRevisionId = obj.getValue("modelRevisionId").jsonPrimitive.content,
                engineBuildId = obj.getValue("engineBuildId").jsonPrimitive.content,
                backend = obj.getValue("backend").jsonPrimitive.content,
                measurementProfileId = obj.getValue("measurementProfileId").jsonPrimitive.content,
                iterations = obj.optionalInt("iterations"),
            )
            "DELETE" -> JobParameters.Delete(
                resourceKind = DeleteResourceKind.requireFromCatalogName(
                    obj.getValue("resourceKind").jsonPrimitive.content,
                ),
                resourceId = obj.getValue("resourceId").jsonPrimitive.content,
                expectedResourceVersion = obj.getValue("expectedResourceVersion").jsonPrimitive.longOrNull
                    ?: error("expectedResourceVersion required"),
                forceAfterDrain = obj["forceAfterDrain"]?.jsonPrimitive?.content?.toBooleanStrict()
                    ?: false,
            )
            "DIAGNOSTIC_EXPORT" -> JobParameters.DiagnosticExport(
                includeDetail = obj["includeDetail"]?.jsonPrimitive?.content?.toBooleanStrict()
                    ?: false,
                categories = obj["categories"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: emptyList(),
                ttlSeconds = obj.optionalInt("ttlSeconds"),
            )
            "CONTENT_REPORT" -> JobParameters.ContentReport(
                reportId = obj.getValue("reportId").jsonPrimitive.content,
            )
            else -> error("Unknown JobParameters type (fail closed): $type")
        }
    }
}

private object JobProgressCodec {
    fun encode(progress: JobProgress): JsonObject = buildJsonObject {
        put("networkBytes", progress.networkBytes)
        put("materializedBytes", progress.materializedBytes)
        put("verifiedBytes", progress.verifiedBytes)
        put("parsedItems", progress.parsedItems)
        put("sampleCount", progress.sampleCount)
        putNullable("currentPhase", progress.currentPhase)
        putNullable("estimatedRemainingMs", progress.estimatedRemainingMs)
        putNullable("totalBytesKnown", progress.totalBytesKnown)
    }

    fun decode(obj: JsonObject): JobProgress =
        JobProgress(
            networkBytes = obj.longOrZero("networkBytes"),
            materializedBytes = obj.longOrZero("materializedBytes"),
            verifiedBytes = obj.longOrZero("verifiedBytes"),
            parsedItems = obj.longOrZero("parsedItems"),
            sampleCount = obj.longOrZero("sampleCount"),
            currentPhase = obj.optionalString("currentPhase"),
            estimatedRemainingMs = obj.optionalLong("estimatedRemainingMs"),
            totalBytesKnown = obj.optionalLong("totalBytesKnown"),
        )
}

private object JobCheckpointCodec {
    fun encode(checkpoint: JobCheckpoint): String =
        JobJson.format.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("attemptNo", checkpoint.attemptNo)
                put("payloadHash", checkpoint.payloadHash)
                put("resumeCursor", checkpoint.resumeCursor)
                put("recordedAtEpochMs", checkpoint.recordedAtEpochMs)
                putJsonObject("durableFields") {
                    for ((k, v) in checkpoint.durableFields) put(k, v)
                }
            },
        )

    fun decode(json: String): JobCheckpoint {
        val obj = JobJson.format.parseToJsonElement(json).jsonObject
        val fields = obj["durableFields"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            ?: emptyMap()
        return JobCheckpoint(
            attemptNo = obj.getValue("attemptNo").jsonPrimitive.intOrNull
                ?: error("attemptNo required"),
            payloadHash = obj.getValue("payloadHash").jsonPrimitive.content,
            resumeCursor = obj.getValue("resumeCursor").jsonPrimitive.content,
            durableFields = fields,
            recordedAtEpochMs = obj.getValue("recordedAtEpochMs").jsonPrimitive.longOrNull
                ?: error("recordedAtEpochMs required"),
        )
    }
}

private object JobErrorCodec {
    fun encode(error: OmniError): JsonObject = buildJsonObject {
        put("code", error.code.code)
        putNullable("message", error.message)
        putJsonObject("details") {
            for ((k, v) in error.details) put(k, v)
        }
    }

    fun decode(obj: JsonObject): OmniError {
        val code = OmniErrorCode.requireFromCode(obj.getValue("code").jsonPrimitive.content)
        val message = obj.optionalString("message")
        val details = obj["details"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
            ?: emptyMap()
        return OmniError.of(code, message, details)
    }
}

private object JobPayloadCodec {
    fun encodeMap(map: Map<String, String>): String =
        JobJson.format.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                for ((k, v) in map) put(k, v)
            },
        )

    fun decodeMap(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val el = JobJson.format.parseToJsonElement(json)
        if (el is JsonNull) return emptyMap()
        return el.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
}

// ---------------------------------------------------------------------------
// JsonObject helpers
// ---------------------------------------------------------------------------

private fun JsonObject.optionalString(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.contentOrNull
}

private fun JsonObject.optionalLong(key: String): Long? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.longOrNull
}

private fun JsonObject.optionalInt(key: String): Int? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return el.jsonPrimitive.intOrNull
}

private fun JsonObject.longOrZero(key: String): Long =
    optionalLong(key) ?: 0L

private fun JsonObject.booleanOrFalse(key: String): Boolean {
    val el = this[key] ?: return false
    if (el is JsonNull) return false
    val prim = el.jsonPrimitive
    return prim.content.toBooleanStrictOrNull()
        ?: (prim.contentOrNull == "true")
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: String?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Long?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: Int?,
) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
