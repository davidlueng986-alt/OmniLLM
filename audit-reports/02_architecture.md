# 02_architecture — Architecture Invariants INV-001..020 & ADRs 001–012

| Field | Value |
|-------|--------|
| **Artifact** | `02_architecture.md` |
| **Role** | Independent architecture invariant / ADR enforcement audit |
| **Audit date (UTC host)** | 2026-08-12 |
| **Docs package (authority)** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Primary sources** | `docs/20-architecture/*`, `governance/adr/ADR-001..012`, `docs/20-architecture/architecture-invariants.md` |
| **Monorepo (under audit)** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Out dir** | `...\omnillm-android\audit-reports` |
| **Method** | `read_file` / `grep` / `list_dir` / run `tools/ci/check_*.py` — fail-closed |
| **Status labels** | `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN` only |

> Specs under docs package `specs/` take precedence over prose when they conflict.  
> Status `PASS` requires concrete path + quote/symbol. No device / Play / OEM matrix claims.

---

## Executive summary

| Area | Result |
|---|---|
| **INV-001..020** | **18 PASS**, **2 PARTIAL** (INV-009 residual device isolation evidence; INV-020 field-authority drift risk) |
| **ADR-001..012** | **11 PASS**, **1 PARTIAL** (ADR-007 companion topology present; multi-UID adversarial device proof not in-repo as executed evidence) |
| **Forbidden edges (CI hard)** | **PASS** — both dependency gates exit 0 |
| **Forbidden edges (soft debt)** | Documented WARN: UI→runtime-service merge, engines:api SPI on UI classpath via features, modelhub→model-store |
| **HTTP selects engines** | **No evidence** of engine selection in `:interfaces:http` (grep empty) |

### Overall architecture posture

The monorepo encodes the NEW docs package invariants at **L1 (modules exist)** and largely at **L2 (wired control plane + CI boundaries + unit/contract tests)**. Key product journeys remain out of scope for this artifact (see other audit numbers for L3).

Process topology matches design:

- UI main process (`:android:app-ui`)
- Control plane `:runtime` (`:android:runtime-service`)
- Crash worker `:engine_worker` / isolated `:parser`
- Separate-UID companion `com.omnillm.companion`

---

## Authority anchors (docs)

| ID | Path | Quote (abbrev.) |
|---|---|---|
| `ARCH-INVARIANTS` | docs package `docs/20-architecture/architecture-invariants.md` | INV-001…020 table (UI no native/DB; Plan/Reserve/Commit; single authority; etc.) |
| `ARCH-PRINCIPLES` | `…/architecture-principles.md` | Plan→reserve→commit→execute; single writer; trust≠compat≠perf |
| ADR-001…012 | `governance/adr/ADR-00N.md` | Capability universe; PRCE; Reservation≠AllocationHandle; client IDs; CommitId; SSE/ACK; companion UID; identity layers; trust dims; single writer; transport parity; portable core |

---

## CI / module-graph enforcement (cross-cutting)

### Commands executed (this audit)

```text
python tools/ci/check_module_dependency_rules.py  → OK hard rules; 3 soft WARN
python tools/ci/check_dependency_edges.py         → OK; 3 soft WARN
```

### Hard rules covered

| Rule | Tool | Outcome |
|---|---|---|
| `:android:app-ui` ↛ `:data:*` / `:engines:*` (direct + native pack API path) | `check_module_dependency_rules.py`, `check_dependency_edges.py` | OK |
| `:engines:*` ↛ `:data:persistence` / `:data:model-store` | both | OK |
| Feature/interface ↛ `:data:persistence` (main configs) | both | OK |
| Companion ↛ data/runtime/engines/http/admin | `check_module_dependency_rules.py` | OK (module present; deps narrow) |
| Workers / parser ↛ domain DB writers | `check_dependency_edges.py` FORBIDDEN list | OK |

### Soft debt (WARN — not hard fail)

| Finding | Path evidence |
|---|---|
| `:features:modelhub` → `:data:model-store` | CI WARN; ARC-02 documented debt |
| API-path `:android:app-ui` → features → `:runtime:orchestrator` → `:engines:api` | SPI types reach UI classpath (no native pack) |
| `:android:app-ui` → `:android:runtime-service` | Process/manifest merge; CI warns ensure no UI-process control-plane attach |

