# 06 — Android Platform Audit

> Historical; CI policy as of `fix/ci-hermetic-gates`: hermetic root `check` has no digest/16kb/apk/sbom gates — 16 KB / digest / APK / SBOM gates run post-assemble.

| Field | Value |
|-------|--------|
| **Artifact** | `06_android.md` |
| **Scope** | Android platform: process topology, FGS, AIDL export, 16 KB native, SAF, targetSdk 36, packaging |
| **Audit date** | 2026-08-12 |
| **Docs authority** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo under audit** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **Out dir** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\audit-reports` |
| **Method** | `list_dir` / `read_file` / `grep` + live `check_elf_16kb_alignment.py` / `check_apk_16kb_zipalign.py` on existing build artifacts |
| **Fail-closed** | No path evidence ⇒ not PASS |
| **Status labels** | `PASS` \| `PARTIAL` \| `MISSING` \| `N_A` \| `BLOCKED_HUMAN` only |

> Prefer machine-readable `specs/` over prose when they conflict (YAML AIDL projection wins over `ANDROID-BINDER` prose sketch of method names).  
> Do **not** treat monorepo `FEATURE_AUDIT.md` / `BUILD_STATUS.md` / e2e notes as design authority — used only as non-authority hints for where to look.  
> No device OEM matrix, Play Console upload, or engine QUALIFIED/SUPPORTED claims invented.

### Completeness layers (this report)

| Layer | Meaning |
|-------|---------|
| **L1** | Module / manifest / types exist on disk |
| **L2** | Wired into control plane / merge graph / binder clients |
| **L3** | Product journey software-complete (legal start → service → durable effect) — not device/Play PASS |

---

## 0) Authority sources read

### Docs package `docs/60-android/`

| Doc ID | Path | Authority |
|--------|------|-----------|
| NAV-ANDROID | `docs/60-android/README.md` | GUIDANCE |
| ANDROID-BASELINE | `docs/60-android/android-baseline.md` | NORMATIVE |
| ANDROID-SERVICE | `docs/60-android/services-processes-fgs.md` | NORMATIVE |
| ANDROID-BINDER | `docs/60-android/binder-aidl-boundaries.md` | NORMATIVE |
| ANDROID-NATIVE | `docs/60-android/native-packaging-16kb.md` | NORMATIVE |
| ANDROID-STORAGE | `docs/60-android/storage-saf.md` | NORMATIVE |
| ANDROID-DIST | `docs/60-android/distribution-policy.md` | NORMATIVE |
| ANDROID-DEVICE | `docs/60-android/device-driver-memory.md` | NORMATIVE |

### Related design + specs (consulted)

| ID / file | Why |
|-----------|-----|
| `docs/20-architecture/process-trust-topology.md` (ARCH-TRUST-TOPOLOGY) | Process table `main` / `:runtime` / `:engine_worker` / `:parser` / `:sandbox_cpu` / companion |
| `specs/platform-policy-register.yaml` | PLAY-TARGET-API-2026, ANDROID-16KB, PLAY-FGS-DECLARATION, ANDROID-ISOLATED-PROCESS |
| `specs/aidl/omnillm-aidl.yaml` | Canonical AIDL surface (`binderTransactionBudgetBytes: 524288`) |

### Greps / paths exercised (non-exhaustive)

- `**/AndroidManifest.xml` under `android/` for `android:process`, `isolatedProcess`, `exported`, FGS types  
- `android/**/*.kt` for FGS (`startForeground`, `START_STICKY`), SAF (`OpenDocument`, `importLocalFile`), principal (`getCallingUid`), companion bind  
- `tools/ci/check_elf_16kb_alignment.py`, `check_apk_16kb_zipalign.py` executed against current artifacts  
- `EngineWorkerService` / `IsolatedParserService` cross-repo references for L2 host clients (none found outside own modules)

---

## 1) Executive summary

| Area | Status | One-line |
|------|--------|----------|
| **Toolchain / targetSdk 36** | **PASS** | Catalog lock `compileSdk=36` / `targetSdk=36` / NDK `28.2.x` |
| **Process topology (declared)** | **PARTIAL** | `main`, `:runtime`, `:engine_worker`, `:parser`, companion present; `:sandbox_cpu` constant-only; worker/parser **not L2-bound** from control plane |
| **FGS (inference specialUse)** | **PARTIAL** | Manifest + service + notification + lifecycle FSM present; product-complete long-running proof not claimed |
| **FGS (transfer dataSync)** | **PARTIAL** | Service/manifest/permissions present; **no production caller** of `TransferService.requestStart` found |
| **AIDL export rules** | **PARTIAL** | Exported vs non-exported split correct; UID/registration gates present; BIND permission raised to **signature** (stricter than prose “normal”); third-party pairing PENDING |
| **16 KB native packaging** | **PASS** (software gates) | Linker flags + NDK r28 + fail-closed CI; live scan **81** `.so` OK; release/debug APK zip-align OK (Python fallback) — **not** 16 KB page-size device cold-start matrix |
| **SAF / storage** | **PARTIAL** | App-private store + PFD materialize + UI OpenDocument + `importLocalFile`; strategy-2 persistable URI **not** implemented; no `takePersistableUriPermission` |
| **Packaging / distribution software** | **PARTIAL** | AAB/APK outputs exist; companion separate APK; no DFM/SplitCompat; R8 off; Play Console **BLOCKED_HUMAN** |
| **Device fingerprint / memory (ANDROID-DEVICE)** | **PARTIAL** | Contract type used; Android collector incomplete vs full fingerprint schema |
| **Overall ANDROID platform** | **PARTIAL** | Strong L1 + substantial L2 control-plane/UI; worker/parser/transfer L2 holes and L3 residual |

---

## 2) Target / compile SDK & toolchain lock

### Requirement (ANDROID-BASELINE §2, PLAY-TARGET-API-2026)

Play builds must lock **compile + target API 36** (as of register `2026-07-31`).

### Evidence

| Item | Value | Evidence |
|------|-------|----------|
| compileSdk | `36` | `gradle/libs.versions.toml` → `compileSdk = "36"` |
| targetSdk | `36` | `targetSdk = "36"` |
| minSdk | `28` | product decision lock |
| NDK | `28.2.13676358` | `ndk = "28.2.13676358"` |
| AGP / Gradle / JDK | `9.3.0` / `9.5.0` / `17` | same catalog |
| App apply | app-ui uses catalog | `android/app-ui/build.gradle.kts` `compileSdk` / `targetSdk` from `libs.versions.*` |
| Companion apply | same lock | `android/companion-sandbox/build.gradle.kts` |
| Manifest tools | `tools:targetApi="36"` | `android/app-ui/src/main/AndroidManifest.xml` |

**Status: PASS** (software lock present).  
**Not claimed:** Play Console submission, form-factor exceptions validation, full Android 16 behavior-change test matrix on devices → **BLOCKED_HUMAN** / residual for release gate only.

---

## 3) Process topology

### Requirement (ARCH-TRUST-TOPOLOGY §1 + ANDROID-BASELINE §3)

| Unit | UID | Role |
|------|-----|------|
| `main` | App UID | UI only; no native / no DB write |
| `:runtime` | App UID | Control plane, FGS, single writer, trusted engine host |
| `:engine_worker` | App UID | Crash containment only (not security sandbox) |
| `:parser` | isolated UID | Bounded metadata parse via RO FD |
| `:sandbox_cpu` | isolated UID | Untrusted CPU inference |
| Companion | different package/UID | Untrusted accelerated inference |

### 3.1 main (UI)

| Layer | Status | Evidence |
|-------|--------|----------|
| L1 | **PASS** | Module `:android:app-ui`; `applicationId = "com.omnillm"` |
| L2 | **PASS** | `OmniApplication` branches UI vs `:runtime`; UI binds **only** `AdminBindingService` (`AdminRuntimeConnection`) |
| L3 | **PARTIAL** | UI session + Admin path software-present; end-to-end journeys outside this audit |

Quotes:

```text
// OmniApplication.kt — main UI: no control plane / DB / native
ProcessIdentity.isMainUiProcess(this) -> {
    RuntimeProcessBootstrap.onMainUiProcessCreate(this)
    uiSession = UiSession(this).also { it.start() }
}
```

```text
// AdminRuntimeConnection.kt
* UI-process client that binds **only** [AdminBindingService]
* Must never open domain DB writers or load native engines from the UI process.
```

**Note:** App process still **packages** merged native libs (`useLegacyPackaging = false`) for AAB merge; INV-001 is about **load/write**, not zip contents.

### 3.2 `:runtime`

| Layer | Status | Evidence |
|-------|--------|----------|
| L1 | **PASS** | Services with `android:process=":runtime"` in `android/runtime-service/src/main/AndroidManifest.xml` |
| L2 | **PASS** | `RuntimeControlPlane.attach` only from runtime process bootstrap / services; ProcessIdentity checks |
| L3 | **PARTIAL** | Control plane hosts jobs/model-store/admin/features; full product journeys residual |

Services in `:runtime` (all non-exported except binding):

| Service | exported | FGS type | Evidence |
|---------|----------|----------|----------|
| `RuntimeForegroundService` | false | specialUse | manifest L57–69 |
| `RuntimeBindingService` | **true** | none | L75–83 |
| `AdminBindingService` | false | none | L86–93 |
| `TransferService` | false | dataSync | L99–107 |

### 3.3 `:engine_worker`

| Layer | Status | Evidence |
|-------|--------|----------|
| L1 | **PASS** | `EngineWorkerService` + `android:process=":engine_worker"` + `exported="false"` — `android/workers/src/main/AndroidManifest.xml` |
| L2 | **PARTIAL** | Merged via `api(project(":android:workers"))` in `runtime-service/build.gradle.kts`; **no** control-plane `bindService` / host client to `EngineWorkerService` found repo-wide |
| L3 | **MISSING** | Service comment: engine native “not wired here yet”; journal/command gate only |

Grep result: references to `EngineWorkerService` only under `android/workers/**` (no runtime host).

`ProcessTopology.WorkerPlacement` correctly rejects `ISOLATED_CPU_UNTRUSTED` / `EXTERNAL_UID_ACCELERATED` in same-UID worker — policy L1/L2 unit-testable, not full worker admission path.

### 3.4 `:parser` (isolatedProcess)

| Layer | Status | Evidence |
|-------|--------|----------|
| L1 | **PASS** | `IsolatedParserService` + `android:isolatedProcess="true"` + `android:process=":parser"` + `exported="false"` |
| L2 | **PARTIAL** | Merged via `api(project(":android:parser-isolated"))`; service implements RO FD registry + epoch fence; **no** runtime host binder client found |
| L3 | **MISSING** | Import/parse product path uses control-plane materialize; isolated parser not observed in journey wiring |

### 3.5 `:sandbox_cpu`

| Layer | Status | Evidence |
|-------|--------|----------|
| L1 | **MISSING** | Only string constants: `ProcessTopology.SANDBOX_CPU_SUFFIX = ":sandbox_cpu"` and `ProcessNames.SANDBOX_CPU_SUFFIX` — **no** `AndroidManifest` service with that process |
| L2 | **MISSING** | Placement policy routes untrusted CPU to isolated class / companion; no service bind path |
| L3 | **MISSING** | — |

Comment in `ProcessTopology.kt`: *“Not implemented in this module — companion owns accelerated untrusted (ADR-007).”*

### 3.6 Companion (different package / UID)

| Layer | Status | Evidence |
|-------|--------|----------|
| L1 | **PASS** | Separate application `:android:companion-sandbox`, `applicationId = "com.omnillm.companion"`; `CompanionSandboxService` exported under signature permission `BIND_SANDBOX` |
| L2 | **PARTIAL** | Host `CompanionHostClient` binds explicit component + signature permission + ticket handshake; placement gate refuses same-UID fallback |
| L3 | **PARTIAL** | Package present; untrusted accelerated product journey not asserted complete; INTERNET removed via `tools:node="remove"` |

**Not claimed:** co-install on device matrix, same-signer production signing ceremony → residual / **BLOCKED_HUMAN** for store multi-package.

### Process topology rollup

**Status: PARTIAL**  
Declared multi-process architecture is largely L1-complete; control plane + UI L2 strong; **worker/parser not control-plane-bound**; **sandbox_cpu absent**.

---

## 4) Foreground services (FGS)

### Requirement (ANDROID-SERVICE §1, §3–5; PLAY-FGS-DECLARATION)

- Inference FGS: non-exported, `specialUse`, base + `FOREGROUND_SERVICE_SPECIAL_USE`, subtype property  
- Transfer: separate `dataSync` + `FOREGROUND_SERVICE_DATA_SYNC`  
- Notification with real state + cancel → canonical command (not process kill)  
- `START_STICKY` ≠ recovery SLA  

### 4.1 Inference `RuntimeForegroundService`

| Check | Status | Evidence |
|-------|--------|----------|
| Non-exported | **PASS** | `android:exported="false"` |
| Process `:runtime` | **PASS** | `android:process=":runtime"` |
| Type specialUse | **PASS** | `android:foregroundServiceType="specialUse"` |
| Subtype property | **PASS** | `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` = on-device LLM inference control plane… |
| Permissions | **PASS** | FGS + SPECIAL_USE on runtime-service + app-ui merge |
| startForeground typed | **PASS** | `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_SPECIAL_USE)` |
| Notification + cancel | **PASS** | `RuntimeNotifications.runtimeNotification` + `ACTION_CANCEL` → drain path |
| Lifecycle states | **PASS** | `WAITING_FOR_USER_FOREGROUND` / RECOVERING / READY / DEGRADED / FAULTED text + controller |
| Legal UI start | **PASS** | `AdminRuntimeConnection.connect` + `AdminBindingService.onBind` call `RuntimeForegroundService.requestStart` |
| Device quota / Play declaration | **BLOCKED_HUMAN** | Checklist only: `gradle/RELEASE_CHECKLIST.md` |

**Software L1+L2: PASS.** **L3 product long-run / Console declaration: PARTIAL / BLOCKED_HUMAN.**

### 4.2 Transfer `TransferService`

| Check | Status | Evidence |
|-------|--------|----------|
| Non-exported + dataSync type | **PASS** | manifest |
| Permission DATA_SYNC | **PASS** | uses-permission present |
| startForeground typed | **PASS** | `FOREGROUND_SERVICE_TYPE_DATA_SYNC` |
| Notification + cancel action | **PASS** | `transferNotification` |
| Job cancel → canonical command | **PARTIAL** | Cancel zeroes `activeTransfers` and stops self; comment *“TODO job-manager”* / *scaffold only* |
| Product caller starts transfer FGS | **MISSING** | Grep: only `TransferService` itself calls `startForegroundService`; no ModelHub/download path invokes `TransferService.requestStart` |

**Status: PARTIAL** (scaffold + correct type separation; not product-wired).

### FGS rollup

**Status: PARTIAL**

---

## 5) AIDL / Binder export rules

### Requirement (ANDROID-SERVICE §1–2, §6; ANDROID-BINDER; specs/aidl)

- Exported binding returns runtime facade only — not Admin / secret broker / arbitrary files  
- Auth: calling UID + user + ClientRegistration + scope/epoch (app-defined normal permission is **not** auth boundary in prose)  
- Large payloads: PFD / handles; budget 524288 in YAML  
- Stream: half-open seq + ACK credit (YAML omits `grantCredit` on `IStreamSession` wire)

### 5.1 Export surface

| Component | exported | Permission | Binder returned | Status |
|-----------|----------|------------|-----------------|--------|
| `RuntimeBindingService` | true | `com.omnillm.permission.BIND_RUNTIME` | `OmniBindingFacade` → `IOmniBinding` only | **PASS** |
| `AdminBindingService` | false | (none) | `OmniAdminFacade` → `IOmniAdmin` | **PASS** |
| `RuntimeForegroundService` | false | — | null | **PASS** |
| `TransferService` | false | — | null | **PASS** |
| Worker / parser services | false | — | local binders | **PASS** |
| Companion service | true (other APK) | signature `BIND_SANDBOX` | narrow sandbox wire | **PASS** (L1/L2 structure) |

### 5.2 BIND_RUNTIME protection level (deviation note)

| Prose ANDROID-SERVICE | Implementation |
|-----------------------|----------------|
| “app-defined **normal** permission (discovery/filtering only)” | `android:protectionLevel="signature"` on `com.omnillm.permission.BIND_RUNTIME` |

Evidence comment in manifest: *“SEC-01: raised from normal → signature”*.  
Instrumented smoke asserts signature (`RuntimeServiceInstrumentedSmokeTest`).

**Verdict:** Still **PASS** on security intent (stricter gate); document as **intentional deviation** from prose discovery model. Third-party pairing remains PENDING (`OmniBindingFacade` same-app auto-approve; others `PENDING`).

### 5.3 Principal + registration

| Check | Status | Evidence |
|-------|--------|----------|
| `Binder.getCallingUid` observation | **PASS** | `PrincipalObservation.observe` |
| Package name not auth key | **PASS** | packages = display candidates only |
| ClientRegistration UID match | **PASS** | `ClientRegistrationStore` + unit tests spoof UID null |
| Unregistered openRuntime → null | **PASS** | `OmniBindingFacade.openRuntime` blank/invalid handle returns null |
| Admin asserts LOCAL_UI | **PASS** | `OmniAdminFacade` same-app principal |

### 5.4 AIDL surface vs YAML (authority)

| Check | Status | Evidence |
|-------|--------|----------|
| Spec → `.aidl` projections | **PASS** | 44 `.aidl` under `interfaces/aidl/src/main/aidl/ai/omnillm/api/`; `checkAidlDrift` in root `check` |
| Budget constant | **PASS** | `AidlAuthority.BINDER_TRANSACTION_BUDGET_BYTES = 524_288` |
| Large assets via PFD | **PASS** | `uploadAssetContent` / `importLocalFile` take `ParcelFileDescriptor` |
| `IStreamSession` wire | **PASS** vs YAML | `ackEvents` / `cancel` / `query` / `close` — matches YAML; prose `grantCredit` implemented **off-wire** on `StreamSessionFacade.grantCredit` |
| Credit/ACK engine | **PASS** (software) | `StreamCreditWindow` half-open seq + hard caps |

### 5.5 Gaps

| Gap | Status |
|-----|--------|
| Third-party pairing approval UX / durable ACL product path | **PARTIAL** (scaffold PENDING) |
| Parcel schemaVersion fields on all parcelables | **PARTIAL** — not audited field-by-field; not claimed PASS |
| Runtime binder oversize → 413 mapping | **N_A** / residual — docs note platform may not convert oversize |

**AIDL rollup: PARTIAL** (export topology + principal gates solid; pairing/product completeness incomplete; prose/YAML grantCredit difference documented as YAML-win).

---

## 6) 16 KB native packaging (ANDROID-NATIVE / ANDROID-16KB)

### Requirement

NDK r28+ preferred; dual linker flags when needed; scan all `.so`; APK/AAB uncompressed lib zip alignment; device cold-start on 16 KB pages for full claim.

### Evidence

| Gate | Status | Evidence |
|------|--------|----------|
| NDK lock r28.2 | **PASS** | `libs.versions.toml` |
| CMake flexible page sizes | **PASS** | `android/native/build.gradle.kts` `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` |
| Linker both flags | **PASS** | `CMakeLists.txt` `OMNILLM_PAGE_FLAGS` max-page-size + common-page-size 16384; applied via `target_link_options` |
| Policy constants | **PASS** | `NativePackagingNotes` / `AbiPackaging` |
| `useLegacyPackaging = false` | **PASS** | app-ui + companion + native packaging blocks |
| Fail-closed ELF scanner | **PASS** | `tools/ci/check_elf_16kb_alignment.py` — no `.so` = fail |
| Root `check` wires 16 KB | **PASS** | `build.gradle.kts` `checkNative16kb` + `:android:native:verifyNativeLibsPresent` |
| Live ELF scan (this audit) | **PASS** | `check_elf_16kb_alignment: OK checked=81 min_align=16384` |
| Live APK zip-align release | **PASS** | `app-ui-release.apk` → Python fallback OK (`zipalign` not on PATH) |
| Live APK zip-align debug | **PASS** | `app-ui-debug.apk` OK |
| ABI filters production | **PASS** | arm64-v8a + x86_64 only on app-ui |
| Load only packaged library name | **PASS** (policy) | `JniNativeBridge` / `System.loadLibrary` for packaged name; docs forbid arbitrary path |
| Dynamic Feature / SplitCompat engine modules | **MISSING** | No `com.android.dynamic-feature` / SplitCompat wiring found |
| 16 KB **device** cold-start / engine load matrix | **BLOCKED_HUMAN** / not present | Do not invent |

**16 KB software packaging rollup: PASS** (gates + live artifact checks).  
**Full ANDROID-NATIVE §2 device clause: PARTIAL / BLOCKED_HUMAN** (no device evidence in repo).

For this audit’s packaging checklist item (tools + flags + APK), score **PASS** with explicit non-claim of device matrix.

---

## 7) Storage / SAF (ANDROID-STORAGE)

### Requirement

- Model store / DB / trust in app-private / no-backup; SAF import/export only  
- Import strategy 1: immediate materialize to quarantine  
- Strategy 2: persistable URI + typed ImportSpec  
- PFD: dup, fstat, bounds  
- Atomic promote + backup exclude  

### Evidence

| Check | Status | Evidence |
|-------|--------|----------|
| App-private roots | **PASS** | `AndroidStorageRoots` → `filesDir` model-store/quarantine; `noBackupFilesDir` trust/journal; DB via `getDatabasePath` |
| External not active store | **PASS** | documented + layout under private files |
| Backup disabled | **PASS** | `android:allowBackup="false"` + exclude-all `backup_rules.xml` / `data_extraction_rules.xml` |
| PFD inspect/dup/materialize | **PASS** | `PfdMaterializeHelpers` |
| Typed ImportSpec data class | **PASS** | `data/model-store/.../MaterializeBounds.kt` `ImportSpec` |
| Strategy 1 UI → Admin PFD | **PASS** (L2) | `ModelHubScreen` `OpenDocument` → `ModelHubLocalImporter` → `IOmniAdmin.importLocalFile` → facade materialize pipeline |
| Strategy 2 persistable grant | **MISSING** | No `takePersistableUriPermission` in repo; helper builds ImportSpec only |
| Atomic promote | **PASS** (L1/L2 data layer) | model-store filesystem port used by control plane |
| L3 import journey on device | **PARTIAL** | Prior e2e notes (non-authority) reported stub issues; current code **has** OpenDocument + importLocalFile wiring — residual device proof not in this audit |

**SAF/storage rollup: PARTIAL**

---

## 8) Packaging & distribution (ANDROID-DIST software)

| Check | Status | Evidence |
|-------|--------|----------|
| Main AAB packaging config | **PASS** | app-ui `bundle { abi.enableSplit = true; density... }` |
| AAB artifact present | **PASS** | `android/app-ui/build/outputs/bundle/release/app-ui-release.aab` (observed) |
| APK release present | **PASS** | `app-ui-release.apk` |
| Companion separate APK | **PASS** | `applicationId com.omnillm.companion`; artifacts under companion `build/outputs/apk/` |
| Models not embedded as sole supply | **PASS** (design) | catalog/download/SAF paths; not audited every asset |
| Data Safety inventory doc | **PASS** (software artifact) | `android/app-ui/play/DATA_SAFETY_INVENTORY.md` |
| Release checklist human gates | **BLOCKED_HUMAN** | `gradle/RELEASE_CHECKLIST.md` FGS/Data Safety/AI reporting/signing |
| R8 minify on release | **PARTIAL** | `isMinifyEnabled = false` until keep-rules smoke |
| Engine DFM modules | **MISSING** | no dynamic feature modules |
| Play Console upload / approval | **BLOCKED_HUMAN** | not claimed |
| Side-load security same as Play | **PARTIAL** | same manifests; not empirically proven |

**Packaging rollup: PARTIAL**

---

## 9) Device / driver / memory (ANDROID-DEVICE) — secondary to task but in package

| Check | Status | Evidence |
|-------|--------|----------|
| `DeviceExecutionFingerprint` type | **PASS** (L1 contract) | `core/contracts` used by runtime/features |
| Full Android collector (SoC, page size, GPU/NPU, NNAPI…) | **PARTIAL** | UI stubs use `Build.FINGERPRINT` slices / hard-coded pageSizeBytes in places — not full ANDROID-DEVICE schema collector |
| Cross-UID `/proc` not sole memory control | **PASS** (policy comments + topology) | design-aligned; full governor device calibration residual |
| OEM quirk matrix evidence | **BLOCKED_HUMAN** / **MISSING** | not invent |

**Status: PARTIAL** (out of primary task depth; recorded for completeness).

---

## 10) L1 / L2 / L3 matrix (Android platform modules)

| Module | L1 exists | L2 wired to control plane / merge | L3 journey software-complete |
|--------|-----------|-------------------------------------|------------------------------|
| `:android:app-ui` main | **PASS** | **PASS** Admin bind + FGS request | **PARTIAL** |
| `:android:runtime-service` | **PASS** | **PASS** plane attach, binders, FGS | **PARTIAL** |
| `:android:workers` | **PASS** | **PARTIAL** merge only; no host bind | **MISSING** |
| `:android:parser-isolated` | **PASS** | **PARTIAL** merge only; no host bind | **MISSING** |
| `:android:companion-sandbox` | **PASS** | **PARTIAL** host client + gates | **PARTIAL** |
| `:android:native` | **PASS** | **PARTIAL**/engine attach path for llama | residual engine readiness outside this audit |
| `:interfaces:aidl` | **PASS** | **PASS** facades implement stubs | **PARTIAL** third-party |
| Transfer FGS | **PASS** | **MISSING** product start path | **MISSING** |
| `:sandbox_cpu` process | **MISSING** | **MISSING** | **MISSING** |

