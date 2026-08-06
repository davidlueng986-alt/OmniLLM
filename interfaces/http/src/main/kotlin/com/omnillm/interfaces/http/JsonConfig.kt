package com.omnillm.interfaces.http

import kotlinx.serialization.json.Json

/** Shared JSON codec for HTTP wire (OpenAPI snake_case DTOs). */
object HttpJson {
    val codec: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
        prettyPrint = false
    }
}
