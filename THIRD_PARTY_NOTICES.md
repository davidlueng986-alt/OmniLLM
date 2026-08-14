# OmniLLM — Third-Party Notices

This file lists third-party software components distributed with or referenced by
OmniLLM, together with their licenses. Full texts follow the summary table.

> This file is the launch-readiness source of truth for dependency licensing
> (BLD-03). Keep it in sync with `gradle/libs.versions.toml` and
> `engines/*/UPSTREAM.lock` when dependencies change.

## Summary

| Component | Version | License | Notes |
|---|---|---|---|
| Ktor (server core / CIO / Netty engines) | 3.1.3 | Apache-2.0 | `io.ktor:*`, JetBrains |
| OkHttp | 4.12.0 | Apache-2.0 | `com.squareup.okhttp3:okhttp`, Square |
| SQLDelight (runtime, coroutines, drivers) | 2.0.2 | Apache-2.0 | `app.cash.sqldelight:*`, Cash App |
| kotlinx-coroutines | 1.10.2 | Apache-2.0 | `org.jetbrains.kotlinx` |
| kotlinx-serialization | 1.8.1 | Apache-2.0 | `org.jetbrains.kotlinx` |
| Kotlin (stdlib / reflect / annotations) | 2.2.21 / 2.2.0 | Apache-2.0 | `org.jetbrains.kotlin:kotlin-stdlib|kotlin-reflect`, `org.jetbrains:annotations` |
| AndroidX libraries (incl. Room) + Compose BOM | BOM 2025.07.00 | Apache-2.0 | `androidx.*` (per-module `.version` files shipped in APK) |
| Room | 2.7.2 | Apache-2.0 | Declared in version catalog but **unused** — no code links `androidx.room`; listed for completeness |
| kotlinx-io | 0.7.0 | Apache-2.0 | `org.jetbrains.kotlinx:kotlinx-io-core` |
| Okio | 3.6.0 | Apache-2.0 | `com.squareup.okio:okio` |
| SLF4J API | 1.7.36 / 2.0.16 | MIT | `org.slf4j:slf4j-api` |
| jansi | 2.4.1 | Apache-2.0 | `org.fusesource.jansi:jansi` — **excluded from the APK** (JVM-only payload, D10); on classpath only |
| sqlite-jdbc | 3.45.2.0 | Apache-2.0 | `org.xerial:sqlite-jdbc` — **excluded from the APK** (JVM-only payload, D10); class files on classpath only |
| Typesafe Config | 1.4.3 | Apache-2.0 | `com.typesafe:config` |
| Netty (via `ktor-server-netty`) | 4.1.119.Final (13 modules + `org.eclipse.jetty.alpn:alpn-api` 1.1.3.v20160715) | Apache-2.0 | LICENSE/NOTICE metadata is excluded from APK packaging (`META-INF/io.netty.versions.properties` etc.); attribution compensated here |
| Public Suffix List | embedded in okhttp NOTICE | MPL-2.0 | `okhttp3/internal/publicsuffix/NOTICE` packaged in APK |
| listenablefuture / jspecify | 1.0 / 1.0.0 | Apache-2.0 | `com.google.guava:listenablefuture`, `org.jspecify:jspecify` |
| llama.cpp (vendored) | b9999 | MIT | `android/native/src/main/cpp/third_party/llama.cpp`; lock in `engines/llama-cpp/UPSTREAM.lock` (licenseDigest below) |
| LiteRT-LM (Android) | 0.15.0 | Apache-2.0 | `com.google.ai.edge.litertlm:litertlm-android` — **packaged in APK** (C-07), arm64-v8a + x86_64 |
| ONNX Runtime GenAI (Android) | 0.14.0 | MIT | GitHub release asset — **packaged in APK** (C-07), arm64-v8a + x86_64 |
| ONNX Runtime (Android base) | 1.25.1 | MIT | `com.microsoft.onnxruntime:onnxruntime-android` — **packaged in APK** (C-07) |
| mllm | 2.0.0 | MIT | `UbiquitousLearning/mllm` — `libMllm*.so` packaged arm64-v8a only (C-07) |
| mllm Go runtime | from `mllm_server.aar` | BSD-3-Clause | `libgojni.so` (arm64-v8a only) |
| NDK runtimes (libc++_shared / libomp) | NDK 28.2 | Apache-2.0 WITH LLVM-exception | Android NDK LLVM libc++ + OpenMP |
| mlc-llm | main @ 2f78caa4 (pin) | Apache-2.0 | `mlc-ai/mlc-llm` — **pinned-not-shipped** (no packaged artifact) |
| Gemma e2e model (GGUF) | n/a | Google Terms of Service | E2E fixture only — downloaded per-device at runtime, **not** packaged in the APK |

