# 00_MAP — OmniLLM Docs Package & Monorepo Inventory

| Field | Value |
|-------|--------|
| **Artifact** | `00_MAP.md` |
| **Role** | Tree inventory + ID catalog only (no PASS/PARTIAL product verdicts) |
| **Audit date (UTC host)** | 2026-08-12 |
| **Docs package (authority)** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo (under audit)** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Out dir** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports` |
| **Method** | `list_dir` / `read_file` / `grep` / path existence checks — fail-closed on missing paths |

> Specs under docs package `specs/` take precedence over prose when IDs conflict.  
> Prior monorepo reports (`FEATURE_AUDIT.md`, `APPIUM_E2E_REPORT.md`, `BUILD_STATUS.md`) are **non-authority hints only**.

---

## A) Docs package confirmation

**Root:** `...\OmniLLM_Product_Documents`

| Required item | Status | Evidence |
|---|---|---|
| `README.md` | **PRESENT** | Package intro; points to `DOCUMENT-MAP.md`, `specs/`, `tools/validate_repository.py` |
| `DOCUMENT-MAP.md` | **PRESENT** | Stable ID map for `docs/00-product` … `docs/90-future-platforms`, `governance/`, `specs/` |
| `docs/00-product/` … `docs/90-future-platforms/` | **PRESENT** | Full decade dirs listed (see short table below) |
| `governance/` | **PRESENT** | ADR-001…012 + authority/change/risk/status docs |
| `specs/` | **PRESENT** | Machine-readable catalogs (YAML/SQL/OpenAPI/AIDL) |
| `tools/validate_repository.py` | **PRESENT** | Repo structure/integrity validator (+ `check_markdown_anchors.py`) |

### Docs tree (short inventory)

| Path | Contents (observed) |
|---|---|
| `docs/00-product/` | 9 files: charter, modes, glossary, personas, capability model, eligibility, quality, boundaries, README |
| `docs/10-experience/` | 7 files: journeys, IA, UX arch, a11y/i18n, safety copy, state/error catalog, README |
| `docs/20-architecture/` | 9 files: invariants, principles, logical, sequences, trust topology, runtime lifecycle, extension, system context, README |
| `docs/30-core-platform/` | 10 files: engine/interface/model platforms, orchestrator, resource, session/KV, observability, contracts, capability, README |
| `docs/40-domain-data/` | 8 files: domain, identity, config, ownership, storage, states, measurement, README |
| `docs/50-security-reliability/` | 10 files: threat, profile, auth/net, privacy, supply chain, sandbox companion, recovery, input abuse, placement, README |
| `docs/60-android/` | 8 files: baseline, binder/AIDL, services/FGS, storage/SAF, native 16KB, device/driver, distribution, README |
| `docs/70-features/` | 14 files: 12 FEAT designs + `feature-design-system.md` (`FEATURE-SYSTEM`) + README |
| `docs/80-engines/` | 8 files: standard + 5 engine designs + qualification status + README |
| `docs/90-future-platforms/` | 6 files: roadmap, iOS/PC/IoT/portable core, README |
| `governance/` | 8 top-level MD + `adr/ADR-001.md`…`ADR-012.md` |
| `specs/` | Catalogs: capability, feature-capability-map, engine-qualification-*, openapi, aidl, database, state-machines, security, UX, etc. |
| `templates/` | ADR / engine / feature design templates |
| `tools/` | `validate_repository.py`, `check_markdown_anchors.py` |
| Root extras | `MANIFEST.sha256`, `PACKAGE-CONTENTS.md` |

**Section A status:** all required package anchors confirmed **PRESENT** via path checks.

---

## B) Monorepo confirmation

**Root:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android`  
**Gradle root name:** `omnillm-android` (`settings.gradle.kts`)

### B.1 `settings.gradle.kts` modules (44 includes)

| Layer | Gradle projects | Count |
|---|---|---|
| **core** | `:core:canonical`, `:core:state`, `:core:contracts`, `:core:errors`, `:core:resource`, `:core:identity`, `:core:ports` | 7 |
| **data** | `:data:persistence`, `:data:model-store` | 2 |
| **runtime** | `:runtime:request-registry`, `:runtime:orchestrator`, `:runtime:governor`, `:runtime:session`, `:runtime:model-manager`, `:runtime:job-manager`, `:runtime:policy`, `:runtime:observability` | 8 |
| **engines** | `:engines:api`, `:engines:llama-cpp`, `:engines:litert-lm`, `:engines:mlc-llm`, `:engines:mllm`, `:engines:ort-genai` | 6 |
| **interfaces** | `:interfaces:http`, `:interfaces:aidl`, `:interfaces:admin` | 3 |
| **features** | `:features:auto-setup`, `:features:modelhub`, `:features:playground`, `:features:server`, `:features:lan`, `:features:dashboard`, `:features:benchmark`, `:features:diagnostics`, `:features:routing`, `:features:tools`, `:features:admin`, `:features:ai-content-report` | 12 |
| **android** | `:android:app-ui`, `:android:runtime-service`, `:android:workers`, `:android:parser-isolated`, `:android:companion-sandbox`, `:android:native` | 6 |
| **TOTAL** | | **44** |