**app-ui production deps** (`android/app-ui/build.gradle.kts`): `runtime-service`, `interfaces:aidl`, `interfaces:admin`, `core:{canonical,contracts,errors}`, feature packs — **no** `:engines:*` or `:data:*`.

**companion deps** (`android/companion-sandbox/build.gradle.kts`): only `:core:canonical`, `:core:errors`; `applicationId = "com.omnillm.companion"`.

---

## INV-001..020 detail

### INV-001 — UI process does not load native engines; does not write DB / model store

| | |
|---|---|
| **Status** | **PASS** (with residual soft debt documented) |
| **Doc** | UI process 不載入 native engine、不直接寫 DB／model store |
| **Enforcement** | Module graph CI; process manifests; source greps; SingleWriterPolicy |

**Evidence**

1. **Gradle / CI** — hard dependency rules pass (commands above). FORBIDDEN edges in `tools/ci/check_dependency_edges.py` lines 32–54 include `app-ui → engines:*` and `app-ui → data:*`.
2. **app-ui source** — grep `System.loadLibrary|loadLibrary|native fun` under `android/app-ui` → **no matches**. Grep `Room|SQLite|ControlPlaneDatabase|SqlDelight` under `android/app-ui` → **no matches**.
3. **Process split** — `android/runtime-service/src/main/AndroidManifest.xml`: services on `android:process=":runtime"`; app-ui manifest comment: “Main UI process only… Runtime services merge… into process :runtime”.
4. **Policy marker** — `core/ports/.../SingleWriterPolicy.kt`: forbidden roles include `"app-ui"`; `assertWriterAllowed` only allows `"runtime-control-plane"`.
5. **UI code comments** — e.g. `android/app-ui/.../ModelHubLocalImporter.kt`: “UI never writes model-store / DB (INV-001 / ADR-010)”.

**Residual (does not demote hard PASS for declared edges)**

- Soft API path of `:engines:api` types via feature→orchestrator (SPI only; CI soft WARN).
- Manifest merge of `:runtime-service` into app APK is intentional; services still declare `:runtime` process — process-attach correctness is code convention + FGS host, not a second module edge fail.

---

### INV-002 — High-cost / domain mutation ops must complete reproducible Plan + multi-dim Reservation first

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 高成本或 domain mutation 必須先 Plan 與多維 Reservation |
| **Enforcement** | Contracts + orchestrator + governor |

**Evidence**

- `core/contracts/src/main/kotlin/.../PlanCommitContracts.kt` — `Plan`, `Commit`, `PreparedOperation` types; module comment ADR-002.
- `runtime/orchestrator/.../OrchestratorPorts.kt` — `planInference` → `commitInference` → execute ports.
- `runtime/governor/.../ResourceGovernor.kt` — reserve / convert / release multi-dimension ledger.
- Tests: `OrchestratorPipelineTest`, `ResourceGovernorConcurrencyTest`, `CommitBindingReconcileTest`.

---

### INV-003 — Plan does not mutate Session/KV, model lifecycle, persistent state, or resource allocation

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | Plan 不修改 Session/KV、model lifecycle、persistent state 或 resource allocation |
| **Enforcement** | Pure `Plan` type (no reservation/allocation fields); planner purity tests |

**Evidence**

- `Plan` data class (`PlanCommitContracts.kt`): fields are ids, digests, envelope, epochs, expiry — **no** `reservationId` / `allocationHandleId`.
- `CommitBindingReconcileTest.kt` comment: “ADR-002: Plan has no reservationId / allocationHandleId.”
- `CandidatePlanner.plan` returns planning/rejection only; allocation is governor `convert` after reserve.
- AGENTS.md: “Plan has **no** domain mutation (ADR-002 / INV-002–003).”

---

### INV-004 — Commit has `commitId` bound to request, owner, plan, lease, epoch; retryable / queryable

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | Commit 具 commitId，綁 request、owner、plan、lease、epoch |
| **Enforcement** | `Commit` type + durable commit ledger + reconcile tests |

**Evidence**

- `Commit` (`PlanCommitContracts.kt`): `commitId`, `planId`, `requestId`, `principalId`, `reservationId`, `revisionLeaseId`, `runtimeEpoch`, `revocationEpoch`, …
- `PreparedOperation` binds same dimensions + optional `allocationHandleId`.
- Persistence: `data/persistence/.../SqlDelightCommitLedgerStoreTest.kt` — findByCommitId, prepared/bindings.
- Runtime: `runtime/request-registry/.../CommitLedger.kt`, `CommitLedgerRecoveryTest.kt`.
- Specs: monorepo `specs/runtime-recovery-fixtures.yaml` — query CommitId / no blind replay.

