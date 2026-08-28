# 11 — Contract Drift Audit

> Historical; CI policy as of `fix/ci-hermetic-gates`: hermetic root `check` has no digest/16kb/apk/sbom gates — artifact gates run post-assemble.

| Field | Value |
|---|---|
| Audit ID | `11_contracts` |
| Auditor role | Senior independent auditor (OmniLLM local edge, Android-first) |
| Product docs (design authority) | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| Implementation monorepo | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| Artifact path | `audit-reports/11_contracts.md` |
| Audit date | 2026-08-12 |
| Method | Live `list_dir` / file hash / Python YAML parse / codegen `--check` / greps on real paths only |
| Overall contract posture | **PARTIAL** — monorepo internal gates green; **docs package `specs/` and monorepo `specs/` are not synchronized** |

---

## 0. Executive summary

### Verdict

| Layer | Status | One-line evidence |
|---|---|---|
| Docs package formal-contract inventory | **PASS** | All core catalogs present under docs `specs/` (OpenAPI, AIDL YAML, canonical-types, state-machines, error-catalog, schema SQL) |
| Monorepo formal-contract inventory | **PASS** | Same core set present under monorepo `specs/` plus implementation fixtures/migrations |
| Docs ↔ monorepo `specs/` byte parity | **PARTIAL** | 14/33 shared files identical SHA-256; **19 diverged**; **0 only-in-docs**; **6 only-in-repo** |
| Monorepo lagging 新版本? | **N_A (inverted)** | Monorepo does **not** lag 新版本 for machine-readable contracts; monorepo is a **superset / evolved** set. 新版本 package is thinner on OpenAPI/AIDL/types/SQL/FSM CONTENT_REPORT/ACL/config/engines |
| Monorepo catalog → generated Kotlin drift | **PASS** | `python tools/codegen/generate_contracts.py --check` → `Contract drift check: OK` |
| Monorepo AIDL YAML → `.aidl` drift | **PASS** | `python tools/codegen/extract_aidl.py --check` → `AIDL drift check: OK (44 declarations)` |
| Packaged OpenAPI / SQL vs monorepo specs | **PASS** | SHA-256 match for OpenAPI + schema SQL + migration policy + `0001_to_0002.sql` |
| Private / transport aliases outside catalogs | **PARTIAL** | `LEGACY_ALIASES` in playground are **not** REQUEST FSM states; content-report scope rename vs docs; monorepo README still points at **Critical修正版** |
| Docs package `validate_repository.py` | **PARTIAL** | Ran successfully; **2 issues** (missing compiled design compendium referenced by validator/manifest) |
| Engine QUALIFIED/SUPPORTED claims in catalogs | **PASS (honest UNQUALIFIED)** | Both sides list all engines `qualificationStatus: UNQUALIFIED`; monorepo adds LOCKED/INTEGRATED notes without elevating to SUPPORTED |

### Hard rule outcomes

1. **Fail closed observed:** no PASS claimed without path + hash/quote evidence.
2. **No QUALIFIED/SUPPORTED elevation** found in either `engine-qualification-status.yaml`.
3. **L1/L2/L3 distinction:** this audit is **L1 contract artifacts + L2 codegen wiring**. Product journey software-complete (L3) is out of scope except where private aliases touch UI projection.
4. Compared against **新版本** package only (not prior audit memory). Monorepo `specs/README.md` still cites Critical修正版 — documented as authority-path drift.

---

## 1. Method and search evidence

### 1.1 Paths read / listed