## Apache License 2.0 components

Ktor, OkHttp, SQLDelight, kotlinx-coroutines, kotlinx-serialization, AndroidX
(including Room) and Compose are licensed under the Apache License, Version 2.0.
A copy of the Apache License 2.0 is provided in the repository root (`LICENSE`).
Full upstream license texts are available at:

- https://www.apache.org/licenses/LICENSE-2.0.txt
- https://github.com/square/okhttp (LICENSE.txt)
- https://github.com/JetBrains/kotlin (kotlinx-\*; LICENSE)
- https://developer.android.com/license (AndroidX)

## Netty

Netty is an Apache-2.0 component pulled in transitively through
`io.ktor:ktor-server-netty` (LAN TLS gateway). Netty's own `LICENSE` / `NOTICE`
metadata files are excluded from the packaged APK by Android packaging rules;
this notice compensates for that exclusion. Upstream:
https://netty.io/ (Apache License 2.0, with the "The Netty Project" attribution
notice reproduced in the Netty distribution).

## llama.cpp (MIT)

Vendored tree: `android/native/src/main/cpp/third_party/llama.cpp`
Upstream: https://github.com/ggml-org/llama.cpp
Pin: tag `b9999`, commit `47c786924ad1ab7e91da2cdc72fcdb563780c2bd`
License digest (SHA-256 of upstream LICENSE at pin): `bcd8ec749126d45cb06737d0690295d73df4b6e7e194205bcf91190368f27285`
(recorded as `license.licenseDigest` in `engines/llama-cpp/UPSTREAM.lock`).