---

## 11) Findings catalog (actionable)

| ID | Severity | Status | Finding | Evidence |
|----|----------|--------|---------|----------|
| AND-01 | High | **PARTIAL** | `:engine_worker` service not bound by runtime control plane | No host client; only workers module |
| AND-02 | High | **PARTIAL** | `:parser` isolated service not bound by runtime | Same |
| AND-03 | Medium | **MISSING** | `:sandbox_cpu` process not implemented (constants only) | `ProcessTopology.SANDBOX_CPU_SUFFIX` |
| AND-04 | High | **MISSING** | `TransferService` never started from download/import product path | Grep only self-start |
| AND-05 | Medium | **PARTIAL** | Transfer cancel not fully canonical job-manager command | TransferService TODO |
| AND-06 | Low | Deviation | BIND_RUNTIME is signature not prose-normal | Manifest + SEC-01 comment |
| AND-07 | Medium | **MISSING** | SAF strategy-2 persistable URI grants | No `takePersistableUriPermission` |
| AND-08 | Medium | **MISSING** | Engine dynamic feature / SplitCompat lifecycle | Grep empty for DFM |
| AND-09 | Info | **PASS** | 16 KB ELF+APK software gates green on current artifacts | Live tool runs |
| AND-10 | Release | **BLOCKED_HUMAN** | Play FGS declaration, Data Safety submit, AI report Console, signing ceremony | RELEASE_CHECKLIST |
| AND-11 | Medium | **PARTIAL** | DeviceExecutionFingerprint full Android probe incomplete | UI fingerprint stubs |
| AND-12 | Medium | **PARTIAL** | Worker service: native engine load deferred | EngineWorkerService header comment |
| AND-13 | Info | **PASS** | UI↔Admin non-export; Runtime export facade separation | manifests + AdminRuntimeConnection |
| AND-14 | Info | **PASS** | targetSdk/compileSdk 36 catalog lock | libs.versions.toml |

