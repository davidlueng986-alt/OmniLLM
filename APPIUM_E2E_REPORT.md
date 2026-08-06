# OmniLLM Android — Appium E2E Smoke Report

| Field | Value |
|-------|--------|
| **Verdict** | **SMOKE_PARTIAL** |
| Date (UTC) | 2026-08-06 |
| Repo | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| Package under test | `com.omnillm.debug` (debug `applicationId`; base id `com.omnillm` **not** installed) |
| Activity | `com.omnillm.ui.MainActivity` |
| Automation | Appium MCP embedded UiAutomator2 (primary); adb for install / push / logcat / final screencap |
| Engine qualification claim | **NONE** — smoke E2E only; do **not** invent engine PASS |

---

## 1. Device properties

| Property | Value |
|----------|--------|
| Serial / UDID | `emulator-5554` |
| Product model | `sdk_gphone64_x86_64` (Pixel 7 class AVD) |
| Manufacturer | Google |
| Android version | 16 |
| API level | 36 |
| ABI | `x86_64` |
| Hardware | `ranchu` |
| Display | 1080 × 2400 |
| RAM (AVD context) | ~6 GB |

Evidence: `e2e-artifacts/device.txt`, live `adb shell getprop` at report time.

---

## 2. APK / package version

| Field | Value |
|-------|--------|
| APK path | `android/app-ui/build/outputs/apk/debug/app-ui-debug.apk` |
| APK size | 103,894,118 bytes (~99 MiB) |
| Install | Streamed `adb install -r` → **Success** (`e2e-artifacts/install.log`) |
| Installed package | `com.omnillm.debug` |
| versionName | `0.1.0-debug` |
| versionCode | `1` |
| minSdk / targetSdk | 28 / 36 |
| firstInstallTime | 2026-08-06 07:26:37 |
| lastUpdateTime | 2026-08-06 07:31:51 |
| dataDir | `/data/user/0/com.omnillm.debug` |

Evidence: `e2e-artifacts/package.txt`, `e2e-artifacts/install.log`.

**Note:** Task package `com.omnillm` is not present on device. All Appium caps and grants used `com.omnillm.debug`.

---

## 3. Model under test

