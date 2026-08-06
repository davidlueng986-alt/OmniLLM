package com.omnillm.engines.api

/**
 * Module `:engines:api` — Engine Pack SPI (CORE-ENGINE / ENGINE-STANDARD /
 * ENGINE-QUALIFICATION-STATUS).
 *
 * Contracts:
 * - [OmniEngine] — describe / probe / planLoad / commitLoad / queryCommit / bind
 * - [LoadedModelPort] — plan/commit/start inference & embedding, close, unload
 * - [EngineLoadPort] — load subset used by Model Manager (extended by [OmniEngine])
 * - [EngineRegistry] — only QUALIFIED_WITH_ENVELOPE + PASS evidence ⇒ SUPPORTED;
 *   default UNKNOWN
 * - [com.omnillm.engines.api.fake.FakeEngine] — Plan→Reserve→Commit→Execute without native code
 *
 * Adapters must not write DB/model store or redefine Orchestrator semantics.
 */
object ApiModule {
    const val MODULE_PATH: String = ":engines:api"
}