---

### INV-005 — Request terminal releases temporary reservation only; resident model/KV charged via AllocationHandle until reclaim

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | terminal 只釋放 temporary reservation；常駐由 AllocationHandle 計費 |
| **Enforcement** | Governor separate release vs allocation; conservation |

**Evidence**

- `ResourceGovernor.releaseReservation` KDoc: “Resident AllocationHandle charges are unaffected (INV-005 / ADR-003).”
- `convert` path builds `AllocationHandle` from reservation remainder (`ResourceGovernor.kt` ~260–288).
- `core/resource/.../Conservation.kt` — capacity conservation `reserved + allocated + free`.
- Tests: `ResourceConservationPropertyTest`, `ResourceConservationTest`.
- Session records carry `allocationHandleId` (`SessionDescriptor.kt`).

---

### INV-006 — SSE socket write ≠ client-delivered checkpoint; AIDL only via application ACK

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | SSE write 不建立 client-delivered checkpoint；AIDL 只由 application ACK |
| **Enforcement** | DeliverySemantics + tests |

**Evidence**

- `runtime/session/.../DeliverySemantics.kt`:
  - `SseStatelessByDefault` → `establishesClientDeliveredCheckpoint() = false`
  - `AidlApplicationAck` → `true`
- `InMemorySessionManager.kt` comment INV-006 / ADR-006.
- Tests: `TransportParityTest.deliveryGuarantees_areExplicitAndDifferByTransport`, `SseDisconnectClaimSemanticsTest`, `StreamDeliveryAckCreditTest`.

---

### INV-007 — Sessions with partial mutation / untrusted epoch must not re-enter pool

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 可能部分 mutation 或 epoch 不可信的 Session 不得回池 |
| **Enforcement** | SessionPoolPolicy + poison/orphan managers + negative tests |

**Evidence**

- `SessionPoolPolicy.kt`: POISONED never eligible; revocationEpoch fence; “POISONED / ORPHANED / partial-mutation uncertainty never re-pool (INV-007).”
- `SessionManager.poison` KDoc: “Never returns to pool (INV-007).”
- `DurableSessionManager`: after restart free pool not auto re-hydrated for poisoned.
- Tests: `PoisonedSessionNotReusedNegativeTest`, `PoisonedSessionSurvivesReloadTest`, `SessionManagerTest.pool_revocationEpochFence`.

---

### INV-008 — Source evidence decides privileged placement; dry-load / benchmark do not elevate source trust

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 來源證據決定 privileged placement；dry-load／benchmark 不提升來源信任 |
| **Enforcement** | EvaluationDimensions separation; re-verify ignores dry-load; engine stubs |

**Evidence**

- `runtime/model-manager/.../EvaluationDimensions.kt` (ADR-009 / INV-008): authenticity / license / compatibility / performance / placement separated; “Dry-load / benchmark / compatibility success must **not** promote authenticity.”
- `DefaultPrivilegedLoadReverify.kt`: “Dry-load / benchmark evidence is never consulted (INV-008).”
- Engine stubs (llama-cpp, litert, mlc, mllm, ort-genai): “Dry load never elevates model trust (INV-008).”
- `engines/api/.../OmniEngine.kt`: probe “Does not elevate trust or mark capabilities SUPPORTED (INV-008).”

---

### INV-009 — Untrusted native accelerated execution does not share writable UID with privileged app data / secrets

| | |
|---|---|
| **Status** | **PARTIAL** |
| **Doc** | 不受信任 native accelerated execution 不與 privileged app data／secrets 共用可寫 UID |
| **Enforcement** | Companion separate package/UID + dependency isolation **present**; full multi-UID device adversarial run **not evidenced as executed in-repo** |

**Evidence (structure — L1/L2)**

- `android/companion-sandbox/build.gradle.kts`: `applicationId = "com.omnillm.companion"`; deps only core canonical/errors.
- Manifest: no `sharedUserId`; `BIND_SANDBOX` signature permission; package differs from `com.omnillm`.
- CI forbids companion → data/runtime/engines.
- `CompanionIsolationPolicyTest` documents required adversarial instrumentation steps and static checks (`companionPackageDiffersFromMainApp`).

**Gap (why PARTIAL, not PASS)**

