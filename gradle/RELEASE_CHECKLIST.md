# OmniLLM Play Console — human release checklist

**Authority:** `ANDROID-BASELINE`, `ANDROID-DIST`, `SEC-PRIVACY`, `FEAT-AI-CONTENT-REPORT`, `specs/platform-policy-register.yaml`  
**Toolchain lock:** `gradle/libs.versions.toml` + `gradle.properties`  
**App version line:** `appVersionName` / `appVersionCode` (main) and `companionVersionName` / `companionVersionCode` in the catalog  
**Recheck trigger:** before every Play submission, and whenever `targetSdk`, FGS type, NDK/AGP/bundletool, or Play policy changes.

This checklist is **human-only Play / Console work**. Completing it does **not** invent Play approval, OEM matrix results, or engine PASS evidence.

**Out of scope for automation:** Play Console upload requiring human secrets, App Signing ceremony, Data Safety / FGS / AI questionnaires, store listing legal copy. CI builds artifacts only (`.github/workflows/release.yml`).

**Software pre-gates (run before Console work):** see [§0](#0-software-pre-gates-not-console) — not substitutes for Console forms.

---

## 0. Software pre-gates (not Console)

Run from repo root (or `.\tools\ci\local_ci.ps1` / `bash tools/ci/local_ci.sh`):

```bash
pip install -r tools/codegen/requirements.txt
./gradlew checkContractDrift
./gradlew checkModuleDependencyRules
./gradlew checkDependencyEdges
./gradlew test
./gradlew checkNative16kb
./gradlew :android:app-ui:lintRelease :android:companion-sandbox:lintRelease
./gradlew :android:app-ui:assembleRelease :android:companion-sandbox:assembleRelease
./gradlew :android:app-ui:bundleRelease   # when preparing AAB upload

# After APK materialization with .so present:
python tools/ci/check_elf_16kb_alignment.py --min-align 16384
python tools/ci/check_apk_16kb_zipalign.py android/app-ui/build/outputs/apk/release/*.apk
python tools/ci/check_apk_16kb_zipalign.py android/companion-sandbox/build/outputs/apk/release/*.apk
```

| Check | Expected |
|---|---|
| versionName / versionCode | Catalog `0.2.0` / `2` (main + companion lockstep unless protocol forces companion-only bump) |
| Signing | Optional CI secrets or local upload keystore — **never commit** keystores |
| R8 minify | Default **off** until §H smoke sign-off |
| detekt | **Not configured** — intentionally skipped; AGP lint + unit tests + architecture gates required |
| Engine cells | All remain UNQUALIFIED / UNKNOWN — do not invent PASS |

Artifacts:

| Package | Task | Output |
|---|---|---|
| `com.omnillm` | `:android:app-ui:bundleRelease` | `android/app-ui/build/outputs/bundle/release/*.aab` |
| `com.omnillm` | `:android:app-ui:assembleRelease` | `android/app-ui/build/outputs/apk/release/*.apk` |
| `com.omnillm.companion` | `:android:companion-sandbox:assembleRelease` | companion APK (prefer APK multi-package same-signer; see `PACKAGING.md`) |

---

## A. Toolchain lock (must match catalog before submit)

| Item | Locked value | Source |
|---|---|---|
| JDK | 17 | `libs.versions.toml` `jdk` |
| Gradle | 9.5.0 | wrapper + `gradleWrapper` |
| AGP | 9.3.0 | `agp` |
| Kotlin | 2.2.0 | `kotlin` |
| **compileSdk** | **36** | `compileSdk` — **PLAY-TARGET-API-2026** |
| **targetSdk** | **36** | `targetSdk` — same lock; do not ship Play AAB with lower target |
| minSdk | 28 | product decision (`README.md`) |
| NDK | 28.2.13676358 | `ndk` — **ANDROID-16KB** |
| Build-Tools | 36.0.0 | `buildTools` |
| versionName / versionCode | `0.2.0` / `2` | `appVersionName` / `appVersionCode` |

Play policy claim (`PLAY-TARGET-API-2026`): starting **2026-08-31**, new apps and updates must target **Android 16 / API 36** (form-factor exceptions per official policy). Source: https://developer.android.com/google/play/requirements/target-sdk — re-fetch before submit.

---

## B. Signing & upload key (human secrets)

- [ ] Play App Signing enrolled for `com.omnillm`
- [ ] Upload keystore held offline / secret store — **never** in git, `local.properties`, or CI logs
- [ ] Optional GitHub secrets for `release.yml` only: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` (see `tools/ci/README.md`)
- [ ] Companion APK signed with **same upload key** as main (ADR-007 `BIND_SANDBOX` signature permission)
- [ ] Mapping file (if R8 enabled) archived with the release for crash deobfuscation

Missing CI secrets → unsigned release artifacts only (expected for PR CI).

---

## C. Play Console — FGS declaration (PLAY-FGS-DECLARATION)

Confirm merged main-app manifest (human review of release APK/AAB dump or merged manifest):

| Requirement | Expected |
|---|---|
| `FOREGROUND_SERVICE` | present |
| `FOREGROUND_SERVICE_SPECIAL_USE` | present |
| `FOREGROUND_SERVICE_DATA_SYNC` | present |
| `POST_NOTIFICATIONS` | present |
| `RuntimeForegroundService` | `foregroundServiceType=specialUse`, **exported=false**, property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` set |
| `TransferService` | `foregroundServiceType=dataSync`, **exported=false** |
| `RuntimeBindingService` | exported=true + normal discovery permission only |
| `AdminBindingService` / workers / isolated parser | **not** exported |
| Inference vs download types | **never mixed** on one service |

Sources: `android/runtime-service/src/main/AndroidManifest.xml`, merge into `:android:app-ui`.

### Console form (manual)

For **each** FGS type used:

1. Open **Play Console → Policy → App content → Foreground service permissions** (label may vary).
2. Declare **specialUse** with user-visible purpose: on-device LLM inference control plane / trusted engine host; user-started or perceptible via notification; cancel action available.
3. Declare **dataSync** for user-started model download/import only; describe timeout/quota behavior (pause / checkpoint — not silent specialUse piggyback).
4. Attach demo video / screenshots showing user starting inference and download, notification, and stop/cancel.
5. Recheck when target SDK or FGS types change.

Official reference: https://support.google.com/googleplay/android-developer/answer/16559646

---

## D. Play Console — AI-generated content reporting (PLAY-AI-REPORTING)

Product rule: generative UI must offer **in-app** report/flag — **not** external-web-only (`ANDROID-DIST` §2, `FEAT-AI-CONTENT-REPORT`).

| In-app surface | Location (code) |
|---|---|
| Navigation drawer **Content report** | `OmniDestination.ContentReport` |
| Playground active request **Report** action | `PlaygroundScreen` |
| Per-assistant message report entry | `PlaygroundScreen` conversation rows |
| Review / consent / offline queue UI | `ContentReportScreen` + `features/ai-content-report` |

### Console form (manual)

1. **App content → AI-generated content** (or current policy questionnaire): declare on-device generative features.
2. Confirm **in-app reporting** is available; link internal test build path (Playground → Report / Content report).
3. Configure **developer reporting endpoint**, privacy disclosure, retention, and contact **before** production track if reports can leave the device.
4. Align Data Safety answers with `android/app-ui/play/DATA_SAFETY_INVENTORY.md`.
5. Report stream must **not** be labeled as analytics/telemetry.

Reference: https://support.google.com/googleplay/android-developer/answer/13985936

---

## E. Play Console — Data Safety (SEC-PRIVACY / ANDROID-DIST §4)

1. Open **Play Console → App content → Data safety**.
2. Fill from `android/app-ui/play/DATA_SAFETY_INVENTORY.md` — do not invent sharing.
3. Defaults: prompts/outputs **not** shared; telemetry **off**; report is user-initiated separate stream.
4. Update inventory markdown in the same PR as any new collection path.
5. Privacy policy URL must match actual behavior (legal-owned).

---

## F. 16 KB page size (ANDROID-16KB) — device residual

Software gates (ELF + zip-align) run in CI. Human residual:

1. NDK r28+ lock remains; `packaging.jniLibs.useLegacyPackaging = false`.
2. Test on a **16 KB system image** when full native engines ship (field validation is human/device).
3. Reference: https://developer.android.com/guide/practices/page-sizes

---

## G. Companion package (ADR-007) — distribution ops

| Check | Expected |
|---|---|
| applicationId | `com.omnillm.companion` ≠ `com.omnillm` |
| sharedUserId | **absent** |
| INTERNET | **absent** (manifest tools:node remove) |
| Same signer as main | required for `BIND_SANDBOX` signature permission |
| Version/protocol handshake | fail closed on mismatch |
| Play distribution | multi-package strategy in `android/companion-sandbox/PACKAGING.md`; do not claim single-APK sandbox |

Human: validate dual-APK install path on device; document Play multi-package listing strategy.

---

## H. R8 / ProGuard enable (optional harden — human sign-off)

| Step | Action |
|---|---|
| 1 | Review `android/app-ui/proguard-rules.pro` + library `consumer-rules.pro` (AIDL + JNI keeps present) |
| 2 | Enable `isMinifyEnabled = true` / `isShrinkResources = true` on release **only after** smoke |
| 3 | Smoke: cold start UI, Admin bind, start FGS notification, transfer job, content report draft→queue, companion bind (if installed), JNI load in `:runtime` when `.so` present |
| 4 | Mapping file archived with the release — do not commit secrets |
| 5 | Default remains minify **off** until this section is signed PASS |

---

## I. Store listing / policy questionnaires (manual Console)

- [ ] Target API 36 confirmed on uploaded AAB
- [ ] App access / login instructions (if restricted)
- [ ] Ads declaration (OmniLLM: no ads unless product changes)
- [ ] Content ratings questionnaire
- [ ] News / COVID / crypto etc. as applicable (usually N/A)
- [ ] Financial features N/A
- [ ] Health features N/A
- [ ] Government apps N/A
- [ ] Data safety published
- [ ] AI-generated content + in-app reporting declared
- [ ] FGS declarations submitted
- [ ] Privacy policy URL live
- [ ] Rollback / DB migration note ready (`ANDROID-DIST` §6)
- [ ] versionName / versionCode match intended release track

---

## J. Pre-submit fail-closed rules

From `specs/platform-policy-register.yaml`:

- Recheck policies before each Play submission.
- Policy design obligations **do not** imply a real AAB or review has passed.
- Unverified policy status **fails closed** for publication claims.
- Do **not** invent engine qualification benchmark results or mark QUALIFIED/SUPPORTED without evidence packs.

---

## K. Suggested owner sign-off table

| Gate | Owner role | Date | Result (PASS/FAIL/N/A) | Notes |
|---|---|---|---|---|
| API 36 lock | android-platform | | | |
| Software pre-gates (§0) | android-platform | | | CI green |
| FGS Console form | android-platform | | | |
| Data Safety | privacy-security | | | |
| AI report surface + endpoint | trust-safety | | | |
| 16 KB device image | android-platform / engines | | | human/device |
| Companion dual-APK | security-architecture | | | |
| R8 minify enable | android-platform | | | default FAIL until smoke |
| Store questionnaires | product / legal | | | |
| Privacy policy URL | legal | | | |
