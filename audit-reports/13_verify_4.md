# Adversarial verify — Claim #4

**Claim:** ModelHub local import SAF path is software-complete for GGUF  
**Auditor:** independent re-inspection (docs package 新版本 + monorepo source only)  
**Date:** 2026-08-12  
**Method:** `list_dir` / `grep` / `read_file` on real paths; fail-closed if no evidence; default `real=false` if uncertain.  
**Docs authority:** `OmniLLM_Product_Documents` 新版本 (`FEAT-MODELHUB`, `ANDROID-STORAGE`, `CORE-MODEL` / `model-platform.md`).

## Verdict

| Field | Value |
|---|---|
| **real** | **false** |
| **software_status** | **PARTIAL** (L1 exists · L2 strategy-1 SAF→PFD→`importLocalFile`→pipeline wired · L3 GGUF product journey **not** software-complete) |
| **device_status** | **N_A** for inventing PASS; last checked Appium journey for SAF GGUF was **FAIL** (stale UI stub narrative; no replacement device evidence of success under current `OpenDocument` wiring) |

### reason

The monorepo **does** implement a real strategy-1 local-import control path labeled for GGUF:

UI `OpenDocument` → hash content URI → RO PFD → `IOmniAdmin.importLocalFile` → `AcquisitionPipeline.executeSafImport(expectedFormat="gguf")` → quarantine materialize → identity verify (digest) → FSM `COMPATIBILITY_CHECK` → `promoteToReady` → job SUCCEEDED, with acquisition channel `LOCAL_IMPORT` and risk flag `SOURCE_UNVERIFIED` on the card projection.

That is **L1 + L2 software wiring**, not full **L3 “software-complete for GGUF”** under product docs:

1. **GGUF is a label, not validated content.** Import does not sniff GGUF magic (`GGUF`) or run the isolated typed parser before READY. Any octet stream whose UI-computed SHA-256 matches the declared digest can be promoted with `expectedFormat = "gguf"`.
2. **CORE-MODEL identity pipeline is short-circuited.** UI mints `ModelRevisionId` from simplified JSON `{"artifactPackageId":…,"format":"gguf","schemaVersion":1}` without trusted parser output (architecture / tokenizer / quantization descriptors required by docs §3).
3. **FEAT-MODELHUB §5–§6 install steps are incomplete.** Promote path does digest/identity store checks and FSM transitions; it does **not** invoke `IsolatedParseEngine`, dry-load, or smoke generation as compatibility evidence before READY.
4. **Trust evaluation for LOCAL_IMPORT is not honest at promote time.** Production `DefaultTrustEvaluationPort` always returns `authenticityOk = true` / `licenseOk = true` / `PRIVILEGED_TRUSTED`, which is required by `promoteToReady` gates — contradicting “local import has no authenticity guarantee” (CORE-MODEL §2 Local Import / FEAT-MODELHUB §5). UI only stamps `SOURCE_UNVERIFIED` via channel projection, not via evaluation dimensions.
5. **Unit/host “SAF import” tests use synthetic fixture bytes**, explicitly **not** real GGUF weights (`FixtureArtifact` KDoc).
6. **Large GGUF product path is not production-hardened:** full-file hash in UI process + full re-copy on binder `runBlocking` inside `importLocalFile`; no `TransferService` / FGS handoff found for this path; strategy-2 persistable URI path is typed-only (`ImportSpec` helper) with **zero** `takePersistableUriPermission` usage.
7. **No current in-repo device evidence** that a real gemma/other GGUF completes SAF → READY under the wired `OpenDocument` path. `APPIUM_E2E_REPORT.md` records journey **C FAIL** (but quotes an older stub `onClick`; current tree has real launchers — report is **stale**, not a PASS).

Therefore the claim as stated is **not real**. Closest accurate status: **strategy-1 SAF import software path exists and is control-plane-wired; GGUF-complete install journey is PARTIAL**.

---

## Layer map (this claim)