| Field | Value |
|-------|--------|
| Identity | **gemma-3-270m** Q8_0 GGUF (base; chat quality may be weak) |
| Hugging Face / source | [https://huggingface.co/ggml-org/gemma-3-270m-GGUF](https://huggingface.co/ggml-org/gemma-3-270m-GGUF) (variant **Q8_0**) |
| Host / local path | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\e2e-artifacts\models\gemma-3-270m-Q8_0.gguf` |
| Host size | 301,651,328 bytes (~287.7 MiB) |
| On-device path (public Download) | `/sdcard/Download/omnillm-e2e/gemma-3-270m-Q8_0.gguf` |
| On-device present | **Yes** (verified at install and report time) |
| App model-store (private) | **Empty** — `files/model-store/blobs/` and `packages/` have no GGUF/blob packages |

Evidence: `e2e-artifacts/prep.json`, `e2e-artifacts/models_paths.txt`, `e2e-artifacts/install.log`, live `ls` of `/sdcard/Download/omnillm-e2e/`.

**Implication:** File is staged for SAF/import only. App never registered or materialised it into the private model-store; no READY model for Playground.

---

## 4. Appium session

| Field | Value |
|-------|--------|
| Session success | **Yes** |
| Session ID | `38b166be-46b4-4cc0-ab8d-888cef4bdab3` |
| Mode | Local / embedded UiAutomator2 via Appium MCP |
| Caps | `appPackage=com.omnillm.debug`, `appActivity=com.omnillm.ui.MainActivity`, `udid=emulator-5554`, `noReset=false`, `autoGrantPermissions=true` |
| End of run | Session deleted after report capture; final frame via `adb screencap` → `e2e-artifacts/99_final.png` (Appium screenshot timed out on stale session) |

Evidence: `e2e-artifacts/session.json`, `e2e-artifacts/01_session_notes.md`.

Initial create showed launcher first; activate + `am start` brought MainActivity up. Early runtime crash (Keystore IV) produced “OmniLLM keeps stopping” until mid-session code fixes and reinstall.

---

## 5. Launch blockers fixed mid-session (context)

These are **not** engine qualifications; they unblocked UI smoke:

1. **Keystore IV** — runtime FATAL `InvalidAlgorithmParameterException: Caller-provided IV not permitted` in `AndroidKeystoreMasterKey` → use Keystore-generated GCM IV.
2. **primaryRail nulls** — UI FATAL `NoWhenBranchMatchedException` / NPE from eager `primaryRail` before data-object init → `by lazy`.
3. **ModelHub LazyColumn keys** — duplicate key for pin + SAF suggested cards → composite keys.

After fixes: cold start → **Home**, runtime process **READY**, Admin binder connected.

---

## 6. Journey steps (PASS / FAIL / BLOCKED)

| ID | Step | Result | Evidence |
|----|------|--------|----------|
| **A** | Launch app; dismiss first-run/onboarding if present | **PASS** | Home reached; Runtime READY; no blocking onboarding. `e2e-artifacts/A_launch_home.png` |
| **B** | Settings — enable `runtime.exploratoryExecuteEnabled` | **FAIL** | Key shown read-only as `false`; no Switch / apply control. `e2e-artifacts/B_settings.png` |
| **C** | ModelHub — SAF import of gemma GGUF | **FAIL** | ModelHub opens; Offline Fixture / LOCAL_IMPORT cards visible; detail actions Download / View license / View evidence. Action `onClick` stubbed — **no DocumentsUI / SAF picker**. `e2e-artifacts/C_modelhub.png`, `modelhub.png`, `C_import_detail.png`, `import.png` |
| **D** | Wait for import/install job (≤10 min) | **FAIL** | No job started; nothing to poll. |
| **E** | Playground — select model, enter prompt | **FAIL** | Empty gate: “No usable model for inference”; CTA Open ModelHub; no prompt field / model picker. `e2e-artifacts/playground_before.png` |
| **F** | Send/Generate — wait ≤5 min for output/error | **FAIL** | No Send control; blocked by no model + exploratory flag false. `e2e-artifacts/playground_after.png` |
| **G** | Capture screenshots | **PASS** | Required screens + `99_final.png` (adb). |
| **H** | Record journey metadata | **PASS** | `e2e-artifacts/journey.json`, `journey_notes.md` |

Primary write-up: `e2e-artifacts/journey.json`.

### Observed settings (step B)

- `privacy.telemetryMode` = `LOCAL_ONLY`
- `runtime.backendPreference` = `AUTO`
- `runtime.exploratoryExecuteEnabled` = **`false`** (no UI to flip)
- `runtime.fallbackPolicy` = `NONE`
- `server.lanEnabled` / `server.loopbackEnabled` = `false`

### Import code blocker (step C)

```kotlin
// ModelHubScreen.kt — ModelDetailPane (stub)
card.allowedActions.forEach { action ->
    SecondaryActionButton(
        label = actionLabel(action),
        onClick = { /* mutations via ViewModel when user confirms */ },
    )
}
```

ViewModel may expose `import(StartImportSpec)` / `download(...)`, but UI never invokes them; `StartImportSpec.assetId` is opaque (not a filesystem path).

---

## 7. Model output observed?

| Question | Answer |
|----------|--------|
| Any generate / completion tokens in UI? | **No** |
| Any engine inference run for user prompt? | **No** |
| Playground state | Gate only — “No usable model for inference” |

---

## 8. Logcat / runtime errors

### Post-fix stable boot (positive)

From fuller buffer (`e2e-artifacts/logcat_snip_fuller.txt`):

- UI process bootstrap (Admin binder only; no control plane in UI)
- `:runtime` process starts; control plane attaches; FGS legal start
- `libomnillm_llama.so` loads OK
- Engine packs attach: llama.cpp, LiteRT-LM, MLC-LLM, mllm, ONNX-Runtime-GenAI  
  - **`cells=63 anySupported=false`**  
  - llama-cpp: `cells=UNQUALIFIED`, `EXPERIMENTAL_FIXTURE`, exploratoryDefault=false  
  - Note: **cells remain UNKNOWN/UNQUALIFIED** — **not** locked/qualified for PASS
- HTTP gateway port **11434**; **`runtime state=READY`**
- MainActivity displayed (~2s)

### Noise / non-fatal

- ActivityThread “Package reported as REPLACED” (install churn)
- artd “no usable artifacts”
- Appium/UiAutomator QueryController matching UI text
- Early-session crash (pre-fix Keystore IV) — **not** present in post-fix stable log sample

### Filtered snip (`logcat_snip.txt`)

Sparse — no `AndroidRuntime` FATAL / llama load failure in last-500 filtered window after journey.

**No crash stack in post-journey diagnostics** that blocks current Home navigation. Failures are product-path residuals (import stub, exploratory flag, empty model-store), not late process death.

---

## 9. Residuals (blockers for green smoke path)

| Residual | Status | Notes |
|----------|--------|-------|
| **SAF / local import** | **Open** | GGUF on `/sdcard/Download/omnillm-e2e/` but UI never launches OpenDocument / registers asset; ModelHub actions stubbed |
| **`runtime.exploratoryExecuteEnabled`** | **Open** | Remains `false`; Settings display-only; no shared_prefs / settings-table write path found for UI toggle |
| **Engine NOT_LOCKED / UNQUALIFIED** | **Open** | Registry: `anySupported=false`, cells UNQUALIFIED/UNKNOWN; **smoke only — not engine PASS** |
| Debug package id | Acknowledged | Tests must target `com.omnillm.debug` |
| Model-store empty | Consequence of SAF residual | Private blobs/packages empty despite public GGUF |

### Suggested next blockers for green path

1. Wire ModelHub action buttons → ViewModel download/import + SAF `OpenDocument` → asset registration → `StartImportSpec`.
2. Settings toggle or `applySettings` for `runtime.exploratoryExecuteEnabled` when product allows exploratory generate.
3. Optional smoke harness: offline fixture materialisation without full SAF if policy allows.

---

## 10. Artifact index

| Artifact | Path |
|----------|------|
| Journey machine record | `e2e-artifacts/journey.json` |
| Journey notes | `e2e-artifacts/journey_notes.md` |
| Session | `e2e-artifacts/session.json`, `01_session_notes.md` |
| Install / push | `e2e-artifacts/install.log`, `prep.json` |
| Device / package / models | `device.txt`, `package.txt`, `models_paths.txt` |
| Diagnostics | `diagnostics_capture.md`, `logcat_snip.txt`, `logcat_snip_fuller.txt` |
| Screenshots | `A_launch_home.png`, `B_settings.png`, `C_modelhub.png`, `modelhub.png`, `C_import_detail.png`, `import.png`, `playground_before.png`, `playground_after.png`, `01_launch.png`, **`99_final.png`** |
| Host model | `e2e-artifacts/models/gemma-3-270m-Q8_0.gguf` |
| Summary JSON | `e2e-artifacts/SUMMARY.json` |

---

## 11. Honest verdict

### **SMOKE_PARTIAL**

**Why not SMOKE_PASS**

- Cannot import the staged GGUF via SAF/UI.
- Cannot enable exploratory execute from Settings.
- Cannot open Playground chat input or observe any model output.
- Engines remain UNQUALIFIED / `anySupported=false` — no inference qualification.

**Why not pure SMOKE_FAIL**

- Device install + model push succeeded.
- Appium session created and drove Compose UI (text/accessibility selectors).
- After mid-session crash fixes, app **launches stably** to Home with **runtime READY**, Admin binder, HTTP gateway.
- Navigation to Settings, ModelHub, and Playground works; empty/gate states are intentional product UX, not blank crashes.
- Screenshots and structured journey records captured.

**Bottom line:** Shell + runtime smoke is viable; **model acquisition + generate path is blocked** by known UI/settings residuals. Treat as partial smoke only — **never** as engine or product E2E PASS.
