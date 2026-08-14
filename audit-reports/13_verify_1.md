# Adversarial verify — Claim #1

**Claim:** Production claim/commit ledgers are SQLite durable not InMemory  
**Auditor:** independent re-inspection (docs package 新版本 + monorepo source only)  
**Date:** 2026-08-12  
**Method:** `list_dir` / `grep` / `read_file` on real paths; fail-closed if no evidence.

## Verdict

| Field | Value |
|---|---|
| **real** | **true** |
| **software_status** | **PASS** (L2 production attach wires SQLDelight/SQLite file DB; InMemory claim/commit stores absent from `runtime-service` main) |
| **device_recovery_status** | **N_A** for this claim (claim is wiring/durability backend choice, not kill-at-boundary product evidence) |

### reason

Production control-plane attach opens a **file-named** `AndroidSqliteDriver` (`omnillm.db`), wraps it in `ControlPlaneDatabase.open`, and binds claim/commit ports via `SqlDelightClaimLedgerStore` / `SqlDelightCommitLedgerStore` through `RequestRegistryModule.createWithCommits`. The process-memory `InMemoryClaimLedgerStore` / `InMemoryCommitLedgerStore` and `createInMemory*` factories are **documented test-only** and have **zero** references under `android/runtime-service/src/main`. Docs (DATA-OWNERSHIP, ADR-005) require durable request/commit ledgers; schema tables exist in product and repo SQL.

### real: true — concrete evidence

#### A) Production attach path (sole live plane construction)

**File:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\controlplane\RuntimeControlPlane.kt`

Class KDoc (holds durable ledgers under SQLite):

```text
* - Durable claim + commit + session + job + installation/lease ledgers
*   (SQLDelight / SQLite under [ControlPlaneDatabase])
```

Attach wiring (file SQLite driver + durable ledgers):

```text
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

Attach log asserts durable file DB:

```text
"db=${StorageLayout.DATABASE_NAME} durable=true "
```

**Database name:** `StorageLayout.DATABASE_NAME = "omnillm.db"`  
**File:** `...\data\model-store\src\main\kotlin\com\omnillm\data\modelstore\StorageLayout.kt`

**Call sites of production `attach` (all runtime services / bootstrap):**

| Caller | Path |
|---|---|
| `RuntimeProcessBootstrap` | `android/runtime-service/src/main/.../RuntimeProcessBootstrap.kt` → `RuntimeControlPlane.attach` |
| `RuntimeForegroundService` | `.../service/RuntimeForegroundService.kt` |
| `RuntimeBindingService` | `.../service/RuntimeBindingService.kt` |
| `AdminBindingService` | `.../service/AdminBindingService.kt` |
| `TransferService` | `.../service/TransferService.kt` |

No alternate production factory opens claim/commit InMemory stores.

#### B) ControlPlaneDatabase always exposes SQLDelight claim/commit stores

**File:** `...\data\persistence\src\main\kotlin\com\omnillm\data\persistence\ControlPlaneDatabase.kt`

```text
claims = SqlDelightClaimLedgerStore(database, writerRole),
commits = SqlDelightCommitLedgerStore(database, writerRole),
```

```text
/** True when control-plane ledgers are SQLite-backed (process-crash durable). */
val durable: Boolean = true
```

`openInMemory()` uses `JdbcSqliteDriver.IN_MEMORY` for **unit tests of DAO mapping** — still SQLDelight over SQLite, **not** the HashMap `InMemory*LedgerStore` classes; production attach does **not** call it.

#### C) SQLDelight-backed stores (symbols + tables)

| Store | Path | Tables (KDoc / `.sq`) |
|---|---|---|
| `SqlDelightClaimLedgerStore` | `data/persistence/.../SqlDelightClaimLedgerStore.kt` | `inference_requests`, `request_attempts`, `request_terminals`, `idempotent_commands` |
| `SqlDelightCommitLedgerStore` | `data/persistence/.../SqlDelightCommitLedgerStore.kt` | `commit_records`, `prepared_operations`, `commit_resource_bindings` |

SQLDelight schema sources:

- `data/persistence/src/main/sqldelight/.../InferenceRequests.sq` — `CREATE TABLE inference_requests (...)`
- `data/persistence/src/main/sqldelight/.../CommitRecords.sq` — `CREATE TABLE commit_records (...)`  
  comment: `INTENT_RECORDED must be durable before worker commitLoad`

#### D) InMemory claim/commit = test-only, not production main

| Symbol | Path | Quote / evidence |
|---|---|---|
| `InMemoryClaimLedgerStore` | `data/persistence/.../InMemoryClaimLedgerStore.kt` | `**Test-only.** Production ... wires [SqlDelightClaimLedgerStore] ... Do not use this store from live RuntimeControlPlane.` Uses `linkedMapOf` (process memory). |
| `InMemoryCommitLedgerStore` | `data/persistence/.../InMemoryCommitLedgerStore.kt` | Same test-only warning; `**Not process-crash durable.**` |
| `RequestRegistryModule.createWithCommits` | `runtime/request-registry/.../RequestRegistryModule.kt` | `Production / durable wiring... Prefer this over [createInMemoryWithCommits] for live RuntimeControlPlane.` |
| `createInMemory` / `createInMemoryWithCommits` | same | `Convenience for unit tests` / `**Test-only / not process-crash durable.**` |
| `PersistenceModule` | `data/persistence/.../PersistenceModule.kt` | `In-memory claim/commit/session stores are **test-only** (not bound on live control plane).` |