> Note: root `BUILD_STATUS.md` claims “43 modules” — disk `settings.gradle.kts` currently includes **44**. Treat this map’s count as authoritative for this audit pass.

### B.2 Directory presence (L1 module trees)

| Tree | Status | Subdirs observed |
|---|---|---|
| `features/*` | **PRESENT** | `admin`, `ai-content-report`, `auto-setup`, `benchmark`, `dashboard`, `diagnostics`, `lan`, `modelhub`, `playground`, `routing`, `server`, `tools` (12) |
| `engines/*` | **PRESENT** | `api`, `litert-lm`, `llama-cpp`, `mlc-llm`, `mllm`, `ort-genai` (6); engine packs carry `capability-matrix.yaml` + `UPSTREAM.lock` except pure API |
| `runtime/*` | **PRESENT** | `governor`, `job-manager`, `model-manager`, `observability`, `orchestrator`, `policy`, `request-registry`, `session` (8) |
| `android/*` | **PRESENT** | `app-ui`, `companion-sandbox`, `native`, `parser-isolated`, `runtime-service`, `workers` (6) |
| `interfaces/*` | **PRESENT** | `admin`, `aidl` (44 `.aidl` sources), `http` |
| `core/*` | **PRESENT** | `canonical`, `contracts`, `errors`, `identity`, `ports`, `resource`, `state` |
| `data/*` | **PRESENT** | `model-store`, `persistence` (SQLDelight + schema resources) |
| `specs/` | **PRESENT** | In-repo copy of machine-readable contracts (+ a few monorepo-only fixtures) |
| `PRODUCT_READINESS.md` | **PRESENT** | Software verification pass notes (non-authority for design) |
| Also present | **PRESENT** | `PRODUCT_READINESS_CHECKLIST.md`, `README.md`, `AGENTS.md`, `tools/codegen/`, `tools/ci/`, `gradle/`, build logs, e2e artifacts |

**Section B status:** monorepo topology and required trees confirmed **PRESENT**.

---

## C) FEAT-* and ENGINE-* catalog (docs package authority)

### C.1 FEAT-* from `docs/70-features` (prose document IDs)

Source: front-matter `id:` + `docs/70-features/README.md` + `DOCUMENT-MAP.md`.

| FEAT / related ID | Design file | Monorepo feature module (L1 name only) |
|---|---|---|
| `FEAT-ADMIN` | `administration-jobs.md` | `features/admin` |
| `FEAT-AI-CONTENT-REPORT` | `ai-content-reporting.md` | `features/ai-content-report` |
| `FEAT-AUTOSETUP` | `auto-setup.md` | `features/auto-setup` |
| `FEAT-BENCHMARK` | `benchmark-research.md` | `features/benchmark` |
| `FEAT-DASHBOARD` | `dashboard-monitoring.md` | `features/dashboard` |
| `FEAT-SERVER` | `developer-server.md` | `features/server` |
| `FEAT-DIAGNOSTICS` | `diagnostics-export.md` | `features/diagnostics` |
| `FEAT-LAN` | `lan-access.md` | `features/lan` |
| `FEAT-PLAYGROUND` | `local-playground.md` | `features/playground` |
| `FEAT-MODELHUB` | `modelhub-acquisition.md` | `features/modelhub` |
| `FEAT-ROUTING` | `multi-model-routing.md` | `features/routing` |
| `FEAT-TOOLS` | `structured-tools.md` | `features/tools` |
| `FEATURE-SYSTEM` *(not FEAT-\*)* | `feature-design-system.md` | N/A (design system, not a pack) |
| `NAV-FEATURES` | `README.md` | N/A |

**Count:** **12** product feature packs (`FEAT-*`) + 1 system doc (`FEATURE-SYSTEM`) + nav.

### C.2 FEAT-* from docs package `specs/feature-capability-map.yaml` (machine-readable precedence)