- Test file itself frames “Full different-UID adversarial instrumentation… ” as procedure / residual; this audit found **no** committed log/report proving companion process cannot open main-app private paths on a real multi-UID install. Fail-closed: no executed device evidence ⇒ not full PASS for security isolation claim.

---

### INV-010 — Privileged load re-verifies opened FD content identity, signature chain, revocation each time

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | Privileged load 每次驗證 FD content identity、signature chain、revocation |
| **Enforcement** | DefaultPrivilegedLoadReverify + ReadyContentPort |

**Evidence**

- `DefaultPrivilegedLoadReverify.kt` steps 1–5: open FDs → `verifyOpenFds` → signatureChainOk → revocationOk → placement; fail-closed tickets.
- `data/model-store/.../FilesystemReadyContentPort.kt` — open read-only FDs + verify digests (INV-010).
- `RuntimeGgufModelSourceResolver.kt` — “INV-010: content identity re-verified against actual opened files.”
- ModelHub tests assert re-verify fail-closed gates admission.

---

### INV-011 — AIDL principal from calling UID / Android user + verified binding; never self-reported package

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | AIDL principal 以 calling UID／Android user 與受驗證 binding；不信任自報 package |
| **Enforcement** | PrincipalObservation + negative security tests |

**Evidence**

- `PrincipalObservation.kt`: `Binder.getCallingUid()` / pid / userId; package list is “Display candidates only — not an authorization key.”
- `OmniRuntimeFacade.kt` KDoc: Principal = observed calling UID + ClientRegistration.
- `AidlCallerNegativeSecurityTest` — spoofed package / uid mismatch paths.
- `interfaces/aidl/.../AidlContractNotes.kt` INV-011 note.
- HTTP: `HttpPrincipal.kt` — “never from caller self-reported headers alone.”

---

### INV-012 — Client creates requestId / idempotencyKey before send; server claim-or-return

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | client 送出前建立 requestId／idempotencyKey；服務 claim-or-return |
| **Enforcement** | RequestIdentity + registry + UI generators |

**Evidence**

- `RequestIdentity` (`PlanCommitContracts.kt`): client generates ids; claim-or-return commentary.
- `runtime/request-registry` — `ClaimOutcome`, `RequestRegistryTest` claim-or-return.
- UI: `AdminFeatureProjections` / `ModelHubLocalImporter` mint `UUID` commandId + client `idempotencyKey` before admin commands.
- Admin: `AdminApiService` requires non-blank idempotencyKey; claim via JobManager / command ledger.

---

### INV-013 — Public HTTP / AIDL / UI project same canonical capability, request, event, error; transport guarantees explicit & may differ

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 公開 HTTP／AIDL／UI 投影同一 canonical…；transport guarantee 可不同且必須明示 |
| **Enforcement** | TransportParityTest + error mapping + delivery guarantee enums |

**Evidence**

- `TransportParityTest` (INV-013 / ADR-011): sample OmniErrorCode set projects equal wire codes on HTTP DTO, AIDL Admin mapper, AdminCommandResult; delivery guarantees differ and are explicit.
- `core/errors` `TransportDeliveryGuarantee` mirrored by runtime-service.
- Interfaces: `:interfaces:http`, `:interfaces:aidl`, `:interfaces:admin` all consume shared error/canonical modules.

**HTTP engine selection check**

- Grep `selectEngine|engineSelection|EngineSelection|routeEngine` under `interfaces/` → **no matches**. Engine routing lives in `:runtime:orchestrator` / features routing — not transport adapters (AGENTS.md rule).

---

### INV-014 — Blob, ArtifactPackage, ModelRevision, Installation, Alias are distinct identities

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 不同身分，不互相代替 |
| **Enforcement** | `core:identity` + golden vectors |

**Evidence**

- Distinct types: `BlobId`, `ArtifactPackageId`, `ModelRevisionId`, `InstallationId` (UUID) in `core/identity` + `core/canonical/generated`.
- Golden tests: `GoldenIdentityEncodingTest` (identity + canonical modules) — permutation rule for package entries; InstallationId UUID parse separate from digests.
- AGENTS.md: `:core:identity` — “Blob / ArtifactPackage / ModelRevision / Installation identities (ADR-008).”

---

### INV-015 — Digests lower-case hex in canonical objects; collections have total order

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | digest lower-case hex；集合 total order |
| **Enforcement** | IdentityHashing + ArtifactPackage canonicalizer + golden tests |

**Evidence**

