# OmniLLM Android — Production Gap Inventory

> **⚠ STALE STATUS WARNING (2026-08-09, Stage 4 refresh):** this document is a **historical snapshot** (2026-08-06) of gaps found before closeout. Do **not** use its line numbers or "Current: No/Yes" claims as current truth. Current status lives in `BUILD_STATUS.md`, `PRODUCT_READINESS_CHECKLIST.md`, `SHIP_BACKLOG.md`, `GAP_CLOSEOUT.md`. The B1–B12 blockers below are resolved as mapped in the table after the historical body.

**Date:** 2026-08-06  
**Repo:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android`  
**Scope:** Read-only inventory of production gaps (ledgers, Feature Packs, engine attach, SQLDelight).  
**Rules preserved:** ADR-010 single writer; Plan→Reserve→Commit→Execute (Plan pure); INV-001 UI never loads native / writes DB; unknown capability fail-closed; no fake QUALIFIED/SUPPORTED.

---

## 1) InMemory* ledgers and RuntimeControlPlane store construction

### 1.1 Production host: `RuntimeControlPlane.attach`

**File:** `android/runtime-service/src/main/kotlin/com/omnillm/android/runtimeservice/controlplane/RuntimeControlPlane.kt`

| Store / service | Construction (evidence) | Backing store | Durable? |
|---|---|---|---|
| Claim + command + request registry | L157–160: `RequestRegistryModule.createInMemoryWithCommits(clock)` | `InMemoryClaimLedgerStore` | **No** |
| Commit ledger | Same bundle → `ledgers.commitLedger` | `InMemoryCommitLedgerStore` | **No** |
| Job manager | L161: `JobManagerModule.createManager()` | default `InMemoryJobStore` | **No** |
| Policy manager | L162: `PolicyModule.createManager()` | in-process settings / revocation (no SQLite settings store) | Partial |
| Observability | L163: `ObservabilityModule.createFacade()` | `InMemoryMetricRegistry` + `InMemoryTraceRecorder` | **No** |
| Admin API | L164–169: `AdminModule.createService(commandLedger, jobManager, policyManager, …)` | reuses command/job/policy | inherits above |
| Content report API | L170–175: `ContentReportModule.createApi(jobManager, observability)` | default `InMemoryContentReportStore` | **No** |
| AIDL client registrations | L177: `ClientRegistrationStore()` | process memory | **No** |
| Stream sessions | L178: `StreamSessionRegistry()` | process memory | **No** |
| Asset broker | L179–182: `AssetHandleBroker(commandLedger, quarantineDir)` | FS quarantine + command ledger | quarantine only |

**Class KDoc admits the gap:** L35–45 — claim/commit ledgers “currently in-memory — process-crash durability TODO”; recovery lands **DEGRADED**; “engine wiring remains TODO”.

**Recovery fail-closed (in-memory):** L92–103 — `partial = true` / `recoveryPartial()` because ledgers cannot prove post-crash recovery.

### 1.2 Factory chain for claim/commit/command

**File:** `runtime/request-registry/src/main/kotlin/com/omnillm/runtime/RequestRegistryModule.kt`

| API | Lines | What it builds |
|---|---|---|
| `create(ports, clock)` | L31–35 | `RequestRegistry` + `CommandLedger` on `ClaimLedgerPorts` |
| `createCommitLedger(ports, clock)` | L38–41 | `CommitLedger` on `CommitLedgerPorts` |
| `createInMemory(clock)` | L44–50 | tests: `InMemoryClaimLedgerStore` only |
| `createInMemoryWithCommits(clock)` | L56–68 | **production path today:** claims + commits in-memory |
| `InMemoryLedgers` data class | L73–79 | bundles registry, command, commit, claimStore, commitStore |

Comment L29: “Production binds SQLDelight/Room DAOs; tests use InMemoryClaimLedgerStore.”  
Comment L54: “**Not process-crash durable** — production must open SQLite under ADR-010.”

### 1.3 InMemory ledger implementations (persistence)

| Type | Path | Implements |
|---|---|---|
| `InMemoryClaimLedgerStore` | `data/persistence/src/main/kotlin/com/omnillm/data/persistence/InMemoryClaimLedgerStore.kt` L12–14 | `ClaimLedgerPorts` (requests/attempts/terminals/commands + tx) |
| `InMemoryCommitLedgerStore` | `data/persistence/src/main/kotlin/com/omnillm/data/persistence/InMemoryCommitLedgerStore.kt` L14–16 | `CommitLedgerPorts` (commits/prepared/bindings + tx) |

Both assert `SingleWriterPolicy` on construct and document “before SQLDelight is bound”.

### 1.4 Other InMemory* stores used by control-plane-adjacent modules

| Type | Path | Used by production plane? |
|---|---|---|
| `InMemoryJobStore` | `runtime/job-manager/.../job/JobStore.kt` L39 | **Yes** — `JobManagerModule.createManager()` default (`JobManagerModule.kt` L19) |
| `InMemorySessionManager` | `runtime/session/.../InMemorySessionManager.kt` L24 | **No** — `SessionModule.createInMemoryManager()` exists (`SessionModule.kt` L16) but **not** constructed in `RuntimeControlPlane` |
| `InMemoryMetricRegistry` / `InMemoryTraceRecorder` | `runtime/observability/...` | **Yes** via `ObservabilityModule.createFacade()` L31–33 |
| `InMemorySecretBroker` | `runtime/policy/.../SecretBroker.kt` L224 | **Indirect** — `PolicyModule.createSecurityStack` defaults to it (L52); `LoopbackTokenService` defaults to it (`LoopbackTokenService.kt` L30). Plane currently calls `createManager()` only, not full security stack. |
| `InMemoryContentReportStore` | `features/ai-content-report/...` (imported in `ContentReportModule.kt` L10, default L55) | **Yes** |
| `InMemorySupplyChainHooks` | `runtime/model-manager/.../SupplyChainHooks.kt` L53 | **No** — model-manager not attached to plane |

### 1.5 Not constructed on the plane (gaps)

Evidence that runtime-service **depends** on modules but does not host them on the plane:

| Missing on plane | Module present | Evidence |
|---|---|---|
| Session manager | `:runtime:session` on classpath (`runtime-service/build.gradle.kts` L58–59) | No `SessionModule` call in `RuntimeControlPlane` |
| Model manager | not even a gradle dep of runtime-service | `OmniRuntimeFacade.kt` L361: “empty catalog until model-manager is attached” |
| Orchestrator | `:runtime:orchestrator` on classpath (L57) | `GatewayLifecycle.kt` L64: `orchestrator = null` |
| Engine registry / llama-cpp | only `:engines:api` (L67–68) | No engine pack dependency; execute path fail-closed (see §3) |

---

## 2) Feature Packs: attached vs library-only

### 2.1 All Feature Pack modules (settings)

`settings.gradle.kts` L77–90 includes **12** packs:

`:features:auto-setup`, `modelhub`, `playground`, `server`, `lan`, `dashboard`, `benchmark`, `diagnostics`, `routing`, `tools`, `admin`, `ai-content-report`.

### 2.2 Attached to live control plane (`:android:runtime-service`)

**Gradle deps** (`android/runtime-service/build.gradle.kts`):

| Dependency | Lines | Role |
|---|---|---|
| `:features:ai-content-report` | L69–70 | Hosted API on plane |
| `:interfaces:admin` | L36 | Admin API (not `:features:admin`) |

**Construction in `RuntimeControlPlane.attach`:**

| Surface | Evidence | Notes |
|---|---|---|
| `AdminApiService` | L164–169 `AdminModule.createService(...)` | Platform Admin facade; jobs/commands/policy |
| `ContentReportApi` | L172–175 `ContentReportModule.createApi(...)` | Only Feature Pack domain API on plane |
| AIDL Admin projection | `OmniAdminFacade.kt` L359–365 | Uses `plane.adminApi` + `plane.contentReportApi` |

**Not attached as control-plane hosts:** all other Feature Packs (no `implementation(project(":features:…"))` except ai-content-report).

### 2.3 Consumed by UI process as libraries (INV-001 projection only)

`android/app-ui/build.gradle.kts` L131–140:

| Feature Pack | app-ui dep | Control-plane host |
|---|---|---|
| `:features:admin` | Yes | No (Admin host is `:interfaces:admin`) |
| `:features:modelhub` | Yes | No |
| `:features:playground` | Yes | No |
| `:features:server` | Yes | No |
| `:features:lan` | Yes | No |
| `:features:dashboard` | Yes | No |
| `:features:diagnostics` | Yes | No |
| `:features:auto-setup` | Yes | No |
| `:features:ai-content-report` | Yes (VM) | **Yes** (API on plane) |

UI uses admin **view models** only, e.g. `app-ui/.../UiSession.kt`, `HomeScreen.kt`, `SettingsScreen.kt` — no DB writers (INV-001 preserved at wiring level).

### 2.4 Library-only (neither runtime-service nor app-ui Gradle)

| Feature Pack | settings | app-ui | runtime-service | Status |
|---|---|---|---|---|
| `:features:benchmark` | Yes | **No** | **No** | Domain + tests only |
| `:features:routing` | Yes | **No** | **No** | Domain + tests only |
| `:features:tools` | Yes | **No** | **No** | Domain + tests only |

These are pure library modules with FeatureModule entry points; no process host wires their ports into Admin/HTTP/AIDL.

### 2.5 Summary matrix

| Pack | Module code | Unit tests | app-ui compile | Control-plane attach |
|---|---|---|---|---|
| admin | Yes | Yes | Yes (VM) | Via `:interfaces:admin` only |
| ai-content-report | Yes | Yes | Yes (VM) | **Yes** (`ContentReportModule`) |
| modelhub, playground, server, lan, dashboard, diagnostics, auto-setup | Yes | Yes | Yes | **No** |
| benchmark, routing, tools | Yes | Yes | **No** | **No** |

---

## 3) Engine attach TODOs and StubNativeBackend (llama-cpp)

### 3.1 Control plane / transport — no engine attach

| Location | Evidence |
|---|---|
| `RuntimeControlPlane.kt` L44–45 | “native engine packs attach only after … READY/DEGRADED (**engine wiring remains TODO**)” |
| `runtime-service/build.gradle.kts` L67–68 | `implementation(project(":engines:api"))` only — **no** `:engines:llama-cpp` |
| `OmniRuntimeFacade.kt` L79–90 | chat: claim accepted then terminal fail-closed — `"engine execute path not yet attached"` |
| `OmniRuntimeFacade.kt` L117–128 | embed: same message |
| `GatewayLifecycle.kt` L64 | `orchestrator = null` — HTTP inference cannot Plan→Execute |
| `ControlPlaneHttpHandler.kt` L85 / L159 | orchestrator not attached ⇒ CAPABILITY_UNSUPPORTED |

### 3.2 llama-cpp StubNativeBackend locations

| Artifact | Path | Lines / note |
|---|---|---|
| Interface + **TODO(JNI)** | `engines/llama-cpp/src/main/kotlin/com/omnillm/engines/llamacpp/native/NativeBackend.kt` | L11–17 documents `JniNativeBackend` requirements |
| **Stub implementation** | `engines/llama-cpp/src/main/kotlin/com/omnillm/engines/llamacpp/native/StubNativeBackend.kt` | L6–14 KDoc: not production; L12–13 TODO(JNI); L15 class; L30 `libraryLabel() = "stub-llama-cpp"` |
| Default factory uses stub | `engines/llama-cpp/.../LlamaCppModule.kt` | L35–40: `backend: NativeBackend = StubNativeBackend()` |
| Pipeline tests (stub only) | `engines/llama-cpp/src/test/.../LlamaCppEnginePipelineTest.kt` | Plan→Reserve→Commit→Execute on stub |
| Native packaging module | `android/native/` | ABI helpers only (`AbiPackaging.kt`) — **no** `libomnillm_llama` / JNI sources |

### 3.3 Peer engine stubs (context; first production path is llama-cpp)

| Engine | Stub / TODO path |
|---|---|
| litert-lm | `engines/litert-lm/.../sdk/StubSdkBackend.kt` TODO(SDK) |
| mlc-llm | `engines/mlc-llm/.../runtime/StubRuntimeBackend.kt` TODO(runtime) |
| mllm | `engines/mllm/.../server/ServerBackend.kt` TODO(AAR) |
| ort-genai | fail-closed execute (see `BUILD_STATUS.md`) |

**Hard rule:** do not mark any engine QUALIFIED/SUPPORTED without real device evidence. Stubs must not elevate registry cells.

---

## 4) SQLDelight `.sq` files and DAOs already available

### 4.1 Module config

`data/persistence/build.gradle.kts`:

- Plugin: SQLDelight L3; database `OmniLlmDatabase` L24–28  
- Package: `com.omnillm.data.persistence`  
- Dialect: sqlite-3-38  
- Deps: `sqldelight.runtime`, `sqldelight.coroutines`; **test** sqlite driver only (L42) — no Android driver wired in this JVM module for production open.

### 4.2 `.sq` sources (10 files)

Directory: `data/persistence/src/main/sqldelight/com/omnillm/data/persistence/`

| File | Tables / purpose | Queries present |
|---|---|---|
| `InferenceRequests.sq` | `inference_requests` claim key | `selectByRequestId`, `selectByClaimKey`, `insertRequest`, `updateState` |
| `RequestAttempts.sq` | `request_attempts` | `selectByRequestId`, `selectMaxAttemptNo`, `insertAttempt`, `endAttempt` |
| `RequestTerminals.sq` | `request_terminals` (exactly one) | `selectByRequestId`, `insertTerminal` |
| `IdempotentCommands.sq` | `idempotent_commands` | `selectByCommandId`, `selectByClaimKey`, `insertCommand`, `updateResult` |
| `CommitRecords.sq` | `commit_records` | `selectByCommitId`, `selectByNonceDigest`, `insertCommit`, `updateState`, `listOpen` |
| `PreparedOperations.sq` | `prepared_operations` | select/insert/update (start-claim durability) |
| `CommitResourceBindings.sq` | `commit_resource_bindings` | `listByCommitId`, `upsertBinding`, `updateDisposition` |
| `SchemaMetadata.sq` | `schema_metadata` | `selectMetadata`, `upsertMetadata` |
| `SchemaMigrationAttempts.sq` | migration attempts | (migration FSM) |
| `SchemaMigrationHistory.sq` | migration history | (migration audit) |

Authority SQL also packaged: `data/persistence/src/main/resources/db/omnillm-schema.sql` (+ migration `0001_to_0002.sql`, `migration-policy.yaml`).  
`SchemaAuthority.kt` L9–11: `.sq` is a **subset** projection of authority SQL.

### 4.3 Hand-written DAO **ports** (interfaces — ready for binding)

| File | Types |
|---|---|
| `ClaimLedgerDao.kt` | `InferenceRequestDao`, `RequestAttemptDao`, `RequestTerminalDao`, `IdempotentCommandDao`, `ClaimLedgerTransaction`, **`ClaimLedgerPorts`** (L92–98) |
| `CommitLedgerDao.kt` | `CommitRecordDao`, `PreparedOperationDao`, `CommitResourceBindingDao`, **`CommitLedgerPorts`** (L74–79) |
| Models | `ClaimLedgerModels.kt`, `CommitLedgerModels.kt` |
| Policy | `SingleWriterPolicy.kt`, `SchemaMetadataModels.kt`, `SchemaAuthority.kt` |
| Module marker | `PersistenceModule.kt` — no DB open factory |

### 4.4 Generated SQLDelight queries (build output)

After compile, generated under:

`data/persistence/build/generated/sqldelight/code/OmniLlmDatabase/main/com/omnillm/data/persistence/`

Includes: `OmniLlmDatabase.kt`, `InferenceRequestsQueries.kt`, `IdempotentCommandsQueries.kt`, `CommitRecordsQueries.kt`, `PreparedOperationsQueries.kt`, `CommitResourceBindingsQueries.kt`, `RequestAttemptsQueries.kt`, `RequestTerminalsQueries.kt`, schema migration query types, `OmniLlmDatabaseImpl.kt`.

### 4.5 Critical gap: no SQLDelight adapter implementing ports

**Search result:** only `InMemoryClaimLedgerStore` / `InMemoryCommitLedgerStore` implement `ClaimLedgerPorts` / `CommitLedgerPorts` under `data/persistence/src/main`.

Missing production pieces:

1. `SqlDelightClaimLedgerStore` / `SqlDelightCommitLedgerStore` adapting generated queries → ports  
2. Control-plane-only `SqlDriver` open (Android SQLite / SQLCipher if required) under ADR-010  
3. `RequestRegistryModule.create(ports)` wired from `RuntimeControlPlane` instead of `createInMemoryWithCommits`  
4. Job durable store (no jobs `.sq` yet — only claim/commit/schema subset)  
5. Content-report durable store (comment L170–171: “until SQLCipher tables land”)

---

## 5) Fix order (recommended production path)

Order respects ADR-010 (single writer), INV-001, fail-closed unknown capability, and “no fake qualification.”

### Phase A — Durable control-plane ledgers (unblocks READY recovery)

1. **Bind SQLDelight ports**  
   - Implement `ClaimLedgerPorts` + `CommitLedgerPorts` over `OmniLlmDatabase` generated queries.  
   - Paths to add under: `data/persistence/src/main/kotlin/com/omnillm/data/persistence/`  
   - Conformance: extend/reuse `ClaimOrReturnConformanceTest.kt`.

2. **Open DB only in `:runtime` process**  
   - Driver + schema apply / migration from packaged `db/` + `.sq` version.  
   - Wire in `RuntimeControlPlane.attach` (replace L157 `createInMemoryWithCommits`).  
   - Keep UI process free of writers (INV-001).

3. **Recovery path**  
   - After durable commits: use `commitLedger.listOpenCommits()` for real reconcile; set `partial=false` only when recovery fixtures pass (`specs/runtime-recovery-fixtures.yaml`).  
   - Until then remain DEGRADED (current fail-closed L92–103 is correct).

4. **Durable jobs** (secondary ledger)  
   - Add jobs tables to authority + `.sq` or Room subset; replace `InMemoryJobStore` default in plane.

### Phase B — Orchestrator + session + model-manager on plane

5. **Attach `SessionModule` singleton** on plane (session delivery INV-006/007).  
6. **Attach Orchestrator** factory; pass into `GatewayLifecycle` / `ControlPlaneHttpHandler` (today `orchestrator = null`).  
7. **Attach ModelManager** (install/load/catalog) so AIDL catalog is not empty (`OmniRuntimeFacade` L361).

### Phase C — Engine pack attach (llama-cpp first, still UNQUALIFIED)

8. **Register** `:engines:llama-cpp` from plane after READY/DEGRADED only (not UI).  
9. Keep **StubNativeBackend** for host tests; add **JniNativeBackend** + `libomnillm_llama` under `:android:native` when NDK packaging exists (TODO at `NativeBackend.kt` L11–17, `StubNativeBackend.kt` L12–13).  
10. Wire Plan→Reserve→Commit→Execute from binder/HTTP claim path — remove fail-closed “engine execute path not yet attached” only when orchestrator+engine are real.  
11. **Do not** mark SUPPORTED/QUALIFIED without device evidence (`specs/engine-qualification-status.yaml`).

### Phase D — Feature Pack host wiring

12. **Host remaining packs** via control-plane ports (Admin/HTTP/AIDL parity), not by opening DB from packs:  
    - High product surface already on app-ui: modelhub, playground, server, lan, dashboard, diagnostics, auto-setup.  
    - Library-only next: **benchmark**, **routing**, **tools**.  
13. Durable content-report store (replace `InMemoryContentReportStore` default at `ContentReportModule.kt` L55).  
14. Align `:features:admin` host ports with `:interfaces:admin` so domain + transport share one writer path.

### Phase E — Out of scope (explicit)

- Play Console upload automation  
- Inventing fake qualification PASS evidence  
- Marking engines QUALIFIED/SUPPORTED without real device runs  

---

## 6) Remaining blockers (path-indexed)

| ID | Blocker | Evidence paths |
|---|---|---|
| B1 | Production claim/commit/command stores are in-memory | `RuntimeControlPlane.kt` L38–39, L157; `RequestRegistryModule.kt` L56–68; `InMemoryClaimLedgerStore.kt`; `InMemoryCommitLedgerStore.kt` |
| B2 | No SQLDelight → ports adapter | DAO interfaces exist (`ClaimLedgerDao.kt`, `CommitLedgerDao.kt`); only InMemory implements; generated queries unused by src/main |
| B3 | Recovery forced DEGRADED | `RuntimeControlPlane.kt` L92–103 |
| B4 | Jobs not durable | `JobManagerModule.kt` L19; `JobStore.kt` L39 `InMemoryJobStore` |
| B5 | Session not on plane | `SessionModule.kt` L16 unused by plane |
| B6 | Orchestrator not on plane / HTTP | `GatewayLifecycle.kt` L64; `ControlPlaneHttpHandler.kt` L159 |
| B7 | Model manager not attached | `OmniRuntimeFacade.kt` L361; no model-manager dep on runtime-service |
| B8 | Engine packs not attached | `RuntimeControlPlane.kt` L44–45; no `:engines:llama-cpp` in runtime-service; `OmniRuntimeFacade.kt` L85, L122 |
| B9 | llama-cpp is StubNativeBackend only | `StubNativeBackend.kt`; `LlamaCppModule.kt` L40; no JNI in `android/native` |
| B10 | Feature Packs mostly library-level | runtime-service only `ai-content-report`; app-ui missing benchmark/routing/tools; plane hosts only Admin + ContentReport |
| B11 | Content report store in-memory | `ContentReportModule.kt` L55; `RuntimeControlPlane.kt` L170–171 |
| B12 | Secrets / tokens default in-memory broker | `PolicyModule.kt` L52; `LoopbackTokenService.kt` L30 |

---

## 7) Files changed by this inventory task

| File | Action |
|---|---|
| `GAP_INVENTORY.md` (repo root) | **Created** (this document) |

No production source code was modified.

---

## 8) Blockers B1–B12 — resolution mapping (Stage 4, 2026-08-09)

Historical blockers from §6, mapped to current state:

| ID | Historical blocker | Current status | Evidence / commit |
|---|---|---|---|
| B1 | Production claim/commit/command stores in-memory | **CLOSED** | `RequestRegistryModule.createWithCommits(claims, commits)` + `AndroidSqliteDriver`/`ControlPlaneDatabase` in `RuntimeControlPlane.attach` (baseline `dbf6f33`/`4a5bebe`) |
| B2 | No SQLDelight → ports adapter | **CLOSED** | `SqlDelightClaimLedgerStore`/`SqlDelightCommitLedgerStore`/`SqlDelightSessionStore`/`SqlDelightJobLedgerStore`/`SqlDelightContentReportStore`/`SqlDelightToolProposalStore`/`SqlDelightInstallationStore`/`SqlDelightRevisionLeaseStore`/`SqlDelightSecretLedgerStore`/`SqlDelightCatalogTrustStore` |
| B3 | Recovery forced DEGRADED | **CLOSED** | `controlPlaneDb.reconcileUnfinishedCommits()` → READY when durable complete; DEGRADED only on reconcile failure; COR-19 restart fence |
| B4 | Jobs not durable | **CLOSED** | `JobManagerModule.createDurableManager(ports=controlDb.jobs)` + `jobs.reconcileAfterRestart()` |
| B5 | Session not on plane | **CLOSED** | `SessionModule.createDurableManager(ports=controlDb.sessions)` |
| B6 | Orchestrator not on plane / HTTP | **CLOSED** | `GatewayLifecycle` receives plane orchestrator; SSE chat live (COR-03); engine port via `EngineExecuteBinding` |
| B7 | Model manager not attached | **CLOSED** | `ModelManagerModule.createDurableControlPlane` (installations + revision leases + FS model-store) |
| B8 | Engine packs not attached | **CLOSED** | `ensureEnginePacksAttached()` after READY/DEGRADED; `EnginePackAttachment.attachAfterReady`; all 5 real backends |
| B9 | llama-cpp StubNativeBackend only | **CLOSED** | vendored b9999 (`105856a`), `libomnillm_llama.so` upstream-linked, real GGUF on emulator (`RealLlamaUpstreamInstrumentedTest` PASS); `StubNativeBackend` test-only |
| B10 | Feature Packs mostly library-level | **CLOSED** | 12/12 constructed on plane (`GAP_CLOSEOUT.md` C); benchmark/routing/tools hosts wired |
| B11 | Content report store in-memory | **CLOSED** | `ContentReports.sq` + `SqlDelightContentReportStore` + `ContentReportModule.createDurableApi` |
| B12 | Secrets/tokens default in-memory broker | **CLOSED** | `ControlPlaneSecurityFactory.createSecurityStack` (Keystore vault + SQLite verifiers) |

**Still open (not historical blockers):** device qualification evidence for all engines (Q1), Tools UI destination (SW-UI-03), modelhub display/link ports (SW-DUR-09), observability in-memory, `applySchema=false` bootstrap documentation (SW-DUR-08) — see `SHIP_BACKLOG.md` / `PRODUCT_READINESS_CHECKLIST.md`.
