# CI helpers

Automated CI/CD for the OmniLLM Android monorepo.

| Surface | Path |
|---|---|
| PR / push CI | [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) |
| Signed release | [`.github/workflows/release.yml`](../../.github/workflows/release.yml) |
| Dependabot | [`.github/dependabot.yml`](../../.github/dependabot.yml) |
| Local parity (Unix) | [`local_ci.sh`](./local_ci.sh) |
| Local parity (Windows) | [`local_ci.ps1`](./local_ci.ps1) |
| Module dependency rules | [`check_module_dependency_rules.py`](./check_module_dependency_rules.py) |
| ELF 16 KB scan | [`check_elf_16kb_alignment.py`](./check_elf_16kb_alignment.py) |
| APK zip-align 16 KB | [`check_apk_16kb_zipalign.py`](./check_apk_16kb_zipalign.py) |
| Module dep edges (INV-001) | [`check_dependency_edges.py`](./check_dependency_edges.py) |
| APK cleanliness (D10) | [`verify_apk_clean.py`](./verify_apk_clean.py) |
| SBOM vs APK (D9) | [`verify_sbom_vs_apk.py`](./verify_sbom_vs_apk.py) |

## Pipeline (fail closed)

Order matches product codegen guidance: **drift gate before in-tree regenerate**.

1. **Checkout** + **JDK 17** + **Python 3** + **Android SDK** (API 36, build-tools 36.0.0, NDK `28.2.13676358`)
2. **Specs authority present** - critical `specs/*` including `engine-qualification-status.yaml` + `capability-availability-matrix.yaml`
3. **`./gradlew checkContractDrift`** - catalogs vs committed `*/generated/*.kt` (must fail on drift)
4. **`./gradlew generateContracts` + clean git** - belt-and-suspenders dirty-tree check on generated sources
5. **`./gradlew checkModuleDependencyRules`** - AGENTS.md / INV-001 / ADR-010 / ADR-007 edge gate (**fail closed**)
6. **`./gradlew checkDependencyEdges`** - INV-001 UI isolation / engines↛data (**fail closed**; also in root `check`)
7. **`./gradlew test`** - all `org.jetbrains.kotlin.jvm` unit tests **and** Android `testDebugUnitTest` (**fail closed**)
8. **`./gradlew check`** - root verification (drift + module rules + dep edges + monorepo `*.so` 16 KB ELF scan)
9. **Lint** - `:android:app-ui:lintDebug` + `:android:companion-sandbox:lintDebug`
10. **Assemble** - `assembleDebug` + **`assembleRelease`** for app-ui + companion (unsigned on PR CI; signing optional on `release.yml`)
11. **Native 16 KB** - ELF scan + per-APK zip-align (`ANDROID-16KB`)
12. **Assert APK artifacts** - fail closed if debug/release APKs missing for app-ui + companion
13. **Upload** APK (and AAB on release workflow) artifacts (`if-no-files-found: error`)

Any non-zero exit fails the job. Test failures are never ignored.

### D10: packaged-APK cleanliness gate

```bash
python tools/ci/verify_apk_clean.py android/app-ui/build/outputs/apk/release/app-ui-release-unsigned.apk
./gradlew checkApkClean          # aggregated in root `check`
```

Fail-closed, both directions:
- **FORBIDDEN (D10)** — JVM-only payloads must NOT ship: jansi
  (`org/fusesource/jansi/**`, `META-INF/native-image/jansi/**`; ←
  `ktor-server-core` runtime scope) and sqlite-jdbc
  (`org/sqlite/native/**`, `sqlite-jdbc.properties`,
  `META-INF/native-image/org.xerial/**`, `META-INF/services/java.sql.Driver`;
  ← `sqldelight:sqlite-driver`). Class files stay on the classpath; the
  JVM-only payloads are stripped via `packaging.resources.excludes` +
  a `META-INF/services/**` merge carve-out in
  `android/app-ui/build.gradle.kts` (AGP default merge shadows excludes).