| Layer | Meaning | Status | Evidence summary |
|---|---|---|---|
| **L1** | Module / API exists | **PASS** | `:features:modelhub` `startImport` / `executeSafImport`; `ModelHubLocalImporter`; `PfdMaterializeHelpers`; AIDL `importLocalFile` |
| **L2** | Wired to control plane / UI | **PASS** (strategy 1) | `OmniNavHost` injects importer; `ModelHubScreen` `OpenDocument` + `IMPORT`; `OmniAdminFacade.importLocalFile` → `ModelhubModule.createAcquisitionPipeline` on plane |
| **L3** | Product journey software-complete **for GGUF** | **PARTIAL / not complete** | End-to-end READY possible for labeled bytes; missing GGUF parse/identity, honest LOCAL_IMPORT trust, FGS/large-file, device proof |

---

## evidence (what *is* implemented)

### Docs requirement (authority)

| Doc | Path | Normative ask |
|---|---|---|
| ANDROID-STORAGE §2–§4 | `…/docs/60-android/storage-saf.md` | Strategy 1: immediate dup/read materialize to quarantine; strategy 2: persistable URI + typed ImportSpec; PFD inspect/bounds; atomic promote |
| FEAT-MODELHUB §5–§6 | `…/docs/70-features/modelhub-acquisition.md` | SAF → quarantine with bounds; UI “來源未驗證”; parser/dry-load/smoke = compatibility only; Identity → source/trust → license → bounded parser → compatibility → fsync → atomic promote → READY |
| CORE-MODEL §2–§4 | `…/docs/30-core-platform/model-platform.md` | Local Import: SAF/PFD materialize or recoverable grant; **no authenticity guarantee**; identity includes **trusted typed parser** for format metadata |

### UI SAF entry (strategy 1)

**File:** `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\app-ui\src\main\kotlin\com\omnillm\ui\screens\ModelHubScreen.kt`

- `ActivityResultContracts.OpenDocument()` launcher  
- On URI: `localImporter.hashUri` then `localImporter.importHashed`  
- Detail action `"IMPORT"` → `openDocument.launch(arrayOf("application/octet-stream", "*/*"))`  
- Copy: “Pick a GGUF file…” / default display “Imported GGUF”  
- Lab button: `importFromPublicE2ePath` for `gemma-3-270m-Q8_0.gguf`

**File:** `…\android\app-ui\src\main\kotlin\com\omnillm\ui\navigation\OmniNavHost.kt`

```text
ModelHubScreen(
    viewModel = session.modelHubVm,
    localImporter = session.adminConnection.admin?.let {
        com.omnillm.ui.admin.ModelHubLocalImporter(it)
    },
    …
)
```

### UI → Admin PFD (no model-store writes in UI)

**File:** `…\android\app-ui\src\main\kotlin\com\omnillm\ui\admin\ModelHubLocalImporter.kt`

KDoc: *“open SAF/content URI, hash bytes, pass RO PFD to runtime via IOmniAdmin.importLocalFile. UI never writes model-store / DB (INV-001 / ADR-010).”*

- Hashes stream; builds `ArtifactPackageId` from weights role + blob;  
- `modelRevisionId` from JSON with **`"format":"gguf"`** (not GGUF-v3 parser output);  
- `admin.importLocalFile(pfd, displayName, sha256, bytes, rev, pkg, installationId, jobId, cmd)`.

### AIDL contract

**File:** `…\interfaces\aidl\src\main\aidl\ai\omnillm\api\IOmniAdmin.aidl`

```text
* LOCAL_UI local-file / SAF import: UI opens the document and passes a
* read-only PFD + content digests. Runtime materializes via AcquisitionPipeline
* (quarantine → verify → READY). Never elevates trust beyond LOCAL_IMPORT.
OmniJobInfo importLocalFile(in ParcelFileDescriptor contentFd, …);
```

### Runtime control-plane execution

**File:** `…\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\binder\OmniAdminFacade.kt` (`importLocalFile`)

- `assertLocalUi()`; validates 64-hex digest / sizes / ids  
- `ParcelFileDescriptor.dup` for stable FD  
- `runBlocking { pipeline.executeSafImport(…, expectedFormat = "gguf") }`  
- Source opens `FileInputStream` on dup FD (role `weights` only)

### Acquisition pipeline + ModelHub service

**Files:**