---

## 12) What was searched when empty / residual

| Search | Result |
|--------|--------|
| `takePersistableUriPermission` | **0** hits in monorepo |
| `com.android.dynamic-feature` / `SplitCompat` | **0** product hits |
| Host bind to `EngineWorkerService` / `IsolatedParserService` outside own modules | **0** |
| `TransferService.requestStart` callers outside TransferService | **0** |
| `:sandbox_cpu` in AndroidManifest | **0** |
| Device 16 KB page-size OEM matrix artifacts | **none** claimed |
| Play upload receipts | **none** |

Empty after thorough search is documented here (valid residual).

---

## 13) Overall verdict

### ANDROID PLATFORM: **PARTIAL**

**Strong:**

- Multi-process **manifest design** for main / `:runtime` / `:engine_worker` / `:parser` / companion  
- Control plane L2: FGS specialUse, Admin + Runtime binders, principal observation, registration, stream credit, storage roots, PFD materialize  
- Toolchain **API 36** + NDK 28.2 + 16 KB **software** packaging gates (live OK)  
- SAF **strategy-1** UI path software-wired (`OpenDocument` → PFD → `importLocalFile`)  
- Companion different-package architecture + host client fail-closed placement  

**Weak / incomplete:**

- Worker + parser **not control-plane-bound** (L2 hole)  
- Transfer FGS **scaffold only** (L2 hole for downloads)  
- `:sandbox_cpu` **MISSING**  
- Persistable SAF, DFM, R8, Play Console **not software-complete / human**  
- No device OEM / 16 KB page-size **PASS** evidence  