- **REQUIRED (C-07)** — the engine natives must be present in both ABIs:
  `liblitertlm_jni.so`, `libonnxruntime-genai.so`, `libonnxruntime-genai-jni.so`,
  `libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libomnillm_llama.so`,
  `libandroidx.graphics.path.so`, `libc++_shared.so`.
- **INVARIANT** — mllm (`libMllm*`, `libgojni.so`, `libomp.so`) stays
  arm64-v8a-only; x86_64 presence is a regression.

### D9: SBOM vs APK gate

```bash
python tools/ci/verify_sbom_vs_apk.py \
  --sbom C:\Users\daive\Downloads\OmniLLM_Release\SBOM-0.2.0-rc2.json \
  --apk android/app-ui/build/outputs/apk/release/app-ui-release-unsigned.apk
./gradlew checkSbomVsApk -Pomnillm.sbom=...   # aggregated in root `check`
```

The canonical SBOM (`SBOM-0.2.0-rc2.json`, CycloneDX 1.5) lives **outside the
repo** under `C:\Users\daive\Downloads\OmniLLM_Release\` (kept alongside
`SBOM-0.2.0.json`). Every component carries an `omnillm:scope` property:

| scope | meaning |
|---|---|
| `packaged-in-apk` | present in the release APK (verified against `omnillm:apk-entries` globs: `lib/<abi>/*.so`, `META-INF/*.version`, characteristic files) |
| `excluded-from-packaging` | on the release runtime classpath but JVM-only payload stripped at packaging (D10: jansi, sqlite-jdbc) |
| `compileOnly-not-shipped` | compile/test classpath only, never packaged (litertlm-jvm, ort-genai classes.jar) |
| `pinned-not-shipped` | UPSTREAM.lock pin without a packaged artifact (mlc-llm) |
| `metadata-only` | BOMs / per-device models never packaged (Compose BOM, gemma) |

The verifier fails on a mismatch in **either** direction: a claimed APK entry
that is missing, or a characteristic APK entry (`.so` / `.version`) not
claimed by any packaged component.

### detekt (intentionally skipped)

**detekt is not configured** in this monorepo (no plugin, no baseline file).  
Reason: static-analysis suite is an org/product decision; current fail-closed quality bar is:

- unit / host tests
- AGP lint (app-ui + companion)
- contract drift
- module dependency rules + dependency edges
- 16 KB native packaging gates

Do **not** invent a partial detekt baseline “for green CI” without an agreed rule set. If product later mandates detekt, add the plugin + committed baseline in a dedicated PR and wire a CI step after lint.

### Dependency boundary gate

```bash
./gradlew checkModuleDependencyRules
# or:
python tools/ci/check_module_dependency_rules.py
```

Hard failures (exit 1): UI→`:data:*` / native engine packs, engine→`:data:persistence|model-store`,
companion→privileged modules, api-path UI→`:data:persistence`. Soft debt (warn only):
UI api-path→`:engines:api` or `:data:model-store` (tracked; prefer Admin projections only).

## Local parity

```bash
# Unix / Git Bash / WSL (repo root)
pip install -r tools/codegen/requirements.txt
bash tools/ci/local_ci.sh

# Faster iteration (skip APK)
bash tools/ci/local_ci.sh --skip-assemble
bash tools/ci/local_ci.sh --skip-lint
bash tools/ci/local_ci.sh --skip-release
```

```powershell
# Windows PowerShell (repo root)
pip install -r tools/codegen/requirements.txt
.\tools\ci\local_ci.ps1
.\tools\ci\local_ci.ps1 -SkipAssemble
.\tools\ci\local_ci.ps1 -SkipLint
.\tools\ci\local_ci.ps1 -SkipRelease
```

Python override: set env `OMNILLM_PYTHON` or Gradle `-Pomnillm.python=...`.

## Contract drift (required)

Formal contracts are generated from `specs/` catalogs. Drift between catalogs and committed generated Kotlin **must fail the build**.

```bash
# Install generator deps once
pip install -r tools/codegen/requirements.txt

# Regenerate after catalog edits (commit the result)
./gradlew generateContracts

# Gate (also hooked into ./gradlew check)
./gradlew checkContractDrift
```

See [tools/codegen/README.md](../codegen/README.md) for full details.

**CI order matters:** run `checkContractDrift` on the checked-out tree **before** regenerating, so a PR that only edits YAML but forgets regenerated Kotlin fails. Do **not** edit generated files by hand - change the YAML authority, then regenerate.

## Native 16 KB packaging (ANDROID-NATIVE / ANDROID-16KB)

```bash
# ELF PT_LOAD alignment for any *.so under the monorepo (pass if none yet)
./gradlew checkNative16kb
# or:
python tools/ci/check_elf_16kb_alignment.py

# After assembling an APK:
python tools/ci/check_apk_16kb_zipalign.py --skip-if-missing \
  android/app-ui/build/outputs/apk/debug/app-ui-debug.apk
```

Policy:

- NDK **r28+** locked in `gradle/libs.versions.toml` (`28.2.13676358`)
- Production ABIs: `arm64-v8a`, `x86_64` (`:android:native` / `AbiPackaging`)
- `packaging.jniLibs.useLegacyPackaging = false` for uncompressed `.so` zip alignment
- Linker (legacy toolchains): `-Wl,-z,max-page-size=16384` **and** `-Wl,-z,common-page-size=16384`

## Secrets (signing) - never commit

Release signing material is **only** stored as GitHub Actions repository/environment secrets.
Do **not** put keystores, passwords, or base64 keystores in the repo, `local.properties`, or committed Gradle files.

### Required secrets for signed `release.yml`

| Secret name | Description |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Base64 encoding of the release `.jks` / `.keystore` file |
| `SIGNING_STORE_PASSWORD` | Keystore store password |
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore |
| `SIGNING_KEY_PASSWORD` | Private key password |

How to produce the base64 blob **locally** (do not commit the output file):

```bash
# Unix
base64 -w0 release.jks > /tmp/keystore.b64   # paste contents into GitHub secret only

# macOS
base64 -i release.jks | tr -d '\n'

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
```

In CI, secrets are applied via AGP **injected signing properties** (no keystore path in VCS):

```text
-Pandroid.injected.signing.store.file=<runner temp path>
-Pandroid.injected.signing.store.password=***
-Pandroid.injected.signing.key.alias=***
-Pandroid.injected.signing.key.password=***
```

If secrets are missing, `release.yml` still builds **unsigned** release APKs and emits a warning.
PR `ci.yml` never requires signing secrets.

### Optional future secrets (not required yet)

| Secret | Purpose |
|---|---|
| Play upload service account JSON | Automated Play Console upload (not wired) |
| Maven / package registry tokens | Publishing Engine Packs (not wired) |

## Dependabot

[`.github/dependabot.yml`](../../.github/dependabot.yml) opens weekly PRs for:

- **Gradle** dependencies / version catalog (`/`)
- **GitHub Actions** workflow actions

Toolchain bumps (AGP, Kotlin, compileSdk, NDK) must be reviewed against `gradle/libs.versions.toml` and product `ANDROID-BASELINE` / `ANDROID-16KB` locks.

## Suggested manual pipeline (short)

1. `pip install -r tools/codegen/requirements.txt`
2. `./gradlew checkContractDrift`
3. `./gradlew checkModuleDependencyRules`
4. `./gradlew checkDependencyEdges`
5. `./gradlew test`
6. `./gradlew checkNative16kb`
7. `./gradlew :android:app-ui:assembleDebug :android:companion-sandbox:assembleDebug`
8. `./gradlew :android:app-ui:assembleRelease :android:companion-sandbox:assembleRelease`
9. `python tools/ci/check_apk_16kb_zipalign.py android/app-ui/build/outputs/apk/release/*.apk`

Or simply: `bash tools/ci/local_ci.sh` / `.\tools\ci\local_ci.ps1`.

**Play Console human steps only:** [`gradle/RELEASE_CHECKLIST.md`](../../gradle/RELEASE_CHECKLIST.md) (no automated upload).