- `…\features\modelhub\src\main\kotlin\com\omnillm\features\modelhub\acquisition\AcquisitionPipeline.kt` — `executeSafImport`: `startImport` → `beginAcquisitionAttempt` → stream materialize → `completeAcquisitionMaterialize`  
- `…\features\modelhub\src\main\kotlin\com\omnillm\features\modelhub\usecase\ModelHubService.kt` — `startImport` uses `AcquisitionChannel.LOCAL_IMPORT` + `JobKind.IMPORT`; `completeAcquisitionMaterialize` → `materializeComplete` → `beginVerify` → `completeIdentityVerify` → `promoteToReady` → `jobManager.succeed`  
- `…\features\modelhub\src\main\kotlin\com\omnillm\features\modelhub\projection\ModelCardProjector.kt` — LOCAL_IMPORT → `SOURCE_UNVERIFIED`; suggested card `authenticityOk = false`

### PFD / bounds helpers (ANDROID-STORAGE §2.1 / §3)

**File:** `…\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\storage\PfdMaterializeHelpers.kt`  
dup / fstat / materializeIntoQuarantine / materializeSafUri / `importSpec` (strategy 2 builder only).

**File:** `…\data\model-store\src\main\kotlin\com\omnillm\data\modelstore\MaterializeBounds.kt`  
defaults include 8 GiB max file (large enough for many GGUF weights).

### Host tests (software path, **not** real GGUF)

**File:** `…\features\modelhub\src\test\kotlin\com\omnillm\features\modelhub\AcquisitionPipelineTest.kt`

- `safImport_marksSourceUnverified_andReachesReady` — asserts READY + `LOCAL_IMPORT` + `SOURCE_UNVERIFIED` + phase `SAF_IMPORT` using `StreamArtifactSource` of **`FixtureArtifact.PAYLOAD_BYTES`**.

**File:** `…\features\modelhub\src\main\kotlin\com\omnillm\features\modelhub\catalog\FixtureArtifact.kt`

```text
* **Not** a real LLM weights file — synthetic material for install state machine,
* digest verify, quarantine, and atomic promote tests
```

### Wave-A production feature wire

**File:** `…\android\runtime-service\src\main\kotlin\com\omnillm\android\runtimeservice\featurehost\WaveAWiring.kt`  
`ModelhubModule.createApi(...)` with offline fixture catalog (pin + SAF hint cards).

---

## counter_evidence (why claim is false)

### C1 — No GGUF content validation on import path

| Check | Result |
|---|---|
| Grep import/complete path for GGUF magic / IsolatedParse | **No call** from `AcquisitionPipeline` / `ModelHubService.completeAcquisitionMaterialize` / `OmniAdminFacade.importLocalFile` into `IsolatedParseEngine` |
| GGUF sniff exists elsewhere | `IsolatedParseEngine.sniffMagic` returns `"gguf"` for bytes `G G U F` — **parser-isolated module only**; not on ModelHub import promote path |
| UI format gate | MIME `*/*` / `application/octet-stream`; no `.gguf` extension check; identity hardcodes `"format":"gguf"` |

### C2 — Normative identity / parser steps skipped

CORE-MODEL §3 steps 3–4 require trusted typed parser → architecture / quant / format metadata → `ModelRevisionId`.  
Import path uses UI-side package+format string only; `completeIdentityVerify` → `modelStore.verifyQuarantineIdentity` (digest of quarantined blobs), then trust port, then promote — **no bounded GGUF parser stage**.

### C3 — COMPATIBILITY_CHECK is FSM-only for this path

`InstallationCoordinator.identityVerifiedOk` → state `COMPATIBILITY_CHECK`; `promoteToReady` requires `authenticityOk && licenseOk` from evaluation, then `atomicPromote`.  
No dry-load / smoke / IsolatedParse evidence object is produced for LOCAL_IMPORT.

### C4 — Default trust port elevates authenticity for all installs

**File:** `…\runtime\model-manager\src\main\kotlin\com\omnillm\runtime\modelmanager\memory\InMemoryModelManagerStores.kt`

```text
class DefaultTrustEvaluationPort : TrustEvaluationPort {
    …
    authenticityOk = true,
    licenseOk = true,
    compatibilityOk = true,
    placementClass = PlacementClassLabels.PRIVILEGED_TRUSTED,
```

`ModelManagerModule.createDurableControlPlane` / `createInMemoryControlPlane` default this port.  
Docs: Local Import has **no** authenticity guarantee; FEAT-MODELHUB: successful parse/smoke **must not** raise trust. Production promote still sees `authenticityOk=true`.

