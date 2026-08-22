package com.omnillm.android.runtimeservice.binder

import com.omnillm.core.errors.generated.OmniError
import com.omnillm.features.playground.api.RequestStripUi
import com.omnillm.features.playground.projection.CancelPhase
import com.omnillm.features.server.api.SmokeTestResult
import com.omnillm.interfaces.http.HttpJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * D18: admin result JSON built with kotlinx.serialization through the shared
 * [HttpJson] codec (mirrors the control-plane handler's pattern).
 *
 * The old facade concatenated raw strings with hand-rolled `replace("\\",
 * "\\\\")` escaping — quotes/backslashes/control characters in assistant
 * text or error messages broke the payload or leaked unescaped bytes. Here
 * every string becomes a [JsonPrimitive] (complete escaping by
 * construction) and messages are never mutated.
 */
object AdminResultJson {

    /** `PlaygroundChatResult` canonical JSON for a request strip. */
    fun playgroundStrip(strip: RequestStripUi): String = HttpJson.codec.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            buildMap {
                put("requestId", JsonPrimitive(strip.requestId))
                put("operationKind", JsonPrimitive(strip.operationKind))
                put("state", JsonPrimitive(strip.state))
                put("isTerminal", JsonPrimitive(strip.isTerminal))
                put("engineBuildId", strip.engineBuildId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("backend", strip.backend?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "actualModelRevisionId",
                    strip.actualModelRevisionId?.let { JsonPrimitive(it) } ?: JsonNull,
                )
                put("assistantText", strip.assistantText?.let { JsonPrimitive(it) } ?: JsonNull)
                put("degraded", JsonPrimitive(strip.degraded))
                put("errorCode", strip.error?.code?.code?.let { JsonPrimitive(it) } ?: JsonNull)
                put("errorMessage", strip.error?.message?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                    "cancelPhase",
                    strip.cancelPhase?.name?.let { JsonPrimitive(it) } ?: JsonNull,
                )
            },
        ),
    )

    /** `ServerSmokeResult` canonical JSON (requestId falls back to the caller id). */
    fun serverSmoke(result: SmokeTestResult, fallbackRequestId: String): String =
        HttpJson.codec.encodeToString(
            JsonElement.serializer(),
            JsonObject(
                buildMap {
                    put("step", JsonPrimitive(result.step))
                    put("success", JsonPrimitive(result.success))
                    put("requestId", JsonPrimitive(result.requestId ?: fallbackRequestId))
                    put("requestState", JsonPrimitive(result.requestState ?: ""))
                    put("errorCode", result.error?.code?.code?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("errorMessage", result.error?.message?.let { JsonPrimitive(it) } ?: JsonNull)
                    put(
                        "engineBuildId",
                        result.actualEngineBuildId?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                    put("backend", result.actualBackend?.let { JsonPrimitive(it) } ?: JsonNull)
                    put(
                        "modelRevisionId",
                        result.actualModelRevisionId?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                },
            ),
        )

    /** `ModelLoadResult` canonical JSON for load/unload. */
    fun modelLoad(result: com.omnillm.features.modelhub.api.ModelLoadResult): String =
        HttpJson.codec.encodeToString(
            JsonElement.serializer(),
            JsonObject(
                buildMap {
                    put(
                        "loadedModelId",
                        result.loadedModelId?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                    put("installationId", JsonPrimitive(result.installationId))
                    put("state", JsonPrimitive(result.state))
                    put(
                        "engineBuildId",
                        result.engineBuildId?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                    put(
                        "placementClass",
                        result.placementClass?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                },
            ),
        )

    /** `PlaygroundCancelResult` canonical JSON. */
    fun playgroundCancel(
        requestId: String,
        phase: CancelPhase,
        requestState: String?,
        isTerminal: Boolean,
    ): String = HttpJson.codec.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            buildMap {
                put("requestId", JsonPrimitive(requestId))
                put("phase", JsonPrimitive(phase.name))
                put("requestState", JsonPrimitive(requestState ?: ""))
                put("isTerminal", JsonPrimitive(isTerminal))
            },
        ),
    )

    /** Convenience for callers that already hold a [JsonObject]. */
    fun errorCodeOf(error: OmniError?): JsonElement =
        error?.code?.code?.let { JsonPrimitive(it) } ?: JsonNull
}