- `IdentityHashing.sha256Hex`: `"%02x".format(b)` (lower-case); catalog rule comment: “Digests are lower-case hex without prefix.”
- `ArtifactPackageEntry` / canonicalizer normalize order before hash; rejects duplicate roles.
- Golden vectors `specs/golden-vectors/canonical-encoding.yaml` exercised by unit tests.
- BlobId upper-case input accepted then normalized in identity validation tests.

---

### INV-016 — Each measurement result traces to full profile, run, method version, raw/sketch evidence

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | measurement result 可回到完整 profile、run、method version 與 raw／sketch evidence |
| **Enforcement** | Benchmark domain models + report builder |

**Evidence**

- `MeasurementRun`: `runId`, `profileId` (64-hex), `runSeq`, metrics, outcome, environmentSnapshot.
- `MeasurementMetrics` / `LatencySketch`: required `methodVersion`; optional `sketchDigest` (64-hex); evidence labels.
- `BenchmarkReportBuilder` requires runs match profile; exports profileId + runs.
- ProfileComparison / sketch tests under `features/benchmark`.

---

### INV-017 — Token / trust / ACL / risk revocation bumps epoch and fences active / queued / pooled state

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 撤銷 bump epoch 並 fence active／queued／pooled |
| **Enforcement** | RevocationEpochManager + session pool fence + security tests |

**Evidence**

- `runtime/policy` `RevocationEpochManager` + `RevocationFenceHooks`; `TokenServiceTest.revoke_fencesEpoch_andRejectsAuth`.
- `RevokedTokenNegativeSecurityTest` — Q-007 principal epoch bump fences tokens.
- Session pool: `SessionPoolPolicy` rejects `record.descriptor.revocationEpoch < query.revocationEpoch`.
- Commit bindings include `revocationEpoch`; mismatch → `IDEMPOTENCY_CONFLICT` (`CommitBindingReconcileTest`).

---

### INV-018 — Unknown capability, unknown cancellation, or unknown memory envelope must not default to safe/supported

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | 未知 capability／cancellation／memory envelope 不能默認為安全／支援 |
| **Enforcement** | Planner fail-closed; FSM fail-closed; cost/envelope labels |

**Evidence**

- `CandidatePlanner.kt`: `CapabilityState.UNKNOWN` → reject `"capability unknown (fail closed)"`.
- `UnknownCapabilityNegativeTest` — INV-018 explicit.
- `StateMachineDriver` / `TransitionOutcome` — unknown edges fail closed.
- `CostClassLabels` — unknown label `error("unknown cost class (fail closed)")`.
- Admin operation kinds: unknown kinds fail closed (`AdminOperationKinds`).

---

### INV-019 — Any fallback must be allowed by caller policy and report actual revision / engine / backend

| | |
|---|---|
| **Status** | **PASS** |
| **Doc** | fallback 必須由 caller policy 允許並回報實際 revision／engine／backend |
| **Enforcement** | FallbackPolicy in orchestrator + actualRouting |

**Evidence**

- `FallbackPolicy` enum used in `CandidatePlanner.orderByFallbackPolicy` (NONE / SAME_REVISION_ONLY / ALLOW_LIST).
- `FallbackPolicyOrchestratorTest` / `OrchestratorPipelineTest.fallbackAllowList_usesSecondary_andReportsUsedFallback`:
  - asserts `result.actualRouting.usedFallback`, `backend`, `modelRevisionId`, `fallbackPolicy`.
- Policy-disallowed fallbacks do not execute secondary revision.

---

### INV-020 — Each data field has one authoritative source; other docs only explain or project

| | |
|---|---|
| **Status** | **PARTIAL** |
| **Doc** | 同一資料欄位只有一個權威來源 |
| **Enforcement** | Specs catalogs + authority-map + AGENTS authority order; **cross-package ID drift residual** |

**Evidence (positive)**

- Docs package `governance/authority-map.md` + `specs/*` as machine-readable SSOT.
- Monorepo `AGENTS.md` authority order: specs → product docs → AGENTS → implementation notes.
- Codegen / catalogs: error codes, state machines, OpenAPI, AIDL, SQL from `specs/`.
- Module comments repeatedly cite single catalog IDs.

**Gap (why PARTIAL)**