### Do not claim

- Engines QUALIFIED/SUPPORTED  
- Play store upload or Console form approval  
- OEM device matrix PASS  
- Full L3 multi-process inference containment on device  

---

## 14) Evidence index (primary paths)

```
docs package:
  docs/60-android/*.md
  docs/20-architecture/process-trust-topology.md
  specs/platform-policy-register.yaml
  specs/aidl/omnillm-aidl.yaml

monorepo:
  gradle/libs.versions.toml
  gradle/RELEASE_CHECKLIST.md
  build.gradle.kts                          # checkNative16kb, checkAidlDrift
  tools/ci/check_elf_16kb_alignment.py
  tools/ci/check_apk_16kb_zipalign.py
  android/app-ui/src/main/AndroidManifest.xml
  android/app-ui/build.gradle.kts
  android/app-ui/src/main/kotlin/.../OmniApplication.kt
  android/app-ui/src/main/kotlin/.../admin/AdminRuntimeConnection.kt
  android/app-ui/src/main/kotlin/.../admin/ModelHubLocalImporter.kt
  android/app-ui/src/main/kotlin/.../screens/ModelHubScreen.kt
  android/app-ui/src/main/res/xml/backup_rules.xml
  android/app-ui/src/main/res/xml/data_extraction_rules.xml
  android/runtime-service/src/main/AndroidManifest.xml
  android/runtime-service/src/main/kotlin/.../service/RuntimeForegroundService.kt
  android/runtime-service/src/main/kotlin/.../service/TransferService.kt
  android/runtime-service/src/main/kotlin/.../service/RuntimeBindingService.kt
  android/runtime-service/src/main/kotlin/.../service/AdminBindingService.kt
  android/runtime-service/src/main/kotlin/.../binder/OmniBindingFacade.kt
  android/runtime-service/src/main/kotlin/.../binder/PrincipalObservation.kt
  android/runtime-service/src/main/kotlin/.../binder/StreamCreditWindow.kt
  android/runtime-service/src/main/kotlin/.../storage/AndroidStorageRoots.kt
  android/runtime-service/src/main/kotlin/.../storage/PfdMaterializeHelpers.kt
  android/runtime-service/src/main/kotlin/.../companion/CompanionHostClient.kt
  android/workers/src/main/AndroidManifest.xml
  android/workers/src/main/kotlin/.../ProcessTopology.kt
  android/workers/src/main/kotlin/.../EngineWorkerService.kt
  android/parser-isolated/src/main/AndroidManifest.xml
  android/parser-isolated/src/main/kotlin/.../IsolatedParserService.kt
  android/companion-sandbox/src/main/AndroidManifest.xml
  android/companion-sandbox/build.gradle.kts
  android/companion-sandbox/PACKAGING.md
  android/native/build.gradle.kts
  android/native/src/main/cpp/CMakeLists.txt
  android/native/src/main/kotlin/.../nativelib/AbiPackaging.kt
  interfaces/aidl/src/main/aidl/ai/omnillm/api/*.aidl
  interfaces/aidl/src/main/kotlin/.../AidlAuthority.kt
  data/model-store/.../MaterializeBounds.kt  # ImportSpec
```

### Live tool outputs (this audit)

```
check_elf_16kb_alignment: OK checked=81 min_align=16384
check_apk_16kb_zipalign: OK (python fallback)  # app-ui-release.apk
check_apk_16kb_zipalign: OK (python fallback)  # app-ui-debug.apk
```

---

## 15) Suggested next engineering close-outs (not executed)

1. Implement runtime **WorkerHostClient** / **ParserHostClient** bind + supervisor death + epoch fence (AND-01/02).  
2. Wire ModelHub download/import jobs to **TransferService.requestStart** + job-manager cancel (AND-04/05).  
3. Decide `:sandbox_cpu` isolated service vs companion-only policy and implement or explicitly N_A in product eligibility matrix (AND-03).  
4. Persistable SAF path only if deferred import jobs required (AND-07).  
5. Keep Play Console / signing / FGS declaration as **human** release checklist (AND-10).  

---

*End of `06_android.md`. Fail-closed audit against 新版本 docs package `docs/60-android` + monorepo paths only.*
