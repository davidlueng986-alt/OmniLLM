package com.omnillm.interfaces

/**
 * Module marker for `:interfaces:http`.
 *
 * HTTP wire contract authority: `specs/openapi/omnillm.openapi.yaml`
 * (packaged at classpath `openapi/omnillm.openapi.yaml`).
 * Transport adapters must not invent paths, scopes, or claim fields.
 */
object HttpModule {
    const val MODULE_PATH: String = ":interfaces:http"

    /** Classpath resource path of the packaged OpenAPI document. */
    const val OPENAPI_CLASSPATH_RESOURCE: String = "openapi/omnillm.openapi.yaml"

    /** Repo-relative authority path (read-mostly; prefer package resource at runtime). */
    const val OPENAPI_SPEC_PATH: String = "specs/openapi/omnillm.openapi.yaml"
}