`schemaVersion: 2` — top-level `features[].featureId`:

| # | featureId (docs package specs) |
|---|---|
| 1 | `FEAT-AUTOSETUP` |
| 2 | `FEAT-MODELHUB` |
| 3 | `FEAT-PLAYGROUND` |
| 4 | `FEAT-SERVER` |
| 5 | `FEAT-LAN` |
| 6 | `FEAT-DASHBOARD` |
| 7 | `FEAT-BENCHMARK` |
| 8 | `FEAT-DIAGNOSTICS` |
| 9 | `FEAT-ROUTING` |
| 10 | `FEAT-TOOLS` |
| 11 | `FEAT-ADMIN` |
| 12 | `FEAT-AI-REPORTING` |

#### ID conflict (inventory only — not closed here)

| Source | AI content feature ID |
|---|---|
| Docs prose `docs/70-features/ai-content-reporting.md` | `FEAT-AI-CONTENT-REPORT` |
| Docs package `specs/feature-capability-map.yaml` | **`FEAT-AI-REPORTING`** (preferred per package rule: specs > prose) |
| Monorepo `specs/feature-capability-map.yaml` | `FEAT-AI-CONTENT-REPORT` (diverges from docs package specs) |
| Monorepo module path | `features/ai-content-report` |

Auditors must treat **docs package specs** as design authority unless later governance reconciles the alias.

### C.3 ENGINE-* from `docs/80-engines`

Source: front-matter `id:` + `docs/80-engines/README.md` + `DOCUMENT-MAP.md`.

| ENGINE ID | File | Kind | Monorepo module |
|---|---|---|---|
| `ENGINE-STANDARD` | `engine-integration-standard.md` | Normative integration standard | N/A (standard, not a pack) |
| `ENGINE-LITERT` | `litert-lm.md` | Engine design | `engines/litert-lm` |
| `ENGINE-LLAMACPP` | `llama-cpp.md` | Engine design | `engines/llama-cpp` |
| `ENGINE-MLC` | `mlc-llm.md` | Engine design | `engines/mlc-llm` |
| `ENGINE-MLLM` | `mllm.md` | Engine design | `engines/mllm` |
| `ENGINE-ORTGENAI` | `onnx-runtime-genai.md` | Engine design | `engines/ort-genai` |
| `ENGINE-QUALIFICATION-STATUS` | `qualification-status-and-evidence.md` | Qualification governance | N/A (status doc) |
| `NAV-ENGINES` | `README.md` | Nav | N/A |

**Count:** **5** engine product designs + standard + qualification status doc.

Related machine-readable (docs package `specs/engine-qualification-status.yaml`):

| engineId (spec string) | qualificationStatus (docs package) | Design ENGINE-* |
|---|---|---|
| `llama.cpp` | `UNQUALIFIED` | `ENGINE-LLAMACPP` |
| `LiteRT-LM` | `UNQUALIFIED` | `ENGINE-LITERT` |
| `MLC-LLM` | `UNQUALIFIED` | `ENGINE-MLC` |
| `mllm` | `UNQUALIFIED` | `ENGINE-MLLM` |
| `ONNX-Runtime-GenAI` | `UNQUALIFIED` | `ENGINE-ORTGENAI` |

Rule quote (docs package specs): *“only a non-expired PASS record can project SUPPORTED”*; all cells currently `UNQUALIFIED` / `UNKNOWN`. This map does **not** promote any engine to QUALIFIED/SUPPORTED.

Monorepo also has `:engines:api` (registry/API surface) — **not** a docs `ENGINE-*` design ID.

---

## D) Prior reports (non-authority hints)

Located at monorepo root. **Not design authority.** Do not use alone for PASS claims in subsequent audits.

| File | Path | Observed role (hint only) |
|---|---|---|
| `FEATURE_AUDIT.md` | `...\omnillm-android\FEATURE_AUDIT.md` | Feature/engine software audit as-of 2026-08-09; claims L1/L2 hosting — re-verify against new docs package |
| `APPIUM_E2E_REPORT.md` | `...\omnillm-android\APPIUM_E2E_REPORT.md` | Device smoke `SMOKE_PARTIAL` on emulator; **no** engine qualification claim |
| `BUILD_STATUS.md` | `...\omnillm-android\BUILD_STATUS.md` | Engineering status inventory as-of 2026-08-09; module count may be stale vs current `settings.gradle.kts` |
| `PRODUCT_READINESS.md` | `...\omnillm-android\PRODUCT_READINESS.md` | Software gate notes as-of 2026-08-06; not device/Play/OEM evidence |