| Path | Role |
|---|---|
| `…Product_Documents\specs\` (recursive) | Design authority catalogs |
| `…omnillm-android\specs\` (recursive) | Implementation contract copy |
| `…omnillm-android\tools\codegen\` | Generators + README |
| `…omnillm-android\build.gradle.kts` | `generateContracts`, `checkContractDrift`, `checkAidlDrift` |
| `…omnillm-android\core\{canonical,state,errors}\…\generated\` | Committed generated sources |
| `…omnillm-android\interfaces\aidl\src\main\aidl\ai\omnillm\api\` | 44 `.aidl` projections |
| `…omnillm-android\interfaces\http\src\main\resources\openapi\` | Packaged OpenAPI |
| `…omnillm-android\data\persistence\src\main\resources\db\` | Packaged SQL + migrations |
| `…Product_Documents\tools\validate_repository.py` | Package validator |
| `…Product_Documents\docs\30-core-platform\formal-contract-artifacts.md` | Drift rule authority |

### 1.2 Commands run (live)

```text
# SHA-256 inventory both specs trees (PowerShell Get-FileHash)
# Python YAML structural diffs (AIDL/OpenAPI/FSM/errors/types/ACL/config/SQL/engines)
# python tools/codegen/generate_contracts.py --repo-root <monorepo> --check  → OK
# python tools/codegen/extract_aidl.py --repo-root <monorepo> --check     → OK
# python …/tools/validate_repository.py <docs package root>              → issues=2
```

### 1.3 Greps / scans

- `generateContracts|checkContractDrift|extract_aidl|openapi` across gradle/md
- `OmniError(?:Code)?\.([A-Z_]+)` across `*.kt` (818 source files, excluding `build/`)
- alias patterns: `LEGACY_ALIASES`, `LEDGER_ALIASES`, routing aliases, Keystore aliases
- `STREAM_RULE` (stream-rule constant, not an error code)
- SQLDelight `*.sq` inventory under `data/persistence`

---

## 2. Specs tree inventory (docs package vs monorepo)

### 2.1 Counts

| Side | Spec files (recursive) | Notes |
|---|---|---|
| Docs package `specs/` | **33** files | No README, no fixture YAMLs, no SQL migration script |
| Monorepo `specs/` | **39** files | +6 implementation-only artifacts |

### 2.2 Files only on one side

#### Only in docs package

**None.** Empty set after recursive inventory.

#### Only in monorepo

| Relative path | Size (bytes) | Role |
|---|---:|---|
| `README.md` | 455 | Claims copy-of-product-package (path still Critical修正版 — see §7) |
| `command-conformance-fixtures.yaml` | 1359 | Implementation conformance fixtures |
| `content-reporting-fixtures.yaml` | 1700 | Content-report fixtures |
| `runtime-recovery-fixtures.yaml` | 1547 | Recovery fixtures |
| `database/migration-fixtures.yaml` | 1153 | Migration test fixtures |
| `database/migrations/0001_to_0002.sql` | 2714 | Forward migration SQL (schema v1→v2) |

**Status:** monorepo-only fixtures = **PASS** as implementation evidence (not required in design package). Design package absence of migration SQL is **PARTIAL** vs monorepo schema evolution (see §3.6).

### 2.3 Identical (full SHA-256 match) — 14 files

| Relative path | Size |
|---|---:|
| `authority-registry.yaml` | 1668 |
| `capability-availability-matrix.yaml` | 13294 |
| `capability-catalog.yaml` | 3631 |
| `compatibility-policy.yaml` | 1044 |
| `design-index.yaml` | 19526 |
| `document-metadata-schema.yaml` | 525 |
| `error-catalog.yaml` | 7457 |
| `observability-catalog.yaml` | 1853 |
| `platform-portability-matrix.yaml` | 1339 |
| `repository-lint-contract.yaml` | 1150 |
| `security-control-catalog.yaml` | 2724 |
| `security-profile.yaml` | 3471 |
| `source-references.md` | 948 |
| `ux-acceptance.yaml` | 1461 |

**Notable:** `error-catalog.yaml` is **byte-identical** across design package and monorepo (25 codes).

### 2.4 Diverged shared files — 19 files

Hash prefix = first 12 hex of SHA-256. Delta = monorepo size − docs size.

| Relative path | Docs size | Repo size | Δ bytes | Docs hash | Repo hash |
|---|---:|---:|---:|---|---|
| `access-control-catalog.yaml` | 4527 | 5351 | +824 | `B45A1EACB779` | `6BE1657300DC` |
| `aidl/omnillm-aidl.yaml` | 17569 | 22773 | +5204 | `6757955C39E2` | `01352A9B9053` |
| `canonical-types.yaml` | 4666 | 10014 | +5348 | `D30D29F33938` | `791265FD7E61` |
| `configuration-catalog.yaml` | 3438 | 5934 | +2496 | `A6DDB271D25A` | `E96038CE5525` |
| `database/migration-policy.yaml` | 978 | 2014 | +1036 | `94FE0AF11F98` | `C175153A4EDC` |
| `database/omnillm-schema.sql` | 18709 | 33603 | +14894 | `5D48A27BCCAE` | `756E269D333F` |
| `engine-qualification-schema.yaml` | 1323 | 2401 | +1078 | `29A065D803BE` | `2A46CF2AD1AF` |
| `engine-qualification-status.yaml` | 1695 | 6688 | +4993 | `145B319C32CB` | `084709D42821` |
| `feature-capability-map.yaml` | 6440 | 6480 | +40 | `A8A7847F4E36` | `B3C05B262FC4` |
| `golden-vectors/canonical-encoding.yaml` | 3768 | 4696 | +928 | `64E271927FF4` | `0E1CE3A38811` |
| `openapi/omnillm.openapi.yaml` | 69781 | 77747 | +7966 | `96EF8E175D40` | `0F85F9DE2E2B` |
| `platform-policy-register.yaml` | 2893 | 2898 | +5 | `EB148B983CCD` | `EE3DB7AE0EDB` |
| `quality-scenarios.yaml` | 5482 | 5576 | +94 | `29A31AF6FE4F` | `6E0585F00AE1` |
| `repository-index.yaml` | 20182 | 20998 | +816 | `748BC55D3E18` | `368B58CA7BA1` |
| `retention-policy.yaml` | 1673 | 2221 | +548 | `0807CB8A7439` | `E27AB73F0FA0` |
| `source-register.yaml` | 3628 | 3633 | +5 | `8785D2437F64` | `3947EC8469A3` |
| `state-machines.yaml` | 53901 | 57420 | +3519 | `6264F857F650` | `56CBC017CDAC` |
| `traceability-matrix.yaml` | 2744 | 2743 | −1 | `538C8509E311` | `D661FE66FB7F` |
| `ux-projection-catalog.yaml` | 2558 | 3507 | +949 | `317D7D23D4D5` | `C03DAF777DEF` |

### 2.5 Lag direction (新版本 vs monorepo)

| Question | Finding | Status |
|---|---|---|
| Does monorepo `specs/` lag 新版本? | **No.** For every major formal contract that diverges, monorepo is larger and contains **additional** types, paths, scopes, tables, or engine evidence notes. Docs is not a strict superset of monorepo for any core contract examined. | **N_A (inverted lag)** |
| Does monorepo `specs/README.md` still claim old package? | **Yes.** Points to `OmniLLM_產品文件完整包_Critical修正版_…` not 新版本. | **PARTIAL** |
| Design authority vs implementation truth | Product package remains design authority per `formal-contract-artifacts.md`. Monorepo has evolved contracts **beyond** 新版本 without a matching package refresh → **authority drift**. | **PARTIAL** |

Evidence quote (`specs/README.md`):

```text
These files are a copy of the product package `specs/` directory.
Product package path (source of truth for design):
`C:\Users\daive\Downloads\OmniLLM_產品文件完整包_Critical修正版_繁體中文\OmniLLM_Product_Documents\specs`
```

---

## 3. Core formal contracts (deep compare)

Authority reference (docs package):

> `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\docs\30-core-platform\formal-contract-artifacts.md`  
> Canonical type/enum: `specs/canonical-types.yaml`; HTTP: `specs/openapi/omnillm.openapi.yaml`; AIDL: `specs/aidl/omnillm-aidl.yaml`; SQL: `specs/database/omnillm-schema.sql`; FSM: `specs/state-machines.yaml`; drift rule: generated code only from catalogs; source authority wins.

### 3.1 OpenAPI — `specs/openapi/omnillm.openapi.yaml`

| Dimension | Docs (新版本) | Monorepo | Status |
|---|---|---|---|
| Presence | Yes (69781 B) | Yes (77747 B) | **PASS** presence |
| `openapi` | 3.1.0 | 3.1.0 | match |
| `info.version` | `1.1.0-design` | `1.1.0-design` | match |
| Path count | **29** | **33** | **PARTIAL** |
| Paths only in monorepo | — | `/omni/v1/commands/{commandId}`, `/omni/v1/content-reports/{reportId}/cancel`, `…/discard`, `…/receipt` | monorepo ahead |
| Paths only in docs | (none) | — | — |
| Packaged resource | n/a | `interfaces/http/src/main/resources/openapi/omnillm.openapi.yaml` **SHA-256 identical** to monorepo specs | **PASS** monorepo internal |

**Status:** **PARTIAL** (docs lag monorepo surface by 4 content-report/command paths).

### 3.2 AIDL YAML — `specs/aidl/omnillm-aidl.yaml`

| Dimension | Docs | Monorepo | Status |
|---|---|---|---|
| Presence | Yes | Yes | **PASS** |
| `schemaVersion` | **1** | **2** | **PARTIAL** version skew |
| Declarations | **41** | **44** | **PARTIAL** |
| Only docs | `OmniContentReportRequest` | — | rename/split in monorepo |
| Only monorepo | — | `OmniCommandRequest`, `OmniConsentGrant`, `OmniContentReportProposalRequest`, `OmniContentReportReceipt` | content-report + command envelope |
| Committed `.aidl` | n/a | **44** under `interfaces/aidl/src/main/aidl/ai/omnillm/api/` | **PASS** |
| Drift gate | n/a | `checkAidlDrift` in root `check`; `--check` **OK** | **PASS** |

`IOmniAdmin` monorepo source adds playground execute/query/cancel, content-report review/submit/receipt, and `OmniCommandRequest`-based cancel (docs still uses bare commandId/idempotencyKey strings).

**Status:** **PARTIAL** (schemaVersion + declaration set drift docs↔repo; monorepo internal OK).

### 3.3 Canonical types — `specs/canonical-types.yaml`

| Dimension | Docs | Monorepo | Status |
|---|---|---|---|
| Presence | Yes | Yes | **PASS** |
| `schemaVersion` | **2** | **3** | **PARTIAL** |
| Type count | **30** | **38** | **PARTIAL** |
| Types only monorepo | — | `ArtifactPackageEntry`, `ArtifactPackageManifest`, `ModelRevisionManifest`, `CommandRequest`, `ContentReportCategory`, `ContentReportState`, `ContentReportPayload`, `ConsentGrant` | identity hash + content-report formalization |
| Types only docs | (none) | — | — |
| Codegen input | listed in generator | Yes → `core/canonical/.../generated/` | **PASS** monorepo |

Monorepo encoding adds:

```text
identityUnknownFields: rejected before canonicalization
identityHashInput: UTF-8 domain separator followed by LF followed by RFC 8785 bytes
```

plus explicit `hash:` formulas for `ArtifactPackageId` / `ModelRevisionId`.

**Status:** **PARTIAL** (schemaVersion 2→3 + 8 types only in monorepo).

### 3.4 State machines — `specs/state-machines.yaml`

| Dimension | Docs | Monorepo | Status |
|---|---|---|---|
| Presence | Yes (53901 B) | Yes (57420 B) | **PASS** |
| `schemaVersion` | 2 | 2 | match |
| Machine set | **20** identical names | same 20 | **PASS** name parity |
| Transition count deltas | most machines **0** | only **CONTENT_REPORT** +12 transitions | **PARTIAL** |
| CONTENT_REPORT states docs | 8: DRAFT, CONSENTED, QUEUED, SUBMITTING, SUBMITTED, FAILED_RETRYABLE, EXPIRED, CANCELLED | — | diverged |
| CONTENT_REPORT states repo | — | 12: DRAFT, REVIEWING, CONSENT_GRANTED, QUEUED_OFFLINE, SUBMITTING, SUBMITTED, FAILED_RETRYABLE, FAILED_FINAL, EXPIRED, CANCELLING, DISCARDED, RECONCILING | diverged |

Machines with **identical** state+transition counts (docs vs repo): REQUEST, SESSION, RESERVATION, JOB, MODEL_INSTALLATION, LOADED_MODEL, COMMIT, RUNTIME, REVOCATION, ASSET, CLIENT_REGISTRATION, OPERATION, ENGINE_MODULE, ALLOCATION, REVISION_LEASE, COMMAND, PAIRING_CHALLENGE, LAN_SERVICE, TOKEN.

Codegen: `generate_contracts.py` → `core/state/.../generated/StateMachines.kt`; `--check` **OK**.

**Status:** **PARTIAL** (CONTENT_REPORT machine diverged; rest count-stable, file text still differs by ~3.5 KB).

### 3.5 Error catalog — `specs/error-catalog.yaml`

| Dimension | Docs | Monorepo | Status |
|---|---|---|---|
| Presence | Yes | Yes | **PASS** |
| SHA-256 | **identical** | **identical** | **PASS** |
| `schemaVersion` | 2 | 2 | match |
| Codes | 25 | 25 (same set) | **PASS** |

Codes (both):

```text
INVALID_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, IDEMPOTENCY_CONFLICT,
STATE_CONFLICT, CONTEXT_LIMIT_EXCEEDED, TRANSPORT_TOO_LARGE, RATE_LIMITED,
CAPABILITY_UNSUPPORTED, CAPABILITY_UNKNOWN, ADMISSION_REJECTED, MODEL_REVOKED,
TRUST_PLACEMENT_REQUIRED, PAIRING_REQUIRED, CURSOR_GONE, ASSET_NOT_READY,
ASSET_EXPIRED, DEADLINE_EXCEEDED, CANCELLED, WORKER_DIED, ABORTED_UNCERTAIN,
STREAM_INTERRUPTED, CONTENT_REPORT_UNAVAILABLE, INTERNAL
```

Generated: `OmniErrorCode` enum contains all 25 codes + `ErrorCategory { SEMANTIC, TRANSPORT }` (categories, not codes).  
`OmniError.STREAM_RULE` / `TransportDeliveryGuarantee.STREAM_RULE` mirror `streamRule` prose — **not** a private error code.

**Status:** **PASS**.

### 3.6 Database schema SQL + migration

| Artifact | Docs | Monorepo | Status |
|---|---|---|---|
| `database/omnillm-schema.sql` | 18709 B, **39** tables | 33603 B, **51** tables | **PARTIAL** |
| `database/migration-policy.yaml` | schemaVersion **1** | schemaVersion **2**, currentVersion **2** | **PARTIAL** |
| `database/migrations/0001_to_0002.sql` | **MISSING** | **PRESENT** (2714 B) | monorepo only |
| Packaged `data/persistence/.../db/*` | n/a | **SHA match** monorepo specs for schema, policy, migration | **PASS** monorepo |
| SQLDelight `.sq` | n/a | **26** files (subset of control-plane queries) | **PASS** L1 subset |

Tables only in monorepo schema (12):

```text
schema_metadata, schema_migration_attempts, schema_migration_history,
runtime_instances, prepared_operations, commit_resource_bindings,
secret_broker_keys, revocation_subjects,
content_report_consent_grants, content_report_receipts,
tool_proposals, tool_result_claims
```

Tables only in docs: **none**.

**Status:** **PARTIAL** — monorepo schema is authority for implementation; 新版本 package SQL is **behind**.

### 3.7 Access control catalog

| Dimension | Docs | Monorepo |
|---|---|---|
| Scopes | 22 | 25 |
| Only docs | `content-reports.create` | — |
| Only monorepo | — | `commands.read-own`, `content-reports.propose`, `content-reports.manage-own`, `content-reports.review-submit` |

Generated monorepo `AccessScope` enum matches monorepo catalog (25 scopes with dotted ids).

**Status:** **PARTIAL** (content-report scope model rename/split; docs `content-reports.create` removed in monorepo).

### 3.8 Configuration catalog

| Dimension | Docs | Monorepo |
|---|---|---|
| Settings count | 11 | 19 |
| Only monorepo | — | `runtime.exploratoryExecuteEnabled`, `product.researchModeEnabled`, `product.riskyPerformanceModeEnabled`, `resource.governorAnonMemoryCapBytes`, `resource.governorFileCacheCapBytes`, `resource.governorThreadCap`, `resource.governorFdCap`, `resource.probeDeadlineMs` |

**Status:** **PARTIAL**.

### 3.9 Engine qualification status

| Dimension | Docs | Monorepo |
|---|---|---|
| Engines | 5 (llama.cpp, LiteRT-LM, MLC-LLM, mllm, ONNX-Runtime-GenAI) | same 5 |
| All `qualificationStatus` | **UNQUALIFIED** | **UNQUALIFIED** |
| `upstreamLockStatus` | all NOT_LOCKED | llama/LiteRT/mllm/ORT **LOCKED**; MLC **NOT_LOCKED** |
| Integration notes | minimal | detailed evidenceNotes; INTEGRATED / INTEGRATED_PENDING_QUALIFICATION |
| SUPPORTED / QUALIFIED claims | **none** | **none** |

**Status:** **PASS** for honest UNQUALIFIED rule; **PARTIAL** for lock/integration evidence richness vs 新版本 package (monorepo ahead).

### 3.10 Minor registry renames (docs vs monorepo)

| File | Diff (unified) |
|---|---|
| `platform-policy-register.yaml` / `source-register.yaml` | `FEAT-AI-REPORTING` → `FEAT-AI-CONTENT-REPORT` |
| `traceability-matrix.yaml` | same feature id rename; `SEC-DATA-FLOW`→`SEC-PRIVACY`; `UX-VALIDATION`→`UX-ARCH`; `specs/source-claims.yaml`→`specs/source-register.yaml` |

**Status:** **PARTIAL** (small but real authority-id drift).

---

## 4. Generators under `tools/codegen`

### 4.1 Inventory (monorepo)

| Path | Size | Purpose |
|---|---:|---|
| `tools/codegen/generate_contracts.py` | 41118 | YAML → Kotlin: canonical, FSM, errors, ACL, capabilities |
| `tools/codegen/extract_aidl.py` | 5723 | AIDL YAML → `.aidl` projections + `--check` |
| `tools/codegen/README.md` | 2590 | Authority + CI order |
| `tools/codegen/requirements.txt` | 14 | `PyYAML==6.0.3` |

### 4.2 Docs package tools (not codegen)

| Path | Purpose |
|---|---|
| `tools/validate_repository.py` | YAML unique keys, markdown links, MANIFEST.sha256 |
| `tools/check_markdown_anchors.py` | Anchor checker |

**No** OpenAPI/AIDL/SQL Kotlin generator ships inside the 新版本 docs package (expected: pre-build design authority only).

### 4.3 Gradle wiring (monorepo) — evidence

From `build.gradle.kts`:

- `generateContracts` → `tools/codegen/generate_contracts.py`
- `checkContractDrift` → same + `--check` (must not depend on generate)
- `checkAidlDrift` → `tools/codegen/extract_aidl.py --check`
- Root `check` depends on both drift gates (+ native 16kb + dependency edges)

Compile modules depending on `generateContracts` include `:core:canonical|state|errors|contracts|…` and many feature/engine modules (grep hits across `*.gradle.kts`).

### 4.4 Live gate results

```text
Contract drift check: OK (generated sources match catalogs).
AIDL drift check: OK (44 declarations match committed .aidl files).
```

### 4.5 Generator coverage matrix

| Catalog | Generator consumes? | Generated outputs | OpenAPI/SQL codegen? |
|---|---|---|---|
| `canonical-types.yaml` | Yes | `core/canonical/.../generated/*` | — |
| `state-machines.yaml` | Yes | `core/state/.../generated/StateMachines.kt` | — |
| `error-catalog.yaml` | Yes | `core/errors/.../generated/OmniError*.kt` | — |
| `access-control-catalog.yaml` | Yes | `AccessControlCatalog.kt` | — |
| `capability-catalog.yaml` | Yes | `CapabilityCatalog.kt` | — |
| `golden-vectors/canonical-encoding.yaml` | Tests | identity golden tests | — |
| `openapi/omnillm.openapi.yaml` | **No generator** | Hand-maintained Ktor routes + packaged YAML copy | **PARTIAL** (copy + smoke tests, not client codegen) |
| `aidl/omnillm-aidl.yaml` | `extract_aidl.py` | 44 `.aidl` | **PASS** |
| `database/omnillm-schema.sql` | **No SQL→Kotlin codegen** | Packaged resource + SQLDelight **subset** | **PARTIAL** |

**Status codegen subsystem:** **PASS** for catalog→Kotlin/AIDL gates; **PARTIAL** for OpenAPI/SQL (no full generator, but packaged byte-parity + SQLDelight subset + route smoke tests exist).

---

## 5. Private aliases / catalog-absent tokens

### 5.1 Error codes

| Finding | Evidence | Status |
|---|---|---|
| No KT `OmniError.*` code outside catalog (except STREAM_RULE constant) | Scan of 818 `*.kt`; STREAM_RULE is stream-rule prose on `OmniError` companion | **PASS** |
| `ErrorCategory.SEMANTIC/TRANSPORT` not error codes | Generated from catalog `category` field | **PASS** |
| Hand-written `const val X = "ERROR_CODE"` | 0 matches | **PASS** |

### 5.2 State aliases

| Alias set | Location | Membership vs FSM | Status |
|---|---|---|---|
| `LEDGER_ALIASES` | `features/admin/.../CommandUiProjection.kt` | Exact set of **COMMAND** states: RECEIVED, CLAIMED, RUNNING, RECONCILING, SUCCEEDED, FAILED, CANCELLED, UNCERTAIN | **PASS** (UI projection of catalog machine) |
| `LEGACY_ALIASES` | `features/playground/.../PlaygroundProjections.kt` | `ACCEPTED`, `RUNNING`, `CANCELLING` — **none** are REQUEST states (REQUEST has RECEIVED/CLAIMED/…/TERMINATING, not ACCEPTED/RUNNING/CANCELLING) | **PARTIAL** — private transport aliases **not** in `state-machines.yaml` |

Quote:

```kotlin
/** Transport / port aliases that may appear before full REQUEST bind. */
private val LEGACY_ALIASES: Set<String> = setOf(
    "ACCEPTED",
    "RUNNING",
    "CANCELLING",
)
```

### 5.3 Other “alias” hits (not catalog drift)

| Kind | Location | Assessment |
|---|---|---|
| Routing model aliases | `features/routing`, `RoutingScreen` | Product feature (model display alias table) — not error/type catalog tokens | **N_A** |
| Keystore aliases | `SecretBroker`, companion isolation comments | Android Keystore naming — not formal contract catalog | **N_A** |
| `typealias ContractResult<T> = OmniResult<T>` | `core/contracts` | Kotlin type alias to catalog type | **PASS** |

### 5.4 Scope rename (docs catalog vs monorepo catalog)

Monorepo removed `content-reports.create` and added propose/manage/review-submit scopes. Generated Kotlin matches **monorepo** catalog only. Callers using docs-only scope id would fail closed via `AccessScope.requireFromId`.

**Status:** **PARTIAL** (docs package ACL catalog not updated).

---

## 6. `validate_repository.py` (docs package)

Command:

```text
python …\Product_Documents\tools\validate_repository.py …\OmniLLM_Product_Documents
```

Result:

```text
root=…\OmniLLM_Product_Documents
files=151
issues=2
- missing compiled design compendium
- manifest missing file: OmniLLM_建置前產品與架構設計總綱_產品版_繁體中文.md
EXIT=1
```

YAML parse of all package YAML (including all `specs/`) reported **no** YAML errors in this run.

**Status:** **PARTIAL** — specs YAML parse clean; package-level manifest/compendium incomplete relative to validator expectations.

---

## 7. Authority / process findings

| ID | Finding | Status | Evidence |
|---|---|---|---|
| C-01 | Monorepo `specs/README.md` still cites **Critical修正版** path, not 新版本 | **PARTIAL** | `specs/README.md` absolute path text |
| C-02 | Monorepo specs are **ahead of** 新版本 package (not lagging) for OpenAPI/AIDL/types/SQL/FSM CONTENT_REPORT/ACL/config/engines | **PARTIAL** (authority refresh debt) | §2.4 size/hash table + structural diffs |
| C-03 | `error-catalog.yaml` in lockstep | **PASS** | identical SHA-256 |
| C-04 | Capability matrix/catalog + design-index + security-profile identical | **PASS** | §2.3 |
| C-05 | Monorepo catalog↔generated Kotlin drift gate green | **PASS** | `generate_contracts.py --check` |
| C-06 | Monorepo AIDL YAML↔`.aidl` drift gate green | **PASS** | `extract_aidl.py --check` |
| C-07 | Packaged OpenAPI/SQL/migrations match monorepo specs | **PASS** | SHA-256 equality §4 packaging |
| C-08 | Playground `LEGACY_ALIASES` not in REQUEST FSM | **PARTIAL** | §5.2 |
| C-09 | No engine QUALIFIED/SUPPORTED in catalogs | **PASS** | engine-qualification-status both sides |
| C-10 | OpenAPI has no generator; hand routes + packaged copy | **PARTIAL** | `contract-integration.md` + no openapi codegen tool |
| C-11 | SQL authority has migration v2 in monorepo; docs package SQL still pre-migration surface | **PARTIAL** | 39 vs 51 tables |
| C-12 | Docs package validator fails on missing compendium in MANIFEST | **PARTIAL** | validate_repository.py issues=2 |

---

## 8. L1 / L2 / L3 readout (contracts only)

| Level | Definition | Result |
|---|---|---|
| **L1** module/artifact exists | Specs files, generators, generated sources, `.aidl`, packaged OpenAPI/SQL | **PASS** monorepo; **PASS** docs core set; **PARTIAL** docs vs monorepo sync |
| **L2** wired to control plane | Gradle `checkContractDrift`/`checkAidlDrift` on root `check`; runtime HTTP handler + binder facades documented | **PASS** software wiring evidence in repo (this audit does not re-prove runtime journeys) |
| **L3** product journey software-complete | E2E user journeys for every OpenAPI path / AIDL method | **N_A** for this contract-drift audit (not claimed) |

---

## 9. Summary status board

| Check | Status |
|---|---|
| Docs package has OpenAPI | **PASS** |
| Docs package has AIDL YAML | **PASS** |
| Docs package has canonical-types | **PASS** |
| Docs package has state-machines | **PASS** |
| Docs package has error-catalog | **PASS** |
| Docs package has omnillm-schema.sql | **PASS** |
| Monorepo has all of the above | **PASS** |
| Docs ↔ monorepo full byte parity | **PARTIAL** |
| Monorepo lags 新版本 | **N_A** (monorepo ahead) |
| Codegen tools present | **PASS** |
| `generate_contracts.py --check` | **PASS** |
| `extract_aidl.py --check` | **PASS** |
| Private error codes outside catalog | **PASS** (none) |
| Private state aliases outside FSM | **PARTIAL** (`LEGACY_ALIASES`) |
| Engine QUALIFIED/SUPPORTED invention | **PASS** (not claimed) |
| `validate_repository.py` | **PARTIAL** (issues=2) |
| **Overall contracts audit** | **PARTIAL** |

---

## 10. Recommended remediation (audit only — not implemented)

1. **Re-baseline design package `specs/`** from monorepo (or reverse-merge monorepo → 新版本) for diverged formal contracts, especially: OpenAPI (+4 paths), AIDL schemaVersion 2, canonical-types v3, schema SQL + migration 0001_to_0002, CONTENT_REPORT FSM, ACL scopes, configuration settings, engine-qualification-status evidence.
2. **Fix monorepo `specs/README.md`** product package path to 新版本 (or “synced from monorepo as of DATE”).
3. **Eliminate or catalog** playground `LEGACY_ALIASES` (`ACCEPTED`/`RUNNING`/`CANCELLING`) — either add to FSM/transport catalog or map them strictly to REQUEST states at the port boundary.
4. **Keep** monorepo drift gates green; do not hand-edit `generated/` or `.aidl`.
5. **Docs package:** restore or remove MANIFEST entry for `OmniLLM_建置前產品與架構設計總綱_產品版_繁體中文.md` so `validate_repository.py` exits 0.
6. Optional: add OpenAPI path inventory gate (similar to AIDL `--check`) against Ktor route table to prevent silent HTTP drift.

---

## 11. Empty-search documentation

No finding category was empty after thorough search:

- Specs inventory: complete both sides.
- Generators: found under `tools/codegen/`.
- Drift gates: executed live.
- Alias scan: 27 alias-like lines reviewed; 2 formal-state alias sets evaluated against FSM.
- validate_repository.py: executed live.

---

## Appendix A — Full monorepo `specs/` relative paths

```text
access-control-catalog.yaml
aidl/omnillm-aidl.yaml
authority-registry.yaml
canonical-types.yaml
capability-availability-matrix.yaml
capability-catalog.yaml
command-conformance-fixtures.yaml
compatibility-policy.yaml
configuration-catalog.yaml
content-reporting-fixtures.yaml
database/migration-fixtures.yaml
database/migration-policy.yaml
database/migrations/0001_to_0002.sql
database/omnillm-schema.sql
design-index.yaml
document-metadata-schema.yaml
engine-qualification-schema.yaml
engine-qualification-status.yaml
error-catalog.yaml
feature-capability-map.yaml
golden-vectors/canonical-encoding.yaml
observability-catalog.yaml
openapi/omnillm.openapi.yaml
platform-policy-register.yaml
platform-portability-matrix.yaml
quality-scenarios.yaml
README.md
repository-index.yaml
repository-lint-contract.yaml
retention-policy.yaml
runtime-recovery-fixtures.yaml
security-control-catalog.yaml
security-profile.yaml
source-references.md
source-register.yaml
state-machines.yaml
traceability-matrix.yaml
ux-acceptance.yaml
ux-projection-catalog.yaml
```

## Appendix B — Full docs package `specs/` relative paths

```text
access-control-catalog.yaml
aidl/omnillm-aidl.yaml
authority-registry.yaml
canonical-types.yaml
capability-availability-matrix.yaml
capability-catalog.yaml
compatibility-policy.yaml
configuration-catalog.yaml
database/migration-policy.yaml
database/omnillm-schema.sql
design-index.yaml
document-metadata-schema.yaml
engine-qualification-schema.yaml
engine-qualification-status.yaml
error-catalog.yaml
feature-capability-map.yaml
golden-vectors/canonical-encoding.yaml
observability-catalog.yaml
openapi/omnillm.openapi.yaml
platform-policy-register.yaml
platform-portability-matrix.yaml
quality-scenarios.yaml
repository-index.yaml
repository-lint-contract.yaml
retention-policy.yaml
security-control-catalog.yaml
security-profile.yaml
source-references.md
source-register.yaml
state-machines.yaml
traceability-matrix.yaml
ux-acceptance.yaml
ux-projection-catalog.yaml
```

## Appendix C — Committed generated contract sources (monorepo)

```text
core/canonical/src/main/kotlin/com/omnillm/core/canonical/generated/
  AccessControlCatalog.kt
  CanonicalEncoding.kt
  CanonicalEnums.kt
  CapabilityCatalog.kt
  ContractCatalogManifest.kt
  DigestTypes.kt
  OmniResult.kt
  ResourceVector.kt
core/state/src/main/kotlin/com/omnillm/core/state/generated/
  StateMachines.kt
core/errors/src/main/kotlin/com/omnillm/core/errors/generated/
  OmniError.kt
  OmniErrorCode.kt
```

---

*End of audit artifact `11_contracts.md`.*