### C5 — Tests do not prove GGUF

`safImport_*` / `import_marksSourceUnverified` use fixture / abstract asset ids — not GGUF magic files.  
No instrumented test found for `OpenDocument` → real GGUF → READY in app-ui/runtime-service androidTest for this claim.

### C6 — Large-file / FGS product path incomplete

| Item | Evidence |
|---|---|
| Binder-thread full pipeline | `OmniAdminFacade.importLocalFile` uses `runBlocking { executeSafImport… }` |
| Transfer FGS for import | `TransferService` exists; grep shows **no** ModelHub / `importLocalFile` caller of `TransferService.requestStart` |
| Strategy 2 | `ImportSpec` + `PfdMaterializeHelpers.importSpec` exist; **no** `takePersistableUriPermission` in repo `*.kt` |

### C7 — Stale / negative device journey evidence

**File:** `…\APPIUM_E2E_REPORT.md` journey **C**: ModelHub SAF import of gemma GGUF **FAIL** — report claims action `onClick` stubbed / no DocumentsUI.  
**Current** `ModelHubScreen.kt` wires real `OpenDocument` and importer (report quote is outdated).  
Fail-closed: **absence of a current device PASS** + prior FAIL means this audit **cannot** treat L3 GGUF journey as software-complete in production reality; software wiring alone ≠ claim.

### C8 — Prior feature audit already L3 PARTIAL

`audit-reports/08_features.md` FEAT-MODELHUB: **L1/L2 PASS, L3 PARTIAL**; LOCAL_UI_INTERFACE “local SAF importer” listed partial; residuals include compatibility often NOT_CHECKED.

---

## residual

| ID | Residual | Severity for this claim |
|---|---|---|
| R1 | Wire IsolatedParseEngine (GGUF magic + bounded header/typed descriptor) into import VERIFYING/COMPATIBILITY_CHECK **without** elevating trust | High — blocks “for GGUF” completeness |
| R2 | Derive `ModelRevisionId` / package metadata from parser output (CORE-MODEL §3), not UI hard-coded `"format":"gguf"` | High |
| R3 | Channel-aware `TrustEvaluationPort`: LOCAL_IMPORT ⇒ authenticity not OK / untrusted placement; keep `SOURCE_UNVERIFIED` consistent with promote gates | High |
| R4 | Move multi-hundred-MB materialize off binder `runBlocking`; use job + optional `TransferService` FGS | Medium (product reliability for real GGUF sizes) |
| R5 | Optional strategy-2 only if deferred jobs required; implement `takePersistableUriPermission` or document out-of-scope | Low for strategy-1 claim |
| R6 | Instrumented / Appium re-run: OpenDocument → real GGUF → READY blob in app-private model-store | Medium (device evidence; do not invent PASS) |
| R7 | Host test with minimal valid GGUF header fixture (not only UTF-8 fixture string) | Medium |
| R8 | UI MIME / extension / magic preflight messaging for non-GGUF picks | Low |

---

## What was grepped / read (empty or negative results matter)

| Probe | Path / pattern | Result |
|---|---|---|
| `OpenDocument` | `android/app-ui/**/*.kt` | Present in `ModelHubScreen.kt` |
| `importLocalFile` | monorepo `*.kt` / `*.aidl` | UI call + facade override + AIDL only |
| `takePersistableUriPermission` | monorepo `*.kt` | **No matches** |
| `executeSafImport` | features + runtime-service | Pipeline + facade + unit test |
| `IsolatedParse` on import path | modelhub + OmniAdminFacade | **Not invoked** |
| TransferService from modelhub/import | `*.kt` | **Not linked** |
| SAF / GGUF in product docs | `modelhub-acquisition.md`, `storage-saf.md`, `model-platform.md` | Normative requirements above |

---

## Final one-liner

**`real: false`** — strategy-1 ModelHub SAF → PFD → quarantine → READY is **software-wired (L2)** and labeled `gguf`, but is **not** software-complete **for GGUF** under 新版本 docs: no GGUF parse/identity, incomplete install verification, over-trusting default evaluation, fixture-only host proof, and no current device success evidence.
)
