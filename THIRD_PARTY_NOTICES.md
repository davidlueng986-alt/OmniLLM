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
| AndroidX libraries (incl. Room) + Compose BOM | BOM 2025.07.00 | Apache-2.0 | `androidx.*` |
| Room | 2.7.2 | Apache-2.0 | Declared in version catalog but **unused** — no code links `androidx.room`; listed for completeness |
| Netty (via `ktor-server-netty`) | via Ktor 3.1.3 | Apache-2.0 | LICENSE/NOTICE metadata is excluded from APK packaging (`META-INF/io.netty.versions.properties` etc.); attribution compensated here |
| llama.cpp (vendored) | b9999 | MIT | `android/native/src/main/cpp/third_party/llama.cpp`; lock in `engines/llama-cpp/UPSTREAM.lock` (licenseDigest below) |
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
