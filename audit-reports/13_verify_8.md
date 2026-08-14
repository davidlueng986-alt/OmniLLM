# Adversarial verification — Claim #8

| Field | Value |
|---|---|
| **Claim** | Single writer ADR-010 holds for secrets tokens model store |
| **real** | **true** |
| **Auditor** | Independent re-read of NEW docs package + monorepo (not prior audit prose alone) |
| **Date** | 2026-08-12 |
| **Out path** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\13_verify_8.md` |
| **Scope of claim** | Software architecture: production durable writers for **secrets**, **tokens**, and **model store** are owned by the runtime control plane (ADR-010 / DATA-OWNERSHIP). Not an OS-enforced multi-UID exclusive lock, and not a device-matrix PASS. |

---

## Verdict

**real: true** — concrete path evidence shows:

1. **Product authority (NEW docs package)** requires the runtime control plane to be the sole privileged writer of domain DB / model store / token-catalog state (ADR-010, DATA-OWNERSHIP, ARCH-PRINCIPLES, migration-policy).
2. **Secrets + tokens (production path)** open only under `RuntimeControlPlane.attach` → `ControlPlaneDatabase` → `SqlDelightSecretLedgerStore` → `ControlPlaneSecurityFactory.createSecurityStack` → `TokenService` with SQLite HMAC verifiers (never plaintext). Gateway token facade injects that stack’s `TokenService`.
3. **Model store (production path)** is constructed only from the same attach path (`ModelStoreModule.createFilesystemPort`) and held on the plane / ModelManager; UI/workers/companion/engines have **no** production direct `:data:model-store` / `:data:persistence` module edges (CI gate OK).
4. **Attach is process-gated**: `ProcessIdentity.isRuntimeProcess()` + `SingleWriterPolicy.assertWriterAllowed` before opening writers; UI bootstrap deliberately does **not** attach.

Residual risks (soft role string, same-UID convention, model-store factory without `assertWriterAllowed`) are **documented** in product principles and do **not** establish a second production writer in-repo.

---

## reason

ADR-010’s decision is: *“Runtime control plane 為 single writer.”* For the three surfaces in the claim:

| Surface | Durable authority in code | Sole production open / host |
|---|---|---|
| **Secrets** (key blobs, pairing ciphertext, revocation epochs) | `ControlPlaneDatabase.secrets` → `SqlDelightSecretLedgerStore` | `RuntimeControlPlane.attach` + `ControlPlaneSecurityFactory` |
| **Tokens** (access token HMAC verifiers) | Same DB `access_tokens` via `AccessTokenStore` inside secret ledger | `PolicyModule.SecurityStack` / `TokenService` wired from plane; `GatewayLifecycle` injects `stack.tokenService` |
| **Model store** (quarantine / promote / ready content under filesDir) | `FilesystemModelStorePort` / `FilesystemQuarantineStore` | `ModelStoreModule.createFilesystemPort` in `RuntimeControlPlane.attach` |

Layering that makes the claim hold at software L2:

1. **Process identity** — only `:runtime` may attach the plane.
2. **Writer role marker** — `SingleWriterPolicy.WRITER_ROLE = "runtime-control-plane"`; non-control roles fail `assertWriterAllowed`.
3. **Module dependency fail-closed CI** — UI / workers / companion / parser-isolated / engines forbidden from depending on `:data:persistence` or `:data:model-store`.
4. **No second production open** of `ControlPlaneDatabase.open` or filesystem model-store factory under `android/**/src/main` outside the control-plane attach / storage-root helpers.

This matches DATA-OWNERSHIP §1 and ARCH-PRINCIPLES §5 as **software sole-writer**, with the docs’ own limit that same-UID “do not write” is convention (not a Linux UID security boundary). Companion is a different UID path (see claim #6); same-UID workers lack writer classpaths.

---

## evidence

### A. Product docs / specs (NEW package, NORMATIVE)

| Source | Quote / rule |
|---|---|
| `...\OmniLLM_Product_Documents\governance\adr\ADR-010.md` | Decision: “Runtime control plane 為 single writer.” Consequences: “所有 mutation 經 narrow commands.” |
| `...\OmniLLM_Product_Documents\docs\40-domain-data\data-ownership-persistence.md` §1 | “Runtime control plane是domain DB、model store projection、trust state、request/command/job/commit ledger、Asset與ClientRegistration的唯一權威writer。UI、Gateway、engine worker、isolated parser與external companion只能透過typed command/event更新；不同process不得直接開Room/DataStore作第二個writer。” |
| `...\OmniLLM_Product_Documents\docs\20-architecture\architecture-principles.md` §5 | “Database、DataStore、model store、token／catalog state 的唯一 privileged writer 是 runtime control plane。不同 UID worker 不取得可寫 private path；同 UID worker 的「不要寫」只能是程式約定，不能算安全控制。” |
| `...\OmniLLM_Product_Documents\docs\30-core-platform\model-platform.md` | Model installation owner is “Model Platform／single writer”. |
| `...\OmniLLM_Product_Documents\specs\database\migration-policy.yaml` | “the Runtime control plane is the only writer” |
| `...\OmniLLM_Product_Documents\docs\50-security-reliability\auth-network-secrets.md` §2 / §5 | Token: DB stores HMAC verifier only; Secret Broker — workers do not use general Keystore aliases; companion cannot obtain token plaintext. |

### B. Single-writer policy object (shared marker)

**Path:** `core/ports/src/main/kotlin/com/omnillm/core/ports/ledger/SingleWriterPolicy.kt`

```text
Runtime control plane is the sole authoritative writer of:
- domain DB (`:data:persistence`)
- model store projection
- trust state
- request / command / job / commit ledgers
- Asset and ClientRegistration durable state
- token vault material
...
const val WRITER_ROLE: String = "runtime-control-plane"
FORBIDDEN_WRITER_ROLES: app-ui, http-gateway, aidl-transport, engine-worker, isolated-parser, companion-sandbox
fun assertWriterAllowed(role: String) { require(role == WRITER_ROLE) ... }
```

### C. Secrets + tokens: durable ledger under control-plane DB only

| Path | Evidence |
|---|---|
| `data/persistence/.../ControlPlaneDatabase.kt` | “Single-writer control-plane SQLite handle (ADR-010)”; `open()` calls `SingleWriterPolicy.assertWriterAllowed(writerRole)`; exposes `val secrets: SqlDelightSecretLedgerStore`; comment: “Token verifiers / pairing challenges / revocation epochs / key blobs”. |
| `data/persistence/.../SqlDelightSecretLedgerStore.kt` | “SQLDelight-backed secret / token / pairing / revocation ledgers (ADR-010)”; “DB stores HMAC verifiers and encrypted pairing secrets only — never bearer plaintext”; `init { SingleWriterPolicy.assertWriterAllowed(writerRole) }`; implements `ControlPlaneWriter`; surfaces `accessTokens`, `pairingChallenges`, `revocationEpochs`, `keyBlobs`. |
| `android/runtime-service/.../ControlPlaneSecurityFactory.kt` | “Builds the production control-plane security stack (ADR-010 / SEC-AUTH-NET)”; uses `controlPlaneDb.secrets` only for vault + `accessTokenStore` / `pairingStore` / `epochStore`. |
| `android/runtime-service/.../RuntimeControlPlane.kt` `attach` | After process check: `ControlPlaneDatabase.open(...)` then `ControlPlaneSecurityFactory.createSecurityStack(appContext, controlPlaneDb, ...)` — durable secrets bound to that single DB handle. |
| `runtime/policy/.../TokenService.kt` | “Single writer: runtime control plane (ADR-010). Production: inject SQLite-backed AccessTokenStore; never keep plaintext.” |
| `android/runtime-service/.../GatewayLifecycle.kt` | Production `LoopbackTokenService(..., tokenService = stack.tokenService)` from plane security stack (not a free-standing second durable vault). |
| Schema authority | Docs + monorepo `specs/database/omnillm-schema.sql`: `access_tokens`, pairing secret ciphertext, token issue receipts — domain DB tables under control-plane schema. |

Unit evidence: `data/persistence/src/test/.../SqlDelightSecretLedgerStoreTest.kt` exercises durable tokens/pairing/key reopen via `ControlPlaneDatabase.openJdbcFile`; `ClaimOrReturnConformanceTest.singleWriter_rejectsNonControlPlaneRole` asserts `assertWriterAllowed("app-ui")` throws and `WRITER_ROLE` passes.

### D. Model store: control-plane filesystem writer only (production)

| Path | Evidence |
|---|---|
| `data/model-store/.../ModelStorePorts.kt` | “Only runtime control plane may write (ADR-010). Paths stay internal — clients never receive filesystem paths.” |
| `data/model-store/.../ModelStoreModule.kt` | “Only runtime control plane may write (ADR-010)”; `createFilesystemPort` comment: “Call only from runtime control plane”. |
| `data/model-store/.../FilesystemModelStorePort.kt` | “Control-plane only (ADR-010 / INV-001). Construct from `:android:runtime-service` attach path…” |
| `data/model-store/.../FilesystemQuarantineStore.kt` | “**Single writer only** — construct inside runtime control plane (ADR-010).” |
| `android/runtime-service/.../RuntimeControlPlane.kt` | `val modelStore = ModelStoreModule.createFilesystemPort(filesRoot = AndroidStorageRoots.filesRootPath(...))` then `ModelManagerModule.createDurableControlPlane(..., modelStore = modelStore, ...)`. |
| `android/runtime-service/.../AndroidStorageRoots.kt` | `modelStore` / `filesystemModelStore` / `quarantineStore` documented “Control-plane only (ADR-010)”; “Must not be constructed from UI / worker / isolated / companion”. |

Grep of production Kotlin under `android/**/src/main`: only `RuntimeControlPlane.kt` and `AndroidStorageRoots.kt` call `ControlPlaneDatabase.open` / `ModelStoreModule.createFilesystemPort` / `FilesystemModelStorePort.create`.

### E. Process attach gate (L2 wiring)

| Path | Evidence |
|---|---|
| `RuntimeControlPlane.attach` | `check(ProcessIdentity.isRuntimeProcess())` + `SingleWriterPolicy.assertWriterAllowed(WRITER_ROLE)` before DB/model-store/security construction; comment: “Role string alone is insufficient — UI/worker processes must never attach.” |
| `ProcessIdentity.kt` | “Only the `:runtime` process may construct the control plane, open domain DB writers…” |
| `ProcessNames.kt` | Runtime process name ends with runtime suffix; same-UID workers are crash containment, not security sandbox. |
| `OmniApplication.kt` | Runtime process → `RuntimeProcessBootstrap.onRuntimeProcessCreate` (attach); main UI → no control plane / no domain DB writer. |
| Services (`RuntimeForegroundService`, `RuntimeBindingService`, `AdminBindingService`, `TransferService`) | Each `onCreate`: `check(ProcessIdentity.isRuntimeProcess())` then `RuntimeControlPlane.attach`. |
| Manifest | `android/runtime-service/.../AndroidManifest.xml`: runtime services `android:process=":runtime"`. |

### F. Module dependency fail-closed (no UI/worker/engine writers)

| Path | Evidence |
|---|---|
| `tools/ci/check_dependency_edges.py` | Forbidden: `app-ui`/`workers`/`companion-sandbox`/`parser-isolated` → `:data:persistence` / `:data:model-store`; engines → `:data:*`. |
| Executed this audit | `python tools/ci/check_dependency_edges.py` → **`check_dependency_edges: OK`** (warnings only; see residual). |
| `android/workers/build.gradle.kts` | “never :data:persistence or token stores”; deps: core + native + llama only. |
| `android/app-ui/src/main/...` | Grep: no `ControlPlaneDatabase` / `SqlDelightSecret` / `FilesystemModelStorePort` usage; `ModelHubLocalImporter` comment: “UI never writes model-store / DB (INV-001 / ADR-010).” |
| `AGENTS.md` | “Only runtime control plane may write (ADR-010). Workers receive FDs / narrow IPC — never open secrets or catalog writable paths.” |

### G. Feature packs host writers only when plane-injected

| Path | Evidence |
|---|---|
| `features/modelhub/.../AcquisitionPipeline.kt` | Uses injected `modelStore`; may cast to `FilesystemModelStorePort` — runs as control-plane-hosted pack, not a second process writer. |
| `features/modelhub/build.gradle.kts` | `implementation(:data:model-store)` (not `api`) so UI classpath does not re-export model-store types by design. |
| `features/tools/.../DurableToolProposalLedger.kt` / content-report durable ports | Comment: UI never holds control-plane DB; stores come from plane attach. |

---

## counter_evidence

Searched for a **second production writer** of secrets / tokens / model-store durable state outside the control plane. Results:

| Probe | Result | Falsifies claim? |
|---|---|---|
| Grep `ControlPlaneDatabase.open*` under `android/**/src/main` | Only `RuntimeControlPlane.attach` | No |
| Grep `createFilesystemPort` / `FilesystemModelStorePort.create` under `android/**/src/main` | Only attach + `AndroidStorageRoots` helpers | No |
| Grep UI main sources for persistence / model-store writers | Empty (comments only) | No |
| Workers / companion / parser-isolated main deps on `:data:persistence` or `:data:model-store` | Absent; CI forbids | No |
| Engines → `:data:*` | CI forbids; edge check OK | No |
| Alternate token durable store (SharedPreferences / Room / DataStore writers) | Grep `SharedPreferences`/`DataStore`/`Room.`/`openOrCreateDatabase` in `*.kt` production paths: **no** domain token/secret vault (only policy comments + `getDatabasePath` for control-plane DB name) | No |
| `LoopbackTokenService` default `InMemorySecretBroker` | Exists for tests / default ctor; **production** `GatewayLifecycle.ensureTokenAndHandler` injects `plane.securityStack.tokenService` | Does not establish a second production durable vault |

Nothing found that implements a **shipping** second durable writer for secrets, tokens, or model-store.

---

## residual

These weaken **hardness** of enforcement; they do **not** show a second implemented production writer:

1. **`SingleWriterPolicy` is a soft role string**  
   `assertWriterAllowed` only checks `role == "runtime-control-plane"`. Callers can pass the default `WRITER_ROLE`. Hard process gate is on `RuntimeControlPlane.attach` / services, **not** inside every `ControlPlaneDatabase.open` or filesystem model-store constructor. Docs agree: same-UID “do not write” is **convention** (ARCH-PRINCIPLES §5).

2. **Model-store factories do not call `assertWriterAllowed`**  
   `FilesystemModelStorePort.create` / `FilesystemQuarantineStore` rely on documentation + host discipline. Contrast: SQL secret/claim stores call `assertWriterAllowed` in `init` / `open`.

3. **Same-UID shared private storage**  
   `:runtime` and `:engine_worker` share App UID and can reach the same `filesDir` / DB path at the OS level. Workers currently **lack** writer modules on classpath (CI + gradle). If a future edge re-added `:data:*` to workers without process checks, ADR-010 would break. Companion different-UID path is separate (claim #6).

4. **Classpath / merge residuals (CI warnings)**  
   - `WARN :features:modelhub → :data:model-store` (feature pack can type-cast and write when given a port).  
   - `WARN :android:app-ui → :android:runtime-service` (APK merge; writer classes exist in the package; UI process must not attach — enforced by `ProcessIdentity`, not by class absence).

5. **`TokenService` / `LoopbackTokenService` defaults to in-memory stores** when not injected — safe only because production gateway injects the plane stack. Mis-wiring could yield a non-durable second logical issuer (still control-plane process if called from gateway, but not the SQLite sole vault).

6. **Not validated here**  
   - Runtime exclusive SQLite connection policy / multi-process DB open under kill tests.  
   - Device adversarial “compromised same-UID code opens DB” (out of scope; docs exclude as security control).  
   - Full product L3 journey proof beyond software attach wiring.

---

## Level map (for audit consumers)

| Layer | Secrets | Tokens | Model store |
|---|---|---|---|
| **L1 module exists** | PASS — `SqlDelightSecretLedgerStore`, schema tables | PASS — `AccessTokenStore` + `TokenService` | PASS — `:data:model-store` |
| **L2 wired to control plane** | PASS — attach → DB.secrets → SecurityFactory | PASS — stack + Gateway inject | PASS — attach → `createFilesystemPort` → ModelManager |
| **L3 product journey software-complete** | PASS for sole-writer attach path | PASS for sole-writer attach path | PASS for sole-writer attach path |
| **OS multi-UID exclusive write** | N_A / residual (same-UID convention) | same | same |

---

## Grep / read inventory (empty-finding discipline)

**Docs package read/grep:** `governance/adr/ADR-010.md`, `docs/40-domain-data/data-ownership-persistence.md`, `docs/20-architecture/architecture-principles.md`, `docs/30-core-platform/model-platform.md`, `docs/50-security-reliability/auth-network-secrets.md`, `specs/database/migration-policy.yaml`, `specs/database/omnillm-schema.sql` (token/secret tables).

**Monorepo grep patterns:** `ADR-010|SingleWriter|assertWriterAllowed|ControlPlaneDatabase|SqlDelightSecret|AccessTokenStore|ModelStoreModule|createFilesystemPort|FilesystemModelStorePort|createSecurityStack|isRuntimeProcess|SharedPreferences|DataStore|Room\.|openOrCreateDatabase` across `*.kt` / manifests / CI.

**Monorepo files read (non-exhaustive of hits):** `SingleWriterPolicy.kt`, `ControlPlaneDatabase.kt`, `SqlDelightSecretLedgerStore.kt`, `RuntimeControlPlane.kt` (attach), `ControlPlaneSecurityFactory.kt`, `AndroidStorageRoots.kt`, `ModelStoreModule.kt`, `FilesystemModelStorePort.kt`, `FilesystemQuarantineStore.kt`, `TokenService.kt`, `LoopbackTokenService.kt`, `GatewayLifecycle.kt`, `ProcessIdentity.kt`, `ProcessNames.kt`, `OmniApplication.kt`, `check_dependency_edges.py`, workers/app-ui/modelhub gradle, secret ledger + single-writer tests.

**CI executed:** `python tools/ci/check_dependency_edges.py` → OK (3 warnings listed under residual).

---

## Final

```yaml
claim: "Single writer ADR-010 holds for secrets tokens model store"
real: true
reason: >
  Production durable secrets, token verifiers, and model-store FS writers are
  opened only on RuntimeControlPlane.attach in the :runtime process, with
  SingleWriterPolicy + process identity + module dependency gates; no second
  production durable writer found for these three surfaces.
```