```
MIT License

Copyright (c) 2023-2024 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Gemma e2e model

End-to-end testing uses a Gemma GGUF model. The model file is downloaded
**per device** from Google's model distribution at runtime and is **never
bundled into the APK or the repository**. Its use is subject to Google's
Terms of Service for the model (Gemma Terms of Use). OmniLLM does not
redistribute the model weights.

## Kotlin ecosystem (Apache-2.0)

`org.jetbrains.kotlin:kotlin-stdlib` (2.2.0 resolved; 2.2.21 on the classpath),
`kotlin-reflect` (2.2.21), `org.jetbrains:annotations` (23.0.0) and
`org.jetbrains.kotlinx:kotlinx-io-core` (0.7.0) are distributed with the APK.
All are Apache-2.0 (see `LICENSE` at repo root). Upstream:
https://github.com/JetBrains/kotlin

## Okio / OkHttp

Okio (`com.squareup.okio:okio`, 3.6.0, Apache-2.0) and OkHttp
(`com.squareup.okhttp3:okhttp`, 4.12.0, Apache-2.0) are packaged in the APK.
OkHttp ships the Mozilla **Public Suffix List** under
`okhttp3/internal/publicsuffix/NOTICE` (MPL-2.0) — this file is packaged
inside the APK and its attribution is preserved there. Upstream:
https://github.com/square/okio, https://github.com/square/okhttp

## SLF4J (MIT)

`org.slf4j:slf4j-api` (1.7.36 / 2.0.16 resolved) is an MIT-licensed facade
API used at runtime. Upstream: https://www.slf4j.org/

## jansi (Apache-2.0) — excluded from the APK (D10)

`org.fusesource.jansi:jansi` 2.4.1 enters the build through
`io.ktor:ktor-server-core` (runtime scope). It bundles Mac/Windows native
libraries that are **never used on Android**, so the entire jansi payload is
excluded from the APK at packaging time (`org/fusesource/jansi/**`,
`META-INF/native-image/jansi/**`). The jar remains on the JVM classpath for
host tests. This notice compensates for its absence from the APK. Upstream:
https://github.com/fusesource/jansi (Apache-2.0)

## sqlite-jdbc (Apache-2.0) — excluded from the APK (D10)

`org.xerial:sqlite-jdbc` 3.45.2.0 enters the build through
`app.cash.sqldelight:sqlite-driver` ← `:data:persistence` (JVM-only JDBC
driver; Android production opens `AndroidSqliteDriver` instead). The 6 MB of
Mac/Windows/Linux native libraries plus the `java.sql.Driver` service
registration are excluded from the APK at packaging time; only the class
files remain on the classpath for JVM-side linkage. This notice compensates
for the excluded payload. Upstream: https://github.com/xerial/sqlite-jdbc
(Apache-2.0)

## Netty (Apache-2.0)

Netty (13 modules, 4.1.119.Final + `org.eclipse.jetty.alpn:alpn-api`
1.1.3.v20160715) is pulled in transitively through
`io.ktor:ktor-server-netty` (LAN TLS gateway). Netty's own `LICENSE` /
`NOTICE` metadata files are excluded from the packaged APK by Android
packaging rules; this notice compensates for that exclusion. Upstream:
https://netty.io/ (Apache License 2.0, with the "The Netty Project"
attribution notice reproduced in the Netty distribution).

## Typesafe Config (Apache-2.0)

`com.typesafe:config` 1.4.3 is packaged in the APK (Ktor HOCON config
loader). Apache-2.0. Upstream: https://github.com/lightbend/config

## listenablefuture / jspecify (Apache-2.0)

`com.google.guava:listenablefuture` 1.0 and `org.jspecify:jspecify` 1.0.0 are
small annotation/future-support artifacts packaged in the APK. Both are
Apache-2.0.

## LiteRT-LM Android (Apache-2.0) — packaged (C-07)

`com.google.ai.edge.litertlm:litertlm-android` 0.15.0
(google-ai-edge/LiteRT-LM @ 2117fc431) is **packaged in the APK** —
`liblitertlm_jni.so` for arm64-v8a + x86_64 (C-07). The JVM artifact
`litertlm-jvm` is compileOnly / host-test only and is **never shipped**.
Pin + sha256 in `engines/litert-lm/UPSTREAM.lock` (and runtime-service).
Upstream: https://github.com/google-ai-edge/LiteRT-LM (Apache-2.0)

## ONNX Runtime GenAI + base ORT (MIT) — packaged (C-07)

`microsoft/onnxruntime-genai` 0.14.0 (Android AAR, GitHub release asset) and
`com.microsoft.onnxruntime:onnxruntime-android` 1.25.1 are **packaged in the
APK** — `libonnxruntime-genai.so`, `libonnxruntime-genai-jni.so`,
`libonnxruntime.so`, `libonnxruntime4j_jni.so` for arm64-v8a + x86_64 (C-07).
Both are MIT. Pins + sha256 in `engines/ort-genai/UPSTREAM.lock`
(`artifactProvisioning` / `ortRuntime`).

## mllm + Go runtime (MIT / BSD-3-Clause) — packaged (C-07)

`UbiquitousLearning/mllm` 2.0.0 (`libMllmCPUBackend.so`, `libMllmRT.so`,
`libMllmSdkC.so`, **arm64-v8a only**) is packaged in the APK (MIT). The Go
in-app server `mllm_server.aar` ships `libgojni.so` (arm64-v8a only) under
BSD-3-Clause. Upstream: https://github.com/UbiquitousLearning/mllm

## NDK runtimes (Apache-2.0 WITH LLVM-exception)

`libc++_shared.so` (arm64-v8a + x86_64) and `libomp.so` (arm64-v8a) from the
Android NDK (28.2.x) are packaged in the APK. LLVM `libc++` / OpenMP are
Apache-2.0 with the LLVM exception.

## mlc-llm (Apache-2.0) — pinned-not-shipped

`mlc-ai/mlc-llm` is pinned in `engines/mlc-llm/UPSTREAM.lock` (main @
2f78caa4) but **no artifact is packaged** — mlc remains metadata-only by
product decision (C-07). Apache-2.0. Upstream: https://github.com/mlc-ai/mlc-llm
