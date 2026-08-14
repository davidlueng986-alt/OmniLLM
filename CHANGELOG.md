# OmniLLM Android — Changelog

All notable changes to this monorepo. Format inspired by Keep a Changelog;
findings referenced by their launch-readiness IDs (BLD-*, COR-*, API-*, SEC-*,
TST-*, FTR-*). Honest statuses only — no invented device evidence.

## [Unreleased / 0.2.0] — next (version bump decided by Stage 6)

Launch-readiness fix wave (Stage 4 of 7, branch `fix/launch-readiness`).

### Build / release

- **BLD-01** `105856a` — llama.cpp vendored as tracked files (real gitlink removed); `third_party/llama.cpp` pinned b9999/47c7869.
- **BLD-02** `47a6513` — `ProductBuildMode` no longer a global `const`; now **variant-scoped** (`BuildConfig.OMNILLM_DEV_SHIP_MODE` per buildType, manual DI), release fail-closed, auditable `-Pomnillm.developmentShipMode` override.
- **BLD-03** `ac695ac` — root Apache-2.0 LICENSE + NOTICE + THIRD_PARTY_NOTICES.md.
- **BLD-05** `176441b` — CI runs `verifyNativeLibsPresent` + emulator connected tests; licenses fail closed.
- **BLD-06** `6cfe534` — Gradle 9.5.0 distro SHA-256 pin; PyYAML==6.0.3 pin.
- **BLD-07** `5078b8b` — release workflow fail-fast signing + `apksigner verify` + SBOM.
- **BLD-08** `99d82c0` — removed committed e2e junk artifacts; `.gitignore` extended.
- **BLD-13** `b68e90a` — `verifyNativeLibsPresent` wired into root `check`; ELF 16 KB check fails closed when no `.so`.
- **D21 (provenance policy)** — RELEASE_CHECKLIST §0 records: `0.2.0-rc1` APK 內嵌 revision `9d1dc13` (R16 recapture 之 commit); tag `v0.2.0-rc1` = `6f14f49`; GA 前必須 **tag == HEAD == APK 內嵌 revision 三點合一**,且由被 tag 的 commit 建置+重驗. Tag itself not moved.

### Engines (real, but all UNQUALIFIED — device evidence is Stage 5)

- `27115ff` — real LiteRT-LM integration (typed official SDK bridge replaces reflection stub); UPSTREAM.lock LOCKED v0.15.0; `INTEGRATED_PENDING_QUALIFICATION`.
- `2da721d` — real ONNX Runtime GenAI integration (`RealGenAiBackend` over genai AAR); UPSTREAM.lock LOCKED 0.14.0; `INTEGRATED`.
- `080dae0` — real MLC-LLM integration (MlcEngineRuntimeBackend + generated mlc4j runtime binding); lock NOT_LOCKED w/ pin; `INTEGRATED` (fail-closed until complete lock).
- `4f98347` — real mllm integration (MllmServerBackend over gomllm server + OpenAI-compatible HTTP/SSE on loopback); UPSTREAM.lock LOCKED 2.0.0; `INTEGRATED_PENDING_QUALIFICATION`.
- llama.cpp (b9999 vendored, LOCKED) — real GGUF path: model import → READY → `LlamaCppInferenceEngineAdapter` in-process GGUF generate; `RealLlamaUpstreamInstrumentedTest` PASS on emulator (gemma-3-270m-Q8_0, 12 completion tokens, `upstreamLinked=true`). Still UNQUALIFIED.
- `b64bb52` — litert-lm registers capability cells when native absent (INV-018) instead of fabricating.
- **FTR-04** `17a9745` — engine-qualification-status.yaml lock states synced to UPSTREAM.lock reality (llama.cpp/litert/ort/mllm LOCKED; mlc NOT_LOCKED w/ pin); schema formalizes `integrationStatus` + `evidenceNotes`.

### Interfaces (API wire-shape conformance)