- Inventory `00_MAP.md` already recorded **FEAT-AI-REPORTING** (docs package `specs/feature-capability-map.yaml`) vs **FEAT-AI-CONTENT-REPORT** (prose + monorepo specs/module). Specs-vs-prose / monorepo-copy drift is exactly an INV-020 risk: two labels for one product field without a reconciled authority alias table in code.
- No automated “field→single owner” linter in monorepo CI equivalent to docs `validate_repository.py` authority checks (docs package has validator; monorepo relies on copied specs + agent discipline).

---

## Resource conservation (architecture-invariants §2)

| | |
|---|---|
| **Status** | **PASS** (implements formula via ledger helpers) |
| **Evidence** | `core/resource/Conservation.kt` capacity ledger; `ResourceGovernor` convert/release barriers; property tests |

Not a separate INV-ID but normative in same document; governor refuses planned eviction pre-credit per comments/tests (eviction after barrier).

---

## ADR-001..012 alignment

| ADR | Decision (abbrev.) | Status | Primary monorepo evidence |
|---|---|---|---|
| **ADR-001** | Capability universe + runtime negotiation | **PASS** | Capability catalogs in `specs/`; `CandidatePlanner` states SUPPORTED/CONDITIONAL/UNKNOWN/UNSUPPORTED; unknown fail-closed; no engine-private uncataloged capabilities as product surface |
| **ADR-002** | Plan → Reservation → Commit → Execute | **PASS** | `core:contracts` + `runtime:orchestrator` + governor (see INV-002/003) |
| **ADR-003** | Reservation ≠ AllocationHandle | **PASS** | Distinct types; governor convert/release; INV-005 |
| **ADR-004** | Client-generated RequestId + IdempotencyKey | **PASS** | `RequestIdentity`; claim-or-return registry; UI generators (INV-012) |
| **ADR-005** | CommitId durable / queryable | **PASS** | Commit ledger + reconcile fixtures (INV-004) |
| **ADR-006** | SSE stateless-by-default; AIDL application ACK | **PASS** | `DeliverySemantics` (INV-006) |
| **ADR-007** | Untrusted accel = different package/UID | **PARTIAL** | Companion module + packaging + CI isolation **PASS structure**; multi-UID device adversarial **not in executed evidence** (same as INV-009) |
| **ADR-008** | Blob / Package / Revision / Installation layering | **PASS** | `core:identity` + golden encoding (INV-014/015) |
| **ADR-009** | Trust / compat / performance / license / placement separated | **PASS** | `EvaluationDimensions` + placement filters (INV-008) |
| **ADR-010** | Runtime control plane single writer | **PASS** | `SingleWriterPolicy`; only runtime-service opens persistence; engines/UI/workers forbidden edges; ADR-010 comments throughout data/* |
| **ADR-011** | HTTP / AIDL / Admin share canonical semantics | **PASS** | `TransportParityTest` (INV-013) |
| **ADR-012** | Portable Core for future iOS/PC/IoT | **PASS** | `core/*` pure Kotlin; grep `import android` under `core/` → **no matches**; platform types confined to `android/*` adapters |

---

## Forbidden edges audit (explicit greps)

| Check | Pattern / method | Result |
|---|---|---|
| UI imports engines native | `loadLibrary|native fun` in `android/app-ui` | **No matches** |
| UI opens Room/SQLDelight | DB symbols in `android/app-ui` | **No matches** |
| Engine → Room/data | CI hard + engines build.gradle | **No hard edges** |
| HTTP selects engines | engine selection symbols in `interfaces/http` | **No matches** |
| Feature → persistence writers | CI hard (`:data:persistence`) | **OK** |
| Feature → model-store | CI soft WARN (`:features:modelhub`) | **Debt** |
| Companion privileged deps | companion `build.gradle.kts` | **Only core:canonical, core:errors** |

---

## L1 / L2 / L3 distinction (this audit)

| Level | Meaning | Architecture outcome |
|---|---|---|
| **L1** | Module exists | **PASS** — 44 Gradle modules; topology matches settings.gradle.kts comments INV-001 / single writer |
| **L2** | Wired to control plane + tests/CI | **PASS** for most INVs; **PARTIAL** INV-009/ADR-007 device isolation proof; **PARTIAL** INV-020 authority drift |
| **L3** | Product journey software-complete | **Out of scope** for this artifact (do not claim Playground/ModelHub E2E architecture PASS here) |

---

## Scoreboard

### Invariants

| ID | Status |
|---|---|
| INV-001 | **PASS** |
| INV-002 | **PASS** |
| INV-003 | **PASS** |
| INV-004 | **PASS** |
| INV-005 | **PASS** |
| INV-006 | **PASS** |
| INV-007 | **PASS** |
| INV-008 | **PASS** |
| INV-009 | **PARTIAL** |
| INV-010 | **PASS** |
| INV-011 | **PASS** |
| INV-012 | **PASS** |
| INV-013 | **PASS** |
| INV-014 | **PASS** |
| INV-015 | **PASS** |
| INV-016 | **PASS** |
| INV-017 | **PASS** |
| INV-018 | **PASS** |
| INV-019 | **PASS** |
| INV-020 | **PARTIAL** |

**Counts:** PASS 18 · PARTIAL 2 · MISSING 0 · N_A 0 · BLOCKED_HUMAN 0

### ADRs

| ID | Status |
|---|---|
| ADR-001 | **PASS** |
| ADR-002 | **PASS** |
| ADR-003 | **PASS** |
| ADR-004 | **PASS** |
| ADR-005 | **PASS** |
| ADR-006 | **PASS** |
| ADR-007 | **PARTIAL** |
| ADR-008 | **PASS** |
| ADR-009 | **PASS** |
| ADR-010 | **PASS** |
| ADR-011 | **PASS** |
| ADR-012 | **PASS** |

**Counts:** PASS 11 · PARTIAL 1

---

## Residual risks / follow-ups (not auto-PASS)

1. **INV-009 / ADR-007** — Capture executed multi-UID instrumentation evidence (companion cannot open main private storage / token vault) under audit or e2e artifacts.
2. **INV-020** — Reconcile `FEAT-AI-REPORTING` vs `FEAT-AI-CONTENT-REPORT` (docs package specs win); re-sync monorepo `specs/feature-capability-map.yaml`.
3. **INV-001 soft debt** — Narrow UI classpath so `:engines:api` SPI does not transit via feature→orchestrator; keep Admin projections only.
4. **ARC-02** — Host model-store types only via runtime control-plane ports; remove `:features:modelhub` → `:data:model-store` edge.
5. **UI → runtime-service merge** — Keep process attributes audited on every manifest change; never attach control-plane writer construction on main process (UiSession already documents this).

---

## Grep / read footprint (empty-findings discipline)

| Target | Patterns / files |
|---|---|
| Docs | `architecture-invariants.md`, `architecture-principles.md`, `docs/20-architecture/README.md`, ADR-001…012 |
| CI | `tools/ci/check_module_dependency_rules.py`, `check_dependency_edges.py` (executed) |
| Module graphs | `settings.gradle.kts`, `android/app-ui/build.gradle.kts`, companion, runtime-service deps |
| INV-001 | app-ui native/DB greps empty; manifests process=; SingleWriterPolicy |
| INV-002…005 | PlanCommitContracts, ResourceGovernor, Conservation, commit ledger tests |
| INV-006 | DeliverySemantics, TransportParityTest, SSE disconnect tests |
| INV-007 | SessionPoolPolicy, poison negative tests |
| INV-008…010 | EvaluationDimensions, DefaultPrivilegedLoadReverify, FilesystemReadyContentPort |
| INV-011 | PrincipalObservation, AidlCallerNegativeSecurityTest |
| INV-012 | RequestIdentity, Admin/UI UUID keys, RequestRegistry |
| INV-013 | TransportParityTest; HTTP engine-select grep empty |
| INV-014…015 | core/identity + IdentityHashing + golden tests |
| INV-016 | MeasurementRun / LatencySketch / BenchmarkReportBuilder |
| INV-017 | RevocationEpochManager + pool epoch fence tests |
| INV-018…019 | CandidatePlanner UNKNOWN; FallbackPolicy + actualRouting tests |
| INV-020 | AGENTS authority order; FEAT-AI id conflict from 00_MAP |
| ADR-012 | `import android` under `core/` → none |
| Engines→data | CI hard OK |

---

## Completeness checklist

| Check | Status |
|---|---|
| INV-001…020 each has status + evidence or explicit gap | **Done** |
| ADR-001…012 each has status + evidence | **Done** |
| Forbidden edges grepped + CI run | **Done** |
| Fail-closed (no evidence ⇒ not PASS) applied to INV-009/020 | **Done** |
| No QUALIFIED/SUPPORTED engine claims; no device/Play PASS invented | **Done** |
| Artifact path | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\02_architecture.md` |

*End of 02_architecture.md.*
