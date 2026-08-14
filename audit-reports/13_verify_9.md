# Adversarial verify — Claim #9

**Claim:** Android target/compile SDK 36 and 16KB native packaging hooks exist

| Field | Value |
| --- | --- |
| **Claim ID** | 9 |
| **Auditor** | Independent subagent (fresh path inspection; not prior audit rollups) |
| **Date** | 2026-08-12 |
| **Docs package** | `C:\Users\daive\Downloads\OmniLLM_產品文件完整包_新版本_繁體中文\OmniLLM_Product_Documents` |
| **Monorepo** | `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android` |
| **real** | **true** |

---

## real: true

```yaml
real: true
```

**Interpretation of claim (narrow):** Software-level evidence that (A) `compileSdk` and `targetSdk` are locked to **36**, and (B) **16 KB** native packaging **hooks** (linker/CMake/packaging + CI scanners) exist in the monorepo. This is **not** a claim of device 16 KB cold-start matrix PASS, Play Console upload, or OEM qualification.

---

## reason

Independent `read_file` / `grep` against live monorepo paths shows:

1. **Toolchain lock to API 36** in the version catalog and properties, applied by application modules via `libs.versions.compileSdk` / `libs.versions.targetSdk`.
2. **16 KB packaging chain hooks** present end-to-end at software level:
   - NDK flexible page-size CMake arg
   - Dual linker flags (`max-page-size` + `common-page-size` = 16384)
   - `jniLibs.useLegacyPackaging = false` on packaging modules
   - Fail-closed ELF scanner + APK zip-align checker
   - Root Gradle `checkNative16kb` wired into `check`
   - CI / release workflows invoke the same gates

Product docs (`PLAY-TARGET-API-2026`, `ANDROID-16KB`, `ANDROID-NATIVE`) require these locks/hooks; implementation matches the software-side requirements. Fail-closed rule is satisfied because every affirmative sub-claim below has a concrete path + quote.

---

## evidence

### A) compileSdk / targetSdk = 36

| Path | Quote / symbol |
| --- | --- |
| `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\gradle\libs.versions.toml` | Comment: `PLAY-TARGET-API-2026: compileSdk AND targetSdk MUST stay at 36`; lines: `compileSdk = "36"`, `targetSdk = "36"`, `ndk = "28.2.13676358"`, `buildTools = "36.0.0"` |
| `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\gradle.properties` | `omnillm.compileSdk=36`, `omnillm.targetSdk=36`; comment: `compileSdk/targetSdk = 36 (Play API 36 baseline)` |
| `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\app-ui\build.gradle.kts` | `// PLAY-TARGET-API-2026 / ANDROID-BASELINE: Play builds lock compile + target to API 36.` then `compileSdk = libs.versions.compileSdk.get().toInt()` and `targetSdk = libs.versions.targetSdk.get().toInt()` |
| `C:\Users\daive\Downloads\OmniLLM_Build\omnillm-android\android\companion-sandbox\build.gradle.kts` | Same catalog wiring: `compileSdk` / `targetSdk` from `libs.versions.*` |
| Other Android library modules | `compileSdk = libs.versions.compileSdk.get().toInt()` in e.g. `android/native`, `android/runtime-service`, `android/workers`, `android/parser-isolated`, `interfaces/aidl`, `engines/mllm` |

### B) 16 KB native packaging hooks

| Layer | Path | Quote / symbol |
| --- | --- | --- |
| CMake linker | `android\native\src\main\cpp\CMakeLists.txt` | `set(OMNILLM_PAGE_FLAGS "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")` + `target_link_options(omnillm_llama PRIVATE ${OMNILLM_PAGE_FLAGS})` |
| Gradle NDK/CMake | `android\native\build.gradle.kts` | `"-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"`; `jniLibs { useLegacyPackaging = false }`; task `checkElf16kbAlignment` with `--min-align` `16384` |
| App packaging | `android\app-ui\build.gradle.kts` | `// Uncompressed native libs for 16 KB zip alignment` + `useLegacyPackaging = false` |
| Companion packaging | `android\companion-sandbox\build.gradle.kts` | `jniLibs { useLegacyPackaging = false }` (grep hit) |
| Policy constants | `android\native\src\main\kotlin\com\omnillm\android\nativelib\AbiPackaging.kt` | `NativePackagingNotes`: `POLICY_ID = "ANDROID-16KB"`, `LINKER_MAX_PAGE = "-Wl,-z,max-page-size=16384"`, `LINKER_COMMON_PAGE = "-Wl,-z,common-page-size=16384"`, `REQUIRED_ALIGNMENT = 16384`, `CMAKE_ANDROID_PAGE_SIZE_ARGS` with `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` |
| Module marker | `android\native\src\main\kotlin\com\omnillm\android\NativeModule.kt` | `REQUIRED_MAX_PAGE_SIZE: Int = 16384` |
| ELF gate | `tools\ci\check_elf_16kb_alignment.py` | Docstring: scan for 16 KB LOAD alignment; authority `ANDROID-16KB`; **FAIL CLOSED** if no `.so` |
| APK zip-align gate | `tools\ci\check_apk_16kb_zipalign.py` | Docstring: `zipalign -c -P 16` or pure-Python 16 KB offset check; `PAGE = 16384` |
| Root Gradle | `build.gradle.kts` | `checkNative16kb` → `check_elf_16kb_alignment.py --min-align 16384`; `check` depends on `checkNative16kb` and `:android:native:verifyNativeLibsPresent` |
| CI | `.github\workflows\ci.yml`, `.github\workflows\release.yml` | Steps: `Native 16 KB ELF alignment`, `APK 16 KB zip-align check`, `./gradlew checkNative16kb`, `check_apk_16kb_zipalign.py` |
| Local CI | `tools\ci\local_ci.ps1`, `tools\ci\local_ci.sh` | Steps for root `check` + ELF + APK 16 KB |

