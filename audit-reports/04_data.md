# 04_data — DOMAIN / DATA / PERSISTENCE Audit

| Field | Value |
|-------|--------|
| **Artifact** | `04_data.md` |
| **Domain** | Domain model, ownership, durable persistence, schema authority, FSM alignment |
| **Audit date (UTC host)** | 2026-08-12 |
| **Docs package (authority)** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo (under audit)** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Out dir** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports` |
| **Method** | `list_dir` / `read_file` / `grep` / Python path+hash comparison — fail-closed |
| **Spec precedence** | Docs package `specs/` > prose when IDs conflict; monorepo divergence called out explicitly |

**Status labels used:** `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN`

**Completeness levels:**

| Level | Meaning |
|-------|---------|
| **L1** | Module / types / tables exist on disk |
| **L2** | Wired into production `RuntimeControlPlane` control plane |
| **L3** | End-to-end product journey software-complete (durable + recovery + FSM guards under real attach path) |

---

## 0) Authority sources read

### Docs package (design authority)

| Path | Role |
|------|------|
| `docs/40-domain-data/README.md` | NAV-DATA index |
| `docs/40-domain-data/data-ownership-persistence.md` | DATA-OWNERSHIP — sole writer, durable classes, tx boundaries, schema authority |
| `docs/40-domain-data/domain-model.md` | DATA-DOMAIN — aggregates |
| `docs/40-domain-data/state-machine-catalog.md` | DATA-STATES — prose summary of FSM |
| `docs/40-domain-data/storage-retention.md` | DATA-STORAGE — layout / retention |
| `docs/40-domain-data/identity-model.md` | DATA-IDENTITY (skimmed for digest rules) |
| `docs/40-domain-data/configuration-policy.md` | DATA-CONFIG (skimmed for policy axis) |
| `specs/database/omnillm-schema.sql` | **Docs** DDL authority (39 tables) |
| `specs/database/migration-policy.yaml` | **Docs** migration policy (`schemaVersion: 1`) |
| `specs/state-machines.yaml` | **Docs** FSM machine authority |

### Monorepo (implementation)

| Path | Role |
|------|------|
| `data/persistence/**` | SQLDelight + ControlPlaneDatabase + ledgers |
| `data/model-store/**` | FS model-store layout |
| `core/state/**` | Generated FSM + `StateMachineDriver` |
| `runtime/request-registry/**` | Claim / command / commit ledgers |
| `runtime/job-manager/**` | Job durability |
| `runtime/session/**` | Durable session manager |
| `android/runtime-service/.../RuntimeControlPlane.kt` | Production attach path |
| `specs/database/*` | In-repo schema + migration (byte-identical to packaged resources) |
| `specs/state-machines.yaml` | In-repo FSM (drives codegen; **≠** docs package for CONTENT_REPORT) |

---

## 1) Executive summary

| Area | Status | Level | One-line finding |
|------|--------|-------|------------------|
| Claim ledger durable in production RCP | **PASS** | L2 | `SqlDelightClaimLedgerStore` via `ControlPlaneDatabase`; **no** `InMemoryClaim*` on main attach |
| Commit ledger durable in production RCP | **PASS** | L2 | `SqlDelightCommitLedgerStore` + restart fence `reconcileUnfinishedCommits` |
| Job durability | **PASS** | L2 | `JobManagerModule.createDurableManager(controlDb.jobs)` + `reconcileAfterRestart()` |
| Secret durability | **PASS** | L2 | Keystore-wrapped vault + SQLite verifiers (`ControlPlaneSecurityFactory`) |
| Session durability | **PASS** | L2 | `SessionModule.createDurableManager` → `DurableSessionManager` |
| InMemory claim/commit **not** production | **PASS** | L2 | Documented test-only; `runtime-service/src/main` has zero `InMemoryClaim/Commit` references |
| Schema subset vs authority SQL | **PARTIAL** | L1–L2 | 26/51 tables IMPLEMENTED via SQLDelight; 25 PLANNED; docs package schema is **older subset** of monorepo |
| Docs package vs monorepo schema/policy | **PARTIAL** | — | Monorepo schema + migration-policy **diverged** from docs package authority files |
| FSM catalog present + generated | **PASS** | L1 | `StateMachines.kt` generated; driver + required machines present |
| FSM ↔ DDL CHECK alignment | **PARTIAL** | L1 | Partial CHECKs on some tables; REQUEST/JOB free-string `state`; `.sq` drops CHECKs/FKs |
| FSM monorepo vs docs package | **PARTIAL** | — | Machine IDs match; **CONTENT_REPORT** state set diverges |
| Request restart fence in production recovery | **PARTIAL** | L2 | API + tests exist; `finishRecovery()` only fences **commits**, not requests |
| ClientRegistration durable | **MISSING** | L1 (in-mem only) | `client_registrations` PLANNED; live store is `ConcurrentHashMap` |
| Asset durable | **MISSING** | L1 (in-mem only) | `assets` PLANNED; `AssetHandleBroker` is process-local map + quarantine files |
| Blob / revision / reservation / allocation / loaded_models | **MISSING** | L1 PLANNED | No SQLDelight projection; model identity still partial |
| Measurement / security_audit / request_events | **MISSING** | L1 PLANNED | Tables only in authority SQL as PLANNED |
| Sole writer (control plane) | **PASS** | L2 | Process identity + `SingleWriterPolicy` on attach/open |
| Migration journal fixture | **PASS** | L1 | Packaged `0001_to_0002.sql` + `SchemaMigrationFixtureTest` |
| Production migration runner | **PARTIAL** | L2 | No runtime migration engine; Android uses SQLDelight `Schema.create` on open |
| PRAGMA foreign_keys on live connections | **PARTIAL** | L2 | Required in policy/SQL; **no** evidence of runtime PRAGMA enable on Android open path |

**Overall domain verdict:** **PARTIAL** — production claim/commit/job/secret/session ledgers are SQLite-durable and correctly wired away from InMemory stores; large planned table surface, non-durable registration/assets, authority drift vs docs package, and incomplete recovery/FSM-DDL enforcement remain.

---

## 2) Production RuntimeControlPlane: claim / commit durability

### 2.1 Attach path evidence — **PASS (L2)**

`RuntimeControlPlane.attach` (production only, `:runtime` process):

1. Opens `AndroidSqliteDriver` with `OmniLlmDatabase.Schema`, DB name `omnillm.db`.
2. `ControlPlaneDatabase.open(driver, applySchema = false)` — binds **only** SQLDelight stores.
3. `RequestRegistryModule.createWithCommits(claims = controlDb.claims, commits = controlDb.commits)`.

Evidence:

```402:416:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\controlplane\RuntimeControlPlane.kt
                val driver = AndroidSqliteDriver(
                    schema = OmniLlmDatabase.Schema,
                    context = appContext,
                    name = StorageLayout.DATABASE_NAME,
                )
                val controlDb = ControlPlaneDatabase.open(
                    driver = driver,
                    applySchema = false,
                    clock = clock,
                )
                val ledgers = RequestRegistryModule.createWithCommits(
                    claims = controlDb.claims,
                    commits = controlDb.commits,
                    clock = clock,
                )
```

`ControlPlaneDatabase.open` constructs durable ports:

```135:144:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\data\persistence\src\main\kotlin\com\omnillm\data\persistence\ControlPlaneDatabase.kt
                claims = SqlDelightClaimLedgerStore(database, writerRole),
                commits = SqlDelightCommitLedgerStore(database, writerRole),
                sessions = SqlDelightSessionStore(database, writerRole),
                jobs = SqlDelightJobLedgerStore(database, writerRole),
                contentReports = SqlDelightContentReportStore(database, writerRole),
                installations = SqlDelightInstallationStore(database, writerRole),
                revisionLeases = SqlDelightRevisionLeaseStore(database, writerRole),
                catalogTrust = SqlDelightCatalogTrustStore(database, writerRole),
                secrets = SqlDelightSecretLedgerStore(database, writerRole),
                toolProposals = SqlDelightToolProposalStore(database, writerRole),
```

`ControlPlaneDatabase.durable = true` (constant).

### 2.2 InMemory claim/commit — test-only — **PASS**

| Symbol | Location | Production main? |
|--------|----------|------------------|
| `InMemoryClaimLedgerStore` | `data/persistence/.../InMemoryClaimLedgerStore.kt` | **No** — KDoc: "Do not use from live RuntimeControlPlane" |
| `InMemoryCommitLedgerStore` | same module | **No** — same |
| `RequestRegistryModule.createInMemory*` | `runtime/request-registry` | **No** on main; used by unit/HTTP tests |
| Grep `InMemoryClaimLedgerStore` / `createInMemoryWithCommits` under `android/runtime-service/src/main` | — | **0 matches** |

`PersistenceModule` documents:

> *In-memory claim/commit/session stores are **test-only** (not bound on live control plane).*

### 2.3 Claim-or-return transaction boundary — **PASS (L2 software)**

`RequestRegistry` inserts request claim inside `ports.tx.inTransaction`; SQLDelight maps to `database.transactionWithResult`. Initial state = `StateMachines.REQUEST.initial` (`RECEIVED`). Claim key uniqueness + digest conflict semantics present in models/store.

### 2.4 Commit restart fence — **PASS (L2)**

- `ControlPlaneDatabase.reconcileUnfinishedCommits()` lists open commits → `RECONCILING`.
- `RuntimeControlPlane.finishRecovery()` calls it before READY/DEGRADED.

### 2.5 Request restart fence — **PARTIAL (L2 incomplete wiring)**

| Item | Evidence | Status |
|------|----------|--------|
| `reconcileUnfinishedRequests()` | `ControlPlaneDatabase.kt` + `listNonTerminal` in `InferenceRequests.sq` | L1 present |
| Unit tests | `RequestRestartFenceTest.kt` | Present |
| Production `finishRecovery()` | Only `reconcileUnfinishedCommits()` | **Not called** |

**Gap:** COR-19/REL-RECOVERY request fence is implemented but not invoked on production recovery path.

---

## 3) Job / secret durability

### 3.1 Jobs — **PASS (L2)**

```424:430:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\controlplane\RuntimeControlPlane.kt
                val jobs = JobManagerModule.createDurableManager(
                    ports = controlDb.jobs,
                    clock = { System.currentTimeMillis() },
                )
                // REL-RECOVERY: fence RUNNING → RECOVERING after process restart
                jobs.reconcileAfterRestart()
```

- Tables: `jobs`, `job_attempts`, `job_events` (SQLDelight).
- `JobManagerModule.createManager` / `InMemoryJobStore` documented test-only.
- Checkpoint field `checkpoint_json` on jobs (no ephemeral FD claim in schema comments).

**Caveat (PARTIAL nuance):** job `state` has **no** DDL `CHECK` against JOB FSM; enforcement relies on application code.

### 3.2 Secrets / tokens / pairing / revocation — **PASS (L2)**

`ControlPlaneSecurityFactory.createSecurityStack`:

- `EncryptedBlobSecretKeyVault` over `secrets.keyBlobs` (`secret_broker_keys`)
- Access tokens: HMAC verifiers only (`access_tokens`)
- Pairing challenges SQLite (`pairing_challenges`)
- Revocation epochs SQLite (`revocation_subjects`)
- Master key via Android Keystore + `noBackupFilesDir`

Aligned with DATA-OWNERSHIP: *token hash / broker metadata durable; plaintext not stored*.

**Caveat:** docs package schema **lacks** `secret_broker_keys` / `revocation_subjects` tables entirely (monorepo extension).

---

## 4) Other durable vs non-durable surfaces

### 4.1 Sessions — **PASS (L2)** with documented non-claims

`SessionModule.createDurableManager` → `DurableSessionManager` rehydrates non-CLOSED rows from SQLite.

Explicit non-claims (correct per DATA-OWNERSHIP): native KV, free-pool membership, active op counts **not** restored.

### 4.2 Installations / revision leases / catalog trust / content reports / tool proposals — **PASS (L2 wiring)**

Wired from `controlDb` into ModelManager, FeaturePackHost content-report + tools ledgers.

### 4.3 ClientRegistration — **MISSING (durable)** / **PARTIAL (process-local L2)**

```13:15:C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\binder\ClientRegistrationStore.kt
 * Durable DB projection is TODO; this is the control-plane authority for the
 * runtime process until persistence wires CLIENT_REGISTRATION rows (ADR-010).
```

- Store: `ConcurrentHashMap` in process memory.
- Schema: `client_registrations` marked **PLANNED** in monorepo authority SQL.
- Violates DATA-OWNERSHIP recovery: registration must reload ACTIVE/SUSPENDED across restart.

### 4.4 Assets — **MISSING (durable domain)** / **PARTIAL (ephemeral materialize)**

`AssetHandleBroker`: in-memory `ConcurrentHashMap` + quarantine directory under `cacheDir`.

- Schema: `assets`, `asset_references` **PLANNED**.
- DATA-OWNERSHIP Asset recovery (CREATED/UPLOADING/VERIFYING/READY/PINNED reconcile) not software-complete.

### 4.5 ModelHub ancillary ports on Wave-A production wire — **PARTIAL**

`WaveAWiring.wire` still uses:

- `InMemoryAcquisitionLinkStore`
- `InMemoryModelDisplayMetadataPort`
- `InMemoryInstallationResourceVersionPort`

while ModelManager/installations themselves are durable. Acquisition **link** metadata is not process-crash durable.

### 4.6 Observability — **N_A / intentional ephemeral**

`ObservabilityModule` defaults to `InMemoryMetricRegistry` / `InMemoryTraceRecorder` — matches DATA-OWNERSHIP “non-durable sampler buffers” for measurement sketches; full `measurement_runs` table remains PLANNED.

---

## 5) Schema subset vs authority SQL

### 5.1 Three schema sources compared

| Source | Tables | Notes |
|--------|--------|-------|
| **Docs package** `specs/database/omnillm-schema.sql` | **39** | Older design authority; **no** schema_metadata / migration tables / prepared_operations / secret_broker_keys / tool_* |
| **Monorepo** `specs/database/omnillm-schema.sql` | **51** | Extended authority with STATUS IMPLEMENTED / PLANNED |
| **Packaged resource** `data/persistence/src/main/resources/db/omnillm-schema.sql` | **51** | **Byte-identical** to monorepo specs schema (SHA256 match) |

Tables only in monorepo (not in docs package):

`schema_metadata`, `schema_migration_attempts`, `schema_migration_history`, `runtime_instances`, `prepared_operations`, `commit_resource_bindings`, `secret_broker_keys`, `revocation_subjects`, `content_report_consent_grants`, `content_report_receipts`, `tool_proposals`, `tool_result_claims`

**Status: PARTIAL** — monorepo is a **superset** of docs package DDL; product docs package is **not** the live monorepo schema authority.

### 5.2 Implemented (SQLDelight) vs PLANNED

| Bucket | Count | Tables (summary) |
|--------|------:|------------------|
| **IMPLEMENTED** + `.sq` | **26** | Exact match: schema_*, inference/request/commit/job/session/install/lease/token/pairing/secret/revocation/content-report/tool/catalog_trust |
| **PLANNED** (no `.sq`) | **25** | blobs, artifact_*, model_revisions, source/license, reservations, allocations, loaded_models, request_events, assets*, downloads*, client_registrations, measurement_runs, security_audit_events, runtime_instances, … |

`SchemaAuthority` KDoc correctly states SQLDelight is a **subset** of authority SQL for control-plane ledgers.

### 5.3 Live DB creation path — **PARTIAL**

Production does **not** apply full monorepo `omnillm-schema.sql` (including PLANNED tables). It creates the **SQLDelight Schema** only (26 tables). Authority SQL with PLANNED DDL is design documentation + future surface, not the runtime create script.

Implications:

- FKs in authority SQL that reference PLANNED parents (e.g. `installations` → `model_revisions`, `commit_records` → `reservations` / `runtime_instances`) are **not** enforced on the live subset (`.sq` projections drop those FKs).
- DATA-OWNERSHIP §9 full table-group inventory is **not** software-complete.

### 5.4 SQLDelight vs authority column fidelity — **PARTIAL**

Examples of intentional projection drift (documented in comments):

| Table | Authority SQL | `.sq` projection |
|-------|---------------|------------------|
| `sessions` | simpler columns + FK to loaded_models | Extended owner/LoadKey/recovery_disposition (INV-007) |
| `installations` | FK to model_revisions; fewer eval columns | Extended quarantine/eval/pin; **no** FK |
| `commit_records` | full CHECK + FKs | No CHECK/FK in `.sq`; state validated in app |
| `inference_requests` | free `state TEXT` | free `state TEXT` + app `RequestLedgerStates` |

### 5.5 Migration policy — **PARTIAL**

| Item | Docs package | Monorepo / packaged |
|------|--------------|---------------------|
| File | `migration-policy.yaml` | same name |
| `schemaVersion` | **1** | **2** |
| `compatibility` block | **absent** | current/min r/w = 2 |
| Journal states | COMPLETED / ROLLED_BACK / QUARANTINED | STARTED / COMMITTED / ROLLED_BACK / FAILED / QUARANTINED |
| Byte identity mono↔pkg | — | **identical** |
| Docs ↔ mono | — | **different** |

Executable fixture: `db/migrations/0001_to_0002.sql` + `SchemaMigrationFixtureTest` (**PASS L1**).

Production attach: **no** migration runner grep hits under `runtime-service/src/main` — relies on SQLDelight create + `ensureSchemaMetadata` seed to version 2 (**PARTIAL L2**).

### 5.6 PRAGMA foreign_keys — **PARTIAL**

- Required by migration-policy + present at top of authority SQL / migration fixture.
- Grep of `data/persistence/src/main/kotlin`: **no** `PRAGMA foreign_keys` enable on open.
- SQLDelight subset has few FKs anyway → risk is lower today, but policy non-compliance remains.

---

## 6) FSM alignment

### 6.1 Catalog generation — **PASS (L1)**

- Source: monorepo `specs/state-machines.yaml` → `tools/codegen` → `core/state/.../generated/StateMachines.kt`.
- Machines present: REQUEST, SESSION, RESERVATION, JOB, MODEL_INSTALLATION, LOADED_MODEL, COMMIT, RUNTIME, REVOCATION, ASSET, CLIENT_REGISTRATION, OPERATION, ENGINE_MODULE, ALLOCATION, REVISION_LEASE, COMMAND, PAIRING_CHALLENGE, LAN_SERVICE, CONTENT_REPORT, TOKEN.
- `StateMachineDriver.REQUIRED_MACHINE_IDS` covers core set; property/illegal-edge tests under `core/state/src/test`.

### 6.2 Runtime use of catalog — **PASS (L2 partial product depth)**

- `RequestRegistry` uses `StateMachines.REQUEST.initial`.
- Domain aggregates in `DomainAggregates.kt` transition via `StateMachineDriver`.
- Job manager / session manager use catalog state strings.
- Full event-driven FSM for every edge in live HTTP/AIDL path is **not** claimed as L3 complete (orchestrator/engine paths still fail-closed in places).

### 6.3 Docs package vs monorepo FSM — **PARTIAL**

| Machine | Docs ↔ Mono |
|---------|-------------|
| Machine ID list | **Match** (20 machines) |
| File identity | **Not identical** (docs 53901 bytes vs mono 57420) |
| **CONTENT_REPORT** states | **Diverge** |

Docs package CONTENT_REPORT states:  
`DRAFT, CONSENTED, QUEUED, SUBMITTING, FAILED_RETRYABLE, SUBMITTED, CANCELLED, EXPIRED`

Monorepo generated + monorepo schema CHECK:  
`DRAFT, REVIEWING, CONSENT_GRANTED, QUEUED_OFFLINE, SUBMITTING, CANCELLING, RECONCILING, FAILED_RETRYABLE, FAILED_FINAL, SUBMITTED, DISCARDED, EXPIRED`

Prose `state-machine-catalog.md` (docs package) still summarizes the older CONTENT_REPORT wording (`DRAFT → REVIEWING → …` in §17, mixed with older YAML).

**Audit rule:** docs package `specs/state-machines.yaml` is design authority for this package; monorepo implementation tracks monorepo specs. Flag as **authority drift**.

### 6.4 DDL CHECK ↔ FSM (DATA-OWNERSHIP constraint rule) — **PARTIAL**

DATA-OWNERSHIP: *canonical aggregate `state` columns CHECK-aligned with `state-machines.yaml`*.

| Table (monorepo authority) | CHECK vs FSM |
|----------------------------|--------------|
| `commit_records.state` | CHECK matches COMMIT states — **PASS** |
| `prepared_operations.state` | CHECK matches OPERATION — **PASS** |
| `idempotent_commands.state` | CHECK matches COMMAND — **PASS** |
| `runtime_instances.state` | CHECK matches RUNTIME (+ FENCED) — **PASS** (table PLANNED) |
| `client_registrations.state` | CHECK matches CLIENT_REGISTRATION — **PASS** (table PLANNED) |
| `content_reports.state` | CHECK matches **monorepo** CONTENT_REPORT — **PASS** vs mono, **FAIL** vs docs YAML |
| `inference_requests.state` | **No CHECK** — app `RequestLedgerStates` only — **PARTIAL** |
| `jobs.state` | **No CHECK** — **PARTIAL** |
| `sessions.state` | **No CHECK** in authority/`.sq` — **PARTIAL** |
| `installations.state` | **No CHECK** — **PARTIAL** |
| SQLDelight `.sq` files | Generally **omit** authority CHECKs — **PARTIAL** |

### 6.5 Commit ledger states vs COMMIT FSM — **PASS (L1/L2 software)**

Open-commit filter excludes `COMMITTED|ABORTED|UNCERTAIN_QUARANTINED`; RECONCILING path present — aligns with COMMIT machine summary in DATA-STATES.

---

## 7) Ownership / single writer

| Control | Evidence | Status |
|---------|----------|--------|
| Only control plane writes domain DB | `RuntimeControlPlane.attach` asserts `ProcessIdentity.isRuntimeProcess()` + `SingleWriterPolicy` | **PASS L2** |
| `ControlPlaneDatabase` sole-writer role | `SingleWriterPolicy.assertWriterAllowed` on open | **PASS L2** |
| UI / workers as second writer | No second Room/DataStore domain writer found in attach path; UI attaches plane only from runtime services | **PASS L2** (process gate) |
| Worker journal as trust elevation | Out of scope detail; workers do not open ControlPlaneDatabase in main sources grepped | **PASS** for “no second SQLite writer” claim |

---

## 8) Storage layout / retention

| Item | Status | Evidence |
|------|--------|----------|
| Logical layout matches DATA-STORAGE | **PASS L1** | `StorageLayout` documents model-store/blobs, quarantine, noBackup trust/journal, `omnillm.db` |
| Android roots | **PASS L2** | `AndroidStorageRoots.ensureLayout` on attach |
| Retention policy execution | **PARTIAL** | `specs/retention-policy.yaml` exists in docs package; no automated purge engine audited as L3 complete in this pass |
| Backup exclusion | **PARTIAL** | Secrets under noBackup path; full BackupAgent exclusion not re-proven here |

---

## 9) Search log (empty / negative findings)

| Query / read | Result |
|--------------|--------|
| `InMemoryClaimLedgerStore` / `createInMemoryWithCommits` in `runtime-service/src/main` | **0 hits** (good) |
| `reconcileUnfinishedRequests` in `RuntimeControlPlane` recovery | **API exists; not called from finishRecovery** |
| Migration apply in `runtime-service/src/main` | **0 hits** |
| `PRAGMA foreign_keys` in persistence main Kotlin | **0 hits** |
| SQLDelight for `assets` / `client_registrations` / `blobs` / `reservations` | **No .sq** (STATUS PLANNED) |
| Docs package schema `secret_broker_keys` | **Absent** |

---

## 10) Per-check scorecard (task checklist)

| Check | Status | Level | Evidence |
|-------|--------|-------|----------|
| Claim durable store in production RCP | **PASS** | L2 | `SqlDelightClaimLedgerStore` in attach |
| Commit durable store in production RCP | **PASS** | L2 | `SqlDelightCommitLedgerStore` + reconcile |
| InMemory **not** production claim/commit | **PASS** | L2 | Test-only factories; 0 main refs |
| Job durability | **PASS** | L2 | `createDurableManager` + jobs tables |
| Secret durability | **PASS** | L2 | `SqlDelightSecretLedgerStore` + Keystore vault |
| Schema subset of authority | **PARTIAL** | L1–L2 | 26/51 implemented; SQLDelight = IMPLEMENTED set |
| Schema vs docs package authority | **PARTIAL** | — | Monorepo superset; docs package outdated |
| FSM catalog present | **PASS** | L1 | Generated `StateMachines` + driver |
| FSM alignment with stores/DDL | **PARTIAL** | L1–L2 | App enums strong for REQUEST; DDL CHECKs incomplete; CONTENT_REPORT drift |
| ClientRegistration durable | **MISSING** | — | In-memory TODO |
| Asset durable | **MISSING** | — | In-memory broker |
| Request recovery fence production | **PARTIAL** | L2 | Implemented, not wired in finishRecovery |
| Sole writer | **PASS** | L2 | Process + role gate |

---

## 11) Findings (actionable)

### F-DATA-01 — Request restart fence not on production recovery path
- **Status:** PARTIAL  
- **Evidence:** `ControlPlaneDatabase.reconcileUnfinishedRequests` + tests; `RuntimeControlPlane.finishRecovery` only commits.  
- **Risk:** Non-terminal requests may resume without RECONCILING fence after process death.  
- **Fix:** Call request fence alongside commit fence (and jobs, already done) before READY.

### F-DATA-02 — ClientRegistration not durable
- **Status:** MISSING  
- **Evidence:** `ClientRegistrationStore` ConcurrentHashMap + TODO; `client_registrations` PLANNED.  
- **Risk:** Process death loses AIDL registration / scopes / epoch binding.  
- **Fix:** SQLDelight projection + rehydrate on attach per DATA-OWNERSHIP §7.

### F-DATA-03 — Asset aggregate not durable
- **Status:** MISSING  
- **Evidence:** `AssetHandleBroker` map; `assets` PLANNED.  
- **Risk:** READY/PINNED assets and pins lost on restart; recovery path undefined.  
- **Fix:** Implement assets + pins tables; pin with request claim transaction.

### F-DATA-04 — Docs package schema/migration/FSM lag monorepo
- **Status:** PARTIAL (governance)  
- **Evidence:** table count 39 vs 51; migration-policy v1 vs v2; CONTENT_REPORT states diverge.  
- **Risk:** Audits against docs package alone understate or mis-state implementation truth.  
- **Fix:** Republish docs package `specs/database/*` + `state-machines.yaml` from monorepo authority (or reverse if monorepo unauthorized).

### F-DATA-05 — DDL CHECK incomplete for REQUEST/JOB/SESSION/INSTALLATION
- **Status:** PARTIAL  
- **Evidence:** free `state TEXT` in authority + `.sq`; app-level sets only.  
- **Risk:** Unknown state pollution if any writer bypasses Kotlin require.  
- **Fix:** Align CHECK constraints with FSM; keep SQLDelight Schema in sync.

### F-DATA-06 — Large PLANNED surface blocks full DATA-OWNERSHIP §9
- **Status:** MISSING (for those aggregates)  
- **Evidence:** 25 PLANNED tables including blobs, model_revisions, reservations, allocations, loaded_models, measurement_runs, security_audit_events.  
- **Risk:** Identity/trust/resource conservation ledgers incomplete; installations store revision_id without durable revision row.  
- **Fix:** Prioritize model_revisions + blobs + reservations/allocations for resource/commit integrity.

### F-DATA-07 — ModelHub link/display/resource-version ports still InMemory on Wave-A wire
- **Status:** PARTIAL  
- **Evidence:** `WaveAWiring.kt` constructs InMemory* ports while control plane model manager is durable.  
- **Risk:** Acquisition UX metadata lost on restart even if installation rows survive.

### F-DATA-08 — No production migration runner; foreign_keys PRAGMA not asserted on open
- **Status:** PARTIAL  
- **Evidence:** fixture tests only; no main-path migration engine; no PRAGMA in Kotlin open.  
- **Risk:** Future schema bumps lack kill-at-every-step production path.

---

## 12) L1 / L2 / L3 matrix (persistence-centric)

| Aggregate / surface | L1 exists | L2 wired production | L3 journey software-complete |
|---------------------|-----------|---------------------|------------------------------|
| Request claim ledger | PASS | PASS | PARTIAL (request fence not on recovery) |
| Command claim ledger | PASS | PASS | PARTIAL |
| Commit ledger | PASS | PASS | PARTIAL (depends on engines/orchestrator) |
| Job ledger | PASS | PASS | PARTIAL |
| Session ledger | PASS | PASS | PARTIAL (native non-restore by design) |
| Secret / token / pairing | PASS | PASS | PARTIAL (full LAN pairing journey not re-audited here) |
| Installation / lease | PASS | PASS | PARTIAL (no model_revisions table) |
| Content report | PASS | PASS | PARTIAL (FSM drift docs vs mono) |
| Tool proposals | PASS | PASS | PARTIAL |
| ClientRegistration | PARTIAL (in-mem) | PARTIAL (in-mem) | MISSING durable |
| Asset | PARTIAL (in-mem + quarantine files) | PARTIAL | MISSING durable |
| Blob / ArtifactPackage / Revision | MISSING | MISSING | MISSING |
| Reservation / Allocation / LoadedModel tables | MISSING | MISSING | MISSING |
| Measurement runs | MISSING | MISSING | MISSING |

---

## 13) What was **not** claimed

- No device PASS, Play upload, or OEM matrix results.
- Engines not marked QUALIFIED/SUPPORTED from this domain audit.
- Prior monorepo readiness docs (`PRODUCT_READINESS.md`, `FEATURE_AUDIT.md`) treated as non-authority hints only.
- Full end-to-end Appium journey durability not re-run in this pass (file-level + wiring audit only).

---

## 14) Intermediate artifacts

This file is the domain deliverable:

`C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\04_data.md`

No additional intermediate files were required; search evidence is embedded in §9–§11.

---

## 15) Verdict

| Dimension | Status |
|-----------|--------|
| **Claim/commit production durability vs InMemory** | **PASS** |
| **Job + secret durability** | **PASS** |
| **Schema subset honesty (IMPLEMENTED vs PLANNED)** | **PASS** (self-labeled) with **PARTIAL** completeness |
| **Schema vs docs package authority** | **PARTIAL** (drift) |
| **FSM presence** | **PASS** |
| **FSM ↔ DDL / docs alignment** | **PARTIAL** |
| **Domain persistence overall** | **PARTIAL** |

**Bottom line:** Production `RuntimeControlPlane` correctly binds **SQLite/SQLDelight** claim, commit, job, secret, session, installation, content-report, and tool ledgers — **not** the InMemory claim/commit stores. The data plane is still **PARTIAL** relative to DATA-OWNERSHIP: half the authority tables are PLANNED-only; ClientRegistration and Assets are process-local; request restart fencing is not production-wired; and the monorepo schema/FSM have drifted from the docs package design authority.
)
