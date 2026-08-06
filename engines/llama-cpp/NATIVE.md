# llama-cpp native path (ENGINE-LLAMACPP)

## What ships today

| Artifact | Location | Notes |
|---|---|---|
| Shared library | `libomnillm_llama.so` via `:android:native` CMake | Real NDK build, ABIs `arm64-v8a` + `x86_64` |
| C API | `android/native/src/main/cpp/omnillm_llama.{h,cpp}` | Lifecycle + path/FD load + tokenize + generate + cooperative cancel |
| Fixture backend | `omnillm_fixture_backend.cpp` | **EXPERIMENTAL_FIXTURE** — real C++ tokenize/generate loop (not Kotlin stub) |
| Upstream backend | `omnillm_upstream_backend.cpp` | Links pinned llama.cpp when `third_party/llama.cpp` present |
| JNI | `android/native/src/main/cpp/jni_bridge.cpp` | Maps to `JniNativeBridge` / `JniNativeBackend` (ABI v2) |
| Kotlin SPI | `engines/llama-cpp/.../native/NativeBackend.kt` | Interface shared by Stub + JNI |
| Host tests | `JniNativeMappingTest` | Pure mapping without NDK |
| Stub | `StubNativeBackend` | Unit tests / hosts without NDK only |

Capability cells remain **UNQUALIFIED / UNKNOWN**. Fixture and upstream exploratory
paths never mint SUPPORTED.

## Upstream pin

See `UPSTREAM.lock` and `android/native/src/main/cpp/third_party/README.md`.

| Field | Value |
|---|---|
| Repository | `https://github.com/ggml-org/llama.cpp` |
| Tag | `b9999` |
| Commit | `47c786924ad1ab7e91da2cdc72fcdb563780c2bd` |

Full `sourceDigest` / `artifactDigest` / `toolchainDigest` remain empty ⇒
`lockState: NOT_LOCKED` (exploratory only).

## Load selection (fail closed)

1. **Explicit fixture markers only** → fixture backend:
   - `installationKey == EXPERIMENTAL_FIXTURE`, or
   - `storageRootKey` / `resolvedModelPath` starts with `fixture:`, or
   - equals `EXPERIMENTAL_FIXTURE`.
2. `modelFd >= 0` or non-empty resolved filesystem path → upstream GGUF load
   (`llama_model_load_from_file` / `llama_model_load_from_file_ptr`) when linked.
3. Broker-only keys **without** fixture markers and without path/FD →
   `NOT_AVAILABLE` / Kotlin `CAPABILITY_UNSUPPORTED` (**no silent fixture**).
4. Path/FD requested but upstream not linked → `NOT_AVAILABLE` (fail closed).

Exploratory control-plane smoke (`LlamaCppInferenceEngineAdapter`) always
passes `fixture:EXPERIMENTAL_FIXTURE` explicitly.

## Generate loop

1. Tokenize (`fixture_tokenize` or `llama_tokenize`).
2. Prefill / decode with cooperative cancel polled each step
   (`cancel_flag` + `omnillm_llama_request_cancel`).
3. Emit `TOKEN_DELTA` digests, `USAGE`, unique `STOP`.

## How to refresh / rebuild real llama.cpp

1. Update pin in `UPSTREAM.lock` (tag, commit, digests when captured).
2. Re-fetch tree under `android/native/src/main/cpp/third_party/llama.cpp`
   (see `third_party/README.md`).
3. Rebuild with NDK r28+ and `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`.
4. Run `./gradlew :android:native:verifyNativeLibsPresent` and
   `tools/ci/check_elf_16kb_alignment.py` on packaged `.so`.
5. Evidence pack required before any cell may become `QUALIFIED_WITH_ENVELOPE` /
   runtime `SUPPORTED`.

## Process placement

- Package `.so` through `:android:native` → consumed by `:android:runtime-service`
  and `:android:workers`.
- **UI process must not** `System.loadLibrary` (INV-001). Control plane attaches
  JNI only after READY/DEGRADED in `:runtime`.
- Missing library ⇒ production adapter not created; execute path remains
  fail-closed (no silent Stub in production).