- **API-01/02/03/04/05/06/13** `75cac80` — wire-shape conformance: `AsyncInferenceRequest` oneOf, token `client_id`/`state`/`epoch` fields, `CommandResult` settings, DELETE 204 + ledger, multipart upload + sha256, loopback-only transport gate.
- **API-16** `fdb4f91` — codec-built JSON (no `jsonEscape`), pinned status-code assertions.
- **API-20** `cfe003f` — `specs/aidl/omnillm-aidl.yaml` resynced to implementation truth: `IOmniAdmin.importLocalFile` (PFD) + `OmniImportJobParameters` (10 fields incl. `contentFd`); `extract_aidl.py` gained `--check` (CI drift gate) + `--out`; verified byte-identical regeneration (44 declarations).
- **API-21** `cfe003f`/`1c66aff` — repo specs canonical: aidl yaml + database schema updated; docs-package mirror sync is the docs-package agent's task (FTR-05: repo `configuration-catalog.yaml` keeps `runtime.exploratoryExecuteEnabled`, docs copy lacked it).

### Correctness

- **COR-01** `0d7dd8c` — refcounted sessions/models so close/unload cannot free memory in use.
- **COR-02/06/07/08/18** `47238cb` — modelhub binder/lifecycle/security hardening.
- **COR-03** `d0f1734` — SSE chat stream never throws inside the flow (honest terminal events; authorize principal before streaming).
- **COR-11** `b43aea2` — atomic enqueue+snapshot insert; backfill-and-retry on missing planning snapshot.
- **COR-12** `a2e4b99` — cancel phase ladder never fabricates CANCELLED on orchestrator reject (honest STATE_CONFLICT).
- **COR-13/15/20/21** `4436940` — binder facade hardening + real Wave-A wiring.
- **COR-16** `9cad23b` — bound adapter bookkeeping; refcount shared load leases.
- **COR-19** `2fffbda` — fence non-terminal requests to RECONCILING on restart; evict terminal aggregates.
- **COR-22/COR-23g** `2675f9d` — atomic content-report proposal claim; **COR-04/05/09/13/17/22** `fdb4f91` — ownership-before-cancel, real aggregated chat text, full-content digests, asset TTL+bound, honest CAPABILITY_UNSUPPORTED, durable pump.

### Security

- **SEC-01** `1a29f41` — `BIND_RUNTIME` protectionLevel normal → signature.
- **SEC-02/05** `4436940`/`47238cb` — binder/security hardening.
- **SEC-07** `1a2561d` — companion sandbox ticket authenticated with HMAC-SHA-256 pairing key.
- **SEC-08** `7127d61` — 1 h bootstrap admin TTL, single-peek plaintext display, receipt erase.
- **SEC-10** `fdb4f91` — asset TTL + byte bound.

### Database / contracts (API-40..44)

- **API-40..44** `1c66aff` — `specs/database/omnillm-schema.sql` realigned to the SQLDelight implementation: +4 implemented tables (secret_broker_keys, revocation_subjects, tool_proposals, tool_result_claims); access_tokens/pairing_challenges epoch_ms fields; sessions/installations/content_reports/revision_leases missing columns added; catalog_trust_state + job_events aligned; **26 tables marked IMPLEMENTED, 25 PLANNED (not yet implemented)**; all 26 `.sq` column-identical to the schema; packaged copy synced byte-identical.

### Tests

- **TST-01** `fdb4f91` — pinned status-code assertions.
- **TST-03** `c112273` — RequestLifecycle REQUEST-FSM driver suite (21 tests).
- **TST-05** `ac3c5d5` — concurrency race suites for governor, request-registry, session (10 tests).
- Stage 4 verification: `checkContractDrift`, `checkModuleDependencyRules`, `:data:persistence:compileKotlin`/`:core:state`/`:core:contracts` GREEN; python yaml sanity on specs.

### Docs / status

- **FTR-06** — readiness docs refreshed to code reality (BUILD_STATUS, FEATURE_AUDIT, PRODUCT_READINESS_CHECKLIST, SHIP_BACKLOG, GAP_INVENTORY/CLOSEOUT, engine-registry-attachment); BLD-02 variant-scoped language replaces old "const = true" claims; engines documented real-but-UNQUALIFIED; UI destinations (LAN/Routing/Benchmark) verified; SW-UI-03 Tools left OPEN.

---

## [0.1.0] — current (2026-08-06 baseline + packaging)

- `4a5bebe` baseline capture; `642536f`/`dbf6f33` e2e GGUF import path + ModelHub import fixes.
- Packaging pass: version line 0.1.0 / versionCode 1, ProGuard JNI/AIDL keeps, CI hardening, RELEASE_CHECKLIST, detekt skip documented (see GAP_CLOSEOUT §E / BUILD_STATUS).
