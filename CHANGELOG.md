# OmniLLM Android — Changelog

All notable changes to this monorepo. Format inspired by Keep a Changelog;
findings referenced by their launch-readiness IDs (BLD-*, COR-*, API-*, SEC-*,
TST-*, FTR-*). Honest statuses only — no invented device evidence.

## [Unreleased / 0.2.0-rc2] — GA-hardening wave (C-01..C-15 + D fixes)

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

## [Unreleased / 0.2.0-rc2] — GA-hardening wave (C-01..C-15 + D fixes)

GA-hardening campaign (2026-08-15, branch `fix/ga-*` waves A–C merged at `231a994`). Fixes follow the D/C/R fix ledger (`FIX_LEDGER`); no device evidence invented — all engines remain UNQUALIFIED.

### Capability fixes (C-01..C-15)

- **C-01** `20242f5` — durable CommitLedger injected into Orchestrator production wiring.
- **C-02** `2fde031`/`4cee252` — control plane surfaces engine token deltas as visible assistant text (sync + SSE `delta.content`).
- **C-03** `cd4c10d`/`0d37094` — GGUF header validation + dry-load on import (non-GGUF labeled gguf rejected fail-closed). `MODEL_FORMAT_INVALID` catalog entry added (specs); pipeline swap from `INVALID_REQUEST` is a follow-up (G1).
- **C-04** `fc0b5a5` — honest capability projection for dashboard (no blanket SUPPORTED).
- **C-05** `08a74df` — real admin model projections on binder path.
- **C-06** `8988303` — diagnostics/routing/content-report ViewModels attached in production UiSession.
- **C-07** `a4b582b`/`1bd00fd`/`ea1455e` — LiteRT-LM + ORT-GenAI backends attached LIVE on control plane when policy + SDK/API present (mlc/mllm stay metadata-only); `MultiEngineInferenceRouter` routes by engineBuildId; attachability ≠ qualification (cells stay UNQUALIFIED/UNKNOWN).
- **C-08a/b/c** `9e3ccf6`/`e661a22`/`e8b84ae`/`cfcbdd1` — request restart-fence into finishRecovery (D5); durable ClientRegistration store (`ClientRegistrations.sq` + `client_registration_epoch` singleton, INV-017 fence survives restart); durable asset metadata (hybrid: metadata in SQLDelight, content bytes on quarantine disk, TTL enforced at access).
- **C-11** `020d9e1`/`f1109d8` — principal-scoped rate + concurrency admission (`PrincipalRateLimiter`, denial = RATE_LIMITED 429 retryable); config keys `security.principalRpsLimit` (default 60, 0=disabled) / `security.principalConcurrentRequests` (default 8, 0=disabled).

### D fixes

- **D2** `1a20092`/`756f5e1`/`3bff161`/`0d7c4f4`/`38efc4c` — stripped-packaged llama.cpp artifactDigest gate (`verify_llama_digest.py` in root check); byte-reproducible `.so` (`-ffile-prefix-map`); per-ABI artifactDigest map.
- **D3** `6b1af0c` — mllm post-start identity probe + fail-closed port-squat detection (SERVER_CRASH / SERVER_IMPERSONATED).
- **D6** `d493260` — `ProductModePolicy` wired into production projection + RiskAck gate (modes default OFF; research widens diagnostics only; risky requires one-use RiskAck bound to settings resourceVersion; re-ack after restart; in-memory ledger — durable adapter G2).
- **D7** `1dddf64` — unified resourceVersion source (durable installation record; delete CAS converges after load/restart).
- **D8** `630ef6c` — durable path models + fail-closes response_format/tools/tool_choice.
- **D18** `91b8cee` — admin result JSON built with kotlinx.serialization.
- **D19** `87f4012` — bounded cancel-token bookkeeping (litert/ort/mllm).
- **D21** `99a32af` — provenance policy: tag == HEAD == APK embedded revision must align pre-GA.
- **D22** `3f2ed23` — `specs/README.md`: drop machine-specific absolute Windows path.
- **D23a–g** `b0d2ea7`/`7f12acf`/`69bbc85`/`9bc4450`/`371f2b0`/`3099993`/`91b8cee`/`a02bc30` — CommandResult.error carries ledger FAILED code; TokenIssueResult no longer emits non-spec `loopback_only`; cursor pagination for listTokens/listClients/listOwnJobs; token issuance rejects empty scopes + out-of-range TTL; engine-silent responses OMIT `omnillm` instead of partial object; admin playground chat digest hashes full content; DiagnosticExportRequest categories modeled.

### Interfaces / wire

- `371f2b0` — `OmniExecutionInfo` three fields (actual_model_revision_id / engine_build_id / backend) + enclosing `omnillm` object now conditional (D23e; OpenAPI mirrored).

### Specs / docs

- Specs synced (docs-mirror wave): `omnillm-schema.sql` (assets/client_registrations IMPLEMENTED + epoch singleton), `error-catalog.yaml` (+`MODEL_FORMAT_INVALID`), `configuration-catalog.yaml` (+C-11 keys), `openapi` (D23e conditional + SSE notes), `engine-qualification-status.yaml` (C-07 `controlPlaneAttach`, D3/D4 mllm), `state-machines.yaml` (ASSET C-08c note), authority-registry/design-index `FEATURE-SYSTEM` → `FEAT-SYSTEM` (canonical; docs package had zero leftovers).
- Status docs refreshed: BUILD_STATUS (module tree real/attached/metadata-only, 0.2.0/2), PRODUCT_READINESS_CHECKLIST (SW-BUILD-06, SW-DUR-08 counts), CHANGELOG (this section), SHIP_BACKLOG (P4 done + G1–G6), FEATURE_AUDIT (C-07/D3 engine rows), product-modes.md §5/§6 (D6), llama-cpp.md §1/§10 (stripped-packaged digests), threat-model.md (mllm impersonation residual).

---

## [0.1.0] — current (2026-08-06 baseline + packaging)

- `4a5bebe` baseline capture; `642536f`/`dbf6f33` e2e GGUF import path + ModelHub import fixes.
- Packaging pass: version line 0.1.0 / versionCode 1, ProGuard JNI/AIDL keeps, CI hardening, RELEASE_CHECKLIST, detekt skip documented (see GAP_CLOSEOUT §E / BUILD_STATUS).
