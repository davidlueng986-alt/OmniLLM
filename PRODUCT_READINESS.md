# OmniLLM Android — Product Readiness (Software Verification Pass)

**As-of:** 2026-08-06  
**Repo:** `omnillm-android/` (edit surface only)  
**Docs authority:** product package `OmniLLM_Product_Documents` + in-repo `specs/`  
**Verification scope:** full software gate (tests, assemble, production wiring greps, qualification honesty)

> Completing software gates does **not** invent device PASS evidence, OEM matrix results, or Play Console approval.

---

## Software ready: **YES**

Software-closeable launch gates for this monorepo are **green**.  
Engines remain **UNQUALIFIED / UNKNOWN** (honest). Exploratory native execute is available only under explicit policy as **CONDITIONAL**, never as invented **SUPPORTED**.

| Gate | Result | Evidence |
|---|---|---|
| A. Unit / host tests | **PASS** | `.\gradlew.bat test --continue` → **BUILD SUCCESSFUL** (302 tasks). Fixed 2 diagnostics redaction assertions (allowlist schema may name never-export keys). |
| B. assembleDebug / assembleRelease (`app-ui`, `companion-sandbox`) | **PASS** | All four assemble tasks **BUILD SUCCESSFUL**. |
| C. FailClosedInferenceEngine misuse when engines attached | **PASS** | `FailClosedInferenceEngine` is the **unbound** default on `DelegatingInferenceEngine` only. Production `EngineExecuteBinding.applyAttachment` binds `LlamaCppInferenceEngineAdapter` when native pack present; unbind restores fail-closed. |
| D. InMemory* not production attach defaults | **PASS** | `RuntimeControlPlane.attach` uses `createWithCommits` (SQL claims/commits), `createDurableManager` (jobs), `ControlPlaneSecurityFactory` (Keystore vault + SQLite tokens/pairing), durable model/session/content-report/tool ledgers. Constructor defaults of `InMemory*` remain **test-only** (e.g. `LoopbackTokenService` ctor default); production injects plane `securityStack`. |
| E. No invented SUPPORTED/QUALIFIED | **PASS** | `specs/engine-qualification-status.yaml`: all five engines `qualificationStatus: UNQUALIFIED`, `registryExposure: UNKNOWN`, `runtimeCapabilityDefault: UNKNOWN`. Per-engine `capability-matrix.yaml` cells UNQUALIFIED. |

---

## APK artifacts (this host)

| Package | Variant | Path |
|---|---|---|
| `com.omnillm` | debug | `android/app-ui/build/outputs/apk/debug/app-ui-debug.apk` |
| `com.omnillm` | release (unsigned) | `android/app-ui/build/outputs/apk/release/app-ui-release-unsigned.apk` |
| companion-sandbox | debug | `android/companion-sandbox/build/outputs/apk/debug/…` |
| companion-sandbox | release | `android/companion-sandbox/build/outputs/apk/release/…` |

**Version line:** `0.1.0` / `versionCode` `1` (`gradle/libs.versions.toml`).

### How to run APK (local)

```powershell
cd C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android

# Build
.\gradlew.bat :android:app-ui:assembleDebug
# or release (unsigned):
.\gradlew.bat :android:app-ui:assembleRelease

# Install debug on a connected device/emulator
adb install -r android\app-ui\build\outputs\apk\debug\app-ui-debug.apk

# Launch (package com.omnillm)
adb shell am start -n com.omnillm/.MainActivity
```

**Notes:**

- UI process never loads native / never writes DB (INV-001). Runtime runs in `:runtime` process via `runtime-service`.
- Release APK from CI/local is **unsigned** unless you apply a local keystore (never commit secrets).
- Companion sandbox is a separate package for external-tool isolation; install only when testing that path.

Full software pre-gates (parity with CI):

```powershell
.\tools\ci\local_ci.ps1
# or:
.\gradlew.bat test
.\gradlew.bat :android:app-ui:assembleRelease :android:companion-sandbox:assembleRelease
```

---

## CI status

| Item | Status |
|---|---|
| Workflows present | `.github/workflows/ci.yml`, `release.yml` |
| CI pipeline | Specs present → contract drift → module dep rules → unit tests → check + lint → assembleDebug/Release → 16 KB ELF/APK gates → upload APK artifacts |
| Local parity | `tools/ci/local_ci.ps1` / `local_ci.sh` |
| Play Console upload automation | **None** (human checklist only: `gradle/RELEASE_CHECKLIST.md`) |
| detekt | Intentionally not configured |
| This machine run | Local `test` + four assemble tasks **PASS**. Full GitHub Actions status depends on remote push/PR (not executed here). |

---

## Residual human-only / out-of-scope items

These are **not** software bugs to invent evidence for:

1. **Physical device tests** — install, FGS, thermal, real GGUF load, multi-ABI on hardware.
2. **OEM / device qualification matrix** — no PASS records may be invented; cells stay UNQUALIFIED until real evidence packs.
3. **Upstream engine locks** — all `UPSTREAM.lock` remain `NOT_LOCKED` (empty digests); full llama.cpp / LiteRT / MLC / mllm / ORT native not production-qualified.
4. **Play Console submission** — signing keystore, App Signing, Data Safety, FGS declaration, store listing, AI content questionnaires (`gradle/RELEASE_CHECKLIST.md`).
5. **R8 minify smoke** — default `isMinifyEnabled=false` until human smoke sign-off.
6. **Exploratory execute opt-in** — product default `runtime.exploratoryExecuteEnabled=false`; LOCAL_ADMIN must enable for CONDITIONAL generate; still never SUPPORTED without PASS.
7. **Device execution fingerprint / evidence expiry** — real cell qualification workflow (human + lab).
8. **Optional secondary polish** — modelhub display/link in-memory ports (low severity); some UI depth vs HTTP/AIDL parity varies by pack.

---

## Production wiring summary (verified)

| Concern | Production path |
|---|---|
| Process gate | `RuntimeControlPlane.attach` only in `:runtime` (`ProcessIdentity` + `SingleWriterPolicy`) |
| Claim / commit | `ControlPlaneDatabase` + `RequestRegistryModule.createWithCommits` |
| Jobs | `JobManagerModule.createDurableManager(controlDb.jobs)` + `reconcileAfterRestart` |
| Secrets / tokens | `ControlPlaneSecurityFactory` → `VaultSecretBroker` + SQLite verifiers; `GatewayLifecycle` injects into `LoopbackTokenService` |
| Sessions / models | Durable session + model manager ports |
| Content reports / tools | SQLite ledgers via `FeaturePackHost.bootstrap` when plane provides ports |
| Engines | Attach-after-READY; llama-cpp JNI when `.so` present; peers registry stubs; capability projection honest |
| Inference bind | `EngineExecuteBinding` → Delegating (fail-closed → llama adapter when attached) |

---

## Related documents

| Doc | Role |
|---|---|
| `FEATURE_AUDIT.md` | Per FEAT-* / ENGINE-* software vs docs |
| `PRODUCT_READINESS_CHECKLIST.md` | Detailed software TODO inventory (may lag attach path; prefer this file for latest gate results) |
| `BUILD_STATUS.md` | Module tree / status inventory |
| `GAP_CLOSEOUT.md` | Gaps 1–3 closeout evidence |
| `gradle/RELEASE_CHECKLIST.md` | Human Play / Console only |
| `docs/architecture/engine-registry-attachment.md` | Engine attach + exploratory CONDITIONAL rules |