### C) Product docs alignment (new package)

| Path | Quote |
| --- | --- |
| `...\specs\platform-policy-register.yaml` | `PLAY-TARGET-API-2026`: target Android 16 / **API 36**; `ANDROID-16KB`: Play apps must support **16 KB** page sizes; native + APK/AAB verification |
| `...\docs\60-android\android-baseline.md` | Play build must have **compile／target API 36 lock** |
| `...\docs\60-android\native-packaging-16kb.md` (`ANDROID-NATIVE`) | Full chain: NDK r28+, dual linker flags, ELF scan, zip-align `-P 16`, device cold start (device step is **beyond** this claim’s “hooks exist”) |

---

## counter_evidence

| Item | Assessment |
| --- | --- |
| Hard-coded non-36 SDK in app modules | **Not found.** Modules use catalog `libs.versions.compileSdk` / `targetSdk`; catalog values are `"36"`. |
| Missing 16 KB linker/CMake hooks | **Not found** for `:android:native` production CMake path. |
| Missing packaging `useLegacyPackaging = false` | **Not found** for `app-ui`, `native`, `companion-sandbox` (present). |
| Missing CI/ELF scanners | **Not found** — scripts + root task + workflows exist. |
| Device 16 KB cold-start matrix | **Absent as product evidence** — but claim only asserts **hooks exist**, not Q-015 device PASS. Not counter to the narrow claim. |
| Formal product `evidenceStatus` for Q-015 | Docs/scenarios may still say `NOT_EXECUTED` for runtime matrix; does **not** negate software hook presence. |
| Prior audit `06_android.md` | Used only as cross-check; this file re-verified primary sources. |

---

## residual

1. **Scope of “hooks” vs full ANDROID-NATIVE §2 claim:** Device / emulator **16 KB page-size cold start + engine load + inference** evidence is a separate residual (**not** claimed true here; do not invent PASS).
2. **Third-party / engine prebuilts:** Hook existence does not prove every engine `.so` will always pass at release time without running the scanners; gates are fail-closed by design when artifacts exist.
3. **Target behavior-change matrix:** Docs require Android 16 behavior-change testing for Play; that is outside “SDK 36 lock + 16 KB packaging hooks exist.”
4. **No live Gradle assemble re-run in this verification pass** — claim is satisfied by source/config presence; residual: build-time evaluation of catalog integers was not re-executed here (static lock is explicit string `"36"`).

---

## method (what was grepped/read)

| Action | Scope |
| --- | --- |
| `grep` | `compileSdk\|targetSdk\|SDK.?36` over monorepo `*.{gradle,kts,toml,properties,md,json,yml,yaml}` |
| `grep` | `16.?KB\|page.?size\|useLegacyPackaging\|max-page-size\|jniLibs` over monorepo |
| `read_file` | `gradle/libs.versions.toml`, `gradle.properties`, `android/app-ui/build.gradle.kts`, `android/native/build.gradle.kts`, `android/native/.../CMakeLists.txt`, `AbiPackaging.kt`, root `build.gradle.kts`, `check_elf_16kb_alignment.py`, `check_apk_16kb_zipalign.py` |
| `grep` / `read_file` | Product `specs/platform-policy-register.yaml`, `docs/60-android/android-baseline.md`, `native-packaging-16kb.md` |
| `grep` | `.github/workflows/ci.yml`, `release.yml` for 16 KB steps |

---

## status rollup (for claim #9 only)

| Sub-claim | Status | Notes |
| --- | --- | --- |
| compileSdk = 36 lock | **PASS** | Catalog + properties + app-ui apply |
| targetSdk = 36 lock | **PASS** | Catalog + app-ui / companion apply |
| 16 KB packaging hooks exist | **PASS** | Linker/CMake/packaging + ELF/APK gates + CI |
| 16 KB device matrix PASS | **N_A** to this claim (not asserted) | Would be MISSING/BLOCKED_HUMAN if claimed |

**Claim #9 overall: real: true**