Related non-authority siblings also present: `PRODUCT_READINESS_CHECKLIST.md`, `GAP_CLOSEOUT.md`, `GAP_INVENTORY.md`, `SHIP_BACKLOG.md`, `e2e-artifacts/`.

---

## E) Out dir & search record

| Action | Result |
|---|---|
| Create `audit-reports/` | **Created** (did not previously exist; path now present) |
| Write this artifact | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports\00_MAP.md` |

### Grep / read footprint (empty-findings discipline)

| Target | Patterns / files |
|---|---|
| Docs `docs/70-features` | `FEAT-*`, front-matter `^id:` |
| Docs `specs/feature-capability-map.yaml` | `featureId:` |
| Docs `docs/80-engines` | `ENGINE-*` |
| Docs `specs/engine-qualification-status.yaml` | engine rows + rule header |
| Monorepo | `settings.gradle.kts`, `list_dir` on features/engines/runtime/android/interfaces/core/data/specs |
| Prior reports | first ~25 lines of FEATURE_AUDIT / APPIUM_E2E / BUILD_STATUS / PRODUCT_READINESS |

---

## Short inventory table (executive)

| Area | Present? | Key count / IDs |
|---|---|---|
| Docs README + DOCUMENT-MAP | Yes | Package root |
| Docs `docs/00`…`90` | Yes | 10 decade sections |
| Docs `governance/` | Yes | ADR-001…012 + 8 gov docs |
| Docs `specs/` | Yes | Machine-readable authority |
| Docs `tools/validate_repository.py` | Yes | Present |
| Monorepo Gradle modules | Yes | **44** includes in `settings.gradle.kts` |
| Monorepo `features/*` | Yes | **12** packs |
| Monorepo `engines/*` | Yes | **5** packs + `api` |
| Monorepo `runtime/*` | Yes | **8** modules |
| Monorepo `android/*` | Yes | **6** modules |
| Monorepo `interfaces/*` | Yes | **3** modules |
| Monorepo `specs/` | Yes | Contracts copy (+ fixtures) |
| Monorepo `PRODUCT_READINESS.md` | Yes | Present (hint) |
| FEAT packs (docs prose) | Yes | 12 (`FEAT-AI-CONTENT-REPORT` …) |
| FEAT packs (docs specs) | Yes | 12 (`FEAT-AI-REPORTING` for AI report) |
| ENGINE designs (docs) | Yes | 5 + STANDARD + QUALIFICATION-STATUS |
| Engine qualification (docs specs) | Yes | All **UNQUALIFIED** / **UNKNOWN** |
| Prior audit/e2e/build reports | Yes | Non-authority |

### Crosswalk snapshot (design ID ↔ module path)

| Design ID (preferred) | Module / adapter path |
|---|---|
| FEAT-AUTOSETUP | `features/auto-setup` |
| FEAT-MODELHUB | `features/modelhub` |
| FEAT-PLAYGROUND | `features/playground` |
| FEAT-SERVER | `features/server` |
| FEAT-LAN | `features/lan` |
| FEAT-DASHBOARD | `features/dashboard` |
| FEAT-BENCHMARK | `features/benchmark` |
| FEAT-DIAGNOSTICS | `features/diagnostics` |
| FEAT-ROUTING | `features/routing` |
| FEAT-TOOLS | `features/tools` |
| FEAT-ADMIN | `features/admin` |
| FEAT-AI-REPORTING *(specs)* / FEAT-AI-CONTENT-REPORT *(prose)* | `features/ai-content-report` |
| ENGINE-LLAMACPP | `engines/llama-cpp` |
| ENGINE-LITERT | `engines/litert-lm` |
| ENGINE-MLC | `engines/mlc-llm` |
| ENGINE-MLLM | `engines/mllm` |
| ENGINE-ORTGENAI | `engines/ort-genai` |
| *(no ENGINE-\* ID)* | `engines/api` |

---

## Inventory completeness

| Check | Status |
|---|---|
| A — Docs package anchors | **PASS** (paths present with evidence) |
| B — Monorepo modules & trees | **PASS** (paths present with evidence) |
| C — FEAT / ENGINE catalogs extracted | **PASS** (with documented ID alias conflict) |
| D — Prior reports noted as non-authority | **PASS** |
| E — `audit-reports/` created + this file written | **PASS** |

*This artifact is inventory-only. Subsequent feature/engine/journey audits must re-open sources; do not inherit L2/L3 or QUALIFIED claims from prior reports without fresh evidence.*
