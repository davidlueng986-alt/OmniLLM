package com.omnillm.interfaces.http

import com.omnillm.interfaces.HttpModule
import java.io.InputStream

/**
 * Resolves the OpenAPI document that is the HTTP wire source of truth (CORE-INTERFACE).
 *
 * Implementation code must derive routes, request envelopes, and claim fields from this
 * document (or codegen from it). Do not invent operationIds or path templates.
 */
object OpenApiAuthority {
    const val SPEC_REPO_PATH: String = HttpModule.OPENAPI_SPEC_PATH
    const val CLASSPATH_RESOURCE: String = HttpModule.OPENAPI_CLASSPATH_RESOURCE
    const val OPENAPI_VERSION_MARKER: String = "1.1.0-design"
    const val TITLE: String = "OmniLLM Local Edge API"

    /**
     * Opens the packaged OpenAPI YAML from the module classpath.
     * Returns null only if packaging is broken (fail closed at gateway boot).
     */
    fun openPackagedDocument(): InputStream? =
        OpenApiAuthority::class.java.classLoader.getResourceAsStream(CLASSPATH_RESOURCE)

    fun requirePackagedDocument(): InputStream =
        openPackagedDocument()
            ?: error("OpenAPI authority missing from classpath: $CLASSPATH_RESOURCE")
}
