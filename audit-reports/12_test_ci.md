# 12 — Test & CI Readiness Audit

| Field | Value |
|---|---|
| **Auditor role** | Senior independent auditor (Test & CI readiness) |
| **Audit date** | 2026-08-12 |
| **Docs authority** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` (新版本) |
| **Implementation** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Artifact** | `audit-reports/12_test_ci.md` |
| **Method** | `list_dir` / `read_file` / `grep` / PowerShell inventory of `src/test`+`src/androidTest`; live run of dep-edge + 16 KB scripts; isolated re-run of one flaky suite; **no** full monorepo `./gradlew test` this pass |
| **Status vocabulary** | `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN` only |

> **Fail-closed:** no path evidence ⇒ not PASS. No invented device matrix, Play upload, or engine QUALIFIED/SUPPORTED. Product `specs/quality-scenarios.yaml` keeps Q-* evidence mostly `NOT_EXECUTED` (docs package) even when software gates exist.

---

## 0) Executive summary

| Area | Status | One-line |
|---|---|---|
| **Unit / host test inventory** | **PASS** (coverage breadth) | ~226 unit test source files across 42/44 modules; residual JUnit XML ~1537 cases |
| **Unit test execution green (current)** | **PARTIAL** | Residual suite once failed (`SessionManagerConcurrencyTest`); isolated re-run this audit **PASS**; full monorepo test not re-executed here |
| **Instrumentation / androidTest** | **PARTIAL** | Only **2** `androidTest` sources; multi-process E2E still thin (checklist OO-01) |
| **Connected CI job** | **PARTIAL** | `connected-tests` job wired, but **no GGUF provision** for `RealLlamaUpstreamInstrumentedTest` (hard fail without model) |
| **GitHub Actions `ci.yml`** | **PASS** (L2 wiring) | Contracts → dep gates → `test` → `check` → lint → assemble → 16 KB → artifacts |
| **GitHub Actions `release.yml`** | **PASS** (L2 wiring) | Fail-closed tag signing + apksigner + SBOM; no Play upload |
| **`local_ci.{ps1,sh}`** | **PASS** (L2 wiring) | Parity with PR CI; Windows + Unix; skip flags documented |
| **16 KB ELF + APK gates** | **PASS** (software tooling) | Live: `checked=81 min_align=16384`; debug APK zip-align OK (Python fallback) |
| **Dep edge / module rules** | **PASS** | Live: hard rules OK; soft WARNs only (modelhub→model-store, UI api-path→engines:api) |
| **PRODUCT_READINESS “unit PASS” claim** | **PARTIAL** | Claim text present; residual reports later showed 1 failure; isolated re-run green; claim not re-proven end-to-end this audit |
| **Q-015 formal product evidence** | **PARTIAL** / docs `NOT_EXECUTED` | Software scanners exist; product catalog still `evidenceStatus: NOT_EXECUTED`; no 16 KB **device** cold-start matrix |
| **detekt** | **N_A** | Intentionally not configured (`tools/ci/README.md`) |
| **Play upload automation** | **N_A** / **BLOCKED_HUMAN** | Explicit non-goal; `gradle/RELEASE_CHECKLIST.md` human-only |

### Overall readiness (software CI surface)

**PARTIAL → near-ready.** CI scaffolding, gates, and unit breadth are strong. Residual risks: (1) thin instrumentation + connected job GGUF gap; (2) at least one historically flaky concurrency unit test; (3) formal quality-scenario evidence packages largely still `NOT_EXECUTED` in product specs; (4) no remote GHA run status audited here.

---

## 1) Authority map (docs package + in-repo)

| Source | Role for this audit |
|---|---|
| `specs/quality-scenarios.yaml` (docs + mirrored in-repo) | Q-001…Q-020; most `evidenceStatus: NOT_EXECUTED`; Q-015 = 16 KB alignment + runtime |
| `docs/60-android/native-packaging-16kb.md` (ANDROID-NATIVE) | NDK r28+, ELF scan, zip-align `-P 16`, device cold start |
| `docs/60-android/android-baseline.md` | ABI / 16 KB / FGS / process baseline |
| `docs/00-product/quality-and-success-model.md` | Quality dimensions incl. negative tests |
| In-repo `AGENTS.md` | Dep edges; `checkModuleDependencyRules` + `checkDependencyEdges` fail-closed |
| In-repo `tools/ci/README.md` | Authoritative pipeline order |
| In-repo `PRODUCT_READINESS.md` / `PRODUCT_READINESS_CHECKLIST.md` | Claims under verification |
| In-repo `build.gradle.kts` | Root `test`, `check`, drift, 16 KB, dep tasks |

**Prefer machine-readable specs** when prose conflicts. Product Q-* cells stay honest (`NOT_EXECUTED`) unless evidence packs exist.

---

## 2) Unit / host tests inventory

### 2.1 Counts (source tree, excluding `build/`)

| Kind | Count | Evidence method |
|---|---|---|
| `*Test*.kt` under `src/test` | **226** files | PowerShell recursive inventory |
| `*Test*.kt` under `src/androidTest` | **2** files | Same |
| Total test-ish Kotlin files | **228** (includes ~9 `TestFixtures.kt`) | Same |
| Residual `TEST-*.xml` suites | **221** suites | `**/build/test-results/**` |
| Residual aggregate cases | **tests≈1537**, failures were **1** (pre re-run), skipped **1** | XML parse 2026-08-12 |

### 2.2 Unit files by top-level area

| Area | Unit `*Test*.kt` files (approx.) |
|---|---|
| `features/*` | 57 |
| `android/*` (host unit) | 44 |
| `runtime/*` | 44 |
| `engines/*` | 32 |
| `data/*` | 17 |
| `core/*` | 16 |
| `interfaces/*` | 16 |

### 2.3 Residual JUnit XML by area (pre isolated re-run)

| Area | Suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| features | 50 | 421 | 0 | 0 | 0 |
| engines | 35 | 289 | 0 | 0 | 1 |
| runtime | 43 | 290 | **1** | 0 | 0 |
| android | 44 | 252 | 0 | 0 | 0 |
| core | 16 | 131 | 0 | 0 | 0 |
| interfaces | 16 | 81 | 0 | 0 | 0 |
| data | 17 | 73 | 0 | 0 | 0 |

### 2.4 Modules with **no** unit `*Test*.kt` (of 44 includes)

| Module | androidTest | Notes |
|---|---|---|
| `:core:ports` | 0 | Port interfaces / models only; `testImplementation(junit)` present but **no** test sources → **MISSING** unit coverage |
| `:android:app-ui` | **1** | No `src/test`; instrumented smoke only |

All other included modules have at least one host unit test file.

### 2.5 Representative coverage (by domain)

| Domain | Example tests (path evidence) | L1 exists | L2 CI via `./gradlew test` |
|---|---|---|---|
| Canonical / golden identity | `core/canonical/.../GoldenIdentityEncodingTest.kt`, `core/identity/...` | PASS | PASS wiring |
| FSM / property | `core/state/.../StateMachinePropertyTest.kt`, `FsmIllegalEdgesFixtureTest.kt` | PASS | PASS wiring |
| Resource conservation | `core/resource/.../ResourceConservationPropertyTest.kt` | PASS | PASS wiring |
| Persistence / claim | `data/persistence/.../ClaimOrReturnConformanceTest.kt`, SqlDelight*Test | PASS | PASS wiring |
| Orchestrator / fail-closed capability | `runtime/orchestrator/.../UnknownCapabilityNegativeTest.kt` | PASS | PASS wiring |
| Session poison / pool | `runtime/session/.../PoisonedSession*.kt`, concurrency | PASS | PASS wiring (see flaky note) |
| Security / ACL / tokens | `runtime/policy/.../RevokedTokenNegativeSecurityTest.kt`, companion isolation | PASS | PASS wiring |
| HTTP wire / OpenAPI smoke | `interfaces/http/.../OpenApiRouteSmokeTest.kt`, security negatives | PASS | PASS wiring |
| Feature packs | each `features/*` has multi-file suites (admin, lan, tools, routing, …) | PASS | PASS wiring |
| Engines (host) | pipeline + lock + mapping tests for all 5 packs + api | PASS | PASS wiring |
| Companion / workers | `companion-sandbox/.../CompanionIsolationPolicyTest.kt`, workers gates | PASS | PASS wiring |
| Runtime-service control plane | `featurehost/*`, `EngineExecuteBindingTest`, AIDL security negatives | PASS | PASS wiring |

### 2.6 Unit execution evidence vs PRODUCT_READINESS

| Claim source | Claim | File evidence | Audit status |
|---|---|---|---|
| `PRODUCT_READINESS.md` Gate A | `.\gradlew.bat test --continue` → BUILD SUCCESSFUL (302 tasks) | Document only (as-of 2026-08-06) | **PARTIAL** — not re-run full suite this audit |
| `GAP_CLOSEOUT.md` §A | BUILD SUCCESSFUL, 302 tasks | Same narrative | **PARTIAL** |
| `PRODUCT_READINESS_CHECKLIST.md` D2 / SW-BUILD-02 | PASS / DONE | Points at `test-verify-gaps.txt` | **PARTIAL** — log file present; binary/encoding issues reading body via text tools; mtime 2026-08-06 |
| Residual XML (before re-run) | — | `SessionManagerConcurrencyTest.concurrentOfferAndAcquire_poolInvariantHolds` **FAILURE** timestamp `2026-08-10T18:23:14Z` | Contradicts “always green” without re-run |
| This audit isolated re-run | `:runtime:session:test --tests …SessionManagerConcurrencyTest` | **BUILD SUCCESSFUL** (4 tests, 0 failures) timestamp `2026-08-12T03:45:59Z` | Flaky or environment-sensitive race |

**Conclusion:** Host test **surface** is broad and CI-wired (**PASS** L1/L2). “Currently green” for the full monorepo is **PARTIAL** until a fresh full `./gradlew test` log is captured after the 2026-08-10 residual failure. Do **not** treat PRODUCT_READINESS Gate A as live proof without re-execution.

---

## 3) Instrumentation / androidTest

### 3.1 Inventory (complete)

| Module | File | Intent |
|---|---|---|
| `:android:app-ui` | `android/app-ui/src/androidTest/.../RuntimeServiceInstrumentedSmokeTest.kt` | Package id, BIND_RUNTIME resolvable, `:runtime` process, exported binding surface |
| `:android:runtime-service` | `android/runtime-service/src/androidTest/.../RealLlamaUpstreamInstrumentedTest.kt` | Real `libomnillm_llama.so` + GGUF load/generate on device/emulator |

No other modules ship `src/androidTest` test sources (companion declares `androidTestImplementation` only).

### 3.2 CI wiring (`ci.yml` job `connected-tests`)

```yaml
# .github/workflows/ci.yml — connected-tests
uses: reactivecircus/android-emulator-runner@v2
api-level: 34
arch: x86_64
script:
  ./gradlew :android:app-ui:assembleDebug :android:runtime-service:assembleDebug
  ./gradlew :android:runtime-service:connectedDebugAndroidTest
               :android:app-ui:connectedDebugAndroidTest
```

| Check | Status | Evidence |
|---|---|---|
| Job present, fail-closed (`continue-on-error: false`) | **PASS** | `ci.yml` lines ~330–402 |
| Separate from unit/assemble job | **PASS** | Documented so emulator flakes do not block APK upload job design intent |
| GGUF / model asset provisioned for RealLlama test | **MISSING** | No `gguf` / `adb push` / assets step in workflow; test **throws** if GGUF absent |
| `local_ci` runs connected tests | **MISSING** | `local_ci.ps1` / `.sh` stop at unit + assemble + 16 KB |
| Multi-process / companion / worker instrumented E2E | **PARTIAL** | Checklist SW-XPORT-04 / OO-01; only thin UI smoke |

**Impact:** As written, `RealLlamaUpstreamInstrumentedTest` will fail closed on every CI emulator run that lacks a pre-pushed GGUF (~287 MiB model under test package files). PRODUCT_READINESS_CHECKLIST claims SW-ENG-09 PASS on a human emulator with adb push — that is **not** automated in GHA.

### 3.3 Manual / exploratory E2E (not CI gate)

| Artifact | Verdict | Note |
|---|---|---|
| `APPIUM_E2E_REPORT.md` | **SMOKE_PARTIAL** | Appium on emulator; model staged in Download, **not** in private model-store; no engine PASS invented |
| `e2e-artifacts/*` | Screenshots / notes | Human exploratory only |

Status: **BLOCKED_HUMAN** for product first-success journey automation; software-only L3 product journeys incomplete.

---

## 4) CI workflows

### 4.1 `.github/workflows/ci.yml` — status **PASS** (structure)

| Step / gate | Present | Fail-closed |
|---|---|---|
| Triggers: push/PR `main|master|develop`, `workflow_dispatch` | Yes | — |
| JDK 17 + Python 3.12 + Gradle + Android SDK API 36 / build-tools 36.0.0 / NDK `28.2.13676358` | Yes | License accept |
| Specs authority file list | Yes | exit 1 if missing |
| `checkContractDrift` before regenerate | Yes | Yes |
| `generateContracts` + clean git on generated trees | Yes | Dirty/untracked fail |
| `checkModuleDependencyRules` + Python double-run | Yes | Yes |
| `checkDependencyEdges` + Python double-run | Yes | Yes |
| `./gradlew test` (JVM + Android host unit) | Yes | Yes (`--continue` still fails step on any failure) |
| `./gradlew check` (drift + AIDL + 16 KB + dep edges + module rules + `verifyNativeLibsPresent`) | Yes | Yes |
| Lint `app-ui` + `companion-sandbox` debug | Yes | Yes |
| assembleDebug + assembleRelease (unsigned) | Yes | Yes |
| `verifyNativeLibsPresent` + ELF 16 KB + APK zip-align | Yes | No `.so` fails |
| Assert APK dirs non-empty | Yes | Yes |
| Upload APK artifacts (`if-no-files-found: error`) | Yes | Yes |
| Upload test reports on failure | Yes | ignore if none |
| `connected-tests` job | Yes | Yes (but GGUF gap — §3) |
| detekt | Explicitly skipped | Documented N_A |
| Play upload | Absent | N_A |

**Note:** `checkAidlDrift` is **not** a named workflow step; it is aggregated by root `check` (`build.gradle.kts` `dependsOn(checkAidlDrift)`). Local scripts call it explicitly — parity OK for fail-closed intent.

### 4.2 `.github/workflows/release.yml` — status **PASS** (structure)

| Item | Status | Evidence |
|---|---|---|
| Tag `v*` + `workflow_dispatch` | PASS | workflow `on:` |
| Contract + dep + unit + check | PASS | release job steps |
| Tag push requires **all four** signing secrets | PASS | fail-fast if missing on `push` |
| Manual dispatch may produce unsigned (warning) | PASS | documented |
| Signing via `ORG_GRADLE_PROJECT_*` env (not argv) | PASS | SEC-09 comment |
| `apksigner verify` on signed APKs | PASS | fail closed |
| Optional AAB `bundleRelease` | PASS | input `build_aab` |
| 16 KB gates on release APKs | PASS | step present |
| Lightweight CycloneDX-ish SBOM | PASS | generated under `sbom/` |
| Scrub keystore always | PASS | `if: always()` |
| Play Console upload | **N_A** | not wired |

### 4.3 Dependabot — status **PASS** (config present)

`C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\.github\dependabot.yml`  
Weekly Gradle + GitHub Actions updates; human review required against toolchain lock.

### 4.4 Remote GHA run status

**BLOCKED_HUMAN / N_A for this audit:** no GitHub Actions run URL or green check audited from this host. Presence of workflow YAML ≠ remote green.

---

## 5) Local CI parity (`tools/ci/local_ci`)

| Surface | Path | Status |
|---|---|---|
| Unix | `tools/ci/local_ci.sh` | **PASS** exists |
| Windows | `tools/ci/local_ci.ps1` | **PASS** exists |
| Docs | `tools/ci/README.md` | **PASS** authoritative pipeline |

### Pipeline order (local; fail closed)

0. `check_dependency_edges.py`  
1. Specs authority present (11 critical files)  
2. pip install codegen requirements  
3. `checkContractDrift`  
4. `generateContracts` + git clean on generated  
5. `checkAidlDrift`  
6. `checkModuleDependencyRules`  
7. `checkDependencyEdges` (Gradle)  
8. `./gradlew test --continue`  
9. `./gradlew check`  
10. Lint app-ui + companion (skippable)  
11. assembleDebug (+ release unless skip)  
12. ELF 16 KB + per-APK zip-align  

Flags: `--skip-assemble` / `-SkipAssemble`, `--skip-lint` / `-SkipLint`, `--skip-release` / `-SkipRelease`.

| Parity vs `ci.yml` | Status |
|---|---|
| Unit + contracts + deps + lint + assemble + 16 KB | **PASS** |
| APK artifact assert | **PASS** (local throws if no APKs after assemble) |
| `connected-tests` / emulator | **MISSING** from local_ci (by design; optional human) |
| `verifyNativeLibsPresent` explicit step | Covered via root `check` dependency |

---

## 6) 16 KB packaging gates

### 6.1 Tooling (L1) — **PASS**

| Artifact | Path | Behavior |
|---|---|---|
| ELF scanner | `tools/ci/check_elf_16kb_alignment.py` | Fail closed if **no** `.so` or `p_align < 16384` |
| APK zip-align | `tools/ci/check_apk_16kb_zipalign.py` | Prefers `zipalign -c -P 16`; pure-Python fallback for stored `lib/**/*.so` |
| Root Gradle | `checkNative16kb` → ELF script | In root `check` |
| Native module | `android/native` `checkElf16kbAlignment`, `verifyNativeLibsPresent` | BLD-13 packaging proof |
| NDK lock | `28.2.13676358` in workflows + catalog | Aligns ANDROID-NATIVE r28+ |
| Flexible page sizes | `android/native/build.gradle.kts` `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` | Documented in prior android audit |

### 6.2 Live execution this audit

| Command | Result |
|---|---|
| `python tools/ci/check_elf_16kb_alignment.py --min-align 16384` | **`OK checked=81 min_align=16384`** exit 0 |
| `python tools/ci/check_apk_16kb_zipalign.py` on `app-ui-debug.apk` | **OK (python fallback)** (zipalign binary not on PATH) |
| androidTest APK zip-align | OK (python fallback) |

### 6.3 Product Q-015 / device runtime

| Layer | Status | Notes |
|---|---|---|
| Software alignment scanners | **PASS** | Live evidence above |
| Product `quality-scenarios.yaml` Q-015 | **PARTIAL** | Docs package: `evidenceStatus: NOT_EXECUTED`, `evidenceRequired: true` |
| 16 KB page-size device cold-start matrix | **BLOCKED_HUMAN** / **MISSING** | ANDROID-NATIVE §2 device step; do not invent PASS |

---

## 7) Dependency edge gates

### 7.1 Dual scanners (intentional belt-and-suspenders)

| Gate | Script / task | Scope |
|---|---|---|
| Direct forbidden pairs | `tools/ci/check_dependency_edges.py` + `./gradlew checkDependencyEdges` | UI↛engines/data, workers/companion/parser↛data, engines↛persistence |
| Graph / API-path rules | `tools/ci/check_module_dependency_rules.py` + `./gradlew checkModuleDependencyRules` | Hard edges + api-path UI→persistence/native engines; soft debt warnings |

Root `check` depends on both (+ AIDL drift + 16 KB + native verify).

### 7.2 Live execution this audit — **PASS** (hard)

```
check_dependency_edges: OK modules_with_project_deps=41 warnings=3
check_module_dependency_rules: OK: hard dependency boundary rules passed
```

**Soft WARN only (do not fail CI):**

1. `:features:modelhub` → `:data:model-store` (ARC-02 debt)  
2. `:android:app-ui` → `:android:runtime-service` (manifest/process merge; INV-001 attach still process-gated)  
3. API-path UI → `:engines:api` via auto-setup / routing / orchestrator (prefer Admin projections)

---

## 8) Root Gradle verification model

From `build.gradle.kts` (evidence):

| Task | Aggregates |
|---|---|
| `test` | `jvmTest` (all kotlin.jvm) + `androidUnitTest` (`testDebugUnitTest` for AGP modules) |
| `check` | `checkContractDrift`, `checkAidlDrift`, `checkNative16kb`, `checkDependencyEdges`, `checkModuleDependencyRules`, `:android:native:verifyNativeLibsPresent` |
| Not in root `test` | `connectedDebugAndroidTest` (explicitly documented — device/emulator only) |

This fixes the historical gap where only pure JVM tests ran and Android host suites under `:android:runtime-service` were skipped (commented in `ci.yml`).

---

## 9) PRODUCT_READINESS claim verification

| Claim | Claimed | Verified this audit | Status |
|---|---|---|---|
| A. Unit / host tests PASS | Yes (302 tasks) | Residual failure history + no full re-run; isolated flaky suite now green | **PARTIAL** |
| B. assembleDebug/Release app-ui + companion | Yes | Prior logs / APKs present; not re-assembled fully here | **PARTIAL** (artifacts exist; not re-proven) |
| C–E engine honesty / wiring | Yes | Out of deep scope; not contradicted by CI config | See engines audit |
| CI workflows present | Yes | Files read | **PASS** |
| Local parity scripts | Yes | Files read | **PASS** |
| Full GHA green on remote | Explicitly not claimed as executed | Not audited | **N_A** |
| Play upload automation | None | Confirmed absent | **N_A** |
| detekt | Intentionally skipped | Confirmed | **N_A** |
| SW-ENG-09 instrumented GGUF PASS | Checklist DONE on human emulator | CI does not provision GGUF | **PARTIAL** (human evidence ≠ automated CI) |
| SW-BUILD-07 CI gates DONE | Yes | YAML matches claim | **PASS** (wiring) |
| SW-XPORT-04 multi-process E2E | PARTIAL in checklist | Confirmed thin androidTest | **PARTIAL** |

---

## 10) Quality scenarios (product) vs software tests

| Scenario | Product evidenceStatus (docs package) | Software test / gate touchpoint | Audit |
|---|---|---|---|
| Q-004 conservation | NOT_EXECUTED | `ResourceConservationPropertyTest` | L1 software only |
| Q-005 SSE disconnect | NOT_EXECUTED | `SseDisconnectClaimSemanticsTest` etc. | L1 software only |
| Q-008 different-UID adversarial | NOT_EXECUTED | Companion isolation unit tests; not full multi-UID device suite | PARTIAL |
| Q-011 golden identity | NOT_EXECUTED | Golden* tests core/identity | L1 software only |
| Q-014 binder not admin | NOT_EXECUTED | `RuntimeServiceInstrumentedSmokeTest` partial; external app manifest adversarial **MISSING** | PARTIAL |
| Q-015 16 KB | NOT_EXECUTED | ELF/APK software gates **PASS**; device matrix **MISSING** | PARTIAL |
| Q-017 docs integrity | PASS (docs package) | Product validator | N_A monorepo |
| Q-020 a11y first-success | NOT_EXECUTED | Appium SMOKE_PARTIAL only | BLOCKED_HUMAN |

Software tests reduce risk but **do not** automatically flip product `evidenceStatus` without formal evidence packs.

---

## 11) Gap list (prioritized)

| ID | Severity | Finding | Status |
|---|---|---|---|
| CI-01 | High | `connected-tests` runs `RealLlamaUpstreamInstrumentedTest` without GGUF provision → systematic fail or requires manual artifact | PARTIAL / fix needed |
| CI-02 | Medium | Host unit residual failure in `SessionManagerConcurrencyTest` (2026-08-10); may be flaky race | PARTIAL — re-run green |
| CI-03 | Medium | Only 2 instrumented classes; multi-process / companion / FGS / revocation device journeys thin | PARTIAL |
| CI-04 | Medium | `:core:ports` and `:android:app-ui` lack host unit tests | PARTIAL |
| CI-05 | Low | `local_ci` omits connected tests (documented tradeoff) | N_A if intentional |
| CI-06 | Low | detekt not configured | N_A intentional |
| CI-07 | Info | Product Q-* still mostly NOT_EXECUTED despite software gates | PARTIAL honesty OK |
| CI-08 | Info | Remote GHA status not verified on this host | BLOCKED_HUMAN |
| CI-09 | Low | Soft dep WARNs (modelhub→model-store, UI api→engines:api) remain debt | PARTIAL debt |
| CI-10 | Info | Appium report SMOKE_PARTIAL; not a CI gate | N_A |

---

## 12) L1 / L2 / L3 distinction

| Layer | Definition | Test & CI finding |
|---|---|---|
| **L1** module exists | Test sources / scripts / workflow files present | Broad unit L1 **PASS**; instrumented L1 thin **PARTIAL**; CI scripts L1 **PASS** |
| **L2** wired to control plane / CI | Gates invoked by `ci.yml` / root `check` / `local_ci` | Unit + contracts + deps + 16 KB + assemble **PASS**; connected GGUF **PARTIAL** |
| **L3** product journey software-complete | First-success, device isolation, Q-evidence packages | **PARTIAL** — Appium partial; Q scenarios NOT_EXECUTED; no invented device PASS |

---

## 13) What was grepped / read (empty-findings discipline)

| Path / pattern | Purpose |
|---|---|
| `list_dir` monorepo root, `tools/ci`, `.github/workflows`, `audit-reports` | Map surface |
| Full read: `ci.yml`, `release.yml`, `local_ci.ps1`, `local_ci.sh`, `tools/ci/README.md` | Pipeline truth |
| Full/partial read: `build.gradle.kts`, dep scripts, 16 KB scripts | Gate semantics |
| `PRODUCT_READINESS.md`, `PRODUCT_READINESS_CHECKLIST.md`, `GAP_CLOSEOUT.md`, `APPIUM_E2E_REPORT.md` | Claim verification |
| Docs: `quality-scenarios.yaml`, `native-packaging-16kb.md`, android baseline hits | Normative requirements |
| PowerShell inventory `src/test` + `src/androidTest` `*Test*.kt` | Test inventory |
| Parse residual `TEST-*.xml` | Execution evidence |
| Live: `check_dependency_edges.py`, `check_module_dependency_rules.py`, `check_elf_16kb_alignment.py`, APK zip-align | Gate health |
| Live: `:runtime:session:test --tests SessionManagerConcurrencyTest` | Flake follow-up |
| Grep: gguf/adb in `ci.yml` → **no matches** | Connected GGUF gap proof |

---

## 14) Scorecard (copy-out)

| Gate | Status |
|---|---|
| Unit test inventory breadth | **PASS** |
| Unit test CI wiring (`./gradlew test`) | **PASS** |
| Unit test current green (full suite) | **PARTIAL** |
| Instrumentation inventory | **PARTIAL** |
| Connected CI job completeness | **PARTIAL** |
| `ci.yml` software pipeline | **PASS** |
| `release.yml` software pipeline | **PASS** |
| `local_ci` parity | **PASS** |
| 16 KB software gates | **PASS** |
| 16 KB device evidence (Q-015 product) | **PARTIAL** / **BLOCKED_HUMAN** |
| Dep edge gates | **PASS** |
| Contract / AIDL drift in `check` | **PASS** |
| detekt | **N_A** |
| Play upload automation | **N_A** |
| PRODUCT_READINESS claim fidelity | **PARTIAL** |

---

## 15) Recommended next actions (audit only — not implemented)

1. **CI-01:** Either (a) provision a small fixture GGUF (or synthetic path) in `connected-tests` with `Assume`/skip when absent, or (b) move RealLlama instrumented test to a manual/nightly workflow with model cache. Fail-closed without hanging PR CI on missing assets.  
2. **CI-02:** Stabilize `SessionManagerConcurrencyTest.concurrentOfferAndAcquire_poolInvariantHolds` (reproducible race); keep under CI.  
3. Capture a fresh full `./gradlew test` + `local_ci.ps1 -SkipAssemble` log after fixes; update PRODUCT_READINESS with new timestamp.  
4. Expand androidTest only where process topology cannot be faked (INV-001, companion UID, exported binder).  
5. Keep product Q-* evidence honest until formal packs exist.

---

## 16) Absolute path index (key evidence)

```
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\.github\workflows\ci.yml
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\.github\workflows\release.yml
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\.github\dependabot.yml
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\local_ci.ps1
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\local_ci.sh
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\README.md
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\check_dependency_edges.py
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\check_module_dependency_rules.py
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\check_elf_16kb_alignment.py
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\tools\ci\check_apk_16kb_zipalign.py
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\build.gradle.kts
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\PRODUCT_READINESS.md
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\PRODUCT_READINESS_CHECKLIST.md
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\APPIUM_E2E_REPORT.md
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\app-ui\src\androidTest\kotlin\com\omnillm\ui\RuntimeServiceInstrumentedSmokeTest.kt
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\runtime-service\src\androidTest\kotlin\com\omnillm\android\runtimeservice\native\RealLlamaUpstreamInstrumentedTest.kt
C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\runtime\session\src\test\kotlin\com\omnillm\runtime\session\SessionManagerConcurrencyTest.kt
C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\specs\quality-scenarios.yaml
C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents\docs\60-android\native-packaging-16kb.md
```

---

*End of audit report 12_test_ci.md*