**Grep evidence:** pattern `InMemoryClaimLedgerStore|InMemoryCommitLedgerStore|createInMemoryWithCommits|createInMemory\(` under  
`android/runtime-service/src/main` → **0 matches**.

InMemory factories appear under `src/test` (e.g. `LaunchCriticalHttpSurfaceTest`, `RequestRegistryTest`, `CommitLedgerRecoveryTest`) and harness helpers (`OrchestratorModule.createInMemoryHarness`) — not live attach.

#### E) Module factory documents production vs test split

**File:** `...\runtime\request-registry\src\main\kotlin\com\omnillm\runtime\RequestRegistryModule.kt`

```text
* Production binds SQLDelight DAOs ([...SqlDelightClaimLedgerStore]);
* unit tests may use [InMemoryClaimLedgerStore].
```

#### F) Recovery path uses SQLite-backed reconcile (wiring present)

- `ControlPlaneDatabase.reconcileUnfinishedCommits()` fences open commits → `RECONCILING` via `commits.commits.*` DAOs.
- `RuntimeControlPlane.finishRecovery()` calls `controlPlaneDb.reconcileUnfinishedCommits()`.
- `RuntimeForegroundService` invokes `plane.finishRecovery()` from RECOVERING.

This is software wiring evidence that recovery expects **durable** commit rows, not process maps.

#### G) Product docs authority (新版本 package)

| Authority | Path | Requirement |
|---|---|---|
| DATA-OWNERSHIP | `...\OmniLLM_Product_Documents\docs\40-domain-data\data-ownership-persistence.md` | Control plane is sole writer of request/command/job/**commit ledger**; Request/Commit **Durable** contents: canonical hash, state, terminal, commit intent/result… |
| ADR-005 | `...\governance\adr\ADR-005.md` | CommitId + **durable/queryable commit**; need journal / queryCommit |
| Schema | `...\specs\database\omnillm-schema.sql` | `CREATE TABLE inference_requests`, `commit_records`, `idempotent_commands` |

Repo packages matching DDL under `data/persistence/src/main/resources/db/omnillm-schema.sql` and SQLDelight `.sq` files.

### counter_evidence

Checked for contradictions that would make the claim false (production still InMemory):

1. **InMemory stores exist in main source** — true, but KDoc forbids live plane use; **not** referenced from `runtime-service/src/main` attach.
2. **`ControlPlaneDatabase.openInMemory`** — test helper (ephemeral JDBC SQLite), **not** production attach path.
3. **`WaveAWiring.bootstrapForTest` default `createInMemoryControlPlane()`** — **test bootstrap only** for model manager; production attach uses `ModelManagerModule.createDurableControlPlane` with `controlDb.installations` / leases (separate from claim/commit, does not reintroduce InMemory claim/commit).
4. **`applySchema = false` on Android attach** — relies on `AndroidSqliteDriver` applying `OmniLlmDatabase.Schema`; not evidence of InMemory ledgers. Residual schema-bootstrap risk is orthogonal to “InMemory vs SQLite store class” claim.
5. **Unit/integration tests still use InMemory ledgers** — expected; does not override production attach.
6. **Prior audit markdown** (`audit-reports/04_data.md`, `BUILD_STATUS.md`) agrees; this verify re-read source independently and did not rely on those as sole authority.

No counter_evidence found that production claim/commit ports are process-memory InMemory stores.

### residual

| Residual | Severity | Note |
|---|---|---|
| Process-death / kill-at-boundary **on-device** proof that rows survive and reconcile correctly | residual product evidence | Software path is SQLite file `omnillm.db`; docs still require kill-at-boundary evidence packages. Does **not** reverse backend choice. |
| `reconcileUnfinishedRequests` implemented on DB but not grepped as called from FGS path | low | Commit reconcile is called via `finishRecovery`; request fence API exists. |
| SQLDelight is a **subset** of authority SQL (other tables PLANNED) | out of claim scope | Claim/commit tables **are** projected in `.sq` + SqlDelight stores. |
| Observability / some registries remain process-memory | out of claim scope | Claim is specifically claim/commit ledgers. |

### search log (fail-closed trail)

| Action | Target | Result |
|---|---|---|
| grep | monorepo `SqlDelightClaim/Commit`, `InMemoryClaim/Commit`, `createWithCommits`, `createInMemory` `*.kt` | Production factories + test-only InMemory |
| grep | `android/runtime-service` main `ControlPlaneDatabase`, claims/commits | attach wires SQLDelight |
| grep | `runtime-service/src/main` InMemory claim/commit | **0 matches** |
| grep | main `AndroidSqliteDriver`, `createWithCommits` | only production attach |
| read | `RuntimeControlPlane.kt` attach + finishRecovery | SQLite + createWithCommits |
| read | `ControlPlaneDatabase.kt`, SqlDelight + InMemory stores, `RequestRegistryModule.kt` | durable vs test split |
| read | `InferenceRequests.sq`, `CommitRecords.sq`, `StorageLayout.kt` | tables + `omnillm.db` |
| read | docs DATA-OWNERSHIP, ADR-005; schema CREATE TABLE | durable authority |
| grep | docs package claim/commit/SQLite | ownership + schema |

### conclusion

**real: true** — With path-level evidence, production RuntimeControlPlane attach binds claim and commit ledgers to **SQLDelight over file SQLite (`omnillm.db`)**, not to `InMemoryClaimLedgerStore` / `InMemoryCommitLedgerStore`. InMemory variants are explicitly test-only and unused on the live control-plane main path.
)
